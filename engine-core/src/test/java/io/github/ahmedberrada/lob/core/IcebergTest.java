package io.github.ahmedberrada.lob.core;

import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_DISPLAY_QUANTITY;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_QUANTITY;
import static io.github.ahmedberrada.lob.core.Side.BUY;
import static io.github.ahmedberrada.lob.core.Side.SELL;
import static io.github.ahmedberrada.lob.core.TimeInForce.FOK;
import static io.github.ahmedberrada.lob.core.TimeInForce.GTC;
import static io.github.ahmedberrada.lob.core.TimeInForce.IOC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Scenario tests for {@code docs/rulebook/iceberg-orders.md}. */
class IcebergTest {

    private static final Instrument INSTRUMENT = new Instrument("TEST", 1_000, 10_000);

    private MatchingEngine engine;

    @BeforeEach
    void newEngine() {
        engine = new MatchingEngine(INSTRUMENT);
    }

    private List<Event> iceberg(Side side, long price, long quantity, long display) {
        return engine.process(new LimitOrder(side, price, quantity, GTC, false, display));
    }

    private List<Event> limit(Side side, long price, long quantity) {
        return engine.process(new LimitOrder(side, price, quantity));
    }

    private List<Event> market(Side side, long quantity) {
        return engine.process(new MarketOrder(side, quantity));
    }

    @Test
    @Rulebook({"IC-001", "IC-002"})
    void icebergRestsWithItsPeakVisibleOnly() {
        assertThat(iceberg(SELL, 100, 50, 10)).containsExactly(
                new OrderAccepted(1, 1),
                new OrderRested(2, 1, SELL, 100, 50, 10));
        assertThat(engine.book().depth(SELL)).containsExactly(new OrderBook.Level(100, 50, 1));
        assertThat(engine.book().displayedDepth(SELL)).containsExactly(new OrderBook.Level(100, 10, 1));
        assertThat(engine.snapshot().restingOrders()).containsExactly(
                new RestingOrder(1, SELL, 100, 50, false, 10, 10));
        assertThat(new LimitOrder(SELL, 1, 1).displayQuantity()).isZero();
    }

    @Test
    @Rulebook("IC-001")
    void displayEqualToTheQuantityOrZeroIsFullyVisible() {
        assertThat(iceberg(SELL, 100, 7, 7)).containsExactly(
                new OrderAccepted(1, 1), new OrderRested(2, 1, SELL, 100, 7, 7));
        assertThat(iceberg(SELL, 101, 7, 0)).containsExactly(
                new OrderAccepted(3, 2), new OrderRested(4, 2, SELL, 101, 7, 7));
        assertThat(engine.snapshot().restingOrders()).containsExactly(
                new RestingOrder(1, SELL, 100, 7), new RestingOrder(2, SELL, 101, 7));
        // being fully visible, a larger replace does not make it an iceberg
        engine.process(new ReplaceOrder(1, 100, 20));
        assertThat(engine.book().displayedDepth(SELL).getFirst()).isEqualTo(new OrderBook.Level(100, 20, 1));
    }

    @ParameterizedTest
    @CsvSource({"10, -1, GTC", "10, 11, GTC", "10, 5, IOC", "10, 5, FOK", "10, 10, IOC"})
    @Rulebook("IC-001")
    void invalidDisplayQuantitiesAreRejected(long quantity, long display, TimeInForce tif) {
        assertThat(engine.process(new LimitOrder(BUY, 100, quantity, tif, false, display)))
                .containsExactly(new OrderRejected(1, 1, INVALID_DISPLAY_QUANTITY));
        // the quantity is checked first
        assertThat(engine.process(new LimitOrder(BUY, 100, 0, tif, false, display)))
                .containsExactly(new OrderRejected(2, 2, INVALID_QUANTITY));
        assertThat(List.of(GTC, IOC, FOK)).contains(tif);
    }

    @Test
    @Rulebook({"IC-003", "IC-004", "IC-006"})
    void aggressorFillsThePeakThenTheIcebergRefillsBehindTheQueue() {
        iceberg(SELL, 100, 25, 10);                           // 1
        limit(SELL, 100, 4);                                  // 2, behind the iceberg

        assertThat(market(BUY, 12)).containsExactly(
                new OrderAccepted(5, 3),
                new TradeExecuted(6, 3, 1, BUY, 100, 10, 2, 15),
                new OrderReplenished(7, 1, SELL, 100, 10),
                new TradeExecuted(8, 3, 2, BUY, 100, 2, 0, 2));
        assertThat(engine.snapshot().restingOrders()).containsExactly(
                new RestingOrder(2, SELL, 100, 2),
                new RestingOrder(1, SELL, 100, 15, false, 10, 10));
        assertThat(engine.book().displayedDepth(SELL)).containsExactly(new OrderBook.Level(100, 12, 2));
    }

