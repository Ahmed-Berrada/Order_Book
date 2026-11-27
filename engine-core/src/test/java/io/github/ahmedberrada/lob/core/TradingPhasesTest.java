package io.github.ahmedberrada.lob.core;

import static io.github.ahmedberrada.lob.core.CancelReason.CLIENT_REQUEST;
import static io.github.ahmedberrada.lob.core.CancelReason.SELF_TRADE_PREVENTION;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_PHASE_TRANSITION;
import static io.github.ahmedberrada.lob.core.RejectReason.NOT_ALLOWED_IN_PHASE;
import static io.github.ahmedberrada.lob.core.Side.BUY;
import static io.github.ahmedberrada.lob.core.Side.SELL;
import static io.github.ahmedberrada.lob.core.TradingPhase.CALL;
import static io.github.ahmedberrada.lob.core.TradingPhase.CLOSED;
import static io.github.ahmedberrada.lob.core.TradingPhase.CONTINUOUS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Scenario tests for {@code docs/rulebook/trading-phases.md}. */
class TradingPhasesTest {

    private static final Instrument INSTRUMENT = new Instrument("TEST", 1_000, 10_000);

    private MatchingEngine engine;

    @BeforeEach
    void newEngine() {
        engine = new MatchingEngine(INSTRUMENT);
    }

    private List<Event> phase(TradingPhase phase) {
        return engine.process(new SetPhase(phase));
    }

    private List<Event> limit(Side side, long price, long quantity) {
        return engine.process(new LimitOrder(side, price, quantity));
    }

    /** Puts orders in a call phase, one per entry: side, price, quantity. */
    private void callWith(Object... orders) {
        phase(CALL);
        for (int i = 0; i < orders.length; i += 3) {
            limit((Side) orders[i], (Long) orders[i + 1], (Long) orders[i + 2]);
        }
    }

    @Test
    @Rulebook({"TP-001", "TP-002"})
    void anEngineStartsContinuousAndRefusesAPhaseChangeToTheCurrentPhase() {
        assertThat(engine.phase()).isEqualTo(CONTINUOUS);
        assertThat(phase(CONTINUOUS)).containsExactly(new PhaseChangeRejected(1, INVALID_PHASE_TRANSITION));
        assertThat(phase(CALL)).containsExactly(new PhaseChanged(2, CONTINUOUS, CALL));
        assertThat(phase(CALL)).containsExactly(new PhaseChangeRejected(3, INVALID_PHASE_TRANSITION));
        assertThat(phase(CLOSED)).containsExactly(new PhaseChanged(4, CALL, CLOSED));
        assertThat(phase(CONTINUOUS)).containsExactly(new PhaseChanged(5, CLOSED, CONTINUOUS));
        assertThatNullPointerException().isThrownBy(() -> new SetPhase(null));
    }

    @Test
    @Rulebook("TP-004")
    void closedRefusesNewOrdersAndReplacesButAcceptsCancels() {
        limit(BUY, 100, 10);
        limit(SELL, 105, 10);
        phase(CLOSED);

        assertThat(limit(BUY, 99, 1)).containsExactly(new OrderRejected(6, 3, NOT_ALLOWED_IN_PHASE));
        assertThat(engine.process(new MarketOrder(BUY, 1))).containsExactly(
                new OrderRejected(7, 4, NOT_ALLOWED_IN_PHASE));
        assertThat(engine.process(new StopOrder(BUY, 120, 1))).containsExactly(
                new OrderRejected(8, 5, NOT_ALLOWED_IN_PHASE));
        assertThat(engine.process(new ReplaceOrder(1, 99, 5))).containsExactly(
                new ReplaceRejected(9, 1, NOT_ALLOWED_IN_PHASE));
        assertThat(engine.process(new CancelOrder(1))).containsExactly(
                new OrderCancelled(10, 1, 10, CLIENT_REQUEST));
        assertThat(engine.book().restingQuantity(2)).isEqualTo(10);
    }

