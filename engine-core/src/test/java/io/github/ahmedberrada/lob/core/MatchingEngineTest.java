package io.github.ahmedberrada.lob.core;

import static io.github.ahmedberrada.lob.core.CancelReason.CLIENT_REQUEST;
import static io.github.ahmedberrada.lob.core.CancelReason.UNFILLED_MARKET_REMAINDER;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_PRICE;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_QUANTITY;
import static io.github.ahmedberrada.lob.core.RejectReason.NO_LIQUIDITY;
import static io.github.ahmedberrada.lob.core.RejectReason.PRICE_ABOVE_MAXIMUM;
import static io.github.ahmedberrada.lob.core.RejectReason.QUANTITY_ABOVE_MAXIMUM;
import static io.github.ahmedberrada.lob.core.RejectReason.UNKNOWN_ORDER;
import static io.github.ahmedberrada.lob.core.Side.BUY;
import static io.github.ahmedberrada.lob.core.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Scenario tests: "given these commands, expect exactly these events". Each scenario cites the
 * rulebook rules it verifies. Order IDs and sequence numbers are spelled out on purpose.
 */
class MatchingEngineTest {

    private static final long MAX_QUANTITY = 1_000;
    private static final long MAX_PRICE = 10_000;

    private MatchingEngine engine;

    @BeforeEach
    void newEngine() {
        engine = new MatchingEngine(new Instrument("TEST", MAX_QUANTITY, MAX_PRICE));
    }

    private List<Event> limit(Side side, long price, long quantity) {
        return engine.process(new LimitOrder(side, price, quantity));
    }

    private List<Event> market(Side side, long quantity) {
        return engine.process(new MarketOrder(side, quantity));
    }

    private List<Event> cancel(long orderId) {
        return engine.process(new CancelOrder(orderId));
    }

    // ---- Resting and sequencing --------------------------------------------------------------------

    @Test
    @Rulebook({"CT-005", "CT-010", "CT-011", "CT-012"})
    void limitOrderOnAnEmptyBookIsAcceptedThenRests() {
        assertThat(limit(BUY, 100, 10)).containsExactly(
                new OrderAccepted(1, 1),
                new OrderRested(2, 1, BUY, 100, 10));
        assertThat(limit(SELL, 105, 4)).containsExactly(
                new OrderAccepted(3, 2),
                new OrderRested(4, 2, SELL, 105, 4));

        assertThat(engine.book().bestBid()).hasValue(100);
        assertThat(engine.book().bestAsk()).hasValue(105);
        assertThat(engine.book().orderCount()).isEqualTo(2);
    }

    @Test
    @Rulebook("CT-004")
    void ordersThatDoNotReachTheOppositeSideBothRest() {
        limit(SELL, 101, 5);
        assertThat(limit(BUY, 100, 5)).containsExactly(
                new OrderAccepted(3, 2),
                new OrderRested(4, 2, BUY, 100, 5));

        assertThat(engine.book().bestBid()).hasValue(100);
        assertThat(engine.book().bestAsk()).hasValue(101);
    }

    @Test
    @Rulebook({"CT-005", "CT-012"})
    void partiallyFilledLimitOrderRestsItsRemainder() {
        limit(SELL, 100, 3);
        assertThat(limit(BUY, 100, 5)).containsExactly(
                new OrderAccepted(3, 2),
                new TradeExecuted(4, 2, 1, BUY, 100, 3, 2, 0),
                new OrderRested(5, 2, BUY, 100, 2));

        assertThat(engine.book().bestAsk()).isEmpty();
        assertThat(engine.book().restingQuantity(2)).isEqualTo(2);
    }

    @Test
    void depthAggregatesEachPriceLevelBestFirst() {
        limit(BUY, 99, 3);
        limit(BUY, 100, 5);
        limit(BUY, 100, 2);
        limit(SELL, 102, 7);
        limit(SELL, 101, 1);

        assertThat(engine.book().depth(BUY)).containsExactly(
                new OrderBook.Level(100, 7, 2),
                new OrderBook.Level(99, 3, 1));
        assertThat(engine.book().depth(SELL)).containsExactly(
                new OrderBook.Level(101, 1, 1),
                new OrderBook.Level(102, 7, 1));
    }

    // ---- Matching ----------------------------------------------------------------------------------

    @Test
    @Rulebook({"CT-001", "CT-003"})
    void buyerSweepsTheBestAsksFirstAtTheirOwnPrices() {
        limit(SELL, 102, 5);                     // order 1
        limit(SELL, 101, 5);                     // order 2

        assertThat(limit(BUY, 105, 7)).containsExactly(
                new OrderAccepted(5, 3),
                new TradeExecuted(6, 3, 2, BUY, 101, 5, 2, 0),
                new TradeExecuted(7, 3, 1, BUY, 102, 2, 0, 3));

        assertThat(engine.book().depth(SELL)).containsExactly(new OrderBook.Level(102, 3, 1));
        assertThat(engine.book().bestBid()).isEmpty();
    }

