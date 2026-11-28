package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole venue in a separate JVM, as it runs in production, crashed the hard ways (RS-012): killed with
 * SIGKILL while clients are entering orders, and with the storage of a journal full. "Acknowledged" here is
 * what a member sees: a {@code 201 Created} with an order ID.
 */
class VenueCrashTest {

    private static final Pattern ORDER_ID = Pattern.compile("\"orderId\"\\s*:\\s*(\\d+)");
    private static final Pattern REASON = Pattern.compile("\"reason\"\\s*:\\s*\"([A-Z_]+)\"");
    private static final Duration START_TIMEOUT = Duration.ofSeconds(90);

    @TempDir
    Path data;

    @TempDir
    Path logs;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Process> venues = new ArrayList<>();

    @AfterEach
    void stopVenues() throws InterruptedException {
        for (Process venue : venues) {
            venue.destroyForcibly();
            venue.waitFor(30, TimeUnit.SECONDS);
        }
    }

    @Test
    @Rulebook({"RS-002", "RS-012"})
    void aVenueKilledWhileClientsTradeLosesNoAcknowledgedOrder() throws Exception {
        int port = freePort();
        Process venue = start(port, "EVERY_COMMAND", 0, "first");
        Map<Long, Long> acknowledged = new ConcurrentHashMap<>();      // order ID -> price in cents
        AtomicInteger sent = new AtomicInteger();
        ExecutorService clients = Executors.newFixedThreadPool(4);
        for (int c = 0; c < 4; c++) {
            clients.submit(() -> {
                while (!Thread.currentThread().isInterrupted() && venue.isAlive()) {
                    long cents = 10_000 + sent.incrementAndGet() % 5_000;      // buys well below any sell: they rest
                    try {
                        HttpResponse<String> response = order(port, "AAPL", "BUY", cents, 1);
                        if (response.statusCode() == 201) {
                            acknowledged.put(orderId(response.body()), cents);
                        }
                    } catch (IOException e) {
                        return;                                                 // the venue is gone
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (acknowledged.size() < 300 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        venue.destroyForcibly();                                        // SIGKILL, with requests in flight
        assertThat(venue.waitFor(30, TimeUnit.SECONDS)).isTrue();
        clients.shutdownNow();
        assertThat(clients.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(acknowledged).as("orders acknowledged before the kill").hasSizeGreaterThanOrEqualTo(300);
        assertThat(sent.get()).as("requests were still being sent when it died").isGreaterThan(acknowledged.size());

        start(port, "EVERY_COMMAND", 0, "after-kill");
        for (Map.Entry<Long, Long> order : acknowledged.entrySet()) {
            HttpResponse<String> status = get(port, "/api/v1/instruments/AAPL/orders/" + order.getKey());
            assertThat(status.body()).as("order %d after the restart", order.getKey())
                    .contains("\"resting\":true").contains("\"restingQuantity\":1");
        }
        HttpResponse<String> next = order(port, "AAPL", "BUY", 10_000, 1);
        assertThat(next.statusCode()).isEqualTo(201);
        assertThat(orderId(next.body())).as("order IDs continue after the highest acknowledged")
                .isGreaterThan(acknowledged.keySet().stream().mapToLong(Long::longValue).max().orElseThrow());
    }

    @Test
    @Rulebook({"OE-006", "RS-012"})
    void aFullDiskHaltsOnlyTheInstrumentWhoseJournalFilledAndARestartRecoversIt() throws Exception {
        assumeThat(System.getProperty("os.name").toLowerCase(Locale.ROOT)).as("ulimit needs a POSIX shell")
                .doesNotContain("windows");
        int port = freePort();
        // Every file the venue writes may hold at most 64 KiB: writing past it fails with "File too large", the
        // same IOException a full disk gives. AAPL's journals reach it; MSFT's, barely used, do not.
        start(port, "OS", 64, "full-disk");
        List<Long> acknowledged = new ArrayList<>();
        String refusal = null;
        for (int i = 0; i < 20_000 && refusal == null; i++) {
            HttpResponse<String> response = order(port, "AAPL", "BUY", 10_000 + i % 5_000, 1);
            if (response.statusCode() == 201) {
                acknowledged.add(orderId(response.body()));
            } else {
                refusal = response.statusCode() + " " + reason(response.body());
            }
        }
        assertThat(refusal).as("the journal filled up").isNotNull();
        assertThat(refusal).isIn("500 OUTCOME_UNKNOWN", "503 HALTED");
        assertThat(acknowledged).hasSizeGreaterThan(100);

        HttpResponse<String> after = order(port, "AAPL", "BUY", 10_000, 1);
        assertThat(after.statusCode()).isEqualTo(503);
        assertThat(reason(after.body())).isEqualTo("HALTED");
        assertThat(order(port, "MSFT", "BUY", 10_000, 1).statusCode()).as("MSFT keeps trading").isEqualTo(201);
        assertThat(get(port, "/actuator/health/readiness").statusCode()).as("readiness is down").isEqualTo(503);
        assertThat(get(port, "/actuator/prometheus").body())
                .containsPattern("lob_instrument_halted\\{[^}]*symbol=\"AAPL\"[^}]*\\} 1\\.0");
        assertThat(Files.readString(logs.resolve("full-disk.log"))).contains("Instrument AAPL halted");

        venues.getLast().destroy();                                     // the operator restarts it, with room
        assertThat(venues.getLast().waitFor(60, TimeUnit.SECONDS)).isTrue();
        start(port, "OS", 0, "after-full-disk");
        for (long orderId : acknowledged) {
            assertThat(get(port, "/api/v1/instruments/AAPL/orders/" + orderId).body())
                    .as("order %d after the restart", orderId).contains("\"resting\":true");
        }
        assertThat(order(port, "AAPL", "BUY", 10_000, 1).statusCode()).as("AAPL trades again").isEqualTo(201);
        assertThat(get(port, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    // ---- The venue as a separate process -------------------------------------------------------------

    /** Starts the venue on {@code port} and waits until it answers; {@code fileLimitKib} 0 means no limit. */
    private Process start(int port, String fsync, int fileLimitKib, String name) throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> command = new ArrayList<>();
        if (fileLimitKib > 0) {
            command.addAll(List.of("bash", "-c", "ulimit -f " + fileLimitKib + "; exec \"$0\" \"$@\""));
        }
        command.addAll(List.of(java, "-Xmx256m", "-cp", System.getProperty("java.class.path"),
                LobEngineApplication.class.getName(),
                "--spring.profiles.active=dev",
                "--server.port=" + port,
                "--lob.data-directory=" + data,
                "--lob.fsync=" + fsync,
                "--lob.fix.enabled=false",
                "--lob.binary-feed.enabled=false"));
        Path log = logs.resolve(name + ".log");
        Process venue = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        venues.add(venue);
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(venue.isAlive()).as("the venue exited during start-up:%n%s", Files.readString(log)).isTrue();
            try {
                if (get(port, "/actuator/health/liveness").statusCode() == 200) {
                    return venue;
                }
            } catch (IOException notYet) {
                // not listening yet
            }
            Thread.sleep(200);
        }
        throw new AssertionError("the venue did not start within " + START_TIMEOUT + ":\n" + Files.readString(log));
    }

    private HttpResponse<String> order(int port, String symbol, String side, long cents, int quantity)
            throws IOException, InterruptedException {
        String body = String.format(Locale.ROOT, "{\"side\":\"%s\",\"type\":\"LIMIT\",\"price\":\"%d.%02d\",\"quantity\":%d}",
                side, cents / 100, cents % 100, quantity);
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/instruments/"
                        + symbol + "/orders")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static long orderId(String body) {
        Matcher m = ORDER_ID.matcher(body);
        assertThat(m.find()).as("an order ID in %s", body).isTrue();
        return Long.parseLong(m.group(1));
    }

    private static String reason(String body) {
        Matcher m = REASON.matcher(body);
        return m.find() ? m.group(1) : body;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
