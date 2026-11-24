package io.github.ahmedberrada.lob.core;

import static io.github.ahmedberrada.lob.core.CancelReason.FILL_OR_KILL;
import static io.github.ahmedberrada.lob.core.CancelReason.SELF_TRADE_PREVENTION;
import static io.github.ahmedberrada.lob.core.CancelReason.UNFILLED_MARKET_REMAINDER;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_DISPLAY_QUANTITY;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_PRICE;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_QUANTITY;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_SELF_TRADE_GROUP;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_TIME_IN_FORCE;
import static io.github.ahmedberrada.lob.core.RejectReason.POST_ONLY_WOULD_TRADE;
import static io.github.ahmedberrada.lob.core.SelfTradeMode.CANCEL_BOTH;
import static io.github.ahmedberrada.lob.core.SelfTradeMode.CANCEL_NEWEST;
import static io.github.ahmedberrada.lob.core.SelfTradeMode.CANCEL_OLDEST;
import static io.github.ahmedberrada.lob.core.Side.BUY;
import static io.github.ahmedberrada.lob.core.Side.SELL;
import static io.github.ahmedberrada.lob.core.TimeInForce.FOK;
import static io.github.ahmedberrada.lob.core.TimeInForce.GTC;
import static io.github.ahmedberrada.lob.core.TimeInForce.IOC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Scenario tests for {@code docs/rulebook/self-trade-prevention.md}. */
class SelfTradePreventionTest {

    private static final Instrument INSTRUMENT = new Instrument("TEST", 1_000, 10_000);

    private MatchingEngine engine;

    @BeforeEach
    void newEngine() {
        engine = new MatchingEngine(INSTRUMENT);
    }

    private static SelfTradeInstruction stp(long group, SelfTradeMode mode) {
        return new SelfTradeInstruction(group, mode);
    }

    private List<Event> limit(Side side, long price, long quantity, long group, SelfTradeMode mode) {
        return engine.process(new LimitOrder(side, price, quantity, GTC, false, 0, stp(group, mode)));
    }

    private List<Event> limit(Side side, long price, long quantity, TimeInForce tif, long group, SelfTradeMode mode) {
        return engine.process(new LimitOrder(side, price, quantity, tif, false, 0, stp(group, mode)));
    }

    private List<Event> market(Side side, long quantity, TimeInForce tif, long group, SelfTradeMode mode) {
        return engine.process(new MarketOrder(side, quantity, tif, stp(group, mode)));
    }

    // ---- Instruction ------------------------------------------------------------------------------

    @Test
    @Rulebook("SP-001")
    void commandsDefaultToNoPreventionAndRequireAnInstruction() {
        assertThat(new LimitOrder(BUY, 1, 1).selfTrade()).isEqualTo(SelfTradeInstruction.NONE);
        assertThat(new LimitOrder(BUY, 1, 1, GTC, false).selfTrade()).isEqualTo(SelfTradeInstruction.NONE);
        assertThat(new MarketOrder(BUY, 1).selfTrade()).isEqualTo(SelfTradeInstruction.NONE);
        assertThat(new MarketOrder(BUY, 1, IOC).selfTrade()).isEqualTo(SelfTradeInstruction.NONE);
        assertThat(new StopOrder(BUY, 1, 1).selfTrade()).isEqualTo(SelfTradeInstruction.NONE);
        assertThat(new ParkedStop(1, BUY, 1, 0, 1).selfTrade()).isEqualTo(SelfTradeInstruction.NONE);
        assertThat(new RestingOrder(1, BUY, 1, 1).selfTrade()).isEqualTo(SelfTradeInstruction.NONE);
        assertThat(SelfTradeInstruction.NONE.group()).isZero();
        assertThatNullPointerException().isThrownBy(() -> new SelfTradeInstruction(1, null));
        assertThatNullPointerException().isThrownBy(() -> new LimitOrder(BUY, 1, 1, GTC, false, 0, null));
        assertThatNullPointerException().isThrownBy(() -> new MarketOrder(BUY, 1, IOC, null));
        assertThatNullPointerException().isThrownBy(() -> new StopOrder(BUY, 1, 0, 1, null));
        assertThatNullPointerException().isThrownBy(() -> new ParkedStop(1, BUY, 1, 0, 1, null));
        assertThatNullPointerException().isThrownBy(() ->
                new RestingOrder(1, BUY, 1, 1, false, 0, 1, null));
    }

