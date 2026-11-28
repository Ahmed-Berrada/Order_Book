package io.github.ahmedberrada.lob.core;

import static io.github.ahmedberrada.lob.core.CancelReason.CLIENT_REQUEST;
import static io.github.ahmedberrada.lob.core.CancelReason.KILL_SWITCH;
import static io.github.ahmedberrada.lob.core.CancelReason.MASS_CANCEL;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_MEMBER;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_MEMBER_LIMITS;
import static io.github.ahmedberrada.lob.core.RejectReason.MEMBER_SUSPENDED;
import static io.github.ahmedberrada.lob.core.RejectReason.RISK_OPEN_ORDERS;
import static io.github.ahmedberrada.lob.core.RejectReason.RISK_ORDER_NOTIONAL;
import static io.github.ahmedberrada.lob.core.RejectReason.RISK_ORDER_QUANTITY;
import static io.github.ahmedberrada.lob.core.RejectReason.RISK_POSITION;
import static io.github.ahmedberrada.lob.core.RejectReason.RISK_PRICE_COLLAR;
import static io.github.ahmedberrada.lob.core.RejectReason.UNKNOWN_ORDER;
import static io.github.ahmedberrada.lob.core.Side.BUY;
import static io.github.ahmedberrada.lob.core.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Scenario tests for {@code docs/rulebook/members-and-risk.md}. */
class MemberRiskTest {

    private static final Instrument INSTRUMENT = new Instrument("RSK", 1_000, 100_000);

    private MatchingEngine engine;

    @BeforeEach
    void newEngine() {
        engine = new MatchingEngine(INSTRUMENT);
    }

    private List<Event> limit(long member, Side side, long price, long quantity) {
        return engine.process(new LimitOrder(side, price, quantity), member);
    }

    private void limits(long member, MemberLimits limits) {
        engine.process(new SetMemberLimits(member, limits), 0);
    }

    private static RejectReason rejection(List<Event> events) {
        return ((OrderRejected) events.getFirst()).reason();
    }

    private static boolean accepted(List<Event> events) {
        return events.getFirst() instanceof OrderAccepted;
    }

