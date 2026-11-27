package io.github.ahmedberrada.lob.core;

import static io.github.ahmedberrada.lob.core.CancelReason.DAY_EXPIRED;
import static io.github.ahmedberrada.lob.core.CancelReason.FILL_OR_KILL;
import static io.github.ahmedberrada.lob.core.CancelReason.VOLATILITY_INTERRUPTION;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_PRICE;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_TICK_SIZE;
import static io.github.ahmedberrada.lob.core.RejectReason.INVALID_TIME_IN_FORCE;
import static io.github.ahmedberrada.lob.core.Side.BUY;
import static io.github.ahmedberrada.lob.core.Side.SELL;
import static io.github.ahmedberrada.lob.core.TradingPhase.CALL;
import static io.github.ahmedberrada.lob.core.TradingPhase.CLOSED;
import static io.github.ahmedberrada.lob.core.TradingPhase.CONTINUOUS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Scenario tests for the tick-size regime, DAY orders and volatility interruptions. */
class VenueControlsTest {

    /** Ticks of 1 below 1000 and of 5 from there; moves of more than 5% interrupt trading. */
    private static final Instrument INSTRUMENT = new Instrument("CTL", 1_000, 10_000,
            List.of(new TickBand(1, 1), new TickBand(1_000, 5)), 500);

    private MatchingEngine engine;

    @BeforeEach
    void newEngine() {
        engine = new MatchingEngine(INSTRUMENT);
    }

    private List<Event> limit(Side side, long price, long quantity) {
        return engine.process(new LimitOrder(side, price, quantity));
    }

    private List<Event> limit(Side side, long price, long quantity, TimeInForce tif) {
        return engine.process(new LimitOrder(side, price, quantity, tif, false));
    }

    private List<Event> phase(TradingPhase phase) {
        return engine.process(new SetPhase(phase));
    }

    /** A first trade at 1000, which becomes the reference price: order IDs 1 and 2, sequence 4. */
    private void reference() {
        limit(SELL, 1_000, 10);
        limit(BUY, 1_000, 10);
    }

    // ---- Tick-size regime -------------------------------------------------------------------------

    @Test
    @Rulebook({"TK-001", "TK-002"})
    void pricesMustBeAMultipleOfTheStepOfTheirBand() {
        assertThat(limit(BUY, 999, 1)).first().isEqualTo(new OrderAccepted(1, 1));
        assertThat(limit(BUY, 1_003, 1)).containsExactly(new OrderRejected(3, 2, INVALID_TICK_SIZE));
        assertThat(limit(BUY, 1_005, 1)).first().isEqualTo(new OrderAccepted(4, 3));
        assertThat(limit(BUY, 1_000, 1)).first().isEqualTo(new OrderAccepted(6, 4));
        assertThat(engine.process(new StopOrder(BUY, 1_003, 1))).containsExactly(
                new OrderRejected(8, 5, INVALID_TICK_SIZE));
        assertThat(engine.process(new StopOrder(BUY, 1_005, 1_002, 1))).containsExactly(
                new OrderRejected(9, 6, INVALID_TICK_SIZE));
        assertThat(engine.process(new StopOrder(BUY, 1_005, 1_005, 1))).first().isEqualTo(new OrderAccepted(10, 7));
    }

    @Test
    @Rulebook("TK-002")
    void theTickCheckComesAfterTheRangeChecksAndAReplaceIsRefusedWithoutChange() {
        assertThat(limit(BUY, -3, 1)).containsExactly(new OrderRejected(1, 1, INVALID_PRICE));
        limit(BUY, 1_000, 4);

        assertThat(engine.process(new ReplaceOrder(2, 1_002, 4))).containsExactly(
                new ReplaceRejected(4, 2, INVALID_TICK_SIZE));
        assertThat(engine.book().restingQuantity(2)).isEqualTo(4);
        assertThat(engine.process(new ReplaceOrder(2, 1_005, 4))).first()
                .isEqualTo(new OrderReplaced(5, 2, BUY, 1_005, 4, false));
    }

    // ---- DAY orders -------------------------------------------------------------------------------

