package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The REST API with security on: who may call what, and which member an order is entered for. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SecurityApiTest {

    private static final String AAPL = "/api/v1/instruments/AAPL";

    @DynamicPropertySource
    static void venue(DynamicPropertyRegistry registry) {
        TemporaryVenue.register(registry);
        registry.add("lob.security.enabled", () -> "true");
        String[][] users = {{"admin", "ADMIN", "0"}, {"alice", "MEMBER", "1"}, {"bob", "MEMBER", "2"},
            {"reader", "READ", "0"}};
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        for (int i = 0; i < users.length; i++) {
            int index = i;
            registry.add("lob.security.users[%d].name".formatted(i), () -> users[index][0]);
            registry.add("lob.security.users[%d].password".formatted(i),
                    () -> "{bcrypt}" + encoder.encode(users[index][0] + "-pass"));
            registry.add("lob.security.users[%d].roles[0]".formatted(i), () -> users[index][1]);
            registry.add("lob.security.users[%d].member-id".formatted(i), () -> users[index][2]);
        }
        // a list from a higher-priority source replaces the whole list, so the instrument is complete here
        registry.add("lob.instruments[0].symbol", () -> "AAPL");
        registry.add("lob.instruments[0].tick-size", () -> "0.01");
        registry.add("lob.instruments[0].max-order-quantity", () -> "1000000");
        registry.add("lob.instruments[0].max-price", () -> "100000.00");
        registry.add("lob.members[0].id", () -> "1");
        registry.add("lob.members[0].name", () -> "Alpha");
        registry.add("lob.members[0].limits.max-order-quantity", () -> "5");
        registry.add("lob.members[1].id", () -> "2");
        registry.add("lob.members[1].name", () -> "Beta");
    }

    @Autowired
    MockMvcTester mvc;

    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + user + "-pass").getBytes(StandardCharsets.UTF_8));
    }

    private MvcTestResult order(String user, String memberHeader, long quantity) {
        var request = mvc.post().uri(AAPL + "/orders").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, basic(user))
                .content("{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"10.00\",\"quantity\":%d}".formatted(quantity));
        return (memberHeader == null ? request : request.header("X-Member-Id", memberHeader)).exchange();
    }

    @Test
    @Rulebook("AC-001")
    void everyRequestNeedsValidCredentials() {
        assertThat(mvc.get().uri("/api/v1/instruments")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/v1/instruments").header(HttpHeaders.AUTHORIZATION,
                "Basic " + Base64.getEncoder().encodeToString("admin:wrong".getBytes(StandardCharsets.UTF_8))))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/v1/instruments").header(HttpHeaders.AUTHORIZATION,
                "Basic " + Base64.getEncoder().encodeToString("nobody:admin-pass".getBytes(StandardCharsets.UTF_8))))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/v1/instruments").header(HttpHeaders.AUTHORIZATION, basic("reader")))
                .hasStatusOk();
        assertThat(mvc.post().uri(AAPL + "/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Rulebook({"AC-001", "AC-004"})
    void healthProbesAreOpenButMetricsNeedARole() {
        assertThat(mvc.get().uri("/actuator/health/liveness")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/health/readiness")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/prometheus")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, basic("reader")))
                .hasStatusOk();
        assertThat(mvc.get().uri("/actuator/env").header(HttpHeaders.AUTHORIZATION, basic("admin")))
                .hasStatus(HttpStatus.NOT_FOUND);                               // not exposed at all
    }

    @Test
    @Rulebook("AC-008")
    void aBrowserCannotBeMadeToWriteFromAnotherSite() {
        String body = "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"10.00\",\"quantity\":1}";
        for (String site : new String[] {"cross-site", "same-site", "something-new"}) {
            assertThat(mvc.post().uri(AAPL + "/orders").contentType(MediaType.APPLICATION_JSON).content(body)
                    .header(HttpHeaders.AUTHORIZATION, basic("alice")).header("Sec-Fetch-Site", site))
                    .as(site).hasStatus(HttpStatus.FORBIDDEN);
        }
        assertThat(mvc.post().uri(AAPL + "/orders").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, basic("alice")).header("Origin", "https://evil.example"))
                .hasStatus(HttpStatus.FORBIDDEN);                              // an old browser: Origin only
        MvcTestResult form = mvc.post().uri(AAPL + "/orders").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("side=BUY").header(HttpHeaders.AUTHORIZATION, basic("alice")).exchange();
        assertThat(form).hasStatus(HttpStatus.FORBIDDEN);                  // a form from a browser sending neither
        assertThat(form.getRequest().getSession(false)).isNull();          // refusing creates no session
        assertThat(form.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(mvc.put().uri("/api/v1/members/2/suspension").header(HttpHeaders.AUTHORIZATION, basic("admin"))
                .header("Sec-Fetch-Site", "cross-site")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.delete().uri(AAPL + "/orders/1").header(HttpHeaders.AUTHORIZATION, basic("alice"))
                .header("Sec-Fetch-Site", "cross-site")).hasStatus(HttpStatus.FORBIDDEN);

        // the venue's own pages, the user's own action, and machine clients
        assertThat(mvc.post().uri(AAPL + "/orders").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, basic("alice")).header("Sec-Fetch-Site", "same-origin"))
                .hasStatus(HttpStatus.CREATED);
        assertThat(mvc.post().uri(AAPL + "/orders").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, basic("alice")).header("Sec-Fetch-Site", "none"))
                .hasStatus(HttpStatus.CREATED);
        assertThat(order("alice", null, 1)).hasStatus(HttpStatus.CREATED);
        // reading across sites is not blocked here: the browser keeps the answer from the other site
        assertThat(mvc.get().uri(AAPL + "/book").header(HttpHeaders.AUTHORIZATION, basic("reader"))
                .header("Sec-Fetch-Site", "cross-site")).hasStatusOk();
    }

    @Test
    @Rulebook("AC-004")
    void rolesLimitWhatACallerMayDo() {
        // READ: queries only
        assertThat(mvc.get().uri(AAPL + "/book").header(HttpHeaders.AUTHORIZATION, basic("reader"))).hasStatusOk();
        assertThat(order("reader", null, 1)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/v1/members/1").header(HttpHeaders.AUTHORIZATION, basic("reader")))
                .hasStatus(HttpStatus.FORBIDDEN);
        // MEMBER: orders, but not the operator's endpoints
        assertThat(order("alice", null, 1)).hasStatus(HttpStatus.CREATED);
        assertThat(mvc.put().uri(AAPL + "/phase").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, basic("alice")).content("{\"phase\":\"CALL\"}"))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.put().uri("/api/v1/members/1/suspension").header(HttpHeaders.AUTHORIZATION, basic("alice")))
                .hasStatus(HttpStatus.FORBIDDEN);
        // ADMIN: everything
        assertThat(mvc.get().uri("/api/v1/members/1").header(HttpHeaders.AUTHORIZATION, basic("admin")))
                .hasStatusOk();
        assertThat(mvc.put().uri(AAPL + "/phase").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, basic("admin")).content("{\"phase\":\"CALL\"}")).hasStatusOk();
        // anything the chain does not name is refused
        assertThat(mvc.patch().uri(AAPL).header(HttpHeaders.AUTHORIZATION, basic("admin")))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    @Rulebook("AC-005")
    void aMemberUserActsForItsOwnMemberOnly() {
        // alice is member 1, whose limit is 5 lots: the limit applies although no header names the member
        assertThat(order("alice", null, 6)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"RISK_ORDER_QUANTITY\"}");
        assertThat(order("alice", "1", 5)).hasStatus(HttpStatus.CREATED);
        assertThat(order("alice", "2", 5)).hasStatus(HttpStatus.FORBIDDEN);          // naming another member
        assertThat(order("bob", null, 600)).hasStatus(HttpStatus.CREATED);           // member 2 has no limit
        // bob cannot cancel alice's order, and the admin can name any member
        assertThat(mvc.delete().uri(AAPL + "/orders/1").header(HttpHeaders.AUTHORIZATION, basic("bob")))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(order("admin", "1", 6)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(order("admin", null, 600)).hasStatus(HttpStatus.CREATED);         // the operator: no limits
    }
}