    @Test
    @Rulebook("TP-005")
    void aCallPhaseQueuesOrdersWithoutMatchingEvenWhenTheyCross() {
        phase(CALL);
        assertThat(limit(BUY, 101, 10)).containsExactly(
                new OrderAccepted(2, 1), new OrderRested(3, 1, BUY, 101, 10, 10));
        assertThat(limit(SELL, 100, 4)).containsExactly(
                new OrderAccepted(4, 2), new OrderRested(5, 2, SELL, 100, 4, 4));
        assertThat(engine.book().bestBid()).hasValue(101);
        assertThat(engine.book().bestAsk()).hasValue(100);                  // crossed, by design
        assertThat(engine.process(new CancelOrder(2))).containsExactly(new OrderCancelled(6, 2, 4, CLIENT_REQUEST));
    }

    @Test
    @Rulebook("TP-005")
    void aCallPhaseRefusesOrdersThatAskToTradeNow() {
        phase(CALL);
        assertThat(engine.process(new MarketOrder(BUY, 1))).containsExactly(
                new OrderRejected(2, 1, NOT_ALLOWED_IN_PHASE));
        assertThat(engine.process(new LimitOrder(BUY, 100, 1, TimeInForce.IOC, false)))
                .containsExactly(new OrderRejected(3, 2, NOT_ALLOWED_IN_PHASE));
        assertThat(engine.process(new LimitOrder(BUY, 100, 1, TimeInForce.FOK, false)))
                .containsExactly(new OrderRejected(4, 3, NOT_ALLOWED_IN_PHASE));
        assertThat(engine.process(new LimitOrder(BUY, 100, 1, TimeInForce.GTC, true)))
                .containsExactly(new OrderRejected(5, 4, NOT_ALLOWED_IN_PHASE));
        assertThat(engine.process(new StopOrder(BUY, 120, 1))).containsExactly(
                new OrderAccepted(6, 5), new OrderParked(7, 5, BUY, 120, 1));
    }

    @Test
    @Rulebook({"AU-001", "AU-002", "AU-005", "TP-003"})
    void theUncrossTradesAtOnePriceAndTheLaterOrderIsTheTaker() {
        callWith(BUY, 101L, 10L, SELL, 100L, 10L);

        List<Event> events = phase(CONTINUOUS);

        assertThat(events).containsExactly(
                new AuctionPriceDetermined(6, 101, 10),
                new TradeExecuted(7, 2, 1, SELL, 101, 10, 0, 0),
                new PhaseChanged(8, CALL, CONTINUOUS));
        assertThat(engine.book().orderCount()).isZero();
        assertThat(engine.snapshot().lastTradePriceTicks()).isEqualTo(101);
    }

    @Test
    @Rulebook("AU-002")
    void eachSideTradesByPriceThenArrivalAndTheRestStays() {
        callWith(BUY, 101L, 10L, BUY, 100L, 10L, SELL, 100L, 15L);

        List<Event> events = phase(CONTINUOUS);

        assertThat(events).containsExactly(
                new AuctionPriceDetermined(8, 100, 15),
                new TradeExecuted(9, 3, 1, SELL, 100, 10, 5, 0),
                new TradeExecuted(10, 3, 2, SELL, 100, 5, 0, 5),
                new PhaseChanged(11, CALL, CONTINUOUS));
        assertThat(engine.book().depth(BUY)).containsExactly(new OrderBook.Level(100, 5, 1));
        assertThat(engine.book().depth(SELL)).isEmpty();
    }

