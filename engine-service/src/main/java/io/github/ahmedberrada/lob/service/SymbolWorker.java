package io.github.ahmedberrada.lob.service;

import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.OrderBook;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.journal.JournaledEngine;
import io.github.ahmedberrada.lob.service.RequestRefusedException.Reason;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The single writer of one instrument (ADR-0004 §2): one platform thread that takes tasks from a
 * bounded queue and runs them against the instrument's {@link JournaledEngine}, one at a time, in
 * queue order (OE-003).
 */
final class SymbolWorker implements AutoCloseable {

    /**
     * A queued unit of work and the future that reports its outcome. {@code journaled} marks a command whose
     * acknowledgement waits for the group's flush; {@code afterSync} runs just before the future completes, once
     * nothing can be lost any more (the listeners).
     */
    private record Task<T>(Supplier<T> work, CompletableFuture<T> result, boolean journaled, Consumer<T> afterSync) {

        /** Runs the work; the outcome is kept, not reported, until {@link Done#report}. */
        Done<T> run() {
            try {
                return new Done<>(this, work.get(), null);
            } catch (RuntimeException e) {
                return new Done<>(this, null, e);
            }
        }
    }

    /** The outcome of a task, held until the group it belongs to is flushed (ADR-0020 §3). */
    private record Done<T>(Task<T> task, T value, RuntimeException failure) {

        void report(UncheckedIOException flushFailure, RuntimeException readRefusal) {
            if (failure != null) {
                task.result().completeExceptionally(failure);
            } else if (flushFailure != null && task.journaled()) {
                task.result().completeExceptionally(flushFailure);       // outcome unknown: not acknowledged
            } else if (flushFailure != null) {
                task.result().completeExceptionally(readRefusal);        // it saw commands of unknown outcome
            } else {
                task.afterSync().accept(value);
                task.result().complete(value);
            }
        }
    }

    /** The most tasks one group holds (ADR-0020 §2). */
    static final int MAX_GROUP = 256;

    private static final System.Logger LOG = System.getLogger(SymbolWorker.class.getName());

    private final String symbol;
    private final JournaledEngine engine;
    private final List<EventListener> listeners;
    private final BlockingQueue<Task<?>> queue;
    private final Thread thread;
    private volatile boolean accepting = true;
    private volatile boolean halted;
    /** A flush failed: the engine's memory holds commands of unknown outcome, so nothing is read from it. */
    private volatile boolean stateUnknown;
    private final CommandObserver observer;
    private final int capacity;

    SymbolWorker(JournaledEngine engine, int queueCapacity) {
        this(engine, queueCapacity, List.of());
    }

    /** @param listeners read on every command; the list may change while the worker runs */
    SymbolWorker(JournaledEngine engine, int queueCapacity, List<EventListener> listeners) {
        this(engine, queueCapacity, listeners, CommandObserver.NONE);
    }

    SymbolWorker(JournaledEngine engine, int queueCapacity, List<EventListener> listeners,
            CommandObserver observer) {
        this.observer = observer;
        this.capacity = queueCapacity;
        this.symbol = engine.instrument().symbol();
        this.engine = engine;
        this.listeners = listeners;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.thread = Thread.ofPlatform().name("lob-" + symbol).start(this::runLoop);
    }

    /**
     * Journals and processes a command on the instrument's thread (OE-004).
     *
     * <p>The future fails with {@link RequestRefusedException} if the command was not journaled, or
     * with {@link UncheckedIOException} if writing it failed and its outcome is unknown (ADR-0003 §8).
     * In both I/O cases the instrument halts (OE-006).
     */
    CompletableFuture<EventBatch> submit(Command command) {
        return submit(command, null);
    }

    CompletableFuture<EventBatch> submit(Command command, Object context) {
        return submit(command, 0, context);
    }

    CompletableFuture<EventBatch> submit(Command command, long memberId, Object context) {
        if (halted) {
            return CompletableFuture.failedFuture(new RequestRefusedException(Reason.HALTED, symbol));
        }
        long[] took = new long[1];
        return enqueue(() -> {
            if (halted) {
                throw new RequestRefusedException(Reason.HALTED, symbol);
            }
            try {
                long started = System.nanoTime();
                EventBatch batch = engine.process(command, memberId);
                took[0] = System.nanoTime() - started;
                if (engine.isStopped()) {       // a later write failed: this command counts, the next ones do not
                    halt(new UncheckedIOException("event journal, snapshot or journal seal write failed", engine.failure()));
                }
                return batch;
            } catch (UncheckedIOException e) {
                halt(e);
                throw e;
            }
        }, true, batch -> {
            observe(batch, took[0]);            // after the flush: metrics never count an unknown outcome
            notifyListeners(batch, context);
        });
    }

    /** Stops the instrument (OE-006) and says why in the log, once: the runbook starts from that line. */
    private void halt(UncheckedIOException cause) {
        if (!halted) {
            halted = true;
            LOG.log(System.Logger.Level.ERROR, "Instrument " + symbol
                    + " halted: its journal could not be written; restart the venue once the cause is fixed", cause);
        }
    }

    /**
     * Copies the best {@code depth} levels of each side as the market may see them, without hidden
     * quantity (IC-002), on the instrument's thread.
     */
    CompletableFuture<BookSnapshot> book(int depth) {
        return enqueue(() -> {
            OrderBook book = engine.book();
            return new BookSnapshot(engine.lastCommandSequence(), engine.phase(), top(book.displayedDepth(Side.BUY), depth),
                    top(book.displayedDepth(Side.SELL), depth));
        });
    }