    @Test
    @Rulebook({"MB-001", "RK-001"})
    void limitsAreSetByACommandAndInvalidOnesAreRefused() {
        MemberLimits some = new MemberLimits(10, 0, 0, 0, 0);
        assertThat(engine.process(new SetMemberLimits(1, some))).containsExactly(
                new MemberLimitsChanged(1, 1, some));
        assertThat(engine.process(new SetMemberLimits(0, some))).containsExactly(
                new MemberCommandRejected(2, 0, INVALID_MEMBER_LIMITS));
        assertThat(engine.process(new SetMemberLimits(2, new MemberLimits(0, -1, 0, 0, 0)))).containsExactly(
                new MemberCommandRejected(3, 2, INVALID_MEMBER_LIMITS));
        assertThat(engine.snapshot().members()).containsExactly(new MemberState(1, some, false, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> engine.process(new SetPhase(TradingPhase.CALL), -1));
        assertThatNullPointerException().isThrownBy(() -> new SetMemberLimits(1, null));
        assertThatNullPointerException().isThrownBy(() -> new MemberState(1, null, false, 0));
    }

    @Test
    @Rulebook("RK-002")
    void quantityAndNotionalLimitsApplyToTheMemberOnly() {
        limits(1, new MemberLimits(10, 1_000, 0, 0, 0));

        assertThat(limit(1, BUY, 50, 11)).containsExactly(new OrderRejected(2, 1, RISK_ORDER_QUANTITY));
        assertThat(limit(1, BUY, 101, 10)).containsExactly(new OrderRejected(3, 2, RISK_ORDER_NOTIONAL));
        assertThat(accepted(limit(1, BUY, 100, 10))).isTrue();                // 1000: on the limit
        assertThat(accepted(limit(2, BUY, 101, 500))).isTrue();               // another member
        assertThat(accepted(limit(0, BUY, 101, 500))).isTrue();               // the operator
        assertThat(engine.process(new StopOrder(BUY, 200, 0, 6), 1)).containsExactly(
                new OrderRejected(10, 6, RISK_ORDER_NOTIONAL));               // the stop price counts: 1200
        assertThat(rejection(engine.process(new StopOrder(BUY, 200, 150, 7), 1))).isEqualTo(RISK_ORDER_NOTIONAL);
        limit(2, SELL, 5_000, 1);                                         // id 8: liquidity, so NO_LIQUIDITY is not met
        assertThat(rejection(engine.process(new MarketOrder(BUY, 11), 1))).isEqualTo(RISK_ORDER_QUANTITY);
    }

    @Test
    @Rulebook("RK-002")
    void aMarketOrderIsValuedAtTheLastTradePrice() {
        limits(1, new MemberLimits(0, 1_000, 0, 0, 0));
        limit(2, SELL, 100, 20);
        assertThat(accepted(engine.process(new MarketOrder(BUY, 10), 1))).isTrue();     // no last trade: unchecked
        // the trade at 100 is now the last trade price: 11 lots are 1100
        assertThat(rejection(engine.process(new MarketOrder(BUY, 11), 1))).isEqualTo(RISK_ORDER_NOTIONAL);
    }

    @Test
    @Rulebook("RK-002")
    void thePriceCollarIsMeasuredFromTheLastTradeAndSkippedBeforeTheFirst() {
        limits(1, new MemberLimits(0, 0, 500, 0, 0));
        assertThat(accepted(limit(1, SELL, 5_000, 1))).isTrue();                // no trade yet, so no collar
        limit(2, SELL, 100, 1);
        limit(2, BUY, 100, 1);                                                  // last trade 100, 5% = 5 ticks

        assertThat(rejection(limit(1, SELL, 106, 1))).isEqualTo(RISK_PRICE_COLLAR);
        assertThat(rejection(limit(1, BUY, 94, 1))).isEqualTo(RISK_PRICE_COLLAR);
        assertThat(accepted(limit(1, SELL, 105, 1))).isTrue();
        assertThat(accepted(limit(1, BUY, 95, 1))).isTrue();
        assertThat(rejection(engine.process(new StopOrder(BUY, 101, 107, 1), 1))).isEqualTo(RISK_PRICE_COLLAR);
        assertThat(accepted(engine.process(new StopOrder(BUY, 101, 0, 1), 1))).isTrue();   // a stop-market has none
    }

    @Test
    @Rulebook("RK-002")
    void openOrdersCountRestingOrdersAndStopsButNotReplaces() {
        limits(1, new MemberLimits(0, 0, 0, 2, 0));
        limit(1, BUY, 90, 1);                                                   // id 1
        engine.process(new StopOrder(BUY, 120, 1), 1);                          // id 2
        assertThat(rejection(limit(1, BUY, 89, 1))).isEqualTo(RISK_OPEN_ORDERS);
        assertThat(engine.process(new ReplaceOrder(1, 91, 2), 1)).first().isInstanceOf(OrderReplaced.class);

        engine.process(new CancelOrder(2), 1);
        assertThat(accepted(limit(1, BUY, 89, 1))).isTrue();
        limit(2, SELL, 91, 2);                                                  // fills id 1: one open order less
        assertThat(accepted(limit(1, BUY, 88, 1))).isTrue();
    }

    @Test
    @Rulebook({"RK-002", "RK-003"})
    void thePositionLimitCountsTheNetPositionAndWhatIsOpen() {
        limits(1, new MemberLimits(0, 0, 0, 0, 10));
        assertThat(accepted(limit(1, BUY, 90, 6))).isTrue();
        assertThat(rejection(limit(1, BUY, 89, 5))).isEqualTo(RISK_POSITION);   // 6 open + 5 would be long 11
        assertThat(accepted(limit(1, SELL, 120, 10))).isTrue();                 // short side is separate
        assertThat(rejection(limit(1, SELL, 121, 1))).isEqualTo(RISK_POSITION);

        limit(2, SELL, 90, 6);                                                  // member 1 buys 6
        assertThat(engine.netPosition(1)).isEqualTo(6);
        assertThat(engine.netPosition(2)).isEqualTo(-6);
        assertThat(rejection(limit(1, BUY, 89, 5))).isEqualTo(RISK_POSITION);   // long 6 + 5
        assertThat(accepted(limit(1, BUY, 89, 4))).isTrue();
        assertThat(engine.netPosition(0)).isZero();
        assertThat(engine.snapshot().members()).extracting(MemberState::netPosition).containsExactly(6L, -6L);
    }

    @Test
    @Rulebook("RK-002")
    void aReplaceCountsItsNewQuantityInsteadOfTheOldOne() {
        limits(1, new MemberLimits(0, 0, 0, 0, 10));
        limit(1, BUY, 90, 8);

        assertThat(engine.process(new ReplaceOrder(1, 90, 10), 1)).first().isInstanceOf(OrderReplaced.class);
        assertThat(engine.process(new ReplaceOrder(1, 90, 11), 1)).containsExactly(
                new ReplaceRejected(6, 1, RISK_POSITION));
        limits(1, new MemberLimits(5, 0, 0, 0, 0));
        assertThat(engine.process(new ReplaceOrder(1, 90, 6), 1)).containsExactly(
                new ReplaceRejected(8, 1, RISK_ORDER_QUANTITY));
    }

    @Test
    @Rulebook("RK-006")
    void aMassCancelCancelsTheMembersOpenOrdersOnASideOrBothInOrderId() {
        limit(1, BUY, 90, 4);                                                   // 1
        limit(2, BUY, 91, 5);                                                   // 2
        engine.process(new LimitOrder(BUY, 80, 9, TimeInForce.GTC, false, 3), 1);   // 3, an iceberg
        limit(1, SELL, 120, 6);                                                 // 4
        engine.process(new StopOrder(SELL, 70, 2), 1);                          // 5, a parked sell stop
        engine.process(new StopOrder(BUY, 130, 2), 1);                          // 6, a parked buy stop

        assertThat(engine.process(new MassCancel(BUY), 1)).containsExactly(
                new MassCancelled(13, 1, BUY, 3),
                new OrderCancelled(14, 1, 4, MASS_CANCEL),
                new OrderCancelled(15, 3, 9, MASS_CANCEL),
                new OrderCancelled(16, 6, 2, MASS_CANCEL));
        assertThat(engine.process(MassCancel.all(), 1)).containsExactly(
                new MassCancelled(17, 1, null, 2),
                new OrderCancelled(18, 4, 6, MASS_CANCEL),
                new OrderCancelled(19, 5, 2, MASS_CANCEL));
        assertThat(engine.process(MassCancel.all(), 1)).as("nothing left: accepted all the same")
                .containsExactly(new MassCancelled(20, 1, null, 0));
        assertThat(engine.book().depth(BUY)).extracting(OrderBook.Level::priceTicks).containsExactly(91L);

        assertThat(limit(1, BUY, 90, 1)).as("not suspended").contains(new OrderAccepted(21, 7));
    }

    @Test
    @Rulebook("RK-006")
    void aSuspendedMemberMayMassCancelAndTheOperatorCancelsEveryone() {
        limit(1, BUY, 90, 4);                                                   // 1
        limit(2, SELL, 110, 5);                                                 // 2
        limit(3, BUY, 80, 6);                                                   // 3
        engine.process(new SuspendMember(1));                                   // cancels 1
        assertThat(engine.process(MassCancel.all(), 1)).containsExactly(new MassCancelled(9, 1, null, 0));

        assertThat(engine.process(new MassCancel(SELL))).containsExactly(
                new MassCancelled(10, 0, SELL, 1),
                new OrderCancelled(11, 2, 5, MASS_CANCEL));
        assertThat(engine.process(MassCancel.all(), 0)).containsExactly(
                new MassCancelled(12, 0, null, 1),
                new OrderCancelled(13, 3, 6, MASS_CANCEL));
    }

    @Test
    @Rulebook("RK-006")
    void aMassCancelIsAcceptedInEveryPhase() {
        limit(1, BUY, 90, 4);
        engine.process(new SetPhase(TradingPhase.CALL));
        assertThat(engine.process(MassCancel.all(), 1)).contains(new OrderCancelled(5, 1, 4, MASS_CANCEL));
        engine.process(new SetPhase(TradingPhase.CLOSED));
        assertThat(engine.process(MassCancel.all(), 1)).hasSize(1);
    }

    @Test
    @Rulebook({"RK-004", "RK-005"})
    void theKillSwitchCancelsEverythingInOrderIdAndBlocksNewOrdersUntilResumed() {
        limit(1, BUY, 90, 4);                                                   // 1
        limit(2, BUY, 91, 5);                                                   // 2
        engine.process(new LimitOrder(BUY, 80, 9, TimeInForce.GTC, false, 3), 1);   // 3, an iceberg
        engine.process(new StopOrder(SELL, 70, 2), 1);                          // 4
        engine.process(new StopOrder(SELL, 60, 2), 2);                          // 5

        assertThat(engine.process(new SuspendMember(1))).containsExactly(
                new MemberSuspended(11, 1, 3),
                new OrderCancelled(12, 1, 4, KILL_SWITCH),
                new OrderCancelled(13, 3, 9, KILL_SWITCH),
                new OrderCancelled(14, 4, 2, KILL_SWITCH));
        assertThat(engine.book().restingQuantity(2)).isEqualTo(5);
        assertThat(engine.snapshot().parkedStops()).extracting(ParkedStop::orderId).containsExactly(5L);

        assertThat(limit(1, BUY, 90, 1)).containsExactly(new OrderRejected(15, 6, MEMBER_SUSPENDED));
        assertThat(engine.process(new CancelOrder(1), 1)).containsExactly(new CancelRejected(16, 1, UNKNOWN_ORDER));
        assertThat(accepted(limit(2, BUY, 90, 1))).isTrue();

        assertThat(engine.process(new ResumeMember(1))).containsExactly(new MemberResumed(19, 1));
        assertThat(accepted(limit(1, BUY, 90, 1))).isTrue();
    }

    @Test
    @Rulebook({"RK-004", "RK-005"})
    void aSuspendedMembersReplaceIsRefusedAndBadSuspensionsAreRejected() {
        limit(1, BUY, 90, 4);
        engine.process(new SuspendMember(1));
        limit(0, BUY, 85, 4);                                                   // the operator is not blocked
        assertThat(engine.process(new ReplaceOrder(3, 86, 4), 1)).containsExactly(
                new ReplaceRejected(7, 3, UNKNOWN_ORDER));                       // not its order

        assertThat(engine.process(new SuspendMember(1))).containsExactly(
                new MemberCommandRejected(8, 1, INVALID_MEMBER));
        assertThat(engine.process(new SuspendMember(0))).containsExactly(
                new MemberCommandRejected(9, 0, INVALID_MEMBER));
        assertThat(engine.process(new ResumeMember(2))).containsExactly(
                new MemberCommandRejected(10, 2, INVALID_MEMBER));
        assertThat(engine.process(new ResumeMember(1))).containsExactly(new MemberResumed(11, 1));
        assertThat(engine.process(new ResumeMember(1))).containsExactly(
                new MemberCommandRejected(12, 1, INVALID_MEMBER));
        assertThat(engine.snapshot().members()).isEmpty();                      // back to the defaults
    }

    @Test
    @Rulebook("MB-003")
    void aMemberCannotCancelOrReplaceAnotherMembersOrdersButTheOperatorCan() {
        limit(1, BUY, 90, 4);                                                   // 1
        engine.process(new StopOrder(SELL, 70, 2), 1);                          // 2

        assertThat(engine.process(new CancelOrder(1), 2)).containsExactly(new CancelRejected(5, 1, UNKNOWN_ORDER));
        assertThat(engine.process(new CancelOrder(2), 2)).containsExactly(new CancelRejected(6, 2, UNKNOWN_ORDER));
        assertThat(engine.process(new ReplaceOrder(1, 91, 4), 2)).containsExactly(
                new ReplaceRejected(7, 1, UNKNOWN_ORDER));
        assertThat(engine.book().restingQuantity(1)).isEqualTo(4);

        assertThat(engine.process(new ReplaceOrder(1, 91, 4), 0)).first().isInstanceOf(OrderReplaced.class);
        assertThat(engine.process(new CancelOrder(2), 0)).containsExactly(new OrderCancelled(10, 2, 2, CLIENT_REQUEST));
        assertThat(engine.process(new CancelOrder(1), 1)).containsExactly(new OrderCancelled(11, 1, 4, CLIENT_REQUEST));
    }

    @Test
    @Rulebook({"MB-002", "RK-003"})
    void tradesNameBothMembersAndAuctionsMovePositionsToo() {
        limit(1, SELL, 100, 5);
        assertThat(limit(2, BUY, 100, 5)).containsExactly(
                new OrderAccepted(3, 2), new TradeExecuted(4, 2, 1, BUY, 100, 5, 0, 0, 2, 1));

        engine.process(new SetPhase(TradingPhase.CALL));
        limit(1, BUY, 100, 3);
        limit(3, SELL, 100, 3);
        List<Event> auction = engine.process(new SetPhase(TradingPhase.CONTINUOUS));

        assertThat(auction).anySatisfy(e -> assertThat(e).isEqualTo(
                new TradeExecuted(auction.getFirst().sequence() + 1, 4, 3, SELL, 100, 3, 0, 0, 3, 1)));
        assertThat(engine.netPosition(1)).isEqualTo(-5 + 3);
        assertThat(engine.netPosition(2)).isEqualTo(5);
        assertThat(engine.netPosition(3)).isEqualTo(-3);
    }

    @Test
    @Rulebook({"MB-004", "RK-002"})
    void aRestoredEngineKeepsLimitsPositionsAndEnforcesThemOverItsOrders() {
        limits(1, new MemberLimits(0, 0, 0, 2, 10));
        limit(1, BUY, 90, 4);
        engine.process(new StopOrder(BUY, 120, 1), 1);
        limit(2, SELL, 90, 1);
        engine.process(new SuspendMember(3));                                   // a member that never traded
        limits(4, new MemberLimits(1, 0, 0, 0, 0));
        engine.process(new SuspendMember(4));
        EngineSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.members()).extracting(MemberState::memberId).containsExactly(1L, 2L, 3L, 4L);

        MatchingEngine restored = MatchingEngine.restore(snapshot);

        assertThat(restored.snapshot()).isEqualTo(snapshot);
        assertThat(restored.process(new LimitOrder(BUY, 89, 1), 1)).isEqualTo(
                engine.process(new LimitOrder(BUY, 89, 1), 1));
        assertThat(rejection(restored.process(new LimitOrder(BUY, 88, 1), 1))).isEqualTo(RISK_OPEN_ORDERS);
        assertThat(rejection(restored.process(new LimitOrder(BUY, 88, 1), 4))).isEqualTo(MEMBER_SUSPENDED);
        assertThat(restored.netPosition(1)).isEqualTo(1);
    }

