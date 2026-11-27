package io.github.ahmedberrada.lob.posttrade;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatementTest {

    static final LocalDate MONDAY = LocalDate.of(2026, 11, 23);

    static TradeRecord record(LocalDate date, String instrument, long member, Side side, long quantity,
            String notional, String fee) {
        return new TradeRecord(instrument + "-1", date, 0, instrument, member, 1, side, Liquidity.TAKER, 1,
                BigDecimal.ONE, quantity, new BigDecimal(notional), new BigDecimal(fee));
    }

    @Test
    @Rulebook("PT-009")
    void sumsEachMembersRecordsPerDayAndInstrumentInOrder() {
        LocalDate tuesday = MONDAY.plusDays(1);
        List<Statement> lines = Statement.of(List.of(
                record(tuesday, "AAPL", 2, Side.BUY, 5, "50.00", "0.02"),
                record(MONDAY, "MSFT", 2, Side.SELL, 3, "30.00", "-0.01"),
                record(MONDAY, "AAPL", 9, Side.BUY, 1, "10.00", "0.01"),
                record(MONDAY, "AAPL", 2, Side.BUY, 4, "40.00", "0.01"),
                record(MONDAY, "AAPL", 2, Side.SELL, 1, "10.50", "-0.01"),
                record(MONDAY, "AAPL", 2, Side.BUY, 2, "20.00", "0.01")));

        assertThat(lines).containsExactly(
                new Statement(MONDAY, "AAPL", 2, 3, 6, new BigDecimal("60.00"), 1, new BigDecimal("10.50"),
                        new BigDecimal("0.01")),
                new Statement(MONDAY, "AAPL", 9, 1, 1, new BigDecimal("10.00"), 0, BigDecimal.ZERO,
                        new BigDecimal("0.01")),
                new Statement(MONDAY, "MSFT", 2, 1, 0, BigDecimal.ZERO, 3, new BigDecimal("30.00"),
                        new BigDecimal("-0.01")),
                new Statement(tuesday, "AAPL", 2, 1, 5, new BigDecimal("50.00"), 0, BigDecimal.ZERO,
                        new BigDecimal("0.02")));
        assertThat(Statement.of(List.of())).isEmpty();
    }

    @Test
    @Rulebook("PT-008")
    void balancedRecordsPassAndTheFirstImbalanceIsReported() {
        LocalDate tuesday = MONDAY.plusDays(1);
        assertThat(Balance.check(List.of())).isEmpty();
        assertThat(Balance.check(List.of(
                record(MONDAY, "AAPL", 1, Side.BUY, 5, "50.00", "0"),
                record(MONDAY, "AAPL", 2, Side.SELL, 5, "50.0", "0")))).isEmpty();      // equal, whatever the scale
        assertThat(Balance.check(List.of(
                record(tuesday, "AAPL", 1, Side.BUY, 5, "50.00", "0"),
                record(MONDAY, "AAPL", 1, Side.BUY, 5, "50.00", "0"),
                record(MONDAY, "AAPL", 2, Side.SELL, 4, "50.00", "0"))))
                .contains("2026-11-23: 5 lots bought but 4 sold");
        assertThat(Balance.check(List.of(
                record(MONDAY, "AAPL", 1, Side.BUY, 5, "50.00", "0"),
                record(MONDAY, "AAPL", 2, Side.SELL, 5, "50.01", "0"))))
                .contains("2026-11-23: 50.00 bought but 50.01 sold");
        assertThat(Balance.check(List.of(
                record(MONDAY, "AAPL", 1, Side.BUY, 5, "50.00", "0"),
                record(MONDAY, "AAPL", 2, Side.SELL, 5, "50.00", "0"),
                record(tuesday, "AAPL", 2, Side.SELL, 1, "1", "0"))))
                .contains("2026-11-24: 0 lots bought but 1 sold");
    }
}
