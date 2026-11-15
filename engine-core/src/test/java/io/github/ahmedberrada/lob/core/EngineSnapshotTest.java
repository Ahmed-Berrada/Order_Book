package io.github.ahmedberrada.lob.core;

import static io.github.ahmedberrada.lob.core.Side.BUY;
import static io.github.ahmedberrada.lob.core.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.Test;

class EngineSnapshotTest {

    private static final Instrument INSTRUMENT = new Instrument("SNAP", 100, 1_000);

    @Test
    @Rulebook("RS-010")
    void snapshotListsOrdersInPriorityOrderAndRestoresTheSameQueues() {
        MatchingEngine engine = new MatchingEngine(INSTRUMENT);
        engine.process(new LimitOrder(BUY, 99, 1));      // 1
        engine.process(new LimitOrder(BUY, 100, 2));     // 2
        engine.process(new LimitOrder(BUY, 100, 3));     // 3
        engine.process(new LimitOrder(SELL, 102, 4));    // 4
        engine.process(new LimitOrder(SELL, 101, 5));    // 5

        EngineSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.nextOrderId()).isEqualTo(6);
        assertThat(snapshot.nextSequence()).isEqualTo(11);
        assertThat(snapshot.restingOrders()).containsExactly(
                new RestingOrder(2, BUY, 100, 2),
                new RestingOrder(3, BUY, 100, 3),
                new RestingOrder(1, BUY, 99, 1),
                new RestingOrder(5, SELL, 101, 5),
                new RestingOrder(4, SELL, 102, 4));

        MatchingEngine restored = MatchingEngine.restore(snapshot);
        assertThat(restored.snapshot()).isEqualTo(snapshot);
        assertThat(restored.process(new MarketOrder(SELL, 3))).isEqualTo(engine.process(new MarketOrder(SELL, 3)));
    }

    @Test
    @Rulebook("RS-010")
    void restoreRejectsInconsistentSnapshots() {
        assertThatNullPointerException().isThrownBy(() -> MatchingEngine.restore(null));
        assertInvalid(0, 1, List.of());
        assertInvalid(1, 0, List.of());
        assertInvalid(3, 1, List.of(new RestingOrder(3, BUY, 100, 1)));
        assertInvalid(3, 1, List.of(new RestingOrder(0, BUY, 100, 1)));
        assertInvalid(3, 1, List.of(new RestingOrder(1, BUY, 100, 1), new RestingOrder(1, SELL, 101, 1)));
        assertInvalid(3, 1, List.of(new RestingOrder(1, BUY, 100, 0)));
        assertInvalid(3, 1, List.of(new RestingOrder(1, BUY, 100, 101)));
        assertInvalid(3, 1, List.of(new RestingOrder(1, BUY, 0, 1)));
        assertInvalid(3, 1, List.of(new RestingOrder(1, BUY, 1_001, 1)));
        assertInvalid(3, 1, List.of(new RestingOrder(1, BUY, 100, 1), new RestingOrder(2, SELL, 100, 1)));
    }

    @Test
    void restingOrderRequiresASide() {
        assertThatNullPointerException().isThrownBy(() -> new RestingOrder(1, null, 1, 1));
    }

    private static void assertInvalid(long nextOrderId, long nextSequence, List<RestingOrder> orders) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                MatchingEngine.restore(new EngineSnapshot(INSTRUMENT, nextOrderId, nextSequence, orders)));
    }
}
