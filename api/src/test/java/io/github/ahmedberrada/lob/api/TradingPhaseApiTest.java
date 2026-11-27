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

/** Trading phases over REST: the operator endpoint, and what each phase lets through. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TradingPhaseApiTest {

    private static final String AAPL = "/api/v1/instruments/AAPL";

    @DynamicPropertySource
    static void venue(DynamicPropertyRegistry registry) {
        TemporaryVenue.register(registry);
    }

    @Autowired
    MockMvcTester mvc;

    private MvcTestResult phase(String body) {
        return mvc.put().uri(AAPL + "/phase").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult order(String json) {
        return mvc.post().uri(AAPL + "/orders").contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private static String limit(String side, String price, long quantity) {
        return "{\"side\":\"%s\",\"type\":\"LIMIT\",\"price\":\"%s\",\"quantity\":%d}".formatted(side, price, quantity);
    }

    @Test
    @Rulebook({"TP-002", "TP-005", "AU-001", "AU-003"})
    void aCallPhaseCollectsOrdersAndTheUncrossReportsItsPrice() {
        assertThat(phase("{\"phase\":\"CALL\"}")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"symbol":"AAPL","from":"CONTINUOUS","to":"CALL","auctionVolume":0,"trades":0}""");
        assertThat(mvc.get().uri("/api/v1/instruments")).bodyJson()
                .extractingPath("$[0].phase").isEqualTo("CALL");

        assertThat(order(limit("BUY", "185.00", 5))).hasStatus(HttpStatus.CREATED);
        assertThat(order(limit("SELL", "184.00", 5))).hasStatus(HttpStatus.CREATED);
        assertThat(mvc.get().uri(AAPL + "/book")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"phase":"CALL","bids":[{"price":"185.00","quantity":5,"orders":1}],
                 "asks":[{"price":"184.00","quantity":5,"orders":1}]}""");

        assertThat(phase("{\"phase\":\"CONTINUOUS\"}")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"from":"CALL","to":"CONTINUOUS","auctionPrice":"185.00","auctionVolume":5,"trades":1}""");
        assertThat(mvc.get().uri(AAPL + "/book")).bodyJson()
                .isLenientlyEqualTo("{\"phase\":\"CONTINUOUS\",\"bids\":[],\"asks\":[]}");
    }

    @Test
    @Rulebook("TP-005")
    void aCallPhaseRefusesOrdersThatAskToTradeNow() {
        phase("{\"phase\":\"CALL\"}");

        assertThat(order("{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"NOT_ALLOWED_IN_PHASE\"}");
        assertThat(order("{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"1.00\",\"quantity\":1,\"timeInForce\":\"IOC\"}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    @Rulebook("TP-004")
    void closedRefusesOrdersAndReplacesButAcceptsCancels() {
        assertThat(order(limit("BUY", "185.00", 5))).hasStatus(HttpStatus.CREATED);
        assertThat(phase("{\"phase\":\"CLOSED\"}")).hasStatusOk();

        assertThat(order(limit("BUY", "185.00", 5))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"NOT_ALLOWED_IN_PHASE\"}");
        assertThat(mvc.put().uri(AAPL + "/orders/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":\"184.00\",\"quantity\":5}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().isLenientlyEqualTo("{\"reason\":\"NOT_ALLOWED_IN_PHASE\"}");
        assertThat(mvc.delete().uri(AAPL + "/orders/1")).hasStatusOk();
    }

    @Test
    @Rulebook("TP-002")
    void aChangeToTheCurrentPhaseIsAConflictAndBadRequestsAreRefused() {
        assertThat(phase("{\"phase\":\"CONTINUOUS\"}")).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"INVALID_PHASE_TRANSITION\"}");
        assertThat(phase("{}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(phase("{\"phase\":\"LUNCH\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.put().uri("/api/v1/instruments/TSLA/phase").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phase\":\"CALL\"}")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
