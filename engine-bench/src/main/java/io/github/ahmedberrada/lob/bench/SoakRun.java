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
import java.util.Comparator;
import java.util.Objects;

/**
 * A soak and restart test of the journal: a market-like stream runs for a given time, and every so many
 * commands the engine is closed and recovered from its journals. After each recovery the engine's state
 * must equal that of an in-memory engine fed the same commands, and memory use is reported so that a leak
 * shows as a trend (docs/operations/runbook.md, "Soak test").
 *
 * <p>Usage: {@code java -cp engine-bench/target/benchmarks.jar io.github.ahmedberrada.lob.bench.SoakRun
 * [seconds] [commandsBetweenRestarts]}. A 24 hour soak is {@code 86400}. Exit code 0 if every recovery
 * matched, 1 on the first mismatch.
 */
public final class SoakRun {

    private SoakRun() {
    }

    /** The outcome of a run. */
    record Result(long commands, int restarts, boolean consistent, long heapUsedBytes) {
    }

    public static void main(String[] args) throws IOException {
        long seconds = args.length > 0 ? Long.parseLong(args[0]) : 60;
        int restartEvery = args.length > 1 ? Integer.parseInt(args[1]) : 200_000;
        Result result = run(seconds * 1_000_000_000L, restartEvery, Long.MAX_VALUE, System.out);
        System.out.printf("%,d commands, %d restarts, %s%n", result.commands(), result.restarts(),
                result.consistent() ? "every recovery matched" : "MISMATCH");
        System.exit(result.consistent() ? 0 : 1);
    }

    static Result run(long durationNanos, int restartEvery, long maxCommands, PrintStream out) throws IOException {
        Path directory = Files.createTempDirectory("lob-soak");
        try {
            Workload workload = new Workload(7, 5_000);
            MatchingEngine expected = new MatchingEngine(Workload.INSTRUMENT);
            JournalOptions options = new JournalOptions(FsyncPolicy.OS, 50_000);
            JournaledEngine engine = JournaledEngine.open(directory, Workload.INSTRUMENT, options, TimeSource.system());
            long deadline = System.nanoTime() + durationNanos;
            long commands = 0;
            int restarts = 0;
            try {
                while (System.nanoTime() < deadline && commands < maxCommands) {
                    Command command = workload.next();
                    expected.process(command);
                    engine.process(command);
                    commands++;
                    if (commands % restartEvery == 0) {
                        engine.close();
                        engine = JournaledEngine.open(directory, Workload.INSTRUMENT, options, TimeSource.system());
                        restarts++;
                        boolean same = Objects.equals(engine.state(), expected.snapshot());
                        long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                        out.printf("restart %d after %,d commands: %s, heap %,d MB%n", restarts, commands,
                                same ? "state matches" : "STATE DIFFERS", heap >> 20);
                        if (!same) {
                            return new Result(commands, restarts, false, heap);
                        }
                    }
                }
                engine.close();
                engine = JournaledEngine.open(directory, Workload.INSTRUMENT, options, TimeSource.system());
                boolean same = Objects.equals(engine.state(), expected.snapshot());
                return new Result(commands, restarts + 1, same,
                        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            } finally {
                engine.close();
            }
        } finally {
            try (var files = Files.walk(directory)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
