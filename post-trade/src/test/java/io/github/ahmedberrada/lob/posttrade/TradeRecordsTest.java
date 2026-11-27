package io.github.ahmedberrada.lob.posttrade;

import static io.github.ahmedberrada.lob.posttrade.Journals.by;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.SetPhase;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.core.StopOrder;
import io.github.ahmedberrada.lob.core.TimeInForce;
import io.github.ahmedberrada.lob.core.TradingPhase;
import io.github.ahmedberrada.lob.journal.EventJournal;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TradeRecordsTest {

    private static final FeeSchedule FEES = FeeSchedule.parse("maker=-0.2,taker=0.3,auction=0.1", 2);
    private static final BigDecimal CENT = new BigDecimal("0.01");

    @TempDir
    Path directory;

    private List<TradeRecord> records(long start, List<Journals.Entry> entries) throws IOException {
        Journals.write(directory, start, entries);
        try (EventJournal journal = EventJournal.open(directory)) {
            return new TradeRecords("PTX", CENT, FEES).read(journal);
        }
    }

    @Test
    @Rulebook({"PT-001", "PT-002", "PT-003", "PT-004", "PT-007"})
    void aContinuousTradeGivesATakerAndAMakerSide() throws IOException {
        List<TradeRecord> records = records(Journals.MORNING, List.of(
                by(1, new LimitOrder(Side.SELL, 18_550, 400)),                  // order 1 rests: 185.50
                by(2, new MarketOrder(Side.BUY, 300, TimeInForce.IOC))));        // order 2 takes 300

        LocalDate day = LocalDate.of(2026, 11, 26);
        BigDecimal price = new BigDecimal("185.50");
        BigDecimal notional = new BigDecimal("55650.00");
        assertThat(records).containsExactly(
                new TradeRecord("PTX-4", day, Journals.MORNING + 1, "PTX", 2, 2, Side.BUY, Liquidity.TAKER,
                        18_550, price, 300, notional, new BigDecimal("1.67")),     // 1.6695 rounded
                new TradeRecord("PTX-4", day, Journals.MORNING + 1, "PTX", 1, 1, Side.SELL, Liquidity.MAKER,
                        18_550, price, 300, notional, new BigDecimal("-1.11")));  // -1.113: a rebate
    }

    @Test
    @Rulebook({"PT-002", "PT-003"})
    void aSellerWhoTakesIsTheTakerAndOneMemberCanBeBothSides() throws IOException {
        List<TradeRecord> records = records(Journals.MORNING, List.of(
                by(7, new LimitOrder(Side.BUY, 10_000, 10)),
                by(7, new LimitOrder(Side.SELL, 9_900, 4))));                    // trades at 100.00, the bid

        assertThat(records).extracting(TradeRecord::memberId, TradeRecord::orderId, TradeRecord::side,
                TradeRecord::liquidity, TradeRecord::priceTicks).containsExactly(
                        tuple(7L, 1L, Side.BUY, Liquidity.MAKER, 10_000L),
                        tuple(7L, 2L, Side.SELL, Liquidity.TAKER, 10_000L));
    }

    @Test
    @Rulebook("PT-003")
    void bothSidesOfAnUncrossAreAuctionAndAStopItReleasesIsNot() throws IOException {
        List<TradeRecord> records = records(Journals.MORNING, List.of(
                by(0, new SetPhase(TradingPhase.CALL)),
                by(1, new LimitOrder(Side.SELL, 10_000, 5)),                       // order 1
                by(2, new LimitOrder(Side.BUY, 10_000, 5)),                        // order 2: crosses, waits
                by(3, new StopOrder(Side.BUY, 10_000, 2)),                         // order 3: parked
                by(4, new LimitOrder(Side.SELL, 10_100, 2)),                       // order 4: above the auction
                by(0, new SetPhase(TradingPhase.CONTINUOUS))));                    // uncross, then the stop

        assertThat(records).extracting(TradeRecord::memberId, TradeRecord::side, TradeRecord::liquidity,
                TradeRecord::priceTicks, TradeRecord::quantity).containsExactly(
                        tuple(2L, Side.BUY, Liquidity.AUCTION, 10_000L, 5L),
                        tuple(1L, Side.SELL, Liquidity.AUCTION, 10_000L, 5L),
                        tuple(3L, Side.BUY, Liquidity.TAKER, 10_100L, 2L),
                        tuple(4L, Side.SELL, Liquidity.MAKER, 10_100L, 2L));
        assertThat(records.get(0).fee()).isEqualTo("0.00");     // 500.00 at 0.1 bp is 0.005: half to even
        assertThat(records.get(2).fee()).isEqualTo("0.01");     // 202.00 at 0.3 bp is 0.00606
        assertThat(records.get(0).tradeId()).isEqualTo(records.get(1).tradeId())
                .isNotEqualTo(records.get(2).tradeId());
    }

    @Test
    @Rulebook("PT-005")
    void theTradeDateIsTheUtcDateOfTheCommand() throws IOException {
        List<TradeRecord> records = records(Journals.BEFORE_MIDNIGHT, List.of(
                by(1, new LimitOrder(Side.SELL, 100, 10)),
                by(2, new LimitOrder(Side.BUY, 100, 1)),                           // 23:59:59.999999
                by(2, new LimitOrder(Side.BUY, 100, 1))));                         // 00:00:00.000000

        assertThat(records).extracting(TradeRecord::tradeDate).containsExactly(LocalDate.of(2026, 11, 26),
                LocalDate.of(2026, 11, 26), LocalDate.of(2026, 11, 27), LocalDate.of(2026, 11, 27));
        assertThat(records.get(2).timestamp()).hasToString("2026-11-27T00:00:00Z");
    }

    @Test
    void theTickSizeMustBePositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TradeRecords("PTX", BigDecimal.ZERO, FEES))
                .withMessageContaining("tick size");
        assertThatIllegalArgumentException().isThrownBy(() -> new TradeRecords("PTX", CENT.negate(), FEES));
    }
}