    /** The engine's complete state and the last command it reflects, copied on the instrument's thread. */
    CompletableFuture<InstrumentState> state() {
        return enqueue(() -> new InstrumentState(engine.lastCommandSequence(), engine.state()));
    }

    /** Remaining quantity of a resting order, 0 if it is not resting. */
    CompletableFuture<Long> restingQuantity(long orderId) {
        return enqueue(() -> engine.book().restingQuantity(orderId));
    }

    /** Commands waiting for the instrument's thread (for metrics). */
    int queueDepth() {
        return queue.size();
    }

    int queueCapacity() {
        return capacity;
    }

    /** Device flushes of the instrument's command journal so far (for metrics, ADR-0020). */
    long flushes() {
        return engine.flushes();
    }

    private void observe(EventBatch batch, long nanos) {
        try {
            observer.processed(symbol, batch, nanos);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Command observer failed on " + symbol, e);
        }
    }

    /**
     * A listener failure is logged and does not affect the command, which is already journaled, nor the
     * other listeners.
     */
    private void notifyListeners(EventBatch batch, Object context) {
        for (EventListener listener : listeners) {
            try {
                listener.onEvents(symbol, batch, context);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Event listener failed on " + symbol + " command "
                        + batch.commandSequence(), e);
            }
        }
    }

    boolean isHalted() {
        return halted;
    }

    boolean isAccepting() {
        return accepting;
    }

    /** A read: answered from the engine's memory, unless a failed flush left commands of unknown outcome in it. */
    private <T> CompletableFuture<T> enqueue(Supplier<T> work) {
        return enqueue(() -> {
            if (stateUnknown) {
                throw new RequestRefusedException(Reason.HALTED, symbol);
            }
            return work.get();
        }, false, value -> { });
    }

    private <T> CompletableFuture<T> enqueue(Supplier<T> work, boolean journaled, Consumer<T> afterSync) {
        Task<T> task = new Task<>(work, new CompletableFuture<>(), journaled, afterSync);
        if (!accepting) {
            return CompletableFuture.failedFuture(new RequestRefusedException(Reason.STOPPED, symbol));
        }
        if (!queue.offer(task)) {
            return CompletableFuture.failedFuture(new RequestRefusedException(Reason.OVERLOADED, symbol));
        }
        // Raced with close(): if the worker has already drained and exited, nobody will run the task.
        if (!accepting && queue.remove(task)) {
            task.result().completeExceptionally(new RequestRefusedException(Reason.STOPPED, symbol));
        }
        return task.result();
    }

    private void runLoop() {
        int groupSize = engine.groupCommit() ? MAX_GROUP : 1;
        List<Task<?>> group = new ArrayList<>(groupSize);
        List<Done<?>> done = new ArrayList<>(groupSize);
        while (accepting || !queue.isEmpty()) {
            try {
                Task<?> first = queue.poll(10, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                group.add(first);
                queue.drainTo(group, groupSize - 1);           // what is already waiting; never wait for more
                try {
                    for (Task<?> task : group) {
                        done.add(task.run());
                    }
                    int commands = (int) done.stream().filter(d -> d.task().journaled() && d.failure() == null).count();
                    UncheckedIOException flushFailure = flush(commands);
                    RequestRefusedException readRefusal = new RequestRefusedException(Reason.HALTED, symbol);
                    for (Done<?> outcome : done) {
                        outcome.report(flushFailure, readRefusal);
                    }
                } catch (Error e) {
                    // Nothing of this group can be vouched for: fail every task of it rather than leave it hanging.
                    stateUnknown = true;
                    halt(new UncheckedIOException("instrument thread failed", new IOException(e)));
                    for (Task<?> task : group) {
                        task.result().completeExceptionally(e);
                    }
                    throw e;
                }
            } catch (InterruptedException e) {
                // This thread is stopped by close(), never by interruption: keep serving the queue.
            } finally {
                group.clear();
                done.clear();
            }
        }
    }

    /**
     * One device flush for the group's commands (RS-001). A failed flush halts the instrument (OE-006) and leaves
     * its memory untrustworthy; a failed event write after a good flush halts it too, the commands acknowledged.
     */
    private UncheckedIOException flush(int commands) {
        long before = engine.flushes();
        long started = System.nanoTime();
        try {
            engine.sync();
        } catch (UncheckedIOException e) {
            stateUnknown = true;
            halt(e);
            return e;
        }
        if (engine.flushes() > before) {
            try {
                observer.flushed(symbol, commands, System.nanoTime() - started);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Command observer failed on " + symbol, e);
            }
        }
        if (engine.isStopped()) {
            halt(new UncheckedIOException("event journal, snapshot or journal seal write failed", engine.failure()));
        }
        return null;
    }

    /** Stops accepting tasks, processes those already queued, then closes the journals (OE-007). */
    @Override
    public void close() throws IOException {
        accepting = false;
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while stopping " + symbol, e);
        }
        engine.close();
    }

    private static List<OrderBook.Level> top(List<OrderBook.Level> levels, int depth) {
        return levels.subList(0, Math.min(depth, levels.size()));
    }
}
