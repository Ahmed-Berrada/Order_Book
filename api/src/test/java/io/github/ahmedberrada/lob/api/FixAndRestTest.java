package io.github.ahmedberrada.lob.api;

import static io.github.ahmedberrada.lob.fix.Orders.field;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.fix.FixTestClient;
import io.github.ahmedberrada.lob.fix.Orders;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import quickfix.Message;
import quickfix.field.ClOrdID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.Side;

/**
 * One venue, two protocols: a FIX member's resting order is filled and cancelled over REST, and the
 * member is told on its FIX session (ADR-0004 §7, ADR-0006).
 */
class FixAndRestTest {

    @TempDir
    Path directory;

    @Test
    void restActivityOnAFixOrderIsReportedOverFix() throws Exception {
        int fixPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            fixPort = socket.getLocalPort();
        }
        try (ConfigurableApplicationContext venue = new SpringApplicationBuilder(LobEngineApplication.class)
                .run("--server.port=0", "--lob.data-directory=" + directory.resolve("venue"), "--lob.fsync=OS",
                        "--lob.fix.enabled=true", "--lob.fix.port=" + fixPort);
                FixTestClient member = new FixTestClient("MEMBER1", fixPort, directory.resolve("client")).logon()) {

            member.send(Orders.limit("S-1", Side.SELL, "185.30", "10"));
            assertThat(field(member.next(), ExecType.FIELD)).isEqualTo("0");

            HttpResponse<String> buy = rest(venue, "POST", "/api/v1/instruments/AAPL/orders",
                    "{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":4}");
            assertThat(buy.statusCode()).isEqualTo(201);
            assertThat(buy.body()).contains("\"status\":\"FILLED\"", "\"price\":\"185.30\"");

            Message fill = member.next();
            assertThat(field(fill, ClOrdID.FIELD)).isEqualTo("S-1");
            assertThat(field(fill, ExecType.FIELD)).isEqualTo("F");
            assertThat(field(fill, LastPx.FIELD)).isEqualTo("185.30");
            assertThat(field(fill, LastQty.FIELD)).isEqualTo("4");

            assertThat(rest(venue, "DELETE", "/api/v1/instruments/AAPL/orders/1", null).statusCode()).isEqualTo(200);
            Message cancelled = member.next();
            assertThat(field(cancelled, ExecType.FIELD)).isEqualTo("4");
            assertThat(field(cancelled, LeavesQty.FIELD)).isEqualTo("0");
        }
    }

    private static HttpResponse<String> rest(ConfigurableApplicationContext venue, String method, String path,
            String json) throws Exception {
        int port = ((WebServerApplicationContext) venue).getWebServer().getPort();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
