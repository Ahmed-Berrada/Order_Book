package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.marketdata.BookLevel;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryFeedClient;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** The running application publishes the binary feed; a reference consumer rebuilds the book from it. */
class BinaryFeedEndToEndTest {

    @TempDir
    Path directory;

    @Test
    @Rulebook({"BF-001", "BF-005"})
    void ordersOverRestReachABinaryConsumer() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        int streamPort;
        int servicePort;
        try (DatagramSocket udp = new DatagramSocket(0, loopback); ServerSocket tcp = new ServerSocket(0)) {
            streamPort = udp.getLocalPort();
            servicePort = tcp.getLocalPort();
        }
        try (ConfigurableApplicationContext venue = new SpringApplicationBuilder(LobEngineApplication.class)
                .run("--server.port=0", "--lob.data-directory=" + directory, "--lob.fsync=OS",
                        "--lob.fix.enabled=false", "--lob.binary-feed.stream-port=" + streamPort,
                        "--lob.binary-feed.service-port=" + servicePort);
                BinaryFeedClient consumer = new BinaryFeedClient(new InetSocketAddress(loopback, streamPort),
                        new InetSocketAddress(loopback, servicePort)).start()) {
            int port = ((WebServerApplicationContext) venue).getWebServer().getPort();
            HttpClient http = HttpClient.newHttpClient();
            for (String order : List.of("{\"side\":\"SELL\",\"type\":\"LIMIT\",\"price\":\"185.30\",\"quantity\":10}",
                    "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"185.20\",\"quantity\":5}",
                    "{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":4}")) {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port + "/api/v1/instruments/AAPL/orders"))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(order))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(201);
            }

            List<BookLevel> asks = List.of(new BookLevel(18_530, 6, 1));
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!consumer.asks("AAPL").equals(asks) && System.nanoTime() < deadline) {
                consumer.poll(100);
            }
            assertThat(consumer.asks("AAPL")).isEqualTo(asks);
            assertThat(consumer.bids("AAPL")).containsExactly(new BookLevel(18_520, 5, 1));
            assertThat(consumer.feedSequence("AAPL")).isEqualTo(3);
        }
    }
}
