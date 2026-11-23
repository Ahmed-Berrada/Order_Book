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

    // ---- Order attributes and replace -------------------------------------------------------------

    private MvcTestResult put(long orderId, String json) {
        return mvc.put().uri(ORDERS + "/" + orderId).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    @Test
    @Rulebook({"OT-002", "OT-003"})
    void iocAndFokOrders() {
        post(limit("SELL", "185.30", 3));
        assertThat(post("{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"185.30\",\"quantity\":5,"
                + "\"timeInForce\":\"FOK\"}")).hasStatus(HttpStatus.CREATED).bodyJson().isLenientlyEqualTo("""
                {"orderId":2,"status":"CANCELLED","filledQuantity":0,"restingQuantity":0,"cancelledQuantity":5}""");
        assertThat(post("{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"185.30\",\"quantity\":5,"
                + "\"timeInForce\":\"IOC\"}")).bodyJson().isLenientlyEqualTo("""
                {"orderId":3,"status":"CANCELLED","filledQuantity":3,"restingQuantity":0,"cancelledQuantity":2}""");
        assertThat(post("{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1,\"timeInForce\":\"GTC\"}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"INVALID_TIME_IN_FORCE\",\"orderId\":4}");
    }

    @Test
    @Rulebook("OT-004")
    void postOnlyOrders() {
        post(limit("SELL", "185.30", 3));
        assertThat(post("{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"185.30\",\"quantity\":1,"
                + "\"postOnly\":true}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"POST_ONLY_WOULD_TRADE\",\"orderId\":2}");
        assertThat(post("{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"185.20\",\"quantity\":1,"
                + "\"postOnly\":true}")).hasStatus(HttpStatus.CREATED).bodyJson()
                .isLenientlyEqualTo("{\"orderId\":3,\"status\":\"NEW\"}");
    }

    @Test
    @Rulebook({"IC-001", "IC-002", "IC-004"})
    void icebergOrdersShowOnlyTheirPeak() {
        assertThat(post("{\"side\":\"SELL\",\"type\":\"LIMIT\",\"price\":\"185.30\",\"quantity\":50,"
                + "\"displayQuantity\":10}")).hasStatus(HttpStatus.CREATED).bodyJson()
                .isLenientlyEqualTo("{\"orderId\":1,\"status\":\"NEW\",\"restingQuantity\":50}");
        assertThat(mvc.get().uri("/api/v1/instruments/AAPL/book")).bodyJson()
                .isLenientlyEqualTo("{\"asks\":[{\"price\":\"185.30\",\"quantity\":10,\"orders\":1}]}");
        // a market order through two peaks: its batch also holds the iceberg's refills
        assertThat(post(market("BUY", 15))).hasStatus(HttpStatus.CREATED).bodyJson().isLenientlyEqualTo("""
                {"orderId":2,"status":"FILLED","filledQuantity":15,
                 "trades":[{"makerOrderId":1,"quantity":10},{"makerOrderId":1,"quantity":5}]}""");
        assertThat(mvc.get().uri("/api/v1/instruments/AAPL/book")).bodyJson()
                .isLenientlyEqualTo("{\"asks\":[{\"price\":\"185.30\",\"quantity\":5,\"orders\":1}]}");
        assertThat(post("{\"side\":\"SELL\",\"type\":\"LIMIT\",\"price\":\"185.30\",\"quantity\":5,"
                + "\"displayQuantity\":6}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"INVALID_DISPLAY_QUANTITY\",\"orderId\":3}");
        assertThat(post("{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1,\"displayQuantity\":1}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Rulebook({"ST-001", "ST-002", "ST-003", "ST-005"})
    void stopOrdersParkThenTradeWhenTriggered() {
        post(limit("SELL", "185.30", 2));                      // 1
        post(limit("SELL", "185.40", 5));                      // 2
        assertThat(post("{\"side\":\"BUY\",\"type\":\"STOP\",\"stopPrice\":\"185.30\",\"quantity\":3}"))
                .hasStatus(HttpStatus.CREATED).bodyJson().isLenientlyEqualTo("""
                {"orderId":3,"status":"PARKED","filledQuantity":0,"restingQuantity":0,"trades":[]}""");
        assertThat(post("{\"side\":\"BUY\",\"type\":\"STOP_LIMIT\",\"stopPrice\":\"185.40\","
                + "\"price\":\"185.35\",\"quantity\":2}")).bodyJson()
                .isLenientlyEqualTo("{\"orderId\":4,\"status\":\"PARKED\"}");

        // order 5 trades at 185.30 and releases stop 3, whose fills at 185.30 and 185.40 release stop 4;
        // order 5's response holds its own fill only
        assertThat(post(limit("BUY", "185.30", 1))).bodyJson().isLenientlyEqualTo("""
                {"orderId":5,"status":"FILLED","filledQuantity":1,
                 "trades":[{"makerOrderId":1,"price":"185.30","quantity":1}]}""");
        assertThat(mvc.get().uri("/api/v1/instruments/AAPL/book")).bodyJson().isLenientlyEqualTo("""
                {"bids":[{"price":"185.35","quantity":2,"orders":1}],"asks":[{"price":"185.40","quantity":3}]}""");
        assertThat(post("{\"side\":\"SELL\",\"type\":\"STOP\",\"stopPrice\":\"185.50\",\"quantity\":1}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"STOP_PRICE_ALREADY_REACHED\",\"orderId\":6}");
    }

    @Test
    @Rulebook("ST-005")
    void anOrderFilledAsAMakerByAStopItReleasedSeesTheFill() {
        post(limit("SELL", "185.30", 1));                      // 1
        post("{\"side\":\"SELL\",\"type\":\"STOP\",\"stopPrice\":\"185.30\",\"quantity\":2}"); // 2
        // order 3 buys 5: 1 fills against order 1, the rest rests and is then hit by the released stop
        assertThat(post(limit("BUY", "185.30", 5))).bodyJson().isLenientlyEqualTo("""
                {"orderId":3,"status":"PARTIALLY_FILLED","filledQuantity":3,"restingQuantity":2,
                 "trades":[{"makerOrderId":1,"quantity":1},{"makerOrderId":2,"quantity":2}]}""");
    }

    @Test
    @Rulebook({"OT-007", "ST-005"})
    void anAggressiveReplaceThatReleasesAStopSeesTheStopsFillAgainstIt() {
        post(limit("SELL", "185.10", 1));                      // 1
        post(limit("BUY", "185.00", 5));                       // 2
        post("{\"side\":\"SELL\",\"type\":\"STOP\",\"stopPrice\":\"185.10\",\"quantity\":2}"); // 3

        // order 2 moves to 185.10, buys 1 from order 1 and triggers stop 3, which sells 2 into order 2
        assertThat(put(2, "{\"price\":\"185.10\",\"quantity\":5}")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"orderId":2,"priorityKept":false,"filledQuantity":3,"restingQuantity":2,
                 "trades":[{"makerOrderId":1,"quantity":1},{"makerOrderId":3,"quantity":2}]}""");
        // priority kept, and nothing else happens
        assertThat(put(2, "{\"price\":\"185.10\",\"quantity\":1}")).bodyJson()
                .isLenientlyEqualTo("{\"priorityKept\":true,\"restingQuantity\":1,\"trades\":[]}");
    }

    @Test
    @Rulebook({"OT-005", "OT-006", "OT-007"})
    void replaceKeepsOrLosesPriorityAndCanTrade() {
        post(limit("BUY", "185.00", 10));                      // 1
        post(limit("SELL", "185.50", 4));                      // 2

        assertThat(put(1, "{\"price\":\"185.00\",\"quantity\":6}")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"orderId":1,"price":"185.00","priorityKept":true,"commandSequence":3,"filledQuantity":0,
                 "restingQuantity":6,"trades":[]}""");
        assertThat(put(1, "{\"price\":\"185.50\",\"quantity\":6}")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"orderId":1,"price":"185.50","priorityKept":false,"filledQuantity":4,"restingQuantity":2,
                 "trades":[{"makerOrderId":2,"price":"185.50","quantity":4}]}""");
        assertThat(mvc.get().uri(ORDERS + "/1")).bodyJson()
                .isLenientlyEqualTo("{\"orderId\":1,\"resting\":true,\"restingQuantity\":2}");
        assertThat(put(1, "{\"price\":185.6,\"quantity\":1}")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"price\":\"185.60\",\"priorityKept\":false,\"restingQuantity\":1}");
    }

    @Test
    @Rulebook("OT-005")
    void refusedReplacesLeaveTheOrderUnchanged() {
        post(limit("BUY", "185.00", 10));                      // 1

        assertThat(put(1, "{\"price\":\"185.00\",\"quantity\":0}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().isLenientlyEqualTo("""
                {"title":"Replace rejected","reason":"INVALID_QUANTITY","orderId":1}""");
        assertThat(put(9, "{\"price\":\"185.00\",\"quantity\":1}")).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"UNKNOWN_ORDER\",\"orderId\":9}");
        assertThat(put(1, "{\"price\":\"185.005\",\"quantity\":1}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().isLenientlyEqualTo("{\"reason\":\"OFF_TICK_PRICE\"}");
        assertThat(put(1, "{\"price\":\"185.00\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri(ORDERS + "/1")).bodyJson()
                .isLenientlyEqualTo("{\"orderId\":1,\"resting\":true,\"restingQuantity\":10}");
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
            "{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1,\"trailing\":1}",     // unknown field
            "{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1,\"timeInForce\":\"DAY\"}", // unknown TIF
            "{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1,\"postOnly\":false}", // post-only market
            "{\"side\":\"BUY\",\"type\":\"STOP\",\"quantity\":1}",                      // stop without stop price
            "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"1.00\",\"stopPrice\":\"1.00\",\"quantity\":1}",
            "{\"side\":\"BUY\",\"type\":\"STOP\",\"stopPrice\":\"1.00\",\"price\":\"1.00\",\"quantity\":1}",
            "{\"side\":\"BUY\",\"type\":\"STOP_LIMIT\",\"stopPrice\":\"1.00\",\"quantity\":1}",
            "{\"side\":\"BUY\",\"type\":\"STOP\",\"stopPrice\":\"1.00\",\"quantity\":1,\"timeInForce\":\"IOC\"}",
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
