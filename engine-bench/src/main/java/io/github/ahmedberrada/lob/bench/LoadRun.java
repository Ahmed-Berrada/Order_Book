package io.github.ahmedberrada.lob.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/**
 * An end-to-end load test of a running venue over its REST API (docs/performance.md): orders, cancels and market
 * orders sent at a fixed rate whatever the answers (an open loop), through HTTP, authentication, the queue, the
 * journal and matching.
 *
 * <p>Latency is measured from the moment each request was due, not from when it was sent: a venue that stalls makes
 * every request scheduled behind the stall wait, and that waiting is counted (no coordinated omission).
 *
 * <p>Usage: {@code java -cp engine-bench/target/benchmarks.jar io.github.ahmedberrada.lob.bench.LoadRun
 * http://127.0.0.1:8080 [rate per second] [seconds] [symbol] [user:password]}.
 */
public final class LoadRun {

    private static final Pattern ORDER_ID = Pattern.compile("\"orderId\"\\s*:\\s*(\\d+)");
    private static final Pattern RESTING = Pattern.compile("\"status\"\\s*:\\s*\"(NEW|PARTIALLY_FILLED)\"");

    /** What one run measured. */
    record Result(int targetRate, double seconds, long sent, long dropped, Map<Integer, Long> statuses, long failures,
            Histogram latency) {

        double achievedRate() {
            return sent / seconds;
        }

        String row() {
            return String.format(Locale.ROOT, "| %,d | %,.0f | %s | %s | %s | %s | %s | %d | %d |", targetRate,
                    achievedRate(), micros(latency.getValueAtPercentile(50)), micros(latency.getValueAtPercentile(99)),
                    micros(latency.getValueAtPercentile(99.9)), micros(latency.getMaxValue()), statuses, failures,
                    dropped);
        }

        private static String micros(long nanos) {
            return nanos >= 1_000_000 ? String.format(Locale.ROOT, "%.1f ms", nanos / 1e6)
                    : String.format(Locale.ROOT, "%d µs", nanos / 1_000);
        }
    }

    private LoadRun() {
    }

    public static void main(String[] args) throws Exception {
        URI base = URI.create(args.length > 0 ? args[0] : "http://127.0.0.1:8080");
        int rate = args.length > 1 ? Integer.parseInt(args[1]) : 1_000;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        String symbol = args.length > 3 ? args[3] : "AAPL";
        String credentials = args.length > 4 ? args[4] : null;
        run(base, symbol, credentials, rate, Duration.ofSeconds(Math.max(5, seconds / 5)));
        System.out.println("| Target /s | Achieved /s | p50 | p99 | p99.9 | max | HTTP statuses | failures | dropped |");
        System.out.println("|---:|---:|---:|---:|---:|---:|---|---:|---:|");
        System.out.println(run(base, symbol, credentials, rate, Duration.ofSeconds(seconds)).row());
    }

    /**
     * Sends at {@code rate} requests a second for {@code duration}, then waits up to 30 s for the answers.
     *
     * @param credentials {@code user:password} for HTTP Basic, or null with security off
     */
    static Result run(URI base, String symbol, String credentials, int rate, Duration duration)
            throws InterruptedException {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        String authorization = credentials == null ? null
                : "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        URI orders = base.resolve("/api/v1/instruments/" + symbol + "/orders");
        Recorder recorder = new Recorder(60_000_000_000L, 3);
        Map<Integer, AtomicLong> statuses = new ConcurrentHashMap<>();
        AtomicLong failures = new AtomicLong();
        AtomicInteger inFlight = new AtomicInteger();
        ConcurrentLinkedQueue<Long> resting = new ConcurrentLinkedQueue<>();
        List<CompletableFuture<?>> pending = new ArrayList<>();
        SplittableRandom random = new SplittableRandom(42);
        int maxInFlight = Math.max(1_000, rate * 2);

        long interval = TimeUnit.SECONDS.toNanos(1) / rate;
        long start = System.nanoTime();
        long end = start + duration.toNanos();
        long sent = 0;
        long dropped = 0;
        for (long due = start; due < end; due += interval) {
            long wait = due - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            }
            if (inFlight.get() >= maxInFlight) {
                dropped++;                                   // the venue is that far behind: count it, do not queue it
                continue;
            }
            HttpRequest request = next(random, orders, authorization, resting);
            long scheduled = due;
            inFlight.incrementAndGet();
            sent++;
            CompletableFuture<?> f = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, failure) -> {
                        recorder.recordValue(Math.max(0, System.nanoTime() - scheduled));
                        inFlight.decrementAndGet();
                        if (failure != null) {
                            failures.incrementAndGet();
                            return;
                        }
                        statuses.computeIfAbsent(response.statusCode(), k -> new AtomicLong()).incrementAndGet();
                        if (response.statusCode() == 201) {
                            Matcher id = ORDER_ID.matcher(response.body());
                            if (id.find() && RESTING.matcher(response.body()).find()) {
                                resting.add(Long.parseLong(id.group(1)));
                            }
                        }
                    })
                    .handle((response, failure) -> null);            // counted above; never fails the run
            pending.add(f);
            if (pending.size() > 10_000) {
                pending.removeIf(CompletableFuture::isDone);
            }
        }
        long sendingEnded = System.nanoTime();
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).completeOnTimeout(null, 30, TimeUnit.SECONDS)
                .join();
        Map<Integer, Long> counts = new TreeMap<>();
        statuses.forEach((status, count) -> counts.put(status, count.get()));
        return new Result(rate, (sendingEnded - start) / 1e9, sent, dropped, counts, failures.get(),
                recorder.getIntervalHistogram());
    }

    /** 60% resting limit orders around 100.00, 30% cancels of orders this run left resting, 10% market orders. */
    private static HttpRequest next(SplittableRandom random, URI orders, String authorization,
            ConcurrentLinkedQueue<Long> resting) {
        int dice = random.nextInt(100);
        HttpRequest.Builder builder;
        Long cancel = dice < 30 ? resting.poll() : null;
        if (cancel != null) {
            builder = HttpRequest.newBuilder(URI.create(orders + "/" + cancel)).DELETE();
        } else {
            boolean buy = random.nextBoolean();
            String body;
            if (dice >= 90) {
                body = "{\"side\":\"" + (buy ? "BUY" : "SELL") + "\",\"type\":\"MARKET\",\"quantity\":1}";
            } else {
                int ticks = 10_000 + (buy ? -1 : 1) * random.nextInt(1, 200);          // never crossing: they rest
                body = String.format(Locale.ROOT, "{\"side\":\"%s\",\"type\":\"LIMIT\",\"price\":\"%d.%02d\",\"quantity\":%d}",
                        buy ? "BUY" : "SELL", ticks / 100, ticks % 100, random.nextInt(1, 10));
            }
            builder = HttpRequest.newBuilder(orders).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return builder.timeout(Duration.ofSeconds(30)).build();
    }
}