    @Test
    @Rulebook({"AU-001", "AU-003"})
    void theAuctionPriceFollowsTheTieBreaks() {
        // more buy than sell volume at the best volume: highest price
        callWith(BUY, 102L, 10L, SELL, 100L, 5L);
        assertThat(phase(CONTINUOUS)).first().isEqualTo(new AuctionPriceDetermined(6, 102, 5));

        // more sell than buy: lowest price
        newEngine();
        callWith(BUY, 102L, 5L, SELL, 100L, 10L);
        assertThat(phase(CONTINUOUS)).first().isEqualTo(new AuctionPriceDetermined(6, 100, 5));

        // no surplus and no reference price: the highest
        newEngine();
        callWith(BUY, 104L, 10L, SELL, 100L, 10L);
        assertThat(phase(CONTINUOUS)).first().isEqualTo(new AuctionPriceDetermined(6, 104, 10));
    }

    @Test
    @Rulebook("AU-001")
    void withoutASurplusTheReferencePriceDecides() {
        limit(SELL, 101, 1);
        limit(BUY, 101, 1);                                                  // last trade at 101
        callWith(BUY, 104L, 10L, SELL, 100L, 10L);
        assertThat(phase(CONTINUOUS)).first().isEqualTo(new AuctionPriceDetermined(10, 100, 10));

        newEngine();                                                         // two equally close: the higher
        limit(SELL, 102, 1);
        limit(BUY, 102, 1);
        callWith(BUY, 104L, 10L, SELL, 100L, 10L);
        assertThat(phase(CONTINUOUS)).first().isEqualTo(new AuctionPriceDetermined(10, 104, 10));
    }

    @Test
    @Rulebook({"AU-001", "AU-003"})
    void withoutExecutableVolumeNothingTradesAndTheBookIsKept() {
        callWith(BUY, 99L, 10L, SELL, 100L, 10L);

        assertThat(phase(CONTINUOUS)).containsExactly(new PhaseChanged(6, CALL, CONTINUOUS));
        assertThat(engine.book().bestBid()).hasValue(99);
        assertThat(engine.book().bestAsk()).hasValue(100);
        assertThat(phase(CALL)).hasSize(1);
        assertThat(phase(CLOSED)).containsExactly(new PhaseChanged(8, CALL, CLOSED));      // empty call is fine
    }

    @Test
    @Rulebook({"AU-001", "TP-003"})
    void anUncrossLeavingForClosedKeepsTheRestingOrders() {
        callWith(BUY, 100L, 10L, SELL, 100L, 4L);

        assertThat(phase(CLOSED)).containsExactly(
                new AuctionPriceDetermined(6, 100, 4),
                new TradeExecuted(7, 2, 1, SELL, 100, 4, 0, 6),
                new PhaseChanged(8, CALL, CLOSED));
        assertThat(engine.book().restingQuantity(1)).isEqualTo(6);
    }

    @Test
    @Rulebook({"TP-008", "TP-003", "AU-002"})
    void anIcebergIsShownInFullDuringACallPhase() {
        engine.process(new LimitOrder(BUY, 100, 10, TimeInForce.GTC, false, 2));
        assertThat(engine.book().displayedDepth(BUY)).containsExactly(new OrderBook.Level(100, 2, 1));

        assertThat(phase(CALL)).containsExactly(
                new PhaseChanged(3, CONTINUOUS, CALL), new OrderReplenished(4, 1, BUY, 100, 10));
        assertThat(engine.book().displayedDepth(BUY)).containsExactly(new OrderBook.Level(100, 10, 1));

        assertThat(engine.process(new LimitOrder(SELL, 99, 15, TimeInForce.GTC, false, 3))).containsExactly(
                new OrderAccepted(5, 2), new OrderRested(6, 2, SELL, 99, 15, 15));

        // the uncross trades hidden quantity too; what is left gets its peak back
        assertThat(phase(CONTINUOUS)).containsExactly(
                new AuctionPriceDetermined(7, 99, 10),
                new TradeExecuted(8, 2, 1, SELL, 99, 10, 5, 0),
                new PhaseChanged(9, CALL, CONTINUOUS),
                new OrderReplenished(10, 2, SELL, 99, 3));
        assertThat(engine.book().displayedDepth(SELL)).containsExactly(new OrderBook.Level(99, 3, 1));
        assertThat(engine.book().depth(SELL)).containsExactly(new OrderBook.Level(99, 5, 1));
    }

