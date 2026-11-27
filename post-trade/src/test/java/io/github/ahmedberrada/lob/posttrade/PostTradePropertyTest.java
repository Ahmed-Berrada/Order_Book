package io.github.ahmedberrada.lob.posttrade;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.AuctionPriceDetermined;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.SetPhase;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.core.StopOrder;
import io.github.ahmedberrada.lob.core.TimeInForce;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.core.TradingPhase;
import io.github.ahmedberrada.lob.journal.EventBatch;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.statistics.Statistics;

/** The records of random trading days, checked against the engine's own trades and an independent fee formula. */
class PostTradePropertyTest {

    private static final BigDecimal TICK = new BigDecimal("0.05");
    private static final FeeSchedule FEES = FeeSchedule.parse("maker=-0.25,taker=0.35,auction=0.15", 2);

    private static final LocalDate NEXT_DAY = LocalDate.of(2026, 11, 27);

    private record Day(List<EventBatch> batches, List<Command> commands) {
    }

    /** A day of a few hundred commands from members 1 to 4, with call phases, stops and icebergs. */
    private static Day day(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        MatchingEngine engine = new MatchingEngine(Journals.INSTRUMENT);
        List<EventBatch> batches = new ArrayList<>();
        List<Command> commands = new ArrayList<>();
        long micros = Journals.BEFORE_MIDNIGHT - random.nextLong(0, 300);           // some days cross midnight
        for (int i = 0; i < 300; i++) {
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            int kind = random.nextInt(20);
            Command command;
            if (kind < 12) {
                command = new LimitOrder(side, random.nextLong(95, 106), random.nextLong(1, 40), TimeInForce.GTC,
                        false, random.nextInt(4) == 0 ? random.nextLong(1, 10) : 0);
            } else if (kind < 16) {
                command = new MarketOrder(side, random.nextLong(1, 40));
            } else if (kind < 18) {
                command = new StopOrder(side, random.nextLong(95, 106), random.nextLong(1, 20));
            } else {
                command = new SetPhase(random.nextBoolean() ? TradingPhase.CALL : TradingPhase.CONTINUOUS);
            }
            long member = random.nextLong(1, 5);
            List<Event> events = engine.process(command, member);
            batches.add(new EventBatch(i + 1, micros++, events, member));
            commands.add(command);
        }
        return new Day(batches, commands);
    }

    @Property(tries = 200)
    @Rulebook({"PT-001", "PT-002", "PT-003", "PT-004", "PT-005", "PT-007"})
    void everyTradeGivesItsBuyerAndItsSellerWithTheRightFee(@ForAll long seed) {
        Day day = day(seed);
        TradeRecords trades = new TradeRecords("PTX", TICK, FEES);
        boolean[] seen = new boolean[2];          // an auction trade, a trade on the second date
        for (int i = 0; i < day.batches().size(); i++) {
            EventBatch batch = day.batches().get(i);
            List<TradeRecord> records = trades.of(batch);
            List<TradeExecuted> executed = batch.events().stream().filter(TradeExecuted.class::isInstance)
                    .map(TradeExecuted.class::cast).toList();
            assertThat(records).hasSize(2 * executed.size());
            long auctionVolume = batch.events().stream().filter(AuctionPriceDetermined.class::isInstance)
                    .mapToLong(e -> ((AuctionPriceDetermined) e).volume()).sum();
            long auctionLots = 0;
            for (int t = 0; t < executed.size(); t++) {
                TradeExecuted trade = executed.get(t);
                TradeRecord buy = records.get(2 * t);
                TradeRecord sell = records.get(2 * t + 1);
                boolean buyerTakes = trade.aggressorSide() == Side.BUY;
                assertThat(buy.side()).isEqualTo(Side.BUY);
                assertThat(sell.side()).isEqualTo(Side.SELL);
                assertThat(buy.orderId()).isEqualTo(buyerTakes ? trade.takerOrderId() : trade.makerOrderId());
                assertThat(sell.orderId()).isEqualTo(buyerTakes ? trade.makerOrderId() : trade.takerOrderId());
                assertThat(buy.memberId()).isEqualTo(buyerTakes ? trade.takerMemberId() : trade.makerMemberId());
                assertThat(sell.memberId()).isEqualTo(buyerTakes ? trade.makerMemberId() : trade.takerMemberId());
                for (TradeRecord record : List.of(buy, sell)) {
                    assertThat(record.tradeId()).isEqualTo("PTX-" + trade.sequence());
                    assertThat(record.quantity()).isEqualTo(trade.quantity());
                    assertThat(record.priceTicks()).isEqualTo(trade.priceTicks());
                    BigDecimal notional = new BigDecimal(trade.priceTicks() * trade.quantity()).multiply(TICK);
                    assertThat(record.notional()).isEqualByComparingTo(notional);
                    assertThat(record.fee()).isEqualTo(notional.multiply(FEES.rate(record.liquidity()))
                            .divide(BigDecimal.valueOf(10_000), 2, RoundingMode.HALF_EVEN));
                    assertThat(record.tradeDate()).isEqualTo(record.timestamp().atZone(ZoneOffset.UTC)
                            .toLocalDate());
                }
                seen[1] |= buy.tradeDate().equals(NEXT_DAY);
                if (buy.liquidity() == Liquidity.AUCTION) {
                    seen[0] = true;
                    assertThat(sell.liquidity()).isEqualTo(Liquidity.AUCTION);
                    auctionLots += trade.quantity();
                } else {
                    assertThat(List.of(buy.liquidity(), sell.liquidity())).containsExactlyInAnyOrder(
                            Liquidity.TAKER, Liquidity.MAKER);
                    assertThat(buy.liquidity()).isEqualTo(buyerTakes ? Liquidity.TAKER : Liquidity.MAKER);
                }
            }
            // the auction trades are exactly the uncross volume, and only a phase change has any
            assertThat(auctionLots).isEqualTo(auctionVolume);
            if (!(day.commands().get(i) instanceof SetPhase)) {
                assertThat(auctionLots).isZero();
            }
        }
        Statistics.label("auction trade").collect(seen[0]);
        Statistics.label("trade on the next date").collect(seen[1]);
        Statistics.label("auction trade").coverage(c -> c.check(true).percentage(p -> p > 20));
        Statistics.label("trade on the next date").coverage(c -> c.check(true).percentage(p -> p > 20));
    }

    @Property(tries = 200)
    @Rulebook({"PT-008", "PT-009"})
    void theRecordsBalanceAndTheStatementAddsUpToThem(@ForAll long seed) {
        TradeRecords trades = new TradeRecords("PTX", TICK, FEES);
        List<TradeRecord> records = day(seed).batches().stream().flatMap(b -> trades.of(b).stream()).toList();
        assertThat(Balance.check(records)).isEmpty();

        List<Statement> statement = Statement.of(records);
        assertThat(statement.stream().mapToLong(Statement::records).sum()).isEqualTo(records.size());
        assertThat(statement.stream().map(Statement::fees).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(records.stream().map(TradeRecord::fee).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertThat(statement.stream().mapToLong(Statement::boughtQuantity).sum())
                .isEqualTo(statement.stream().mapToLong(Statement::soldQuantity).sum());
    }
}
