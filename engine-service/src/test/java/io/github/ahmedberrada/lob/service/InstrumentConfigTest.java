package io.github.ahmedberrada.lob.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.Rulebook;
import java.math.BigDecimal;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InstrumentConfigTest {

    private static final InstrumentConfig AAPL =
            new InstrumentConfig("AAPL", new BigDecimal("0.01"), 1_000_000, new BigDecimal("100000.00"));

    @Test
    @Rulebook("OE-002")
    void convertsPricesOnTheTickGrid() {
        assertThat(AAPL.toTicks(new BigDecimal("185.25"))).isEqualTo(18_525);
        assertThat(AAPL.toTicks(new BigDecimal("185.250000"))).isEqualTo(18_525);
        assertThat(AAPL.toTicks(new BigDecimal("185"))).isEqualTo(18_500);
        assertThat(AAPL.toTicks(new BigDecimal("-1.00"))).isEqualTo(-100);   // the engine rejects it (CT-009)
        assertThat(AAPL.toPrice(18_525)).isEqualByComparingTo("185.25").hasToString("185.25");

        InstrumentConfig fiveCents = new InstrumentConfig("X", new BigDecimal("0.05"), 10, new BigDecimal("10.00"));
        assertThat(fiveCents.toTicks(new BigDecimal("1.15"))).isEqualTo(23);
    }

    @ParameterizedTest
    @ValueSource(strings = {"185.255", "0.001", "1E-7", "99999999999999999999999.00"})
    @Rulebook("OE-002")
    void refusesOffTickPrices(String price) {
        assertThatThrownBy(() -> AAPL.toTicks(new BigDecimal(price)))
                .isInstanceOfSatisfying(RequestRefusedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(RequestRefusedException.Reason.OFF_TICK_PRICE);
                    assertThat(e.symbol()).isEqualTo("AAPL");
                });
    }

    @Property
    @Rulebook("OE-002")
    void ticksSurviveARoundTrip(@ForAll @LongRange(min = -10_000_000, max = 10_000_000) long ticks) {
        assertThat(AAPL.toTicks(AAPL.toPrice(ticks))).isEqualTo(ticks);
    }

    @Test
    void definesTheCoreInstrument() {
        assertThat(AAPL.instrument()).isEqualTo(new Instrument("AAPL", 1_000_000, 10_000_000));
        InstrumentConfig huge = new InstrumentConfig("X", new BigDecimal("1E-18"), 1, new BigDecimal("1000"));
        assertThatIllegalArgumentException().isThrownBy(huge::instrument).withMessageContaining("too large");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "aapl", "../etc", "..", ".", "A/B", "SEVENTEEN_CHARS_X", "A B"})
    void rejectsSymbolsThatCouldEscapeTheJournalDirectory(String symbol) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new InstrumentConfig(symbol, BigDecimal.ONE, 1, BigDecimal.TEN));
    }

    @Test
    void rejectsInvalidDefinitions() {
        assertThatNullPointerException().isThrownBy(() -> new InstrumentConfig(null, BigDecimal.ONE, 1, BigDecimal.TEN));
        assertThatNullPointerException().isThrownBy(() -> new InstrumentConfig("A", null, 1, BigDecimal.TEN));
        assertThatNullPointerException().isThrownBy(() -> new InstrumentConfig("A", BigDecimal.ONE, 1, null));
        assertThatNullPointerException().isThrownBy(() -> AAPL.toTicks(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new InstrumentConfig("A", BigDecimal.ZERO, 1, BigDecimal.TEN));
        assertThatIllegalArgumentException().isThrownBy(() -> new InstrumentConfig("A", BigDecimal.ONE, 1, BigDecimal.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> new InstrumentConfig("A", BigDecimal.ONE, 1, new BigDecimal("2.5")));
        assertThatNullPointerException().isThrownBy(() -> new RequestRefusedException(null, "A"));
    }
}
