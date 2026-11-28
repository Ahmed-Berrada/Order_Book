package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import io.github.ahmedberrada.lob.service.RequestRefusedException.Reason;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Refusals that a healthy venue cannot produce on demand, mapped with a stubbed service. */
@WebMvcTest(OrderController.class)
@Import(MemberResolver.class)
@AutoConfigureMockMvc(addFilters = false)      // the security chain has its own tests (SecurityApiTest)
class RefusalMappingTest {

    private static final InstrumentConfig AAPL =
            new InstrumentConfig("AAPL", new BigDecimal("0.01"), 1_000, new BigDecimal("1000.00"));

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    MatchingService service;

    @BeforeEach
    void listAapl() {
        when(service.instrument("AAPL")).thenReturn(Optional.of(AAPL));
    }

    private MvcTestResult placeOrderFailingWith(Throwable failure) {
        when(service.submit(anyString(), any(), anyLong(), any())).thenReturn(CompletableFuture.failedFuture(failure));
        return mvc.post().uri("/api/v1/instruments/AAPL/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1}").exchange();
    }

    @Test
    @Rulebook("OE-005")
    void overloadedAsksTheClientToRetry() {
        assertThat(placeOrderFailingWith(new RequestRefusedException(Reason.OVERLOADED, "AAPL")))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE).hasHeader("Retry-After", "1")
                .bodyJson().isLenientlyEqualTo("{\"reason\":\"OVERLOADED\",\"status\":503}");
    }

    @Test
    @Rulebook({"OE-006", "OE-007"})
    void haltedAndStoppedAreUnavailable() {
        assertThat(placeOrderFailingWith(new RequestRefusedException(Reason.HALTED, "AAPL")))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE).bodyJson().isLenientlyEqualTo("{\"reason\":\"HALTED\"}");
        assertThat(placeOrderFailingWith(new RequestRefusedException(Reason.STOPPED, "AAPL")))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE).bodyJson().isLenientlyEqualTo("{\"reason\":\"STOPPED\"}");
    }

    @Test
    @Rulebook("OE-008")
    void aThrottledMemberIsToldWhenToRetry() {
        assertThat(placeOrderFailingWith(new RequestRefusedException(Reason.THROTTLED, "AAPL", Duration.ofMillis(1))))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS).hasHeader("Retry-After", "1")
                .bodyJson().isLenientlyEqualTo("{\"reason\":\"THROTTLED\",\"status\":429}");
        assertThat(placeOrderFailingWith(new RequestRefusedException(Reason.THROTTLED, "AAPL", Duration.ofMillis(2_001))))
                .hasHeader("Retry-After", "3");
        assertThat(placeOrderFailingWith(new RequestRefusedException(Reason.THROTTLED, "AAPL", Duration.ofSeconds(2))))
                .hasHeader("Retry-After", "2");
        assertThat(placeOrderFailingWith(new RequestRefusedException(Reason.THROTTLED, "AAPL")))
                .hasHeader("Retry-After", "1");
    }

    @Test
    @Rulebook("OE-009")
    void aMemberOverItsOrderToTradeRatioIsToldWhenTheDayEnds() {
        assertThat(placeOrderFailingWith(new RequestRefusedException(Reason.ORDER_TO_TRADE_LIMIT, "AAPL",
                Duration.ofHours(14))))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS).hasHeader("Retry-After", "50400")
                .bodyJson().isLenientlyEqualTo("{\"reason\":\"ORDER_TO_TRADE_LIMIT\"}");
    }

    @Test
    void failedJournalWriteIsAnUnknownOutcome() {
        assertThat(placeOrderFailingWith(new UncheckedIOException(new IOException("disk"))))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"OUTCOME_UNKNOWN\",\"title\":\"Outcome unknown\"}");
    }
}
