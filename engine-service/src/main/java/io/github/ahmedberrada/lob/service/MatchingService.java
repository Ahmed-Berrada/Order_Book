package io.github.ahmedberrada.lob.service;

import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.journal.JournalOptions;
import io.github.ahmedberrada.lob.journal.JournaledEngine;
import io.github.ahmedberrada.lob.journal.RecoveryReport;
import io.github.ahmedberrada.lob.journal.TimeSource;
import io.github.ahmedberrada.lob.service.RequestRefusedException.Reason;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Entry point to the matching engines of all listed instruments (ADR-0004). Thread-safe: any thread
 * may call it. Each command is routed to the single writer thread of its instrument.
 *
 * <p>Every method returns a future and never blocks. Refusals (OE-001, OE-005 to OE-007) complete the
 * future exceptionally with a {@link RequestRefusedException}.
 */
public final class MatchingService implements AutoCloseable {

    private final Map<String, InstrumentConfig> instruments;
    private final Map<String, SymbolWorker> workers;
    private final List<EventListener> listeners;
    private final Map<String, RecoveryReport> recoveries;

    private MatchingService(Map<String, InstrumentConfig> instruments, Map<String, SymbolWorker> workers,
            Map<String, RecoveryReport> recoveries, List<EventListener> listeners) {
        this.listeners = listeners;
        this.instruments = Collections.unmodifiableMap(instruments);
        this.workers = workers;
        this.recoveries = Collections.unmodifiableMap(recoveries);
    }

    /**
     * Recovers every instrument from its journal under {@code dataDirectory/<symbol>} and starts its
     * writer thread. If any instrument fails to recover, those already started are stopped again.
     *
     * @param queueCapacity commands each instrument can hold before refusing with {@code OVERLOADED}
     */
    public static MatchingService start(Path dataDirectory, List<InstrumentConfig> instruments,
            JournalOptions options, TimeSource timeSource, int queueCapacity) throws IOException {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive: " + queueCapacity);
        }
        Map<String, InstrumentConfig> bySymbol = new LinkedHashMap<>();
        for (InstrumentConfig config : instruments) {
            if (bySymbol.putIfAbsent(config.symbol(), config) != null) {
                throw new IllegalArgumentException("duplicate instrument: " + config.symbol());
            }
        }
        Map<String, SymbolWorker> workers = new LinkedHashMap<>();
        Map<String, RecoveryReport> recoveries = new LinkedHashMap<>();
        List<EventListener> listeners = new CopyOnWriteArrayList<>();
        try {
            for (InstrumentConfig config : bySymbol.values()) {
                JournaledEngine engine = JournaledEngine.open(
                        dataDirectory.resolve(config.symbol()), config.instrument(), options, timeSource);
                recoveries.put(config.symbol(), engine.recovery());
                workers.put(config.symbol(), new SymbolWorker(engine, queueCapacity, listeners));
            }
        } catch (IOException | RuntimeException e) {
            IOException closeFailure = closeAll(workers.values());
            if (closeFailure != null) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        return new MatchingService(bySymbol, workers, recoveries, listeners);
    }

    /** Journals and processes a command for an instrument (OE-003, OE-004). */
    public CompletableFuture<EventBatch> submit(String symbol, Command command) {
        return submit(symbol, command, null);
    }

    /**
     * Like {@link #submit(String, Command)}, attaching a context that {@link EventListener}s receive with
     * the command's events, for example which session sent it.
     */
    public CompletableFuture<EventBatch> submit(String symbol, Command command, Object context) {
        Objects.requireNonNull(command, "command");
        return withWorker(symbol, worker -> worker.submit(command, context));
    }

    /** Registers a listener for the events of every instrument, from the next command on. */
    public void addListener(EventListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** The best {@code depth} levels of each side of an instrument's book. */
    public CompletableFuture<BookSnapshot> book(String symbol, int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative: " + depth);
        }
        return withWorker(symbol, worker -> worker.book(depth));
    }

    /** Remaining quantity of a resting order, or 0 if the order is not resting. */
    public CompletableFuture<Long> restingQuantity(String symbol, long orderId) {
        return withWorker(symbol, worker -> worker.restingQuantity(orderId));
    }

    /** The listed instrument with this symbol, if any. */
    public Optional<InstrumentConfig> instrument(String symbol) {
        return Optional.ofNullable(instruments.get(symbol));
    }

    /** All listed instruments, in configuration order. */
    public List<InstrumentConfig> instruments() {
        return List.copyOf(instruments.values());
    }

    /** Whether an instrument stopped after a journal failure (OE-006). */
    public boolean isHalted(String symbol) {
        SymbolWorker worker = workers.get(symbol);
        return worker != null && worker.isHalted();
    }

    /** What recovery did for each instrument at start. */
    public Map<String, RecoveryReport> recoveries() {
        return recoveries;
    }

    /** Processes what is queued, then closes every journal (OE-007). */
    @Override
    public void close() throws IOException {
        IOException failure = closeAll(workers.values());
        if (failure != null) {
            throw failure;
        }
    }

    private <T> CompletableFuture<T> withWorker(String symbol, Function<SymbolWorker, CompletableFuture<T>> call) {
        SymbolWorker worker = workers.get(symbol);
        if (worker == null) {
            return CompletableFuture.failedFuture(new RequestRefusedException(Reason.UNKNOWN_INSTRUMENT, symbol));
        }
        return call.apply(worker);
    }

    /** Closes every worker, even if some fail, and returns the first failure with the others suppressed. */
    private static IOException closeAll(Collection<SymbolWorker> workers) {
        IOException failure = null;
        for (SymbolWorker worker : workers) {
            try {
                worker.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        return failure;
    }
}
