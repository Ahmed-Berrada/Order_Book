package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The tick-size regime, DAY orders and the volatility band, configured for AAPL and used over REST. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class VenueControlsApiTest {

    private static final String AAPL = "/api/v1/instruments/AAPL";

    @DynamicPropertySource
    static void venue(DynamicPropertyRegistry registry) {
        TemporaryVenue.register(registry);
        // a list from a higher-priority source replaces the whole list, so the instrument is complete here
        registry.add("lob.instruments[0].symbol", () -> "AAPL");
        registry.add("lob.instruments[0].tick-size", () -> "0.01");
        registry.add("lob.instruments[0].max-order-quantity", () -> "1000000");
        registry.add("lob.instruments[0].max-price", () -> "100000.00");
        registry.add("lob.instruments[0].tick-regime[0].from-price", () -> "0.01");
        registry.add("lob.instruments[0].tick-regime[0].step", () -> "0.01");
        registry.add("lob.instruments[0].tick-regime[1].from-price", () -> "100.00");
        registry.add("lob.instruments[0].tick-regime[1].step", () -> "0.05");
        registry.add("lob.instruments[0].volatility-band-bps", () -> "500");
    }

    @Autowired
    MockMvcTester mvc;

    private MvcTestResult order(String json) {
        return mvc.post().uri(AAPL + "/orders").contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private static String limit(String side, String price, long quantity) {
        return "{\"side\":\"%s\",\"type\":\"LIMIT\",\"price\":\"%s\",\"quantity\":%d}".formatted(side, price, quantity);
    }

    @Test
    @Rulebook("TK-002")
    void aPriceOffTheBandsStepIsRejectedByTheEngine() {
        assertThat(order(limit("BUY", "99.97", 1))).hasStatus(HttpStatus.CREATED);
        assertThat(order(limit("BUY", "100.03", 1))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"INVALID_TICK_SIZE\"}");
        assertThat(order(limit("BUY", "100.05", 1))).hasStatus(HttpStatus.CREATED);
        assertThat(mvc.put().uri(AAPL + "/orders/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":\"100.02\",\"quantity\":1}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().isLenientlyEqualTo("{\"reason\":\"INVALID_TICK_SIZE\"}");
    }

    @Test
    @Rulebook("DY-001")
    void aDayOrderRestsAndExpiresWhenTheInstrumentCloses() {
        assertThat(order("{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"50.00\",\"quantity\":2,\"timeInForce\":\"DAY\"}"))
                .hasStatus(HttpStatus.CREATED).bodyJson().isLenientlyEqualTo("{\"status\":\"NEW\"}");
        assertThat(order("{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":2,\"timeInForce\":\"DAY\"}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"INVALID_TIME_IN_FORCE\"}");

        assertThat(mvc.put().uri(AAPL + "/phase").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phase\":\"CLOSED\"}")).hasStatusOk();

        assertThat(mvc.get().uri(AAPL + "/orders/1")).bodyJson().isLenientlyEqualTo("{\"resting\":false}");
    }

    @Test
    @Rulebook({"VI-002", "VI-003"})
    void aTradeOutsideTheBandInterruptsTrading() {
        assertThat(order(limit("SELL", "100.00", 10))).hasStatus(HttpStatus.CREATED);
        assertThat(order(limit("BUY", "100.00", 10))).hasStatus(HttpStatus.CREATED);     // the reference price
        assertThat(order(limit("SELL", "106.00", 5))).hasStatus(HttpStatus.CREATED);

        assertThat(order("{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":5}"))
                .hasStatus(HttpStatus.CREATED).bodyJson().isLenientlyEqualTo(
                        "{\"status\":\"CANCELLED\",\"filledQuantity\":0,\"cancelledQuantity\":5}");

        assertThat(mvc.get().uri("/api/v1/instruments")).bodyJson().extractingPath("$[0].phase").isEqualTo("CALL");
        assertThat(order(limit("BUY", "100.00", 1))).hasStatus(HttpStatus.CREATED);   // collected, not matched
    }
}
