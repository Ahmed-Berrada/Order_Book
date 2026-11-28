package io.github.ahmedberrada.lob.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The load harness keeps its rate, mixes its requests and counts every answer; against a stub, not a venue. */
class LoadRunTest {

    @Test
    void sendsAtTheRateItWasGivenAndCountsEveryAnswer() throws IOException, InterruptedException {
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.setExecutor(Executors.newFixedThreadPool(4));
        stub.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean cancel = "DELETE".equals(exchange.getRequestMethod());
            if (cancel) {
                cancels.incrementAndGet();
            }
            byte[] body = (cancel ? "{}" : "{\"orderId\":" + ids.incrementAndGet() + ",\"status\":\"NEW\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(cancel ? 200 : 201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + stub.getAddress().getPort());
            LoadRun.Result result = LoadRun.run(base, "AAPL", "user:pass", 400, Duration.ofSeconds(2));

            assertThat(result.sent()).isBetween(780L, 800L);
            assertThat(result.failures()).isZero();
            assertThat(result.dropped()).isZero();
            assertThat(result.latency().getTotalCount()).isEqualTo(result.sent());
            assertThat(result.statuses().get(201) + result.statuses().get(200)).isEqualTo(result.sent());
            assertThat(cancels.get()).as("about 30% cancels of resting orders").isBetween(150, 300);
            assertThat(result.row()).contains("| 400 |");
        } finally {
            stub.stop(0);
        }
    }
}