    @Test
    @Rulebook("SP-001")
    void aNegativeGroupIsRejectedAfterTheEarlierChecks() {
        SelfTradeInstruction invalid = stp(-1, CANCEL_NEWEST);
        assertThat(engine.process(new LimitOrder(BUY, 100, 5, GTC, false, 0, invalid)))
                .containsExactly(new OrderRejected(1, 1, INVALID_SELF_TRADE_GROUP));
        assertThat(engine.process(new LimitOrder(BUY, 100, 0, GTC, false, 0, invalid)))
                .containsExactly(new OrderRejected(2, 2, INVALID_QUANTITY));
        assertThat(engine.process(new LimitOrder(BUY, 100, 5, GTC, false, 9, invalid)))
                .containsExactly(new OrderRejected(3, 3, INVALID_DISPLAY_QUANTITY));
        assertThat(engine.process(new MarketOrder(BUY, 1, IOC, invalid)))     // checked before NO_LIQUIDITY
                .containsExactly(new OrderRejected(4, 4, INVALID_SELF_TRADE_GROUP));
        assertThat(engine.process(new MarketOrder(BUY, 1, GTC, invalid)))
                .containsExactly(new OrderRejected(5, 5, INVALID_TIME_IN_FORCE));
        assertThat(engine.process(new StopOrder(BUY, 100, 0, 5, invalid)))
                .containsExactly(new OrderRejected(6, 6, INVALID_SELF_TRADE_GROUP));
        assertThat(engine.process(new StopOrder(BUY, 0, 0, 5, invalid)))
                .containsExactly(new OrderRejected(7, 7, INVALID_PRICE));
        assertThat(engine.book().orderCount()).isZero();
        assertThat(engine.snapshot().parkedStops()).isEmpty();
    }

    @Test
    @Rulebook("SP-001")
    void ordersWithoutAGroupOrInDifferentGroupsTradeNormally() {
        limit(SELL, 100, 5, 0, CANCEL_BOTH);                                   // 1
        assertThat(limit(BUY, 100, 5, 0, CANCEL_BOTH)).containsExactly(
                new OrderAccepted(3, 2), new TradeExecuted(4, 2, 1, BUY, 100, 5, 0, 0));
        limit(SELL, 100, 3, 1, CANCEL_BOTH);                                   // 3
        assertThat(limit(BUY, 100, 3, 2, CANCEL_BOTH)).containsExactly(
                new OrderAccepted(7, 4), new TradeExecuted(8, 4, 3, BUY, 100, 3, 0, 0));
        // group 0 on one side only: a grouped order does not conflict with an ungrouped one
        limit(SELL, 100, 1, 0, CANCEL_NEWEST);                                 // 5
        assertThat(limit(BUY, 100, 1, 1, CANCEL_NEWEST)).containsExactly(
                new OrderAccepted(11, 6), new TradeExecuted(12, 6, 5, BUY, 100, 1, 0, 0));
    }

    // ---- Modes ------------------------------------------------------------------------------------

    @Test
    @Rulebook({"SP-002", "SP-005"})
    void cancelNewestStopsAtTheOwnOrderAndKeepsEarlierFills() {
        limit(SELL, 100, 3, 2, CANCEL_NEWEST);                                 // 1
        limit(SELL, 100, 4, 1, CANCEL_NEWEST);                                 // 2, own, behind order 1
        limit(SELL, 101, 9, 2, CANCEL_NEWEST);                                 // 3

        assertThat(limit(BUY, 101, 5, 1, CANCEL_NEWEST)).containsExactly(
                new OrderAccepted(7, 4),
                new TradeExecuted(8, 4, 1, BUY, 100, 3, 2, 0),
                new OrderCancelled(9, 4, 2, SELF_TRADE_PREVENTION));
        // the remainder did not rest, and the own order stays
        assertThat(engine.book().bestBid()).isEmpty();
        assertThat(engine.book().restingQuantity(2)).isEqualTo(4);

        // now the own order is first: nothing trades, and a market order says why it was cancelled
        assertThat(market(BUY, 20, IOC, 1, CANCEL_NEWEST)).containsExactly(
                new OrderAccepted(10, 5),
                new OrderCancelled(11, 5, 20, SELF_TRADE_PREVENTION));
        assertThat(engine.book().orderCount()).isEqualTo(2);
    }

