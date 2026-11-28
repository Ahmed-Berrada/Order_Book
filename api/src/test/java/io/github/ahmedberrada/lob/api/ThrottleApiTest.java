package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The message-rate throttle through the real venue (OE-008): 429 with Retry-After, cancels still accepted. */
@SpringBootTest
@AutoConfigureMockMvc
class ThrottleApiTest {

    private static final String AAPL = "/api/v1/instruments/AAPL";

    @DynamicPropertySource
    static void venue(DynamicPropertyRegistry registry) {
        TemporaryVenue.register(registry);
        registry.add("lob.throttle.messages-per-second", () -> "1");
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MeterRegistry meters;

    private MvcTestResult order(String member) {
        var request = mvc.post().uri(AAPL + "/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"10.00\",\"quantity\":1}");
        return (member == null ? request : request.header("X-Member-Id", member)).exchange();
    }

    @Test
    @Rulebook("OE-008")
    void aMemberOverItsRateIsAskedToRetryAndMayStillCancel() throws Exception {
        MvcTestResult accepted = order("2");
        assertThat(accepted).hasStatus(HttpStatus.CREATED);
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("\"orderId\":(\\d+)")
                .matcher(accepted.getResponse().getContentAsString());
        assertThat(id.find()).isTrue();
        long orderId = Long.parseLong(id.group(1));

        // At one message a second, one of the next few is refused however slow the machine is.
        MvcTestResult refused = null;
        for (int i = 0; i < 10 && refused == null; i++) {
            MvcTestResult next = order("2");
            if (next.getResponse().getStatus() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                refused = next;
            }
        }
        assertThat(refused).isNotNull();
        assertThat(refused).hasHeader("Retry-After", "1")
                .bodyJson().isLenientlyEqualTo("{\"reason\":\"THROTTLED\",\"status\":429}");
        assertThat(mvc.delete().uri(AAPL + "/orders/" + orderId).header("X-Member-Id", "2")).hasStatusOk();
        assertThat(order(null)).hasStatus(HttpStatus.CREATED);                // the operator is exempt
        assertThat(order(null)).hasStatus(HttpStatus.CREATED);
        assertThat(meters.get("lob.throttled").tag("symbol", "AAPL").tag("member", "2").tag("reason", "THROTTLED")
                .counter().count()).isEqualTo(1.0);
    }
}
