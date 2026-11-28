package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.SelfTradeMode;
import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import io.github.ahmedberrada.lob.service.MatchingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/** The application refuses to start on an incomplete or invalid venue configuration. */
class LobPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(VenueConfiguration.class);

    private static final String[] VALID = {
        "lob.data-directory=target/test-venue",
        "lob.instruments[0].symbol=AAPL",
        "lob.instruments[0].tick-size=0.01",
        "lob.instruments[0].max-order-quantity=100",
        "lob.instruments[0].max-price=1000.00",
    };

    private static String[] with(String... more) {
        return java.util.stream.Stream.concat(java.util.Arrays.stream(VALID), java.util.Arrays.stream(more))
                .toArray(String[]::new);
    }

    @Test
    void appliesDefaults() {
        runner.withPropertyValues(VALID).run(context -> {
            assertThat(context).hasNotFailed();
            LobProperties properties = context.getBean(LobProperties.class);
            assertThat(properties.queueCapacity()).isEqualTo(10_000);
            assertThat(properties.fsync()).isEqualTo(FsyncPolicy.GROUP);     // ADR-0020 §5
            assertThat(properties.snapshotInterval()).isEqualTo(10_000);
            assertThat(properties.security().maxFailures()).isEqualTo(5);                    // AC-009
            assertThat(properties.security().lockout()).isEqualTo(java.time.Duration.ofMinutes(1));
            assertThat(properties.fix().enabled()).isFalse();
            assertThat(properties.fix().port()).isEqualTo(9878);
            assertThat(properties.fix().bindAddress()).isEqualTo("127.0.0.1");
            assertThat(properties.fix().senderCompId()).isEqualTo("LOB");
            assertThat(properties.fix().selfTradeMode()).isEqualTo(SelfTradeMode.CANCEL_NEWEST);
            assertThat(context.getBean(MatchingService.class).instruments()).hasSize(1);
        });
    }

    @Test
    void readsTheSelfTradeMode() {
        runner.withPropertyValues(VALID).withPropertyValues("lob.fix.self-trade-mode=CANCEL_BOTH").run(context ->
                assertThat(context.getBean(LobProperties.class).fix().selfTradeMode()).isEqualTo(SelfTradeMode.CANCEL_BOTH));
        runner.withPropertyValues(VALID).withPropertyValues("lob.fix.self-trade-mode=CANCEL_EVERYONE")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void readsTheTickRegimeAndTheVolatilityBand() {
        runner.withPropertyValues(with(
                "lob.data-directory=target/test-venue-regime", "lob.instruments[0].tick-regime[0].from-price=0.01",
                "lob.instruments[0].tick-regime[0].step=0.01",
                "lob.instruments[0].tick-regime[1].from-price=500.00", "lob.instruments[0].tick-regime[1].step=0.05",
                "lob.instruments[0].volatility-band-bps=300")).run(context -> {
                    var instrument = context.getBean(MatchingService.class).instruments().getFirst();
                    assertThat(instrument.tickRegime()).hasSize(2);
                    assertThat(instrument.instrument().tickBands()).extracting(b -> b.stepTicks()).containsExactly(1L, 5L);
                    assertThat(instrument.volatilityBandBps()).isEqualTo(300);
                });
        runner.withPropertyValues(with("lob.data-directory=target/test-venue-regime", "lob.instruments[0].volatility-band-bps=-1"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesAVenueWithoutInstruments() {
        runner.withPropertyValues("lob.data-directory=target/test-venue")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesInvalidValues() {
        runner.withPropertyValues(VALID).withPropertyValues("lob.queue-capacity=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(VALID).withPropertyValues("lob.instruments[0].tick-size=-0.01")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(VALID).withPropertyValues("lob.instruments[0].symbol=../escape")
                .run(context -> assertThat(context).hasFailed());
    }
}
