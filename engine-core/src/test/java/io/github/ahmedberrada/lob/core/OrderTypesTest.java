package io.github.ahmedberrada.lob.core;

import static io.github.ahmedberrada.lob.core.CancelReason.FILL_OR_KILL;
import static io.github.ahmedberrada.lob.core.CancelReason.IMMEDIATE_OR_CANCEL;
import static io.github.ahmedberrada.lob.core.CancelReason.UNFILLED_MARKET_REMAINDER;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_PRICE;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_QUANTITY;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_TIME_IN_FORCE;
import static io.github.ahmedberrada.lob.core.RejectReason.NO_LIQUIDITY;
import static io.github.ahmedberrada.lob.core.RejectReason.POST_ONLY_WOULD_TRADE;
import static io.github.ahmedberrada.lob.core.RejectReason.PRICE_ABOVE_MAXIMUM;
import static io.github.ahmedberrada.lob.core.RejectReason.QUANTITY_ABOVE_MAXIMUM;
import static io.github.ahmedberrada.lob.core.RejectReason.UNKNOWN_ORDER;
import static io.github.ahmedberrada.lob.core.Side.BUY;
import static io.github.ahmedberrada.lob.core.Side.SELL;
import static io.github.ahmedberrada.lob.core.TimeInForce.FOK;
import static io.github.ahmedberrada.lob.core.TimeInForce.GTC;
import static io.github.ahmedberrada.lob.core.TimeInForce.IOC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Scenario tests for {@code docs/rulebook/order-types.md}: time in force, post-only and
 * cancel/replace. Test classes are flat on purpose (ADR-0003, test structure).
 */
class OrderTypesTest {

    private MatchingEngine engine;

    @BeforeEach
    void newEngine() {
        engine = new MatchingEngine(new Instrument("TEST", 1_000, 10_000));
    }

    private List<Event> limit(Side side, long price, long quantity) {
        return engine.process(new LimitOrder(side, price, quantity));
    }

    private List<Event> limit(Side side, long price, long quantity, TimeInForce tif, boolean postOnly) {
        return engine.process(new LimitOrder(side, price, quantity, tif, postOnly));
    }

    private List<Event> replace(long orderId, long price, long quantity) {
        return engine.process(new ReplaceOrder(orderId, price, quantity));
    }

    private List<RestingOrder> resting() {
        return engine.snapshot().restingOrders();
    }

    // ---- Time in force -----------------------------------------------------------------------------

    @Test
    @Rulebook("OT-001")
    void defaultsAreGoodTillCancelledForLimitAndImmediateOrCancelForMarket() {
        assertThat(new LimitOrder(BUY, 1, 1).timeInForce()).isEqualTo(GTC);
        assertThat(new LimitOrder(BUY, 1, 1).postOnly()).isFalse();
        assertThat(new MarketOrder(BUY, 1).timeInForce()).isEqualTo(IOC);
        assertThatNullPointerException().isThrownBy(() -> new LimitOrder(BUY, 1, 1, null, false));
        assertThatNullPointerException().isThrownBy(() -> new MarketOrder(BUY, 1, null));
    }

    @Test
    @Rulebook({"OT-001", "OT-002"})
    void iocLimitTradesWhatItCanAndCancelsTheRest() {
        limit(SELL, 100, 3);                                  // order 1
        limit(SELL, 102, 5);                                  // order 2, beyond the limit

        assertThat(limit(BUY, 101, 10, IOC, false)).containsExactly(
                new OrderAccepted(5, 3),
                new TradeExecuted(6, 3, 1, BUY, 100, 3, 7, 0),
                new OrderCancelled(7, 3, 7, IMMEDIATE_OR_CANCEL));
        assertThat(resting()).containsExactly(new RestingOrder(2, SELL, 102, 5));
    }

    @Test
    @Rulebook("OT-002")
    void iocThatDoesNotTradeIsCancelledWholeAndFullyFilledIocLeavesNoTrace() {
        assertThat(limit(BUY, 100, 4, IOC, false)).containsExactly(
                new OrderAccepted(1, 1),
                new OrderCancelled(2, 1, 4, IMMEDIATE_OR_CANCEL));
        limit(SELL, 100, 6);                                  // order 2
        assertThat(limit(BUY, 100, 6, IOC, false)).containsExactly(
                new OrderAccepted(5, 3),
                new TradeExecuted(6, 3, 2, BUY, 100, 6, 0, 0));
        assertThat(engine.book().orderCount()).isZero();
    }