    @Test
    @Rulebook({"CT-001", "CT-003", "CT-004"})
    void sellerHitsTheBestBidsFirstAndStopsAtItsLimit() {
        limit(BUY, 98, 5);                       // order 1
        limit(BUY, 100, 2);                      // order 2
        limit(BUY, 99, 2);                       // order 3

        assertThat(limit(SELL, 99, 10)).containsExactly(
                new OrderAccepted(7, 4),
                new TradeExecuted(8, 4, 2, SELL, 100, 2, 8, 0),
                new TradeExecuted(9, 4, 3, SELL, 99, 2, 6, 0),
                new OrderRested(10, 4, SELL, 99, 6));

        assertThat(engine.book().bestBid()).hasValue(98);
        assertThat(engine.book().bestAsk()).hasValue(99);
    }

    @Test
    @Rulebook("CT-002")
    void ordersAtTheSamePriceFillInArrivalOrder() {
        limit(SELL, 100, 5);                     // order 1
        limit(SELL, 100, 5);                     // order 2

        assertThat(limit(BUY, 100, 7)).containsExactly(
                new OrderAccepted(5, 3),
                new TradeExecuted(6, 3, 1, BUY, 100, 5, 2, 0),
                new TradeExecuted(7, 3, 2, BUY, 100, 2, 0, 3));
    }

    @Test
    @Rulebook({"CT-002", "CT-005"})
    void partiallyFilledOrderKeepsItsPlaceAheadOfLaterOrders() {
        limit(SELL, 100, 5);                     // order 1
        limit(BUY, 100, 2);                      // order 2: order 1 keeps 3
        limit(SELL, 100, 4);                     // order 3, behind order 1

        assertThat(limit(BUY, 100, 3)).containsExactly(
                new OrderAccepted(7, 4),
                new TradeExecuted(8, 4, 1, BUY, 100, 3, 0, 0));
        assertThat(engine.book().depth(SELL)).containsExactly(new OrderBook.Level(100, 4, 1));
    }

    // ---- Market orders -----------------------------------------------------------------------------

    @Test
    @Rulebook({"CT-006", "CT-012"})
    void unfilledRemainderIsCancelledNotRested() {
        limit(SELL, 100, 3);                     // order 1
        limit(SELL, 101, 3);                     // order 2

        assertThat(market(BUY, 10)).containsExactly(
                new OrderAccepted(5, 3),
                new TradeExecuted(6, 3, 1, BUY, 100, 3, 7, 0),
                new TradeExecuted(7, 3, 2, BUY, 101, 3, 4, 0),
                new OrderCancelled(8, 3, 4, UNFILLED_MARKET_REMAINDER));

        assertThat(engine.book().orderCount()).isZero();
        assertThat(engine.book().bestBid()).isEmpty();
    }

    @Test
    @Rulebook("CT-006")
    void fullyFilledMarketOrderLeavesNoTrace() {
        limit(BUY, 100, 10);                     // order 1

        assertThat(market(SELL, 4)).containsExactly(
                new OrderAccepted(3, 2),
                new TradeExecuted(4, 2, 1, SELL, 100, 4, 0, 6));
        assertThat(engine.book().restingQuantity(1)).isEqualTo(6);
    }

    @Test
    @Rulebook("CT-007")
    void marketOrderAgainstAnEmptySideIsRejected() {
        limit(BUY, 100, 10);                     // only bids

        assertThat(market(BUY, 5)).containsExactly(new OrderRejected(3, 2, NO_LIQUIDITY));
        assertThat(engine.book().depth(BUY)).containsExactly(new OrderBook.Level(100, 10, 1));
    }

    @Test
    @Rulebook({"CT-007", "CT-009"})
    void invalidQuantityTakesPrecedenceOverNoLiquidity() {
        assertThat(market(SELL, 0)).containsExactly(new OrderRejected(1, 1, INVALID_QUANTITY));
        assertThat(market(SELL, MAX_QUANTITY + 1))
                .containsExactly(new OrderRejected(2, 2, QUANTITY_ABOVE_MAXIMUM));
    }

    // ---- Cancels -----------------------------------------------------------------------------------

    @Test
    @Rulebook("CT-008")
    void restingOrderCanBeCancelled() {
        limit(BUY, 100, 10);

        assertThat(cancel(1)).containsExactly(new OrderCancelled(3, 1, 10, CLIENT_REQUEST));
        assertThat(engine.book().orderCount()).isZero();
        assertThat(engine.book().bestBid()).isEmpty();
    }