    @Test
    @Rulebook("MB-004")
    void aSnapshotWithInvalidMemberStatesIsRefused() {
        MemberLimits none = MemberLimits.NONE;
        for (List<MemberState> invalid : List.of(
                List.of(new MemberState(-1, none, false, 0)),
                List.of(new MemberState(2, none, true, 0), new MemberState(1, none, false, 5)),
                List.of(new MemberState(1, none, false, 1), new MemberState(1, none, false, 2)),
                List.of(new MemberState(1, new MemberLimits(-1, 0, 0, 0, 0), false, 0)),
                List.of(new MemberState(0, none, true, 0)))) {
            assertThatIllegalArgumentException().isThrownBy(() -> MatchingEngine.restore(new EngineSnapshot(
                    INSTRUMENT, 1, 1, List.of(), 0, List.of(), TradingPhase.CONTINUOUS, 0, invalid)));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> MatchingEngine.restore(new EngineSnapshot(
                INSTRUMENT, 2, 1, List.of(new RestingOrder(1, BUY, 5, 1, false, 0, 1, SelfTradeInstruction.NONE,
                        false, -1)), 0, List.of(), TradingPhase.CONTINUOUS, 0, List.of())));
        assertThatIllegalArgumentException().isThrownBy(() -> MatchingEngine.restore(new EngineSnapshot(
                INSTRUMENT, 2, 1, List.of(), 0,
                List.of(new ParkedStop(1, BUY, 5, 0, 1, SelfTradeInstruction.NONE, -1)), TradingPhase.CONTINUOUS, 0,
                List.of())));
    }
}
