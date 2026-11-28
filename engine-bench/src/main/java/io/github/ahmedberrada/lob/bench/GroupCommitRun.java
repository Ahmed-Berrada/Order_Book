package io.github.ahmedberrada.lob.bench;

import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import io.github.ahmedberrada.lob.journal.JournalOptions;
import io.github.ahmedberrada.lob.journal.TimeSource;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.HdrHistogram.Histogram;

/**
 * Durable throughput of one instrument with clients waiting for their acknowledgement (closed loop), with every
 * command flushed on its own ({@code EVERY_COMMAND}) or in groups ({@code GROUP}, ADR-0020): docs/performance.md.
 *
 * <p>Usage: {@code java -cp engine-bench/target/benchmarks.jar io.github.ahmedberrada.lob.bench.GroupCommitRun
 * [commandsPerClient] [clients,...]}. Each client sends a resting limit order and waits for its acknowledgement
 * before the next, through the real service (queue, instrument thread, journal), as a REST or FIX session does.
 */
public final class GroupCommitRun {

    static final InstrumentConfig INSTRUMENT =
            new InstrumentConfig("BENCH", new BigDecimal("0.01"), 1_000_000, new BigDecimal("100000.00"));

    record Result(FsyncPolicy policy, int clients, long commands, double seconds, Histogram latency, long flushes) {

        double perSecond() {
            return commands / seconds;
        }

        String row() {
            return String.format(Locale.ROOT, "| %s | %d | %,.0f | %s | %s | %.2f |", policy, clients, perSecond(),
                    micros(latency.getValueAtPercentile(50)), micros(latency.getValueAtPercentile(99)),
                    flushes / (double) commands);
        }

        private static String micros(long nanos) {
            return nanos >= 1_000_000 ? String.format(Locale.ROOT, "%.1f ms", nanos / 1e6)
                    : String.format(Locale.ROOT, "%d us", nanos / 1_000);
        }
    }

    private GroupCommitRun() {
    }

    public static void main(String[] args) throws Exception {
        int perClient = args.length > 0 ? Integer.parseInt(args[0]) : 2_000;
        int[] clients = args.length > 1
                ? Stream.of(args[1].split(",")).mapToInt(Integer::parseInt).toArray() : new int[] {1, 4, 16, 64};
        System.out.println("| Policy | Clients | Acknowledged commands per second | p50 | p99 | Flushes per command |");
        System.out.println("|---|---:|---:|---:|---:|---:|");
        for (int i = 0; i < clients.length; i++) {
            // Alternate which policy runs first, so neither always gets the warmer JVM.
            boolean groupFirst = i % 2 == 1;
            Result first = run(groupFirst ? FsyncPolicy.GROUP : FsyncPolicy.EVERY_COMMAND, clients[i], perClient);
            Result second = run(groupFirst ? FsyncPolicy.EVERY_COMMAND : FsyncPolicy.GROUP, clients[i], perClient);
            System.out.println((groupFirst ? second : first).row());
            System.out.println((groupFirst ? first : second).row());
        }
    }

    static Result run(FsyncPolicy policy, int clients, int perClient) throws IOException, InterruptedException {
        Path directory = Files.createTempDirectory("lob-group-commit");
        try {
            try (MatchingService service = MatchingService.start(directory, List.of(INSTRUMENT),
                    new JournalOptions(policy, 0), TimeSource.system(), 100_000)) {
                warmUp(service, 2_000);
                long flushesBefore = service.flushes(INSTRUMENT.symbol());
                List<Histogram> latencies = new ArrayList<>();
                CountDownLatch start = new CountDownLatch(1);
                List<Thread> threads = new ArrayList<>();
                for (int c = 0; c < clients; c++) {
                    Histogram histogram = new Histogram(60_000_000_000L, 3);
                    latencies.add(histogram);
                    int client = c;
                    threads.add(Thread.ofPlatform().start(() -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            return;
                        }
                        for (int i = 0; i < perClient; i++) {
                            long price = 1_000 + (client * 7L + i) % 5_000;          // buys: they rest
                            long t0 = System.nanoTime();
                            service.submit(INSTRUMENT.symbol(), new LimitOrder(Side.BUY, price, 1)).join();
                            histogram.recordValue(System.nanoTime() - t0);
                        }
                    }));
                }
                long t0 = System.nanoTime();
                start.countDown();
                for (Thread thread : threads) {
                    thread.join();
                }
                double seconds = (System.nanoTime() - t0) / 1e9;
                Histogram all = new Histogram(60_000_000_000L, 3);
                latencies.forEach(all::add);
                return new Result(policy, clients, (long) clients * perClient, seconds, all,
                        service.flushes(INSTRUMENT.symbol()) - flushesBefore);
            }
        } finally {
            delete(directory);
        }
    }

    private static void warmUp(MatchingService service, int commands) {
        for (int i = 0; i < commands; i++) {
            service.submit(INSTRUMENT.symbol(), new LimitOrder(Side.BUY, 500 + i % 100, 1)).join();
        }
    }

    private static void delete(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }
}