    @Test
    @Rulebook({"SP-003", "SP-005"})
    void cancelOldestCancelsEveryOwnOrderItMeetsHiddenQuantityIncluded() {
        limit(SELL, 100, 5, 1, CANCEL_NEWEST);                                 // 1, own
        limit(SELL, 100, 4, 2, CANCEL_NEWEST);                                 // 2
        engine.process(new LimitOrder(SELL, 101, 30, GTC, false, 10, stp(1, CANCEL_NEWEST)));   // 3, own iceberg

        assertThat(limit(BUY, 101, 12, 1, CANCEL_OLDEST)).containsExactly(
                new OrderAccepted(7, 4),
                new OrderCancelled(8, 1, 5, SELF_TRADE_PREVENTION),
                new TradeExecuted(9, 4, 2, BUY, 100, 4, 8, 0),
                new OrderCancelled(10, 3, 30, SELF_TRADE_PREVENTION),
                new OrderRested(11, 4, BUY, 101, 8, 8));
        assertThat(engine.book().orderCount()).isEqualTo(1);
    }

    @Test
    @Rulebook({"SP-003", "SP-002"})
    void cancelOldestThenAnEmptyBookIsAnOrdinaryUnfilledMarketRemainder() {
        limit(SELL, 100, 5, 1, CANCEL_NEWEST);                                 // 1
        assertThat(market(BUY, 8, IOC, 1, CANCEL_OLDEST)).containsExactly(
                new OrderAccepted(3, 2),
                new OrderCancelled(4, 1, 5, SELF_TRADE_PREVENTION),
                new OrderCancelled(5, 2, 8, UNFILLED_MARKET_REMAINDER));
    }

    @Test
    @Rulebook({"SP-004", "SP-005"})
    void cancelBothCancelsTheRestingOrderThenTheIncomingRemainder() {
        limit(SELL, 100, 3, 2, CANCEL_NEWEST);                                 // 1
        limit(SELL, 100, 4, 1, CANCEL_NEWEST);                                 // 2, own
        limit(SELL, 100, 6, 2, CANCEL_NEWEST);                                 // 3, behind the own order

        assertThat(limit(BUY, 100, 10, 1, CANCEL_BOTH)).containsExactly(
                new OrderAccepted(7, 4),
                new TradeExecuted(8, 4, 1, BUY, 100, 3, 7, 0),
                new OrderCancelled(9, 2, 4, SELF_TRADE_PREVENTION),
                new OrderCancelled(10, 4, 7, SELF_TRADE_PREVENTION));
        assertThat(engine.book().restingQuantity(3)).isEqualTo(6);
        assertThat(engine.book().orderCount()).isEqualTo(1);
    }

    // ---- Interaction with other rules -------------------------------------------------------------

    @Test
    @Rulebook("SP-006")
    void fillOrKillCountsOnlyLevelsBetterThanTheFirstOwnOrderAndNeverCancelsOne() {
        limit(SELL, 100, 3, 2, CANCEL_NEWEST);                                 // 1
        limit(SELL, 101, 5, 1, CANCEL_NEWEST);                                 // 2, own

        assertThat(limit(BUY, 101, 3, FOK, 1, CANCEL_OLDEST)).containsExactly(
                new OrderAccepted(5, 3), new TradeExecuted(6, 3, 1, BUY, 100, 3, 0, 0));
        limit(SELL, 100, 3, 2, CANCEL_NEWEST);                                 // 4
        assertThat(limit(BUY, 101, 4, FOK, 1, CANCEL_OLDEST)).containsExactly(
                new OrderAccepted(9, 5), new OrderCancelled(10, 5, 4, FILL_OR_KILL));
        assertThat(engine.book().restingQuantity(2)).as("own order untouched").isEqualTo(5);
        assertThat(engine.book().restingQuantity(4)).isEqualTo(3);
    }