    @Test
    @Rulebook("TP-006")
    void aReplaceInACallPhaseKeepsOrLosesPriorityAndNeverMatches() {
        callWith(BUY, 100L, 10L, BUY, 100L, 10L, SELL, 105L, 10L);

        assertThat(engine.process(new ReplaceOrder(1, 100, 4))).containsExactly(
                new OrderReplaced(8, 1, BUY, 100, 4, true));
        assertThat(engine.process(new ReplaceOrder(1, 106, 4))).containsExactly(
                new OrderReplaced(9, 1, BUY, 106, 4, false), new OrderRested(10, 1, BUY, 106, 4, 4));
        assertThat(engine.book().bestBid()).hasValue(106);
        assertThat(engine.book().bestAsk()).hasValue(105);                  // crossed, and nothing traded
        assertThat(engine.process(new ReplaceOrder(9, 100, 1))).containsExactly(
                new ReplaceRejected(11, 9, RejectReason.UNKNOWN_ORDER));
    }

    @Test
    @Rulebook("AU-004")
    void selfTradePreventionActsWhenAnOrderEntersACallPhase() {
        phase(CALL);
        engine.process(new LimitOrder(SELL, 100, 5, TimeInForce.GTC, false, 0,
                new SelfTradeInstruction(7, SelfTradeMode.CANCEL_NEWEST)));
        engine.process(new LimitOrder(SELL, 101, 5, TimeInForce.GTC, false, 0,
                new SelfTradeInstruction(8, SelfTradeMode.CANCEL_NEWEST)));

        // CANCEL_NEWEST: the entering order is cancelled
        assertThat(engine.process(new LimitOrder(BUY, 101, 3, TimeInForce.GTC, false, 0,
                new SelfTradeInstruction(7, SelfTradeMode.CANCEL_NEWEST)))).containsExactly(
                new OrderAccepted(6, 3), new OrderCancelled(7, 3, 3, SELF_TRADE_PREVENTION));
        // CANCEL_OLDEST: the crossed resting order goes, the entering one rests
        assertThat(engine.process(new LimitOrder(BUY, 101, 3, TimeInForce.GTC, false, 0,
                new SelfTradeInstruction(7, SelfTradeMode.CANCEL_OLDEST)))).containsExactly(
                new OrderAccepted(8, 4), new OrderCancelled(9, 1, 5, SELF_TRADE_PREVENTION),
                new OrderRested(10, 4, BUY, 101, 3, 3));
        // CANCEL_BOTH on an order whose group has nothing to cross: it simply rests
        assertThat(engine.process(new LimitOrder(BUY, 101, 2, TimeInForce.GTC, false, 0,
                new SelfTradeInstruction(9, SelfTradeMode.CANCEL_BOTH)))).containsExactly(
                new OrderAccepted(11, 5), new OrderRested(12, 5, BUY, 101, 2, 2));
        // CANCEL_BOTH against group 8
        assertThat(engine.process(new LimitOrder(BUY, 101, 2, TimeInForce.GTC, false, 0,
                new SelfTradeInstruction(8, SelfTradeMode.CANCEL_BOTH)))).containsExactly(
                new OrderAccepted(13, 6), new OrderCancelled(14, 2, 5, SELF_TRADE_PREVENTION),
                new OrderCancelled(15, 6, 2, SELF_TRADE_PREVENTION));
    }

    @Test
    @Rulebook({"TP-007", "AU-005"})
    void stopsTriggeredByTheUncrossAreReleasedWhenTradingContinues() {
        phase(CALL);
        engine.process(new StopOrder(BUY, 101, 5));
        limit(BUY, 101, 10);
        limit(SELL, 101, 10);
        limit(SELL, 103, 5);

        List<Event> events = phase(CONTINUOUS);

        assertThat(events).containsExactly(
                new AuctionPriceDetermined(10, 101, 10),
                new TradeExecuted(11, 3, 2, SELL, 101, 10, 0, 0),
                new PhaseChanged(12, CALL, CONTINUOUS),
                new StopTriggered(13, 1),
                new TradeExecuted(14, 1, 4, BUY, 103, 5, 0, 0));
    }

