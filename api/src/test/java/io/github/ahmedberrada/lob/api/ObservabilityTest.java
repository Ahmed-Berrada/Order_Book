package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.ahmedberrada.lob.core.OrderAccepted;
import io.github.ahmedberrada.lob.core.OrderRejected;
import io.github.ahmedberrada.lob.core.RejectReason;
import io.github.ahmedberrada.lob.core.ReplaceRejected;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.core.VolatilityInterruption;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Metrics and health of a running venue, and of the pieces that feed them. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ObservabilityTest {

    @DynamicPropertySource
    static void venue(DynamicPropertyRegistry registry) {
        TemporaryVenue.register(registry);
    }

    @Autowired
    MockMvcTester mvc;

    @Test
    @Rulebook("OE-006")
    void probesAreUpAndPrometheusShowsTheInstrumentsAndTheirCommands() throws Exception {
        assertThat(mvc.post().uri("/api/v1/instruments/AAPL/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"side\":\"SELL\",\"type\":\"LIMIT\",\"price\":\"10.00\",\"quantity\":5}"))
                .hasStatus(201);
        assertThat(mvc.post().uri("/api/v1/instruments/AAPL/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"10.00\",\"quantity\":5}"))
                .hasStatus(201);

        assertThat(mvc.get().uri("/actuator/health/liveness")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"status\":\"UP\"}");
        assertThat(mvc.get().uri("/actuator/health/readiness")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"status\":\"UP\"}");
        String metrics = mvc.get().uri("/actuator/prometheus").exchange().getResponse().getContentAsString();
        assertThat(metrics).contains("lob_command_duration_seconds_count{", "symbol=\"AAPL\"")
                .contains("lob_trades_total{", "lob_queue_depth{", "lob_queue_capacity{", "lob_instrument_halted{",
                        "lob_journal_flushes_total{", "lob_journal_flush_duration_seconds_count{",
                        "lob_journal_group_size_count{",
                        "lob_command_duration_seconds_bucket{");
        assertThat(metrics.lines().filter(l -> l.startsWith("lob_trades_total{") && l.contains("AAPL")).findFirst())
                .hasValueSatisfying(line -> assertThat(line).endsWith(" 1.0"));
    }

    @Test
    @Rulebook("OE-006")
    void readinessGoesDownWhileAnInstrumentIsHalted() {
        MatchingService service = mock(MatchingService.class);
        InstrumentConfig aapl = new InstrumentConfig("AAPL", new BigDecimal("0.01"), 10, new BigDecimal("10.00"));
        InstrumentConfig msft = new InstrumentConfig("MSFT", new BigDecimal("0.01"), 10, new BigDecimal("10.00"));
        when(service.instruments()).thenReturn(List.of(aapl, msft));
        ObservabilityConfiguration configuration = new ObservabilityConfiguration();

        assertThat(configuration.lobHealthIndicator(service).health().getStatus().getCode()).isEqualTo("UP");

        when(service.isHalted("MSFT")).thenReturn(true);
        var health = configuration.lobHealthIndicator(service).health();
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("halted", List.of("MSFT"));
    }

    @Test
    void theObserverCountsTradesRejectionsAndInterruptionsPerSymbol() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerCommandObserver observer = new MicrometerCommandObserver(registry);

        observer.processed("AAPL", new EventBatch(1, 0, List.of(new OrderAccepted(1, 1),
                new TradeExecuted(2, 2, 1, Side.BUY, 100, 5, 0, 0), new TradeExecuted(3, 2, 3, Side.BUY, 101, 5, 0, 0),
                new VolatilityInterruption(4, 100, 120))), 1_500);
        observer.processed("AAPL", new EventBatch(2, 0, List.of(new OrderRejected(5, 3, RejectReason.INVALID_PRICE),
                new ReplaceRejected(6, 1, RejectReason.UNKNOWN_ORDER))), 900);
        observer.processed("MSFT", new EventBatch(1, 0, List.of(new OrderAccepted(1, 1))), 700);

        assertThat(registry.get("lob.trades").tag("symbol", "AAPL").counter().count()).isEqualTo(2);
        assertThat(registry.get("lob.rejections").tag("symbol", "AAPL").counter().count()).isEqualTo(2);
        assertThat(registry.get("lob.volatility.interruptions").tag("symbol", "AAPL").counter().count()).isEqualTo(1);
        assertThat(registry.get("lob.command.duration").tag("symbol", "AAPL").timer().count()).isEqualTo(2);
        assertThat(registry.get("lob.command.duration").tag("symbol", "MSFT").timer().count()).isEqualTo(1);
        assertThat(registry.get("lob.trades").tag("symbol", "MSFT").counter().count()).isZero();
    }
}
