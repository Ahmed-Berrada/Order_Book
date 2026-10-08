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

/**
 * Order entry through the real service and journals. Each test starts a fresh venue, so order IDs and
 * sequence numbers are predictable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OrderApiTest {

    private static final String ORDERS = "/api/v1/instruments/AAPL/orders";

    @DynamicPropertySource
    static void venue(DynamicPropertyRegistry registry) {
        TemporaryVenue.register(registry);
    }

    @Autowired
    MockMvcTester mvc;

    private MvcTestResult post(String json) {
        return mvc.post().uri(ORDERS).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private static String limit(String side, String price, long quantity) {
        return "{\"side\":\"%s\",\"type\":\"LIMIT\",\"price\":\"%s\",\"quantity\":%d}".formatted(side, price, quantity);
    }

    private static String market(String side, long quantity) {
        return "{\"side\":\"%s\",\"type\":\"MARKET\",\"quantity\":%d}".formatted(side, quantity);
    }

    // ---- Accepted orders --------------------------------------------------------------------------

    @Test
    @Rulebook({"OE-002", "OE-004"})
    void restingOrderThenAFillAgainstIt() {
        MvcTestResult placed = post(limit("BUY", "185.25", 10));
        assertThat(placed).hasStatus(HttpStatus.CREATED).hasHeader("Location", ORDERS + "/1");
        assertThat(placed).bodyJson().isLenientlyEqualTo("""
                {"orderId":1,"status":"NEW","commandSequence":1,"filledQuantity":0,
                 "restingQuantity":10,"cancelledQuantity":0,"trades":[]}""");
        assertThat(placed).bodyJson().extractingPath("$.timestamp").asString().matches("\\d{4}-\\d\\d-\\d\\dT.*Z");

        assertThat(post(market("SELL", 4))).hasStatus(HttpStatus.CREATED).bodyJson().isLenientlyEqualTo("""
                {"orderId":2,"status":"FILLED","commandSequence":2,"filledQuantity":4,"restingQuantity":0,
                 "trades":[{"sequence":4,"makerOrderId":1,"price":"185.25","quantity":4}]}""");

        assertThat(mvc.get().uri(ORDERS + "/1")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"orderId\":1,\"resting\":true,\"restingQuantity\":6}");
        assertThat(mvc.get().uri(ORDERS + "/2")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"orderId\":2,\"resting\":false,\"restingQuantity\":0}");
    }

    @Test
    void partialFillsAndCancelledMarketRemainders() {
        post(limit("SELL", "185.30", 3));
        assertThat(post(limit("BUY", "185.30", 5))).bodyJson().isLenientlyEqualTo("""
                {"orderId":2,"status":"PARTIALLY_FILLED","filledQuantity":3,"restingQuantity":2}""");

        post(limit("SELL", "185.40", 1));
        assertThat(post(market("BUY", 5))).hasStatus(HttpStatus.CREATED).bodyJson().isLenientlyEqualTo("""
                {"orderId":4,"status":"CANCELLED","filledQuantity":1,"restingQuantity":0,"cancelledQuantity":4,
                 "trades":[{"makerOrderId":3,"price":"185.40","quantity":1}]}""");
    }

    @Test
    @Rulebook("OE-002")
    void numericPricesAreAcceptedExactly() {
        assertThat(post("{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":185.1,\"quantity\":1}"))
                .hasStatus(HttpStatus.CREATED);
        assertThat(mvc.get().uri("/api/v1/instruments/AAPL/orders/1")).bodyJson()
                .extractingPath("$.restingQuantity").isEqualTo(1);
    }

    // ---- Cancels ----------------------------------------------------------------------------------

    @Test
    void cancelARestingOrderOnce() {
        post(limit("BUY", "100.00", 7));

        assertThat(mvc.delete().uri(ORDERS + "/1")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"orderId\":1,\"cancelledQuantity\":7,\"commandSequence\":2}");
        assertThat(mvc.delete().uri(ORDERS + "/1")).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"UNKNOWN_ORDER\",\"orderId\":1,\"status\":404}");
    }

    // ---- Rejections and refusals ------------------------------------------------------------------

    @Test
    void engineRejectionsReturnTheConsumedOrderId() {
        assertThat(post(limit("BUY", "185.25", 0))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON).bodyJson().isLenientlyEqualTo("""
                {"title":"Order rejected","status":422,"reason":"INVALID_QUANTITY","orderId":1}""");
        assertThat(post(limit("BUY", "185.25", 2_000_000))).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"QUANTITY_ABOVE_MAXIMUM\",\"orderId\":2}");
        assertThat(post(limit("BUY", "0.00", 1))).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"INVALID_PRICE\",\"orderId\":3}");
        assertThat(post(market("BUY", 1))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"NO_LIQUIDITY\",\"orderId\":4}");
    }

    @Test
    @Rulebook("OE-002")
    void offTickPricesAreRefusedBeforeTheEngine() {
        assertThat(post(limit("BUY", "185.255", 1))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"OFF_TICK_PRICE\",\"status\":422}");

        // no order ID was consumed
        assertThat(post(limit("BUY", "185.25", 1))).bodyJson().extractingPath("$.orderId").isEqualTo(1);
    }

    @Test
    @Rulebook("OE-001")
    void unknownInstrumentsAreNotFound() {
        assertThat(mvc.post().uri("/api/v1/instruments/TSLA/orders").contentType(MediaType.APPLICATION_JSON)
                .content(market("BUY", 1))).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"UNKNOWN_INSTRUMENT\"}");
        assertThat(mvc.delete().uri("/api/v1/instruments/TSLA/orders/1")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/v1/instruments/TSLA/orders/1")).hasStatus(HttpStatus.NOT_FOUND);
    }

    // ---- Strict requests --------------------------------------------------------------------------

    @Test
    void malformedRequestsAreBadRequestsAndNeverReachTheEngine() {
        String[] invalid = {
            "{\"type\":\"MARKET\",\"quantity\":1}",                                        // missing side
            "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1}",                       // limit without price
            "{\"side\":\"BUY\",\"type\":\"MARKET\",\"price\":\"1.00\",\"quantity\":1}",   // market with price
            "{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1,\"timeInForce\":\"IOC\"}", // unknown field
            "{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":10.5}",                   // fractional lots
            "{\"side\":\"HOLD\",\"type\":\"MARKET\",\"quantity\":1}",                     // unknown side
            "{\"side\":\"BUY\",",                                                         // malformed JSON
        };
        for (String body : invalid) {
            assertThat(post(body)).as(body).hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        }
        assertThat(mvc.delete().uri(ORDERS + "/abc")).hasStatus(HttpStatus.BAD_REQUEST);

        // nothing was journaled: the first real order gets ID 1 and sequence 1
        assertThat(post(limit("SELL", "1.00", 1))).bodyJson()
                .isLenientlyEqualTo("{\"orderId\":1,\"commandSequence\":1}");
    }
}