    @Test
    @Rulebook("TP-007")
    void stopsStayParkedWhileTheVenueIsNotContinuous() {
        phase(CALL);
        engine.process(new StopOrder(BUY, 101, 5));
        limit(BUY, 101, 10);
        limit(SELL, 101, 10);

        assertThat(phase(CLOSED)).containsExactly(
                new AuctionPriceDetermined(8, 101, 10),
                new TradeExecuted(9, 3, 2, SELL, 101, 10, 0, 0),
                new PhaseChanged(10, CALL, CLOSED));
        assertThat(engine.snapshot().parkedStops()).hasSize(1);
        // not released by the change of phase either: only by a later trade that reaches it
        assertThat(phase(CONTINUOUS)).containsExactly(new PhaseChanged(11, CLOSED, CONTINUOUS));
        assertThat(engine.snapshot().parkedStops()).hasSize(1);
        assertThat(engine.process(new CancelOrder(1))).containsExactly(
                new OrderCancelled(12, 1, 5, CLIENT_REQUEST));
    }

    @Test
    @Rulebook("AU-006")
    void aSnapshotKeepsThePhaseAndACrossedCallBook() {
        callWith(BUY, 101L, 10L, SELL, 100L, 10L);
        EngineSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.phase()).isEqualTo(CALL);

        MatchingEngine restored = MatchingEngine.restore(snapshot);

        assertThat(restored.phase()).isEqualTo(CALL);
        assertThat(restored.snapshot()).isEqualTo(snapshot);
        assertThat(restored.process(new SetPhase(CONTINUOUS))).isEqualTo(engine.process(new SetPhase(CONTINUOUS)));
        assertThat(new EngineSnapshot(INSTRUMENT, 1, 1, List.of()).phase()).isEqualTo(CONTINUOUS);
        assertThatNullPointerException().isThrownBy(
                () -> new EngineSnapshot(INSTRUMENT, 1, 1, List.of(), 0, List.of(), null));
    }

    @Test
    @Rulebook("AU-006")
    void aCrossedSnapshotIsOnlyValidInACallPhase() {
        List<RestingOrder> crossed = List.of(
                new RestingOrder(1, BUY, 101, 5, false, 0, 5, SelfTradeInstruction.NONE),
                new RestingOrder(2, SELL, 100, 5, false, 0, 5, SelfTradeInstruction.NONE));

        assertThatIllegalArgumentException().isThrownBy(() -> MatchingEngine.restore(
                new EngineSnapshot(INSTRUMENT, 3, 1, crossed, 0, List.of(), CONTINUOUS)));
        assertThatIllegalArgumentException().isThrownBy(() -> MatchingEngine.restore(
                new EngineSnapshot(INSTRUMENT, 3, 1, crossed, 0, List.of(), CLOSED)));
        assertThat(MatchingEngine.restore(new EngineSnapshot(INSTRUMENT, 3, 1, crossed, 0, List.of(), CALL)).phase())
                .isEqualTo(CALL);
        // an iceberg must be shown in full during a call phase
        List<RestingOrder> hidden = List.of(new RestingOrder(1, BUY, 101, 5, false, 2, 2, SelfTradeInstruction.NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> MatchingEngine.restore(
                new EngineSnapshot(INSTRUMENT, 2, 1, hidden, 0, List.of(), CALL)));
        assertThat(MatchingEngine.restore(new EngineSnapshot(INSTRUMENT, 2, 1, hidden, 0, List.of(), CONTINUOUS))
                .phase()).isEqualTo(CONTINUOUS);
    }
}