    @Test
    @Rulebook("DY-001")
    void aDayOrderRestsLikeAGoodTillCancelledOne() {
        assertThat(limit(BUY, 990, 5, TimeInForce.DAY)).containsExactly(
                new OrderAccepted(1, 1), new OrderRested(2, 1, BUY, 990, 5, 5));
        assertThat(limit(SELL, 990, 2)).containsExactly(
                new OrderAccepted(3, 2), new TradeExecuted(4, 2, 1, SELL, 990, 2, 0, 3));
        assertThat(engine.process(new LimitOrder(BUY, 980, 5, TimeInForce.DAY, true))).first()
                .isEqualTo(new OrderAccepted(5, 3));
        assertThat(engine.process(new LimitOrder(BUY, 970, 20, TimeInForce.DAY, false, 5))).first()
                .isEqualTo(new OrderAccepted(7, 4));
        assertThat(engine.process(new MarketOrder(BUY, 1, TimeInForce.DAY))).containsExactly(
                new OrderRejected(9, 5, INVALID_TIME_IN_FORCE));
        assertThat(TimeInForce.DAY.rests()).isTrue();
        assertThat(TimeInForce.IOC.rests()).isFalse();
    }

    @Test
    @Rulebook("DY-002")
    void closingExpiresDayOrdersInOrderIdAndKeepsGoodTillCancelledOnes() {
        limit(BUY, 990, 5, TimeInForce.DAY);                                    // 1
        limit(BUY, 980, 7, TimeInForce.GTC);                                    // 2
        engine.process(new LimitOrder(BUY, 970, 20, TimeInForce.DAY, false, 5));    // 3, an iceberg
        engine.process(new StopOrder(SELL, 900, 1));                              // 4, a stop stays
        assertThat(phase(CLOSED)).containsExactly(
                new PhaseChanged(9, CONTINUOUS, CLOSED),
                new OrderCancelled(10, 1, 5, DAY_EXPIRED),
                new OrderCancelled(11, 3, 20, DAY_EXPIRED));
        assertThat(engine.book().restingQuantity(2)).isEqualTo(7);
        assertThat(engine.snapshot().parkedStops()).hasSize(1);
        assertThat(engine.book().restingQuantity(1)).isZero();
    }

    @Test
    @Rulebook({"DY-001", "DY-002"})
    void aDayOrderEnteredInACallPhaseAndReplacedStillExpiresAfterTheUncross() {
        phase(CALL);                                                                    // seq 1
        limit(BUY, 990, 5, TimeInForce.DAY);                                    // 1; seq 2, 3
        limit(SELL, 990, 3);                                                    // 2; seq 4, 5
        limit(BUY, 900, 5, TimeInForce.DAY);                                    // 3; seq 6, 7
        engine.process(new ReplaceOrder(3, 905, 6));                                    // priority lost, still DAY

        assertThat(phase(CLOSED)).containsExactly(
                new AuctionPriceDetermined(10, 990, 3),
                new TradeExecuted(11, 2, 1, SELL, 990, 3, 0, 2),
                new PhaseChanged(12, CALL, CLOSED),
                new OrderCancelled(13, 1, 2, DAY_EXPIRED),
                new OrderCancelled(14, 3, 6, DAY_EXPIRED));
        assertThat(engine.book().orderCount()).isZero();
    }

    @Test
    @Rulebook("DY-003")
    void theDayAttributeSurvivesASnapshot() {
        limit(BUY, 990, 5, TimeInForce.DAY);
        limit(BUY, 980, 5);
        EngineSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.restingOrders()).extracting(RestingOrder::day).containsExactly(true, false);

        MatchingEngine restored = MatchingEngine.restore(snapshot);