    @Test
    @Rulebook("OT-001")
    void marketOrderMayNotBeGoodTillCancelled() {
        limit(SELL, 100, 3);
        assertThat(engine.process(new MarketOrder(BUY, 2, GTC))).containsExactly(
                new OrderRejected(3, 2, INVALID_TIME_IN_FORCE));
        // the quantity is checked first
        assertThat(engine.process(new MarketOrder(BUY, 0, GTC))).containsExactly(
                new OrderRejected(4, 3, INVALID_QUANTITY));
        assertThat(resting()).containsExactly(new RestingOrder(1, SELL, 100, 3));
    }

    @Test
    @Rulebook("OT-003")
    void fokThatCanBeFilledInFullTradesAcrossLevels() {
        limit(SELL, 100, 3);                                  // 1
        limit(SELL, 101, 4);                                  // 2
        limit(SELL, 102, 9);                                  // 3, beyond the limit

        assertThat(limit(BUY, 101, 7, FOK, false)).containsExactly(
                new OrderAccepted(7, 4),
                new TradeExecuted(8, 4, 1, BUY, 100, 3, 4, 0),
                new TradeExecuted(9, 4, 2, BUY, 101, 4, 0, 0));
        assertThat(resting()).containsExactly(new RestingOrder(3, SELL, 102, 9));
    }

    @Test
    @Rulebook("OT-003")
    void fokThatCannotBeFilledInFullIsCancelledWithoutTrading() {
        limit(SELL, 100, 3);                                  // 1
        limit(SELL, 101, 4);                                  // 2
        limit(SELL, 102, 9);                                  // 3, beyond the limit
        List<RestingOrder> before = resting();

        assertThat(limit(BUY, 101, 8, FOK, false)).containsExactly(
                new OrderAccepted(7, 4),
                new OrderCancelled(8, 4, 8, FILL_OR_KILL));
        assertThat(resting()).isEqualTo(before);
    }

    @Test
    @Rulebook("OT-003")
    void sellFokCountsBidsAtOrAboveItsLimit() {
        limit(BUY, 100, 3);                                   // 1
        limit(BUY, 99, 3);                                    // 2
        assertThat(limit(SELL, 100, 4, FOK, false)).containsExactly(
                new OrderAccepted(5, 3),
                new OrderCancelled(6, 3, 4, FILL_OR_KILL));
        assertThat(limit(SELL, 99, 6, FOK, false)).containsExactly(
                new OrderAccepted(7, 4),
                new TradeExecuted(8, 4, 1, SELL, 100, 3, 3, 0),
                new TradeExecuted(9, 4, 2, SELL, 99, 3, 0, 0));
    }

    @Test
    @Rulebook("OT-003")
    void fokOnAnEmptySideIsCancelledAndMarketFokOnAnEmptySideIsRejected() {
        assertThat(limit(BUY, 100, 1, FOK, false)).containsExactly(
                new OrderAccepted(1, 1),
                new OrderCancelled(2, 1, 1, FILL_OR_KILL));
        assertThat(engine.process(new MarketOrder(BUY, 1, FOK))).containsExactly(
                new OrderRejected(3, 2, NO_LIQUIDITY));
    }

    @Test
    @Rulebook({"OT-002", "OT-003"})
    void marketFokIsAllOrNothingAndMarketIocKeepsItsReason() {
        limit(SELL, 100, 3);                                  // 1
        limit(SELL, 105, 2);                                  // 2
        assertThat(engine.process(new MarketOrder(BUY, 6, FOK))).containsExactly(
                new OrderAccepted(5, 3),
                new OrderCancelled(6, 3, 6, FILL_OR_KILL));
        assertThat(engine.process(new MarketOrder(BUY, 6, IOC))).containsExactly(
                new OrderAccepted(7, 4),
                new TradeExecuted(8, 4, 1, BUY, 100, 3, 3, 0),
                new TradeExecuted(9, 4, 2, BUY, 105, 2, 1, 0),
                new OrderCancelled(10, 4, 1, UNFILLED_MARKET_REMAINDER));
        limit(SELL, 100, 3);                                  // 5
        assertThat(engine.process(new MarketOrder(BUY, 3, FOK))).containsExactly(
                new OrderAccepted(13, 6),
                new TradeExecuted(14, 6, 5, BUY, 100, 3, 0, 0));
    }

    // ---- Post-only ---------------------------------------------------------------------------------

