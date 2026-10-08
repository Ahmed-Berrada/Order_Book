package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The real application on a real port, driven by a plain HTTP client: concurrent orders, then a
 * restart on the same journals, which must bring the book back exactly (RS-002).
 */
class EndToEndTest {

    @TempDir
    Path dataDirectory;

    private final HttpClient http = HttpClient.newHttpClient();

    private ConfigurableApplicationContext startVenue() {
        // Command-line arguments, not builder properties: those are defaults that application.yml overrides.
        return new SpringApplicationBuilder(LobEngineApplication.class)
                .run("--server.port=0", "--lob.data-directory=" + dataDirectory, "--lob.fsync=OS");
    }

    private static URI uri(ConfigurableApplicationContext venue, String path) {
        int port = ((WebServerApplicationContext) venue).getWebServer().getPort();
        return URI.create("http://localhost:" + port + path);
    }

    private CompletableFuture<HttpResponse<String>> post(ConfigurableApplicationContext venue, String json) {
        HttpRequest request = HttpRequest.newBuilder(uri(venue, "/api/v1/instruments/AAPL/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private String get(ConfigurableApplicationContext venue, String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(venue, path)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    @Rulebook({"OE-003", "OE-007", "RS-002"})
    void concurrentOrdersOverHttpSurviveARestart() throws Exception {
        String bookBefore;
        try (ConfigurableApplicationContext venue = startVenue()) {
            List<CompletableFuture<HttpResponse<String>>> responses = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                String side = i % 2 == 0 ? "BUY" : "SELL";
                String price = i % 2 == 0 ? "100.0" + (i % 5) : "100.0" + (5 + i % 5);
                responses.add(post(venue, "{\"side\":\"%s\",\"type\":\"LIMIT\",\"price\":\"%s\",\"quantity\":%d}"
                        .formatted(side, price, 1 + i % 3)));
            }
            for (CompletableFuture<HttpResponse<String>> response : responses) {
                assertThat(response.join().statusCode()).as(response.join().body()).isEqualTo(201);
            }
            // Only once the asks are known to rest: sent concurrently, it could arrive first (NO_LIQUIDITY).
            assertThat(post(venue, "{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":3}").join().statusCode())
                    .isEqualTo(201);
            bookBefore = get(venue, "/api/v1/instruments/AAPL/book?depth=100");
            assertThat(bookBefore).contains("\"commandSequence\":51");
        }

        try (ConfigurableApplicationContext venue = startVenue()) {
            assertThat(get(venue, "/api/v1/instruments/AAPL/book?depth=100")).isEqualTo(bookBefore);
            assertThat(post(venue, "{\"side\":\"SELL\",\"type\":\"MARKET\",\"quantity\":1}").join().body())
                    .contains("\"commandSequence\":52");
        }
    }
}
