package io.github.ahmedberrada.lob.service;

import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.OrderBook;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.journal.JournaledEngine;
import io.github.ahmedberrada.lob.service.RequestRefusedException.Reason;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The single writer of one instrument (ADR-0004 §2): one platform thread that takes tasks from a
 * bounded queue and runs them against the instrument's {@link JournaledEngine}, one at a time, in
 * queue order (OE-003).
 */
final class SymbolWorker implements AutoCloseable {

    /** A queued unit of work and the future that reports its outcome. */
    private record Task<T>(Supplier<T> work, CompletableFuture<T> result) {

        void run() {
            try {
                result.complete(work.get());
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        }
    }

    private final String symbol;
    private final JournaledEngine engine;
    private final BlockingQueue<Task<?>> queue;
    private final Thread thread;
    private volatile boolean accepting = true;
    private volatile boolean halted;

    SymbolWorker(JournaledEngine engine, int queueCapacity) {
        this.symbol = engine.instrument().symbol();
        this.engine = engine;
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
        if (halted) {
            return CompletableFuture.failedFuture(new RequestRefusedException(Reason.HALTED, symbol));
        }
        return enqueue(() -> {
            if (halted) {
                throw new RequestRefusedException(Reason.HALTED, symbol);
            }
            try {
                EventBatch batch = engine.process(command);
                halted = engine.isStopped();    // a later write failed: this command counts, the next ones do not
                return batch;
            } catch (UncheckedIOException e) {
                halted = true;
                throw e;
            }
        });
    }

    /** Copies the best {@code depth} levels of each side, on the instrument's thread. */
    CompletableFuture<BookSnapshot> book(int depth) {
        return enqueue(() -> {
            OrderBook book = engine.book();
            return new BookSnapshot(engine.lastCommandSequence(), top(book.depth(Side.BUY), depth),
                    top(book.depth(Side.SELL), depth));
        });
    }

    /** Remaining quantity of a resting order, 0 if it is not resting. */
    CompletableFuture<Long> restingQuantity(long orderId) {
        return enqueue(() -> engine.book().restingQuantity(orderId));
    }

    boolean isHalted() {
        return halted;
    }

    boolean isAccepting() {
        return accepting;
    }

    private <T> CompletableFuture<T> enqueue(Supplier<T> work) {
        Task<T> task = new Task<>(work, new CompletableFuture<>());
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
        while (accepting || !queue.isEmpty()) {
            try {
                Task<?> task = queue.poll(10, TimeUnit.MILLISECONDS);
                if (task != null) {
                    task.run();
                }
            } catch (InterruptedException e) {
                // This thread is stopped by close(), never by interruption: keep serving the queue.
            }
        }
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