    @Test
    @Rulebook("SP-006")
    void fillOrKillIsConservativeWithinTheLevelOfAnOwnOrder() {
        limit(SELL, 100, 3, 2, CANCEL_NEWEST);                                 // 1
        limit(SELL, 100, 3, 1, CANCEL_NEWEST);                                 // 2, own, same level

        assertThat(limit(BUY, 100, 3, FOK, 1, CANCEL_NEWEST)).containsExactly(
                new OrderAccepted(5, 3), new OrderCancelled(6, 3, 3, FILL_OR_KILL));
        // another group may take it
        assertThat(limit(BUY, 100, 3, FOK, 3, CANCEL_NEWEST)).containsExactly(
                new OrderAccepted(7, 4), new TradeExecuted(8, 4, 1, BUY, 100, 3, 0, 0));
    }

    @Test
    @Rulebook("SP-006")
    void marketFillOrKillFollowsTheSameRule() {
        limit(SELL, 100, 3, 2, CANCEL_NEWEST);                                 // 1
        limit(SELL, 105, 5, 1, CANCEL_NEWEST);                                 // 2, own

        assertThat(market(BUY, 4, FOK, 1, CANCEL_BOTH)).containsExactly(
                new OrderAccepted(5, 3), new OrderCancelled(6, 3, 4, FILL_OR_KILL));
        assertThat(market(BUY, 3, FOK, 1, CANCEL_BOTH)).containsExactly(
                new OrderAccepted(7, 4), new TradeExecuted(8, 4, 1, BUY, 100, 3, 0, 0));
    }

    @Test
    @Rulebook("SP-007")
    void postOnlyIsRejectedWhoeverOwnsTheOrderItWouldTradeWith() {
        limit(SELL, 100, 3, 1, CANCEL_NEWEST);                                 // 1
        assertThat(engine.process(new LimitOrder(BUY, 100, 2, GTC, true, 0, stp(1, CANCEL_OLDEST))))
                .containsExactly(new OrderRejected(3, 2, POST_ONLY_WOULD_TRADE));
        assertThat(engine.book().restingQuantity(1)).isEqualTo(3);
    }

    @Test
    @Rulebook("SP-008")
    void aReplaceThatLosesPriorityAppliesTheOrdersOwnMode() {
        limit(BUY, 99, 5, 1, CANCEL_OLDEST);                                   // 1
        limit(SELL, 100, 3, 1, CANCEL_NEWEST);                                 // 2: the incoming order's mode decides

        assertThat(engine.process(new ReplaceOrder(1, 100, 5))).containsExactly(
                new OrderReplaced(5, 1, BUY, 100, 5, false),
                new OrderCancelled(6, 2, 3, SELF_TRADE_PREVENTION),
                new OrderRested(7, 1, BUY, 100, 5, 5));
        assertThat(engine.snapshot().restingOrders()).containsExactly(
                new RestingOrder(1, BUY, 100, 5, false, 0, 5, stp(1, CANCEL_OLDEST)));
    }

    @Test
    @Rulebook("SP-008")
    void aReplaceThatMeetsAnOwnOrderCanCancelItself() {
        limit(BUY, 99, 5, 1, CANCEL_NEWEST);                                   // 1
        limit(SELL, 100, 3, 1, CANCEL_OLDEST);                                 // 2

        assertThat(engine.process(new ReplaceOrder(1, 100, 5))).containsExactly(
                new OrderReplaced(5, 1, BUY, 100, 5, false),
                new OrderCancelled(6, 1, 5, SELF_TRADE_PREVENTION));
        assertThat(engine.book().restingQuantity(1)).isZero();
        assertThat(engine.book().restingQuantity(2)).isEqualTo(3);
        // a replace that keeps its place never matches, so never prevents anything
        limit(BUY, 98, 5, 1, CANCEL_NEWEST);                                   // 3
        assertThat(engine.process(new ReplaceOrder(3, 98, 2)))
                .containsExactly(new OrderReplaced(9, 3, BUY, 98, 2, true));
    }

