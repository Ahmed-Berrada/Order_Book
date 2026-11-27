package io.github.ahmedberrada.lob.posttrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.ahmedberrada.lob.core.Rulebook;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FeeScheduleTest {

    @Test
    @Rulebook("PT-006")
    void parsesOneRatePerLiquidityInAnyOrder() {
        FeeSchedule fees = FeeSchedule.parse(" Auction = 0.1 ,maker=-0.2,TAKER=0.30", 2);
        assertThat(fees.rate(Liquidity.MAKER)).isEqualTo("-0.2");
        assertThat(fees.rate(Liquidity.TAKER)).isEqualTo("0.30");
        assertThat(fees.rate(Liquidity.AUCTION)).isEqualTo("0.1");
        assertThat(fees.currencyScale()).isEqualTo(2);
        assertThat(FeeSchedule.parse("maker=100,taker=-100,auction=0", 0).rate(Liquidity.TAKER)).isEqualTo("-100");
    }

    @ParameterizedTest
    @ValueSource(strings = {"maker=0,taker=0", "maker=0,taker=0,auction=0,maker=1", "maker=0,taker=0,auction=0,x=1",
        "maker=0,taker=0,auction", "maker=0,taker=0,auction=abc", "maker=100.01,taker=0,auction=0",
        "maker=0,taker=-100.5,auction=0", ""})
    @Rulebook("PT-006")
    void refusesAScheduleThatIsIncompleteOrOutOfBounds(String rates) {
        assertThatIllegalArgumentException().isThrownBy(() -> FeeSchedule.parse(rates, 2));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 9})
    void refusesACurrencyScaleOutOfRange(int scale) {
        assertThatIllegalArgumentException().isThrownBy(() -> FeeSchedule.parse("maker=0,taker=0,auction=0", scale))
                .withMessageContaining("scale");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8})
    void acceptsEveryCurrencyScaleFromZeroToEight(int scale) {
        assertThat(FeeSchedule.parse("maker=0,taker=0,auction=0", scale).currencyScale()).isEqualTo(scale);
    }

    @ParameterizedTest
    @CsvSource({
        // notional, rate in bps, scale, fee
        "55650.00, 0.3, 2, 1.67",           // 1.6695
        "55650.00, -0.2, 2, -1.11",         // -1.113
        "500.00, 0.1, 2, 0.00",             // 0.005: half to even, down
        "1500.00, 0.1, 2, 0.02",            // 0.015: half to even, up
        "2500.00, 0.1, 2, 0.02",            // 0.025: half to even, down
        "2500.00, -0.1, 2, -0.02",          // -0.025: symmetric
        "123456789.12, 1, 0, 12346",        // 12345.678912 to whole units
        "100, 100, 2, 1.00",                // the largest rate: 1%
    })
    @Rulebook("PT-007")
    void roundsEachFeeOnceHalfToEven(String notional, String rate, int scale, String fee) {
        FeeSchedule fees = new FeeSchedule(new BigDecimal(rate), new BigDecimal(rate), new BigDecimal(rate), scale);
        for (Liquidity liquidity : Liquidity.values()) {
            assertThat(fees.fee(new BigDecimal(notional), liquidity)).isEqualTo(fee);
        }
    }
}