        assertThat(restored.process(new SetPhase(CLOSED))).isEqualTo(engine.process(new SetPhase(CLOSED)));
        assertThat(restored.snapshot()).isEqualTo(engine.snapshot());
    }

    // ---- Volatility interruptions -----------------------------------------------------------------

    @Test
    @Rulebook("VI-001")
    void theFirstTradeSetsTheReferencePriceAndOnlyAuctionsMoveIt() {
        assertThat(engine.snapshot().referencePriceTicks()).isZero();
        limit(SELL, 5_000, 1);
        limit(BUY, 5_000, 1);                                  // nothing to compare the first trade with
        assertThat(engine.snapshot().referencePriceTicks()).isEqualTo(5_000);
        limit(SELL, 5_100, 1);
        limit(BUY, 5_100, 1);                                  // inside the band: trades, reference stays
        assertThat(engine.snapshot().referencePriceTicks()).isEqualTo(5_000);
        assertThat(engine.snapshot().lastTradePriceTicks()).isEqualTo(5_100);

        phase(CALL);
        limit(SELL, 5_300, 1);
        limit(BUY, 5_300, 1);
        phase(CONTINUOUS);
        assertThat(engine.snapshot().referencePriceTicks()).isEqualTo(5_300);
    }

    @Test
    @Rulebook({"VI-002", "VI-003"})
    void aLimitOrderThatWouldTradeOutsideTheBandRestsAndTheInstrumentMovesToCall() {
        reference();                                                    // ids 1, 2; reference 1000, band ±50
        limit(SELL, 1_050, 5);                                          // id 3: on the edge of the band
        limit(SELL, 1_055, 5);                                          // id 4, beyond it

        List<Event> events = limit(BUY, 1_060, 8);                      // id 5

        assertThat(events).containsExactly(
                new OrderAccepted(9, 5),
                new TradeExecuted(10, 5, 3, BUY, 1_050, 5, 3, 0),
                new OrderRested(11, 5, BUY, 1_060, 3, 3),
                new VolatilityInterruption(12, 1_000, 1_055),
                new PhaseChanged(13, CONTINUOUS, CALL));
        assertThat(engine.phase()).isEqualTo(CALL);
        assertThat(engine.book().bestBid()).hasValue(1_060);
        assertThat(engine.book().bestAsk()).hasValue(1_055);                  // crossed, as in any call phase
    }

    @Test
    @Rulebook("VI-003")
    void anImmediateOrMarketRemainderIsCancelledWithTheInterruptionAsReason() {
        reference();
        limit(SELL, 1_040, 3);                                          // 3
        limit(SELL, 1_070, 5);                                          // 4

        assertThat(engine.process(new MarketOrder(BUY, 8))).containsExactly(
                new OrderAccepted(9, 5),
                new TradeExecuted(10, 5, 3, BUY, 1_040, 3, 5, 0),
                new OrderCancelled(11, 5, 5, VOLATILITY_INTERRUPTION),
                new VolatilityInterruption(12, 1_000, 1_070),
                new PhaseChanged(13, CONTINUOUS, CALL));

        newEngine();
        reference();
        limit(SELL, 1_070, 5);                                          // 3
        assertThat(engine.process(new LimitOrder(BUY, 1_100, 5, TimeInForce.IOC, false))).containsExactly(
                new OrderAccepted(7, 4),
                new OrderCancelled(8, 4, 5, VOLATILITY_INTERRUPTION),
                new VolatilityInterruption(9, 1_000, 1_070),
                new PhaseChanged(10, CONTINUOUS, CALL));
    }

    @Test
    @Rulebook("VI-004")
    void aFillOrKillOrderCountsOnlyLiquidityInsideTheBandAndNeverStartsAnInterruption() {
        reference();
        limit(SELL, 1_070, 5);                                          // 3, outside the band

        assertThat(engine.process(new LimitOrder(BUY, 1_100, 5, TimeInForce.FOK, false))).containsExactly(
                new OrderAccepted(7, 4), new OrderCancelled(8, 4, 5, FILL_OR_KILL));
        assertThat(engine.process(new MarketOrder(BUY, 5, TimeInForce.FOK))).containsExactly(
                new OrderAccepted(9, 5), new OrderCancelled(10, 5, 5, FILL_OR_KILL));
        assertThat(engine.phase()).isEqualTo(CONTINUOUS);

        limit(SELL, 1_050, 5);                                          // 6, inside
        assertThat(engine.process(new LimitOrder(BUY, 1_100, 5, TimeInForce.FOK, false))).first()
                .isEqualTo(new OrderAccepted(13, 7));
        assertThat(engine.phase()).isEqualTo(CONTINUOUS);
        // selling: the band's lower edge is 950
        limit(BUY, 940, 5);
        assertThat(engine.process(new LimitOrder(SELL, 900, 5, TimeInForce.FOK, false))).hasSize(2);
        assertThat(engine.phase()).isEqualTo(CONTINUOUS);
    }

    @Test
    @Rulebook({"VI-002", "TP-007"})
    void stopsAreNotReleasedByTheTradesOfACommandThatInterruptsTrading() {
        reference();
        limit(SELL, 1_040, 3);                                          // 3
        limit(SELL, 1_070, 5);                                          // 4
        engine.process(new StopOrder(BUY, 1_030, 2));                   // 5 parked; triggered by the next trade

        engine.process(new MarketOrder(BUY, 8));

        assertThat(engine.phase()).isEqualTo(CALL);
        assertThat(engine.snapshot().parkedStops()).hasSize(1);
    }

    @Test
    @Rulebook("VI-003")
    void aReplaceThatBreachesTheBandRestsItsRemainder() {
        reference();
        limit(SELL, 1_070, 5);                                          // 3
        limit(BUY, 990, 3);                                             // 4

        assertThat(engine.process(new ReplaceOrder(4, 1_080, 3))).containsExactly(
                new OrderReplaced(9, 4, BUY, 1_080, 3, false),
                new OrderRested(10, 4, BUY, 1_080, 3, 3),
                new VolatilityInterruption(11, 1_000, 1_070),
                new PhaseChanged(12, CONTINUOUS, CALL));
    }

    @Test
    @Rulebook("VI-003")
    void aReleasedStopThatBreachesTheBandInterruptsToo() {
        reference();
        limit(SELL, 1_020, 1);                                          // 3
        limit(SELL, 1_070, 5);                                          // 4
        engine.process(new StopOrder(BUY, 1_020, 0, 4));                // 5: a market stop
        engine.process(new StopOrder(BUY, 1_020, 1_100, 2));            // 6: a stop-limit

        List<Event> events = limit(BUY, 1_020, 1);                      // 7 trades at 1020, releasing both

        assertThat(events).filteredOn(e -> e instanceof StopTriggered || e instanceof OrderCancelled
                || e instanceof VolatilityInterruption || e instanceof OrderRested)
                .extracting(e -> e.getClass().getSimpleName()).containsExactly(
                        "StopTriggered", "OrderCancelled", "VolatilityInterruption");
        assertThat(events).filteredOn(OrderCancelled.class::isInstance).first().isEqualTo(
                new OrderCancelled(events.stream().filter(OrderCancelled.class::isInstance).findFirst().get()
                        .sequence(), 5, 4, VOLATILITY_INTERRUPTION));
        assertThat(engine.phase()).isEqualTo(CALL);
        assertThat(engine.snapshot().parkedStops()).extracting(ParkedStop::orderId).containsExactly(6L);
    }

    @Test
    @Rulebook("VI-005")
    void theInterruptionEndsWithASetPhaseAndTheAuctionIsNotSubjectToTheBand() {
        reference();
        limit(SELL, 1_055, 5);                                          // 3
        limit(BUY, 1_060, 8);                                           // 4: interrupts, rests whole
        assertThat(engine.phase()).isEqualTo(CALL);

        assertThat(phase(CONTINUOUS)).containsExactly(
                new AuctionPriceDetermined(11, 1_060, 5),
                new TradeExecuted(12, 4, 3, BUY, 1_060, 5, 3, 0),
                new PhaseChanged(13, CALL, CONTINUOUS));
        assertThat(engine.snapshot().referencePriceTicks()).isEqualTo(1_060);
        assertThat(engine.book().restingQuantity(4)).isEqualTo(3);

        // an uncross that trades nothing leaves the reference alone
        phase(CALL);
        limit(BUY, 900, 1);
        phase(CONTINUOUS);
        assertThat(engine.snapshot().referencePriceTicks()).isEqualTo(1_060);
    }

    @Test
    @Rulebook("VI-006")
    void aRestoredEngineInterruptsAtTheSameTrade() {
        reference();
        limit(SELL, 1_070, 5);
        MatchingEngine restored = MatchingEngine.restore(engine.snapshot());
        assertThat(restored.snapshot().referencePriceTicks()).isEqualTo(1_000);

        assertThat(restored.process(new MarketOrder(BUY, 5))).isEqualTo(engine.process(new MarketOrder(BUY, 5)));
        assertThat(restored.phase()).isEqualTo(CALL);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> MatchingEngine.restore(
                new EngineSnapshot(INSTRUMENT, 1, 1, List.of(), 0, List.of(), CONTINUOUS, 10_001)));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> MatchingEngine.restore(
                new EngineSnapshot(INSTRUMENT, 1, 1, List.of(), 0, List.of(), CONTINUOUS, -1)));
    }

    @Test
    @Rulebook("VI-002")
    void withoutABandAnyMoveTrades() {
        MatchingEngine open = new MatchingEngine(new Instrument("OPN", 1_000, 10_000));
        open.process(new LimitOrder(SELL, 100, 1));
        open.process(new LimitOrder(BUY, 100, 1));
        open.process(new LimitOrder(SELL, 9_000, 1));

        assertThat(open.process(new LimitOrder(BUY, 9_000, 1))).hasSize(2);
        assertThat(open.phase()).isEqualTo(CONTINUOUS);
    }
}
