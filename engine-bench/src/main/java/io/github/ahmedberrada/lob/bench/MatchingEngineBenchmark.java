package io.github.ahmedberrada.lob.bench;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import io.github.ahmedberrada.lob.core.Side;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Cost of the engine's basic operations, with the book at a steady size so that a run measures the
 * operation and not the growth of the book. Run: {@code java -jar engine-bench/target/benchmarks.jar}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class MatchingEngineBenchmark {

    /** Orders resting at the price the cancel benchmark picks from. */
    @Param({"10", "1000", "10000"})
    int levelDepth;

    private MatchingEngine engine;
    private long nextId;
    private long oldestId;
    private long cancelTarget;
    private long mixSeed;
    private Workload workload;

    @Setup(Level.Iteration)
    public void fill() {
        engine = new MatchingEngine(Workload.INSTRUMENT);
        nextId = 1;
        // one deep level to cancel from, and a quiet book on each side of it
        for (int i = 0; i < levelDepth; i++) {
            engine.process(new LimitOrder(Side.BUY, 90_000, 5));
            nextId++;
        }
        oldestId = 1;
        cancelTarget = levelDepth / 2 + 1;
        workload = new Workload(mixSeed++, 5_000);
    }

    /** A passive order joining a level, and the cancel of the order that has waited longest elsewhere. */
    @Benchmark
    public void restAndCancel(Blackhole sink) {
        sink.consume(engine.process(new LimitOrder(Side.SELL, 120_000, 5)));
        sink.consume(engine.process(new CancelOrder(nextId++)));
    }

    /** Cancel of an order in the middle of a level that holds {@code levelDepth} orders, then its re-entry. */
    @Benchmark
    public void cancelInTheMiddleOfALevel(Blackhole sink) {
        sink.consume(engine.process(new CancelOrder(cancelTarget)));
        sink.consume(engine.process(new LimitOrder(Side.BUY, 90_000, 5)));
        cancelTarget = nextId++ - (levelDepth / 2);                          // an order about halfway into the queue
        if (cancelTarget < oldestId) {
            cancelTarget = oldestId;
        }
    }

    /** A market order that takes one resting order, and the order that replaces it. */
    @Benchmark
    public void takeLiquidity(Blackhole sink) {
        engine.process(new LimitOrder(Side.SELL, 110_000, 1));
        sink.consume(engine.process(new MarketOrder(Side.BUY, 1)));
    }

    /** The mixed stream of {@link Workload}: the figure that matters for capacity. */
    @Benchmark
    public void mixedMarket(Blackhole sink) {
        Command command = workload.next();
        sink.consume(engine.process(command));
    }
}