    @Test
    @Rulebook("OT-004")
    void postOnlyOrderThatWouldTradeIsRejected() {
        limit(SELL, 100, 3);                                  // 1
        assertThat(limit(BUY, 100, 2, GTC, true)).containsExactly(new OrderRejected(3, 2, POST_ONLY_WOULD_TRADE));
        assertThat(limit(BUY, 105, 2, GTC, true)).containsExactly(new OrderRejected(4, 3, POST_ONLY_WOULD_TRADE));
        limit(BUY, 90, 3);                                    // 4
        assertThat(limit(SELL, 90, 2, GTC, true)).containsExactly(new OrderRejected(7, 5, POST_ONLY_WOULD_TRADE));
        assertThat(resting()).containsExactly(new RestingOrder(4, BUY, 90, 3), new RestingOrder(1, SELL, 100, 3));
    }

    @Test
    @Rulebook("OT-004")
    void postOnlyOrderThatDoesNotCrossRestsAndRemembersTheAttribute() {
        limit(SELL, 100, 3);                                  // 1
        assertThat(limit(BUY, 99, 2, GTC, true)).containsExactly(
                new OrderAccepted(3, 2),
                new OrderRested(4, 2, BUY, 99, 2));
        assertThat(limit(SELL, 101, 2, GTC, true)).containsExactly(
                new OrderAccepted(5, 3),
                new OrderRested(6, 3, SELL, 101, 2));
        assertThat(resting()).containsExactly(
                new RestingOrder(2, BUY, 99, 2, true),
                new RestingOrder(1, SELL, 100, 3, false),
                new RestingOrder(3, SELL, 101, 2, true));
        // on an empty opposite side nothing can trade
        engine = new MatchingEngine(engine.instrument());
        assertThat(limit(BUY, 99, 2, GTC, true)).containsExactly(
                new OrderAccepted(1, 1), new OrderRested(2, 1, BUY, 99, 2));
    }

    @ParameterizedTest
    @CsvSource({"IOC", "FOK"})
    @Rulebook("OT-004")
    void postOnlyRequiresGoodTillCancelled(TimeInForce tif) {
        assertThat(limit(BUY, 99, 2, tif, true)).containsExactly(new OrderRejected(1, 1, INVALID_TIME_IN_FORCE));
        // price and quantity are checked first
        assertThat(limit(BUY, 0, 2, tif, true)).containsExactly(new OrderRejected(2, 2, INVALID_PRICE));
    }

    // ---- Replace -----------------------------------------------------------------------------------

    @Test
    @Rulebook({"OT-005", "OT-006", "OT-008"})
    void reducingQuantityAtTheSamePriceKeepsPriority() {
        limit(BUY, 100, 5);                                   // 1
        limit(BUY, 100, 5);                                   // 2

        assertThat(replace(1, 100, 2)).containsExactly(new OrderReplaced(5, 1, BUY, 100, 2, true));
        assertThat(replace(2, 100, 5)).containsExactly(new OrderReplaced(6, 2, BUY, 100, 5, true));
        assertThat(resting()).containsExactly(new RestingOrder(1, BUY, 100, 2), new RestingOrder(2, BUY, 100, 5));
        assertThat(engine.book().depth(BUY)).containsExactly(new OrderBook.Level(100, 7, 2));
        assertThat(engine.process(new MarketOrder(SELL, 3))).containsExactly(
                new OrderAccepted(7, 3),
                new TradeExecuted(8, 3, 1, SELL, 100, 2, 1, 0),
                new TradeExecuted(9, 3, 2, SELL, 100, 1, 0, 4));
    }

    @Test
    @Rulebook({"OT-006", "OT-008"})
    void increasingQuantityLosesPriority() {
        limit(BUY, 100, 5);                                   // 1
        limit(BUY, 100, 5);                                   // 2

        assertThat(replace(1, 100, 6)).containsExactly(
                new OrderReplaced(5, 1, BUY, 100, 6, false),
                new OrderRested(6, 1, BUY, 100, 6));
        assertThat(resting()).containsExactly(new RestingOrder(2, BUY, 100, 5), new RestingOrder(1, BUY, 100, 6));
        assertThat(engine.book().depth(BUY)).containsExactly(new OrderBook.Level(100, 11, 2));
    }

    @Test
    @Rulebook({"OT-006", "OT-008"})
    void changingThePriceLosesPriorityEvenWhenComingBack() {
        limit(SELL, 105, 1);                                  // 1
        limit(SELL, 105, 1);                                  // 2
        limit(SELL, 106, 1);                                  // 3

        assertThat(replace(1, 106, 1)).containsExactly(
                new OrderReplaced(7, 1, SELL, 106, 1, false),
                new OrderRested(8, 1, SELL, 106, 1));
        assertThat(replace(1, 105, 1)).containsExactly(
                new OrderReplaced(9, 1, SELL, 105, 1, false),
                new OrderRested(10, 1, SELL, 105, 1));
        assertThat(resting()).containsExactly(
                new RestingOrder(2, SELL, 105, 1), new RestingOrder(1, SELL, 105, 1), new RestingOrder(3, SELL, 106, 1));
    }

