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

/** Members over REST: the member header, limits from configuration and from the admin API, the kill switch. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MembersApiTest {

    private static final String AAPL = "/api/v1/instruments/AAPL";

    @DynamicPropertySource
    static void venue(DynamicPropertyRegistry registry) {
        TemporaryVenue.register(registry);
        // a list from a higher-priority source replaces the whole list, so the instrument is complete here
        registry.add("lob.instruments[0].symbol", () -> "AAPL");
        registry.add("lob.instruments[0].tick-size", () -> "0.01");
        registry.add("lob.instruments[0].max-order-quantity", () -> "1000000");
        registry.add("lob.instruments[0].max-price", () -> "100000.00");
        registry.add("lob.members[0].id", () -> "1");
        registry.add("lob.members[0].name", () -> "Alpha");
        registry.add("lob.members[0].limits.max-order-quantity", () -> "5");
        registry.add("lob.members[0].limits.max-order-notional", () -> "1000.00");
        registry.add("lob.members[1].id", () -> "2");
        registry.add("lob.members[1].name", () -> "Beta");
    }

    @Autowired
    MockMvcTester mvc;

    private MvcTestResult order(String member, String side, String price, long quantity) {
        var request = mvc.post().uri(AAPL + "/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"side\":\"%s\",\"type\":\"LIMIT\",\"price\":\"%s\",\"quantity\":%d}"
                        .formatted(side, price, quantity));
        return (member == null ? request : request.header("X-Member-Id", member)).exchange();
    }

    @Test
    @Rulebook({"RK-001", "RK-002"})
    void limitsFromConfigurationApplyToTheMemberInTheHeaderOnly() {
        assertThat(order("1", "BUY", "10.00", 6)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"RISK_ORDER_QUANTITY\"}");
        assertThat(order("1", "BUY", "300.00", 4)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"RISK_ORDER_NOTIONAL\"}");     // 1200 > 1000
        assertThat(order("1", "BUY", "200.00", 5)).hasStatus(HttpStatus.CREATED);         // 1000: on the limit
        assertThat(order("2", "BUY", "10.00", 600)).hasStatus(HttpStatus.CREATED);
        assertThat(order(null, "BUY", "10.00", 600)).hasStatus(HttpStatus.CREATED);       // the operator
        assertThat(mvc.get().uri("/api/v1/members/1")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"memberId":1,"instruments":[{"symbol":"AAPL","suspended":false,"netPosition":0,
                 "maxOrderQuantity":5,"maxOrderNotional":1000.00}]}""");
    }

    @Test
    @Rulebook("RK-001")
    void limitsCanBeSetOverRestAndInvalidOnesAreBadRequests() {
        assertThat(mvc.put().uri("/api/v1/members/2/limits").contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxOrderQuantity\":0,\"maxOrderNotional\":0,\"priceCollarBps\":0,"
                        + "\"maxOpenOrders\":1,\"maxNetPosition\":0}")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"instruments\":[{\"maxOpenOrders\":1}]}");
        assertThat(order("2", "BUY", "10.00", 1)).hasStatus(HttpStatus.CREATED);
        assertThat(order("2", "BUY", "10.00", 1)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"RISK_OPEN_ORDERS\"}");

        assertThat(mvc.put().uri("/api/v1/members/2/limits").contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxOrderQuantity\":-1,\"maxOrderNotional\":0,\"priceCollarBps\":0,"
                        + "\"maxOpenOrders\":1,\"maxNetPosition\":0}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/v1/members/0")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(order("-1", "BUY", "10.00", 1)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(order("abc", "BUY", "10.00", 1)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Rulebook("RK-006")
    void aMemberMassCancelsItsOwnOrdersOnASideOrBoth() {
        assertThat(order("2", "BUY", "10.00", 3)).hasStatus(HttpStatus.CREATED);
        assertThat(order("2", "BUY", "11.00", 3)).hasStatus(HttpStatus.CREATED);
        assertThat(order("2", "SELL", "50.00", 3)).hasStatus(HttpStatus.CREATED);
        assertThat(order("1", "BUY", "12.00", 1)).hasStatus(HttpStatus.CREATED);

        assertThat(mvc.delete().uri(AAPL + "/orders?side=BUY").header("X-Member-Id", "2")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"cancelledOrders\":2,\"cancelledOrderIds\":[1,2]}");
        assertThat(mvc.delete().uri(AAPL + "/orders").header("X-Member-Id", "2")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"cancelledOrders\":1,\"cancelledOrderIds\":[3]}");
        assertThat(mvc.get().uri(AAPL + "/book")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"bids\":[{\"price\":\"12.00\",\"quantity\":1}],\"asks\":[]}");
        assertThat(mvc.delete().uri(AAPL + "/orders?side=SIDEWAYS").header("X-Member-Id", "2"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(order("2", "BUY", "10.00", 1)).as("not suspended").hasStatus(HttpStatus.CREATED);
    }

    @Test
    @Rulebook({"RK-004", "RK-005"})
    void theKillSwitchCancelsOrdersBlocksTheMemberAndCanBeLifted() {
        assertThat(order("2", "BUY", "10.00", 3)).hasStatus(HttpStatus.CREATED);
        assertThat(order("2", "BUY", "11.00", 3)).hasStatus(HttpStatus.CREATED);

        assertThat(mvc.put().uri("/api/v1/members/2/suspension")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"memberId\":2,\"suspended\":true,\"cancelledOrders\":2}");
        assertThat(mvc.get().uri(AAPL + "/orders/1")).bodyJson().isLenientlyEqualTo("{\"resting\":false}");
        assertThat(order("2", "BUY", "10.00", 1)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"MEMBER_SUSPENDED\"}");
        assertThat(mvc.put().uri("/api/v1/members/2/suspension")).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"INVALID_MEMBER\"}");

        assertThat(mvc.delete().uri("/api/v1/members/2/suspension")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"suspended\":false}");
        assertThat(order("2", "BUY", "10.00", 1)).hasStatus(HttpStatus.CREATED);
        assertThat(mvc.delete().uri("/api/v1/members/2/suspension")).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    @Rulebook("MB-003")
    void aMemberCannotCancelOrReplaceAnotherMembersOrder() {
        assertThat(order("1", "BUY", "10.00", 2)).hasStatus(HttpStatus.CREATED);

        assertThat(mvc.delete().uri(AAPL + "/orders/1").header("X-Member-Id", "2")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.put().uri(AAPL + "/orders/1").header("X-Member-Id", "2").contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":\"9.00\",\"quantity\":2}")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri(AAPL + "/orders/1")).bodyJson().isLenientlyEqualTo("{\"resting\":true}");
        assertThat(mvc.put().uri(AAPL + "/orders/1").header("X-Member-Id", "1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":\"9.00\",\"quantity\":2}")).hasStatusOk();
        assertThat(mvc.delete().uri(AAPL + "/orders/1").header("X-Member-Id", "1")).hasStatusOk();
    }
}
