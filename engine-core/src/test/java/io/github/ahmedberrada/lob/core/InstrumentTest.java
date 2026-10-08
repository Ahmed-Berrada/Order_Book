package io.github.ahmedberrada.lob.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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
    void sidesAreOpposite() {
        assertThat(Side.BUY.opposite()).isEqualTo(Side.SELL);
        assertThat(Side.SELL.opposite()).isEqualTo(Side.BUY);
    }
}