    @Test
    @Rulebook({"IC-003", "IC-004", "IC-006"})
    void aSweepReachesTheHiddenQuantityPeakByPeakAndTheLastPeakIsSmaller() {
        iceberg(SELL, 100, 25, 10);                           // 1
        limit(SELL, 101, 5);                                  // 2

        assertThat(limit(BUY, 101, 28)).containsExactly(
                new OrderAccepted(5, 3),
                new TradeExecuted(6, 3, 1, BUY, 100, 10, 18, 15),
                new OrderReplenished(7, 1, SELL, 100, 10),
                new TradeExecuted(8, 3, 1, BUY, 100, 10, 8, 5),
                new OrderReplenished(9, 1, SELL, 100, 5),
                new TradeExecuted(10, 3, 1, BUY, 100, 5, 3, 0),
                new TradeExecuted(11, 3, 2, BUY, 101, 3, 0, 2));
        assertThat(engine.book().orderCount()).isEqualTo(1);
    }

    @Test
    @Rulebook("IC-003")
    void partialFillOfAPeakKeepsPriority() {
        iceberg(BUY, 100, 30, 10);                            // 1
        limit(BUY, 100, 5);                                   // 2

        assertThat(market(SELL, 4)).containsExactly(
                new OrderAccepted(5, 3),
                new TradeExecuted(6, 3, 1, SELL, 100, 4, 0, 26));
        assertThat(engine.book().displayedDepth(BUY)).containsExactly(new OrderBook.Level(100, 11, 2));
        assertThat(engine.snapshot().restingOrders().getFirst()).isEqualTo(
                new RestingOrder(1, BUY, 100, 26, false, 10, 6));
    }

    @Test
    @Rulebook({"IC-004", "OT-003"})
    void fokCountsHiddenQuantity() {
        iceberg(SELL, 100, 25, 5);                            // 1
        assertThat(engine.process(new LimitOrder(BUY, 100, 25, FOK, false))).hasSize(1 + 5 + 4);
        assertThat(engine.book().orderCount()).isZero();
    }

    @Test
    @Rulebook({"IC-005", "OT-006"})
    void replaceKeepingPriorityTakesFromTheHiddenQuantityFirst() {
        iceberg(SELL, 100, 30, 10);                           // 1
        market(BUY, 4);                                       // peak 6, 26 left

        assertThat(engine.process(new ReplaceOrder(1, 100, 20)))
                .containsExactly(new OrderReplaced(5, 1, SELL, 100, 20, true));
        assertThat(engine.book().displayedDepth(SELL)).containsExactly(new OrderBook.Level(100, 6, 1));
        engine.process(new ReplaceOrder(1, 100, 3));
        assertThat(engine.book().displayedDepth(SELL)).containsExactly(new OrderBook.Level(100, 3, 1));
        assertThat(engine.snapshot().restingOrders()).containsExactly(new RestingOrder(1, SELL, 100, 3, false, 10, 3));
    }

    @Test
    @Rulebook("IC-005")
    void replaceLosingPriorityReentersWithAFullPeak() {
        iceberg(SELL, 100, 30, 10);                           // 1
        market(BUY, 4);

        assertThat(engine.process(new ReplaceOrder(1, 101, 30))).containsExactly(
                new OrderReplaced(5, 1, SELL, 101, 30, false),
                new OrderRested(6, 1, SELL, 101, 30, 10));
        // as an aggressor, an iceberg trades its whole quantity: peaks only limit resting orders
        limit(BUY, 99, 50);                                   // 3
        assertThat(engine.process(new ReplaceOrder(1, 99, 30))).containsExactly(
                new OrderReplaced(9, 1, SELL, 99, 30, false),
                new TradeExecuted(10, 1, 3, SELL, 99, 30, 0, 20));
    }

    @Test
    @Rulebook({"IC-002", "RS-010"})
    void icebergsSurviveASnapshot() {
        iceberg(SELL, 100, 30, 10);
        market(BUY, 4);

        MatchingEngine restored = MatchingEngine.restore(engine.snapshot());
        assertThat(restored.snapshot()).isEqualTo(engine.snapshot());
        assertThat(restored.process(new MarketOrder(BUY, 20))).isEqualTo(market(BUY, 20));
    }

    @Test
    @Rulebook("RS-010")
    void snapshotWithAnInvalidPeakIsRefused() {
        for (RestingOrder invalid : List.of(
                new RestingOrder(1, SELL, 100, 30, false, 0, 10),       // visible order hiding quantity
                new RestingOrder(1, SELL, 100, 30, false, -1, 10),      // negative display
                new RestingOrder(1, SELL, 100, 30, false, 10, 0),       // empty peak
                new RestingOrder(1, SELL, 100, 30, false, 10, 11),      // peak above the display size
                new RestingOrder(1, SELL, 100, 5, false, 10, 6))) {     // peak above the remainder
            assertThatIllegalArgumentException().as("%s", invalid).isThrownBy(() ->
                    MatchingEngine.restore(new EngineSnapshot(INSTRUMENT, 2, 1, List.of(invalid))));
        }
        MatchingEngine.restore(new EngineSnapshot(INSTRUMENT, 2, 1,
                List.of(new RestingOrder(1, SELL, 100, 5, false, 10, 5))));
    }
}
