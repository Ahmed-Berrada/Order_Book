package io.github.ahmedberrada.lob.core;

import static io.github.ahmedberrada.lob.core.CancelReason.CLIENT_REQUEST;
import static io.github.ahmedberrada.lob.core.CancelReason.UNFILLED_MARKET_REMAINDER;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_PRICE;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_QUANTITY;
import static io.github.ahmedberrada.lob.core.RejectReason.PRICE_ABOVE_MAXIMUM;
import static io.github.ahmedberrada.lob.core.RejectReason.STOP_PRICE_ALREADY_REACHED;
import static io.github.ahmedberrada.lob.core.RejectReason.UNKNOWN_ORDER;
import static io.github.ahmedberrada.lob.core.Side.BUY;
import static io.github.ahmedberrada.lob.core.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Scenario tests for {@code docs/rulebook/stop-orders.md}. */
class StopOrderTest {

    private static final Instrument INSTRUMENT = new Instrument("TEST", 1_000, 10_000);

    private MatchingEngine engine;

    @BeforeEach
    void newEngine() {
        engine = new MatchingEngine(INSTRUMENT);
    }

    private List<Event> limit(Side side, long price, long quantity) {
        return engine.process(new LimitOrder(side, price, quantity));
    }

    private List<Event> stop(Side side, long stopPrice, long limitPrice, long quantity) {
        return engine.process(new StopOrder(side, stopPrice, limitPrice, quantity));
    }

