package io.github.ahmedberrada.lob.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.Test;

class InstrumentTest {

    @Test
    void acceptsLimitsWhoseProductFitsInALong() {
        Instrument instrument = new Instrument("AAPL", 1_000_000, 1_000_000_000);

        assertThat(instrument.maxOrderQuantity()).isEqualTo(1_000_000);
        assertThat(new Instrument("X", 1, Long.MAX_VALUE).maxPriceTicks()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rejectsLimitsWhoseNotionalCouldOverflow() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Instrument("AAPL", 4_000_000_000L, 4_000_000_000L))
                .withMessageContaining("overflows");
    }

    @Test
    void rejectsInvalidDefinitions() {
        assertThatNullPointerException().isThrownBy(() -> new Instrument(null, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Instrument(" ", 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Instrument("A", 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Instrument("A", 1, 0));
    }

    @Test
    @Rulebook({"TK-001", "TK-002"})
    void aTickTableMustStartAtOneAndIncrease() {
        Instrument instrument = new Instrument("X", 10, 1_000, List.of(new TickBand(1, 1), new TickBand(100, 5),
                new TickBand(500, 25)), 0);

        assertThat(instrument.stepAt(1)).isEqualTo(1);
        assertThat(instrument.stepAt(99)).isEqualTo(1);
        assertThat(instrument.stepAt(100)).isEqualTo(5);
        assertThat(instrument.stepAt(499)).isEqualTo(5);
        assertThat(instrument.stepAt(500)).isEqualTo(25);
        assertThat(instrument.stepAt(1_000)).isEqualTo(25);
        assertThat(new Instrument("X", 1, 1).tickBands()).containsExactly(new TickBand(1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Instrument("X", 1, 9, List.of(), 0));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new Instrument("X", 1, 9, List.of(new TickBand(2, 1)), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Instrument("X", 1, 9,
                List.of(new TickBand(1, 1), new TickBand(5, 2), new TickBand(5, 3)), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new TickBand(0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new TickBand(1, 0));
        assertThatNullPointerException().isThrownBy(() -> new Instrument("X", 1, 9, null, 0));
    }

    @Test
    @Rulebook("VI-002")
    void theVolatilityBandIsBoundedAndItsArithmeticMustFit() {
        List<TickBand> table = List.of(new TickBand(1, 1));
        assertThat(new Instrument("X", 1, 9, table, 10_000).volatilityBandBps()).isEqualTo(10_000);
        assertThatIllegalArgumentException().isThrownBy(() -> new Instrument("X", 1, 9, table, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Instrument("X", 1, 9, table, 10_001));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Instrument("X", 1, Long.MAX_VALUE, table, 1)).withMessageContaining("overflows");
    }

    @Test
    void sidesAreOpposite() {
        assertThat(Side.BUY.opposite()).isEqualTo(Side.SELL);
        assertThat(Side.SELL.opposite()).isEqualTo(Side.BUY);
    }
}