    @Test
    @Rulebook("CT-008")
    void cancellingAPartiallyFilledOrderCancelsOnlyWhatIsLeft() {
        limit(BUY, 100, 10);                     // order 1
        limit(SELL, 100, 4);                     // order 2 fills 4

        assertThat(cancel(1)).containsExactly(new OrderCancelled(5, 1, 6, CLIENT_REQUEST));
    }

    @Test
    @Rulebook("CT-008")
    void cancellingFromTheMiddleOfAQueueKeepsTheOthersInOrder() {
        limit(SELL, 100, 1);                     // order 1
        limit(SELL, 100, 2);                     // order 2
        limit(SELL, 100, 3);                     // order 3
        cancel(2);

        assertThat(engine.book().depth(SELL)).containsExactly(new OrderBook.Level(100, 4, 2));
        assertThat(limit(BUY, 100, 4)).containsExactly(
                new OrderAccepted(8, 4),
                new TradeExecuted(9, 4, 1, BUY, 100, 1, 3, 0),
                new TradeExecuted(10, 4, 3, BUY, 100, 3, 0, 0));
    }

    @Test
    @Rulebook("CT-008")
    void cancelOfAnOrderThatIsNotRestingIsRejected() {
        limit(SELL, 100, 5);                     // order 1
        limit(BUY, 100, 5);                      // order 2 fills order 1 completely
        limit(BUY, 99, 5);                       // order 3
        cancel(3);

        assertThat(cancel(42)).containsExactly(new CancelRejected(8, 42, UNKNOWN_ORDER));
        assertThat(cancel(1)).containsExactly(new CancelRejected(9, 1, UNKNOWN_ORDER));
        assertThat(cancel(3)).containsExactly(new CancelRejected(10, 3, UNKNOWN_ORDER));
        assertThat(engine.book().orderCount()).isZero();
    }

    // ---- Validation --------------------------------------------------------------------------------

    @ParameterizedTest(name = "price {0}, quantity {1} -> {2}")
    @CsvSource({
        "100,     0, INVALID_QUANTITY",
        "100,    -5, INVALID_QUANTITY",
        "100,  1001, QUANTITY_ABOVE_MAXIMUM",
        "  0,    10, INVALID_PRICE",
        " -1,    10, INVALID_PRICE",
        "10001,  10, PRICE_ABOVE_MAXIMUM",
        "  0,     0, INVALID_QUANTITY",
    })
    @Rulebook("CT-009")
    void invalidLimitOrderIsRejectedAndNeverReachesTheBook(long price, long quantity, RejectReason reason) {
        limit(SELL, 200, 1);                     // order 1, so the book is not empty

        assertThat(limit(BUY, price, quantity)).containsExactly(new OrderRejected(3, 2, reason));
        assertThat(engine.book().orderCount()).isEqualTo(1);
        assertThat(engine.book().bestBid()).isEmpty();
    }

    @Test
    @Rulebook("CT-009")
    void limitsAreInclusive() {
        assertThat(limit(SELL, MAX_PRICE, MAX_QUANTITY)).containsExactly(
                new OrderAccepted(1, 1),
                new OrderRested(2, 1, SELL, MAX_PRICE, MAX_QUANTITY));
        assertThat(limit(BUY, 1, 1)).containsExactly(
                new OrderAccepted(3, 2),
                new OrderRested(4, 2, BUY, 1, 1));
    }

    @Test
    @Rulebook("CT-010")
    void rejectedOrdersStillConsumeAnOrderId() {
        limit(BUY, 0, 10);                       // order 1, rejected
        market(SELL, 10);                        // order 2, rejected: no liquidity

        assertThat(limit(BUY, 100, 10)).containsExactly(
                new OrderAccepted(3, 3),
                new OrderRested(4, 3, BUY, 100, 10));
    }

    @Test
    void nullCommandIsAProgrammingError() {
        assertThatNullPointerException().isThrownBy(() -> engine.process(null));
        assertThatNullPointerException().isThrownBy(() -> new MatchingEngine(null));
        assertThatNullPointerException().isThrownBy(() -> new LimitOrder(null, 100, 1));
        assertThatNullPointerException().isThrownBy(() -> new MarketOrder(null, 1));
    }

    @Test
    void eventListIsReadOnly() {
        List<Event> events = limit(BUY, 100, 1);
        assertThatThrownBy(events::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void exposesItsInstrument() {
        assertThat(engine.instrument().symbol()).isEqualTo("TEST");
    }
}