    @Test
    @Rulebook("SP-008")
    void aReleasedStopAppliesTheInstructionItWasEnteredWith() {
        limit(SELL, 100, 1, 2, CANCEL_NEWEST);                                 // 1
        limit(SELL, 101, 6, 1, CANCEL_NEWEST);                                 // 2, own
        engine.process(new StopOrder(BUY, 100, 0, 5, stp(1, CANCEL_OLDEST)));  // 3

        // group 3 triggers the stop with a trade against group 2; the stop (group 1) then meets order 2
        assertThat(market(BUY, 1, IOC, 3, CANCEL_NEWEST)).containsExactly(
                new OrderAccepted(7, 4),
                new TradeExecuted(8, 4, 1, BUY, 100, 1, 0, 0),
                new StopTriggered(9, 3),
                new OrderCancelled(10, 2, 6, SELF_TRADE_PREVENTION),
                new OrderCancelled(11, 3, 5, UNFILLED_MARKET_REMAINDER));

        newEngine();
        limit(SELL, 100, 1, 2, CANCEL_NEWEST);                                 // 1
        limit(SELL, 101, 6, 1, CANCEL_NEWEST);                                 // 2, own
        engine.process(new StopOrder(BUY, 100, 0, 5, stp(1, CANCEL_NEWEST)));  // 3
        assertThat(market(BUY, 1, IOC, 3, CANCEL_NEWEST)).containsExactly(
                new OrderAccepted(7, 4),
                new TradeExecuted(8, 4, 1, BUY, 100, 1, 0, 0),
                new StopTriggered(9, 3),
                new OrderCancelled(10, 3, 5, SELF_TRADE_PREVENTION));
        assertThat(engine.book().restingQuantity(2)).isEqualTo(6);
    }

    @Test
    @Rulebook("SP-009")
    void groupsAndModesSurviveASnapshot() {
        limit(BUY, 99, 5, 1, CANCEL_OLDEST);                                   // 1
        engine.process(new LimitOrder(SELL, 101, 30, GTC, false, 10, stp(2, CANCEL_BOTH)));      // 2
        engine.process(new StopOrder(BUY, 105, 106, 4, stp(1, CANCEL_NEWEST)));                  // 3

        EngineSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.restingOrders()).containsExactly(
                new RestingOrder(1, BUY, 99, 5, false, 0, 5, stp(1, CANCEL_OLDEST)),
                new RestingOrder(2, SELL, 101, 30, false, 10, 10, stp(2, CANCEL_BOTH)));
        assertThat(snapshot.parkedStops()).containsExactly(new ParkedStop(3, BUY, 105, 106, 4, stp(1, CANCEL_NEWEST)));

        MatchingEngine restored = MatchingEngine.restore(snapshot);
        assertThat(restored.snapshot()).isEqualTo(snapshot);
        // the restored engine prevents exactly as the original does
        Command probe = new LimitOrder(BUY, 101, 12, GTC, false, 0, stp(2, CANCEL_OLDEST));
        assertThat(restored.process(probe)).isEqualTo(engine.process(probe)).contains(
                new OrderCancelled(8, 2, 30, SELF_TRADE_PREVENTION));
    }

    @Test
    @Rulebook("SP-009")
    void snapshotsWithANegativeGroupAreRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> MatchingEngine.restore(new EngineSnapshot(
                INSTRUMENT, 3, 1, List.of(new RestingOrder(1, BUY, 100, 5, false, 0, 5, stp(-1, CANCEL_NEWEST))))));
        assertThatIllegalArgumentException().isThrownBy(() -> MatchingEngine.restore(new EngineSnapshot(
                INSTRUMENT, 3, 1, List.of(), 0, List.of(new ParkedStop(1, BUY, 100, 0, 5, stp(-1, CANCEL_NEWEST))))));
        MatchingEngine.restore(new EngineSnapshot(INSTRUMENT, 3, 1,
                List.of(new RestingOrder(1, BUY, 100, 5, false, 0, 5, stp(0, CANCEL_NEWEST)))));
    }
}