    @Test
    @Rulebook({"ST-001", "ST-003"})
    void acceptedStopIsParkedOutsideTheBook() {
        assertThat(stop(BUY, 105, 0, 10)).containsExactly(
                new OrderAccepted(1, 1),
                new OrderParked(2, 1, BUY, 105, 10));
        assertThat(engine.book().orderCount()).isZero();
        assertThat(engine.book().restingQuantity(1)).isZero();
        assertThat(engine.snapshot().parkedStops()).containsExactly(new ParkedStop(1, BUY, 105, 0, 10));
        assertThat(new StopOrder(SELL, 5, 3)).isEqualTo(new StopOrder(SELL, 5, 0, 3));
        assertThatNullPointerException().isThrownBy(() -> new StopOrder(null, 5, 3));
        assertThatNullPointerException().isThrownBy(() -> new ParkedStop(1, null, 5, 0, 3));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0, 5, INVALID_PRICE", "10001, 0, 5, PRICE_ABOVE_MAXIMUM", "100, -1, 5, INVALID_PRICE",
            "100, 10001, 5, PRICE_ABOVE_MAXIMUM", "100, 0, 0, INVALID_QUANTITY", "0, -1, 0, INVALID_QUANTITY"})
    @Rulebook("ST-002")
    void invalidStopsAreRejected(long stopPrice, long limitPrice, long quantity, RejectReason reason) {
        assertThat(stop(BUY, stopPrice, limitPrice, quantity)).containsExactly(new OrderRejected(1, 1, reason));
        assertThat(engine.snapshot().parkedStops()).isEmpty();
        assertThat(List.of(INVALID_PRICE, PRICE_ABOVE_MAXIMUM, INVALID_QUANTITY)).contains(reason);
    }

    @Test
    @Rulebook("ST-002")
    void stopsAlreadyReachedAreRejectedOnceThereIsALastTrade() {
        assertThat(stop(BUY, 1, 0, 1)).containsExactly(new OrderAccepted(1, 1), new OrderParked(2, 1, BUY, 1, 1));
        engine.process(new CancelOrder(1));
        limit(SELL, 100, 1);                                 // 2
        limit(BUY, 100, 1);                                  // 3: last trade 100

        assertThat(stop(BUY, 100, 0, 1)).containsExactly(new OrderRejected(8, 4, STOP_PRICE_ALREADY_REACHED));
        assertThat(stop(BUY, 99, 0, 1)).containsExactly(new OrderRejected(9, 5, STOP_PRICE_ALREADY_REACHED));
        assertThat(stop(SELL, 100, 0, 1)).containsExactly(new OrderRejected(10, 6, STOP_PRICE_ALREADY_REACHED));
        assertThat(stop(SELL, 101, 0, 1)).containsExactly(new OrderRejected(11, 7, STOP_PRICE_ALREADY_REACHED));
        assertThat(stop(BUY, 101, 0, 1)).containsExactly(new OrderAccepted(12, 8), new OrderParked(13, 8, BUY, 101, 1));
        assertThat(stop(SELL, 99, 0, 1)).containsExactly(new OrderAccepted(14, 9), new OrderParked(15, 9, SELL, 99, 1));
    }

    @Test
    @Rulebook({"ST-004", "ST-005"})
    void tradeAtTheStopPriceReleasesAStopMarketOrder() {
        limit(SELL, 105, 2);                                 // 1
        limit(SELL, 106, 5);                                 // 2
        stop(BUY, 105, 0, 4);                                // 3

        assertThat(limit(BUY, 105, 1)).containsExactly(
                new OrderAccepted(7, 4),
                new TradeExecuted(8, 4, 1, BUY, 105, 1, 0, 1),
                new StopTriggered(9, 3),
                new TradeExecuted(10, 3, 1, BUY, 105, 1, 3, 0),
                new TradeExecuted(11, 3, 2, BUY, 106, 3, 0, 2));
        assertThat(engine.snapshot().parkedStops()).isEmpty();
        assertThat(engine.snapshot().lastTradePriceTicks()).isEqualTo(106);
    }

    @Test
    @Rulebook({"ST-004", "ST-005"})
    void sellStopLimitRestsItsRemainderAtItsLimit() {
        limit(BUY, 100, 1);                                  // 1
        limit(BUY, 98, 5);                                   // 2
        stop(SELL, 100, 99, 4);                              // 3

        assertThat(engine.process(new MarketOrder(SELL, 1))).containsExactly(
                new OrderAccepted(7, 4),
                new TradeExecuted(8, 4, 1, SELL, 100, 1, 0, 0),
                new StopTriggered(9, 3),
                new OrderRested(10, 3, SELL, 99, 4));
        assertThat(engine.book().bestAsk()).hasValue(99);
    }

    @Test
    @Rulebook("ST-004")
    void onlyTradesTrigger() {
        limit(SELL, 105, 2);                                 // 1
        stop(BUY, 104, 0, 1);                                // 2
        // quotes and cancels at or beyond the stop price trigger nothing
        limit(BUY, 104, 1);                                  // 3
        engine.process(new CancelOrder(3));
        engine.process(new ReplaceOrder(1, 104, 2));
        assertThat(engine.snapshot().parkedStops()).hasSize(1);
        // a trade beyond the stop price triggers it, even though the stop price never traded
        assertThat(limit(BUY, 104, 1)).contains(new StopTriggered(12, 2));
    }

    @Test
    @Rulebook("ST-004")
    void aSweepTriggersEveryStopItPassedEvenIfItsLastFillIsBeyond() {
        limit(BUY, 100, 1);                                  // 1
        limit(BUY, 95, 1);                                   // 2
        stop(SELL, 99, 0, 1);                                // 3: between the two fills
        stop(SELL, 94, 0, 1);                                // 4: below every fill

        List<Event> events = engine.process(new MarketOrder(SELL, 2));
        assertThat(events).contains(new StopTriggered(12, 3));
        assertThat(events).filteredOn(StopTriggered.class::isInstance).hasSize(1);
        assertThat(engine.snapshot().parkedStops()).extracting(ParkedStop::orderId).containsExactly(4L);
    }

    @Test
    @Rulebook({"ST-005", "ST-006"})
    void cascadesReleaseInOrderOfOrderIdAndAReleasedStopOnAnEmptySideIsCancelled() {
        limit(SELL, 101, 1);                                 // 1
        limit(SELL, 102, 1);                                 // 2
        stop(BUY, 102, 0, 1);                                // 3: triggered by order 4's own sweep
        stop(BUY, 101, 0, 1);                                // 4: triggered first by price, released second
        stop(BUY, 103, 0, 2);                                // 5: never reached

        assertThat(engine.process(new MarketOrder(BUY, 2))).containsExactly(
                new OrderAccepted(11, 6),
                new TradeExecuted(12, 6, 1, BUY, 101, 1, 1, 0),
                new TradeExecuted(13, 6, 2, BUY, 102, 1, 0, 0),
                new StopTriggered(14, 3),
                new OrderCancelled(15, 3, 1, UNFILLED_MARKET_REMAINDER),
                new StopTriggered(16, 4),
                new OrderCancelled(17, 4, 1, UNFILLED_MARKET_REMAINDER));

        limit(SELL, 103, 1);                                 // 7
        limit(SELL, 104, 3);                                 // 8
        stop(SELL, 90, 0, 1);                                // 9
        // order 10 trades at 103, which triggers stop 5, whose fill at 104 is its own
        assertThat(limit(BUY, 103, 1)).containsExactly(
                new OrderAccepted(24, 10),
                new TradeExecuted(25, 10, 7, BUY, 103, 1, 0, 0),
                new StopTriggered(26, 5),
                new TradeExecuted(27, 5, 8, BUY, 104, 2, 0, 1));
    }

    @Test
    @Rulebook("ST-006")
    void releasedStopsTriggerFurtherStops() {
        limit(SELL, 101, 1);                                 // 1
        limit(SELL, 102, 1);                                 // 2
        limit(SELL, 103, 1);                                 // 3
        stop(BUY, 102, 0, 1);                                // 4: triggered by stop 5's fill at 102
        stop(BUY, 101, 0, 1);                                // 5

        assertThat(limit(BUY, 101, 1)).containsExactly(
                new OrderAccepted(11, 6),
                new TradeExecuted(12, 6, 1, BUY, 101, 1, 0, 0),
                new StopTriggered(13, 5),
                new TradeExecuted(14, 5, 2, BUY, 102, 1, 0, 0),
                new StopTriggered(15, 4),
                new TradeExecuted(16, 4, 3, BUY, 103, 1, 0, 0));
    }

    @Test
    @Rulebook("ST-007")
    void parkedStopCanBeCancelledButNotReplaced() {
        stop(SELL, 90, 89, 7);                               // 1

        assertThat(engine.process(new ReplaceOrder(1, 89, 5))).containsExactly(new ReplaceRejected(3, 1, UNKNOWN_ORDER));
        assertThat(engine.process(new CancelOrder(1))).containsExactly(new OrderCancelled(4, 1, 7, CLIENT_REQUEST));
        assertThat(engine.process(new CancelOrder(1))).containsExactly(new CancelRejected(5, 1, UNKNOWN_ORDER));
    }

    @Test
    @Rulebook({"ST-008", "RS-010"})
    void stopsAndTheLastTradePriceSurviveASnapshot() {
        limit(SELL, 100, 1);
        limit(BUY, 100, 1);
        stop(BUY, 101, 0, 1);
        stop(SELL, 99, 98, 2);

        EngineSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.lastTradePriceTicks()).isEqualTo(100);
        MatchingEngine restored = MatchingEngine.restore(snapshot);
        assertThat(restored.snapshot()).isEqualTo(snapshot);
        assertThat(restored.process(new StopOrder(BUY, 100, 1))).isEqualTo(stop(BUY, 100, 0, 1));
        limit(BUY, 101, 1);
        restored.process(new LimitOrder(BUY, 101, 1));
        assertThat(restored.process(new LimitOrder(SELL, 101, 1))).isEqualTo(limit(SELL, 101, 1));
    }

    @Test
    @Rulebook("RS-010")
    void snapshotWithInvalidStopsIsRefused() {
        List<RestingOrder> book = List.of(new RestingOrder(1, BUY, 100, 1));
        for (ParkedStop invalid : List.of(
                new ParkedStop(0, BUY, 100, 0, 1), new ParkedStop(5, BUY, 100, 0, 1),
                new ParkedStop(1, BUY, 100, 0, 1),                                  // also resting
                new ParkedStop(2, BUY, 100, 0, 0), new ParkedStop(2, BUY, 100, 0, 1_001),
                new ParkedStop(2, BUY, 0, 0, 1), new ParkedStop(2, BUY, 10_001, 0, 1),
                new ParkedStop(2, BUY, 100, -1, 1), new ParkedStop(2, BUY, 100, 10_001, 1))) {
            assertThatIllegalArgumentException().as("%s", invalid).isThrownBy(() ->
                    MatchingEngine.restore(new EngineSnapshot(INSTRUMENT, 5, 1, book, 0, List.of(invalid))));
        }
        ParkedStop valid = new ParkedStop(2, BUY, 100, 0, 1);
        assertThatIllegalArgumentException().isThrownBy(() ->
                MatchingEngine.restore(new EngineSnapshot(INSTRUMENT, 5, 1, book, 0, List.of(valid, valid))));
        assertThatIllegalArgumentException().isThrownBy(() ->
                MatchingEngine.restore(new EngineSnapshot(INSTRUMENT, 5, 1, book, -1, List.of())));
        assertThatIllegalArgumentException().isThrownBy(() ->
                MatchingEngine.restore(new EngineSnapshot(INSTRUMENT, 5, 1, book, 10_001, List.of())));
        MatchingEngine.restore(new EngineSnapshot(INSTRUMENT, 5, 1, book, 10_000,
                List.of(valid, new ParkedStop(4, SELL, 10_000, 10_000, 1_000))));
    }
}