    @Test
    @Rulebook({"OT-007", "OT-008"})
    void replaceThatCrossesTradesAsTheAggressorAndRestsItsRemainder() {
        limit(SELL, 101, 2);                                  // 1
        limit(SELL, 102, 2);                                  // 2
        limit(BUY, 99, 3);                                    // 3

        assertThat(replace(3, 101, 5)).containsExactly(
                new OrderReplaced(7, 3, BUY, 101, 5, false),
                new TradeExecuted(8, 3, 1, BUY, 101, 2, 3, 0),
                new OrderRested(9, 3, BUY, 101, 3));
        assertThat(replace(3, 102, 2)).containsExactly(
                new OrderReplaced(10, 3, BUY, 102, 2, false),
                new TradeExecuted(11, 3, 2, BUY, 102, 2, 0, 0));
        assertThat(engine.book().orderCount()).isZero();
    }

    @Test
    @Rulebook("OT-005")
    void replaceOfAnOrderThatIsNotRestingIsRejected() {
        assertThat(replace(1, 100, 1)).containsExactly(new ReplaceRejected(1, 1, UNKNOWN_ORDER));
        // a replace does not consume an order ID
        limit(SELL, 100, 1);                                  // 1
        limit(BUY, 100, 1);                                   // 2 fills 1
        assertThat(replace(1, 100, 1)).containsExactly(new ReplaceRejected(6, 1, UNKNOWN_ORDER));
        // the unknown order wins over an invalid quantity
        assertThat(replace(9, 100, 0)).containsExactly(new ReplaceRejected(7, 9, UNKNOWN_ORDER));
    }

    @ParameterizedTest
    @CsvSource({
            "100, 0, INVALID_QUANTITY",
            "100, -1, INVALID_QUANTITY",
            "100, 1001, QUANTITY_ABOVE_MAXIMUM",
            "0, 5, INVALID_PRICE",
            "10001, 5, PRICE_ABOVE_MAXIMUM",
            "0, 0, INVALID_QUANTITY"})
    @Rulebook("OT-005")
    void invalidReplaceIsRejectedAndChangesNothing(long price, long quantity, RejectReason reason) {
        limit(BUY, 100, 5);                                   // 1
        assertThat(replace(1, price, quantity)).containsExactly(new ReplaceRejected(3, 1, reason));
        assertThat(resting()).containsExactly(new RestingOrder(1, BUY, 100, 5));
        assertThat(List.of(INVALID_QUANTITY, QUANTITY_ABOVE_MAXIMUM, INVALID_PRICE, PRICE_ABOVE_MAXIMUM))
                .contains(reason);
    }

    @Test
    @Rulebook({"OT-004", "OT-005"})
    void postOnlyOrderCannotBeReplacedIntoATrade() {
        limit(SELL, 101, 2);                                  // 1
        limit(BUY, 99, 3, GTC, true);                         // 2

        assertThat(replace(2, 101, 3)).containsExactly(new ReplaceRejected(5, 2, POST_ONLY_WOULD_TRADE));
        assertThat(replace(2, 100, 4)).containsExactly(
                new OrderReplaced(6, 2, BUY, 100, 4, false),
                new OrderRested(7, 2, BUY, 100, 4));
        assertThat(resting()).containsExactly(new RestingOrder(2, BUY, 100, 4, true), new RestingOrder(1, SELL, 101, 2));
        // an ordinary order may be replaced into a trade
        limit(SELL, 105, 1);                                  // 3
        assertThat(replace(3, 100, 1)).containsExactly(
                new OrderReplaced(10, 3, SELL, 100, 1, false),
                new TradeExecuted(11, 3, 2, SELL, 100, 1, 0, 3));
    }

    @Test
    @Rulebook({"OT-006", "RS-010"})
    void replacedOrdersSurviveASnapshot() {
        limit(BUY, 100, 5, GTC, true);                        // 1
        limit(BUY, 100, 5);                                   // 2
        replace(1, 100, 9);

        MatchingEngine restored = MatchingEngine.restore(engine.snapshot());
        assertThat(restored.snapshot()).isEqualTo(engine.snapshot());
        assertThat(restored.process(new ReplaceOrder(1, 101, 1))).isEqualTo(replace(1, 101, 1));
    }
}
