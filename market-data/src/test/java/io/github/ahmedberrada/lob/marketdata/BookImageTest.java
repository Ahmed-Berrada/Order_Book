package io.github.ahmedberrada.lob.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import io.github.ahmedberrada.lob.core.OrderBook;
import io.github.ahmedberrada.lob.core.ReplaceOrder;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.SelfTradeInstruction;
import io.github.ahmedberrada.lob.core.SelfTradeMode;
import io.github.ahmedberrada.lob.core.SetPhase;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.core.StopOrder;
import io.github.ahmedberrada.lob.core.TimeInForce;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.core.TradingPhase;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.marketdata.MarketDataMessage.Update;
import io.github.ahmedberrada.lob.service.InstrumentState;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class BookImageTest {

    private static final Instrument INSTRUMENT = new Instrument("MD", 50, 200);

    @Provide
    Arbitrary<List<Command>> commands() {
        Arbitrary<Side> side = Arbitraries.of(Side.class);
        // two groups and every mode, so that self-trade prevention cancels resting orders now and then
        Arbitrary<SelfTradeInstruction> stp = Combinators.combine(Arbitraries.of(0L, 0L, 1L, 2L),
                Arbitraries.of(SelfTradeMode.class)).as(SelfTradeInstruction::new);
        Arbitrary<Command> limit = Combinators.combine(side, Arbitraries.longs().between(95, 105),
                Arbitraries.longs().between(1, 20), stp)
                .as((s, p, q, st) -> new LimitOrder(s, p, q, TimeInForce.GTC, false, 0, st));
        Arbitrary<Command> market = Combinators.combine(side, Arbitraries.longs().between(1, 30), stp)
                .as((s, q, st) -> new MarketOrder(s, q, TimeInForce.IOC, st));
        Arbitrary<Command> cancel = Arbitraries.longs().between(1, 120).map(CancelOrder::new);
        Arbitrary<Command> invalid = Arbitraries.of(new LimitOrder(Side.BUY, 100, 0), new LimitOrder(Side.SELL, 999, 1));
        Arbitrary<Command> ioc = Combinators.combine(side, Arbitraries.longs().between(95, 105),
                Arbitraries.longs().between(1, 20), Arbitraries.of(TimeInForce.class), Arbitraries.of(true, false))
                .as((s, p, q, t, po) -> new LimitOrder(s, p, q, t, po));
        // a grouped iceberg: preventing a self-trade cancels its hidden quantity too
        Arbitrary<Command> iceberg = Combinators.combine(side, Arbitraries.longs().between(95, 105),
                Arbitraries.longs().between(5, 40), Arbitraries.longs().between(1, 6), stp)
                .as((s, p, q, d, st) -> new LimitOrder(s, p, q, TimeInForce.GTC, false, d, st));
        Arbitrary<Command> stop = Combinators.combine(side, Arbitraries.longs().between(95, 105),
                Arbitraries.of(0L, 95L, 105L), Arbitraries.longs().between(1, 20), stp).as(StopOrder::new);
        Arbitrary<Command> replace = Combinators.combine(Arbitraries.longs().between(1, 120),
                Arbitraries.longs().between(95, 105), Arbitraries.longs().between(1, 20)).as(ReplaceOrder::new);
        Arbitrary<Command> phase = Arbitraries.of(TradingPhase.class).map(SetPhase::new);
        return Arbitraries.frequencyOf(Tuple.of(6, limit), Tuple.of(2, market), Tuple.of(3, cancel), Tuple.of(1, invalid),
                        Tuple.of(2, ioc), Tuple.of(3, replace), Tuple.of(3, iceberg), Tuple.of(3, stop),
                        Tuple.of(2, phase))
                .list().ofMaxSize(120);
    }

    private static List<BookLevel> depth(MatchingEngine engine, Side side) {
        return engine.book().displayedDepth(side).stream()
                .map((OrderBook.Level l) -> new BookLevel(l.priceTicks(), l.quantity(), l.orderCount())).toList();
    }

    @Property(tries = 300)
    @Rulebook({"MD-002", "MD-003", "MD-004", "MD-005", "MD-006", "MD-007", "OT-008", "IC-002", "IC-006", "ST-003", "SP-003", "AU-002", "TP-008"})
    void imageAndSubscribersMatchTheEngineAfterEveryCommand(@ForAll("commands") List<Command> commands,
            @ForAll @IntRange(max = 120) int history, @ForAll @IntRange(max = 120) int subscribeAt) {
        MatchingEngine engine = new MatchingEngine(INSTRUMENT);
        int start = Math.min(history, commands.size());
        long sequence = 0;
        for (Command command : commands.subList(0, start)) {
            engine.process(command);
            sequence++;
        }
        BookImage image = new BookImage("MD", new InstrumentState(sequence, engine.snapshot()));     // MD-007
        assertThat(image.apply(new EventBatch(sequence, 0, List.of()))).isEmpty();           // already included
        assertThat(image.levels(Side.BUY)).isEqualTo(depth(engine, Side.BUY));

        ReplicaBook subscriber = null;
        long lastFeedSequence = 0;
        for (int i = start; i < commands.size(); i++) {
            if (i == Math.max(start, subscribeAt)) {
                subscriber = new ReplicaBook(image.snapshot());
            }
            List<Event> events = engine.process(commands.get(i));
            sequence++;
            Optional<Update> update = image.apply(new EventBatch(sequence, sequence * 10, events));

            assertThat(image.levels(Side.BUY)).isEqualTo(depth(engine, Side.BUY));
            assertThat(image.levels(Side.SELL)).isEqualTo(depth(engine, Side.SELL));
            List<Trade> trades = events.stream().filter(TradeExecuted.class::isInstance).map(TradeExecuted.class::cast)
                    .map(t -> new Trade(t.priceTicks(), t.quantity(), t.aggressorSide())).toList();
            if (update.isPresent()) {
                Update u = update.get();
                assertThat(u.feedSequence()).isEqualTo(lastFeedSequence + 1);
                lastFeedSequence = u.feedSequence();
                assertThat(u.commandSequence()).isEqualTo(sequence);
                assertThat(u.timestampMicros()).isEqualTo(sequence * 10);
                assertThat(u.trades()).isEqualTo(trades);
                assertThat(u.bestBid()).isEqualTo(depth(engine, Side.BUY).stream().findFirst().orElse(null));
                assertThat(u.bestAsk()).isEqualTo(depth(engine, Side.SELL).stream().findFirst().orElse(null));
                if (subscriber != null) {
                    subscriber.apply(u);
                }
            } else {
                assertThat(trades).isEmpty();
            }
            if (subscriber != null) {
                assertThat(subscriber.bids()).isEqualTo(depth(engine, Side.BUY));
                assertThat(subscriber.asks()).isEqualTo(depth(engine, Side.SELL));
            }
        }
    }

    @Test
    @Rulebook("MD-007")
    void eventsTheStartingStateIncludesAreNotAppliedAgain() {
        MatchingEngine engine = new MatchingEngine(INSTRUMENT);
        EventBatch included = new EventBatch(1, 0, engine.process(new LimitOrder(Side.BUY, 100, 5)));
        BookImage image = new BookImage("MD", new InstrumentState(1, engine.snapshot()));

        assertThat(image.apply(included)).isEmpty();
        assertThat(image.levels(Side.BUY)).containsExactly(new BookLevel(100, 5, 1));
    }

    @Test
    @Rulebook({"MD-005", "OT-008"})
    void replacesShrinkLevelsInPlaceAndANoOpReplacePublishesNothing() {
        MatchingEngine engine = new MatchingEngine(INSTRUMENT);
        BookImage image = new BookImage("MD", new InstrumentState(0, engine.snapshot()));
        image.apply(new EventBatch(1, 0, engine.process(new LimitOrder(Side.BUY, 100, 5))));

        assertThat(image.apply(new EventBatch(2, 0, engine.process(new ReplaceOrder(1, 100, 5))))).isEmpty();
        Update reduced = image.apply(new EventBatch(3, 0, engine.process(new ReplaceOrder(1, 100, 2)))).orElseThrow();
        assertThat(reduced.bids()).containsExactly(new BookLevel(100, 2, 1));
        Update moved = image.apply(new EventBatch(4, 0, engine.process(new ReplaceOrder(1, 101, 2)))).orElseThrow();
        assertThat(moved.bids()).containsExactly(new BookLevel(101, 2, 1), new BookLevel(100, 0, 0));
        assertThat(image.levels(Side.BUY)).containsExactly(new BookLevel(101, 2, 1));
    }

    @Test
    @Rulebook({"MD-001", "MD-005", "MD-006"})
    void updatesListOnlyChangedLevelsAndRemovalsAsZero() {
        MatchingEngine engine = new MatchingEngine(INSTRUMENT);
        BookImage image = new BookImage("MD", new InstrumentState(0, engine.snapshot()));
        long[] sequence = {0};
        java.util.function.Function<Command, Optional<Update>> run =
                c -> image.apply(new EventBatch(++sequence[0], 0, engine.process(c)));

        run.apply(new LimitOrder(Side.SELL, 101, 5));
        run.apply(new LimitOrder(Side.SELL, 102, 5));
        run.apply(new LimitOrder(Side.BUY, 99, 5));
        Update sweep = run.apply(new MarketOrder(Side.BUY, 7)).orElseThrow();

        assertThat(sweep.feedSequence()).isEqualTo(4);
        assertThat(sweep.asks()).containsExactly(new BookLevel(101, 0, 0), new BookLevel(102, 3, 1));
        assertThat(sweep.bids()).isEmpty();
        assertThat(sweep.trades()).containsExactly(new Trade(101, 5, Side.BUY), new Trade(102, 2, Side.BUY));
        assertThat(sweep.bestBid()).isEqualTo(new BookLevel(99, 5, 1));
        assertThat(sweep.bestAsk()).isEqualTo(new BookLevel(102, 3, 1));

        assertThat(run.apply(new MarketOrder(Side.BUY, 0))).isEmpty();          // rejected: nothing to publish
        assertThat(run.apply(new CancelOrder(42))).isEmpty();                   // unknown order: nothing either
        Update cancel = run.apply(new CancelOrder(3)).orElseThrow();
        assertThat(cancel.feedSequence()).isEqualTo(5);
        assertThat(cancel.bids()).containsExactly(new BookLevel(99, 0, 0));
        assertThat(cancel.bestBid()).isNull();
        assertThat(image.snapshot().feedSequence()).isEqualTo(5);
        assertThat(image.commandSequence()).isEqualTo(7);
    }
}
