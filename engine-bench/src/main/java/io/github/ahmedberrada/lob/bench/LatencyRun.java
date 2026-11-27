package io.github.ahmedberrada.lob.bench;

import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import io.github.ahmedberrada.lob.journal.JournalOptions;
import io.github.ahmedberrada.lob.journal.JournaledEngine;
import io.github.ahmedberrada.lob.journal.TimeSource;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.HdrHistogram.Histogram;

/**
 * End-to-end latency of one command, from the call to {@code process} to its events, measured per command
 * with HdrHistogram (docs/performance.md).
 *
 * <p>Usage: {@code java -cp engine-bench/target/benchmarks.jar io.github.ahmedberrada.lob.bench.LatencyRun
 * [engine|journal|journal-fsync] [commands] [warmup]}.
 *
 * <p>The run is closed-loop: the next command is sent when the previous one returns, so a slow command
 * delays the ones behind it without being counted against them (coordinated omission). It therefore
 * measures service time, not the latency a client would see under a fixed arrival rate; the document says so.
 */
public final class LatencyRun {

    private LatencyRun() {
    }

    /** Percentiles of a run, in nanoseconds, and its throughput. */
    record Result(String name, long commands, double seconds, Histogram histogram) {
        void print(PrintStream out) {
            out.printf("%-14s %,d commands in %.2f s = %,.0f commands/s%n", name, commands, seconds, commands / seconds);
            out.printf("  p50 %,d ns   p90 %,d ns   p99 %,d ns   p99.9 %,d ns   p99.99 %,d ns   max %,d ns%n",
                    histogram.getValueAtPercentile(50), histogram.getValueAtPercentile(90),
                    histogram.getValueAtPercentile(99), histogram.getValueAtPercentile(99.9),
                    histogram.getValueAtPercentile(99.99), histogram.getMaxValue());
        }
    }

    public static void main(String[] args) throws IOException {
        String mode = args.length > 0 ? args[0] : "engine";
        int commands = args.length > 1 ? Integer.parseInt(args[1]) : 2_000_000;
        int warmup = args.length > 2 ? Integer.parseInt(args[2]) : 500_000;
        Result result = switch (mode) {
            case "engine" -> runEngine(commands, warmup);
            case "journal" -> runJournal(commands, warmup, FsyncPolicy.OS);
            case "journal-fsync" -> runJournal(commands, warmup, FsyncPolicy.EVERY_COMMAND);
            default -> throw new IllegalArgumentException("mode: engine, journal or journal-fsync");
        };
        result.print(System.out);
    }

    static Result runEngine(int commands, int warmup) {
        MatchingEngine engine = new MatchingEngine(Workload.INSTRUMENT);
        Workload workload = new Workload(42, 5_000);
        for (int i = 0; i < warmup; i++) {
            engine.process(workload.next());
        }
        Histogram histogram = new Histogram(3_600_000_000_000L, 3);
        long start = System.nanoTime();
        for (int i = 0; i < commands; i++) {
            Command command = workload.next();
            long before = System.nanoTime();
            engine.process(command);
            histogram.recordValue(System.nanoTime() - before);
        }
        return new Result("engine", commands, (System.nanoTime() - start) / 1e9, histogram);
    }

    static Result runJournal(int commands, int warmup, FsyncPolicy fsync) throws IOException {
        Path directory = Files.createTempDirectory("lob-bench");
        try (JournaledEngine engine = JournaledEngine.open(directory, Workload.INSTRUMENT,
                new JournalOptions(fsync, 0), TimeSource.system())) {
            Workload workload = new Workload(42, 5_000);
            int warm = fsync == FsyncPolicy.EVERY_COMMAND ? Math.min(warmup, 20_000) : warmup;
            int measured = fsync == FsyncPolicy.EVERY_COMMAND ? Math.min(commands, 100_000) : commands;
            for (int i = 0; i < warm; i++) {
                engine.process(workload.next());
            }
            Histogram histogram = new Histogram(3_600_000_000_000L, 3);
            long start = System.nanoTime();
            for (int i = 0; i < measured; i++) {
                Command command = workload.next();
                long before = System.nanoTime();
                engine.process(command);
                histogram.recordValue(System.nanoTime() - before);
            }
            return new Result(fsync == FsyncPolicy.OS ? "journal" : "journal-fsync", measured,
                    (System.nanoTime() - start) / 1e9, histogram);
        } finally {
            try (var files = Files.walk(directory)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
