package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.Rulebook;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The feed as a client sees it: a real WebSocket connection to the running application. */
class MarketDataWebSocketTest {

    @TempDir
    Path directory;

    private ConfigurableApplicationContext venue;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void start() {
        venue = new SpringApplicationBuilder(LobEngineApplication.class)
                .run("--server.port=0", "--lob.data-directory=" + directory, "--lob.fsync=OS", "--lob.fix.enabled=false");
    }

    @AfterEach
    void stop() {
        venue.close();
    }

    private int port() {
        return ((WebServerApplicationContext) venue).getWebServer().getPort();
    }

    /** Collects every text message of a connection. */
    private static final class Listener implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            socket.request(1);
            return null;
        }
    }

    private JsonNode next(Listener listener) throws Exception {
        String message = listener.messages.poll(5, TimeUnit.SECONDS);
        assertThat(message).as("message received").isNotNull();
        return json.readTree(message);
    }

    private void order(String body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port() + "/api/v1/instruments/AAPL/orders"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isIn(201, 422);
    }

    @Test
    @Rulebook({"MD-001", "MD-002", "MD-003", "MD-004", "MD-005"})
    void snapshotThenUpdatesWithDecimalPricesAndNoOrderIds() throws Exception {
        order("{\"side\":\"SELL\",\"type\":\"LIMIT\",\"price\":\"185.30\",\"quantity\":10}");
        Listener listener = new Listener();
        WebSocket socket = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port() + "/api/v1/market-data/AAPL"), listener).get();

        JsonNode snapshot = next(listener);
        assertThat(snapshot.get("type").asString()).isEqualTo("snapshot");
        assertThat(snapshot.get("feedSequence").asLong()).isEqualTo(1);
        assertThat(snapshot.get("asks").toString()).isEqualTo("[{\"price\":\"185.30\",\"quantity\":10,\"orders\":1}]");

        order("{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"185.20\",\"quantity\":5}");
        JsonNode resting = next(listener);
        assertThat(resting.get("feedSequence").asLong()).isEqualTo(2);
        assertThat(resting.get("bids").toString()).isEqualTo("[{\"price\":\"185.20\",\"quantity\":5,\"orders\":1}]");

        order("{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":0}");               // rejected: publishes nothing
        order("{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":10}");
        String raw = listener.messages.poll(5, TimeUnit.SECONDS);
        JsonNode trade = json.readTree(raw);
        assertThat(trade.get("feedSequence").asLong()).isEqualTo(3);
        assertThat(trade.get("commandSequence").asLong()).isEqualTo(4);
        assertThat(trade.get("timestamp").asString()).matches("\\d{4}-\\d\\d-\\d\\dT.*Z");
        assertThat(trade.get("asks").toString()).isEqualTo("[{\"price\":\"185.30\",\"quantity\":0,\"orders\":0}]");
        assertThat(trade.get("trades").toString()).isEqualTo("[{\"price\":\"185.30\",\"quantity\":10,\"aggressor\":\"BUY\"}]");
        assertThat(trade.get("bestBid").toString()).isEqualTo("{\"price\":\"185.20\",\"quantity\":5,\"orders\":1}");
        assertThat(trade.get("bestAsk").isNull()).isTrue();
        assertThat(raw).doesNotContain("orderId", "clOrdId", "member");                 // MD-001

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    @Rulebook("OE-001")
    void unknownSymbolsAreRefusedAtTheHandshake() {
        CompletableFuture<WebSocket> connection = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port() + "/api/v1/market-data/TSLA"), new Listener());

        assertThatThrownBy(connection::get).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOfSatisfying(WebSocketHandshakeException.class,
                        e -> assertThat(e.getResponse().statusCode()).isEqualTo(404));
    }
}
