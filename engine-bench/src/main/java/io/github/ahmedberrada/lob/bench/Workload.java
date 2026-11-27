package io.github.ahmedberrada.lob.bench;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.Side;
import java.util.SplittableRandom;

/**
 * A reproducible stream of commands that looks like a market: orders cluster around a drifting mid
 * price, most are passive and many are cancelled, some cross the book. The same seed always gives the
 * same stream, so runs are comparable.
 */
final class Workload {

    static final Instrument INSTRUMENT = new Instrument("BENCH", 1_000_000, 10_000_000);

    private final SplittableRandom random;
    private long mid = 100_000;
    private long placed;              // order IDs the engine will have handed out
    private final long[] live;         // recent order IDs, candidates for a cancel
    private int liveCount;

    Workload(long seed, int liveWindow) {
        this.random = new SplittableRandom(seed);
        this.live = new long[liveWindow];
    }

    /**
     * The next command: 62% passive limit orders, 30% cancels of a recent order, 6% marketable limit
     * orders, 2% market orders.
     */
    Command next() {
        int roll = random.nextInt(100);
        if (random.nextInt(8) == 0) {
            mid += random.nextInt(3) - 1;                                   // the market drifts
        }
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        long quantity = 1 + random.nextInt(100);
        if (roll < 62) {
            long distance = 1 + random.nextInt(50);
            long price = side == Side.BUY ? mid - distance : mid + distance;
            return place(new LimitOrder(side, price, quantity));
        } else if (roll < 92 && liveCount > 0) {
            int index = random.nextInt(liveCount);
            long id = live[index];
            live[index] = live[--liveCount];
            return new CancelOrder(id);
        } else if (roll < 98) {
            long price = side == Side.BUY ? mid + 5 : mid - 5;              // reaches into the other side
            return place(new LimitOrder(side, price, quantity));
        }
        placed++;
        return new MarketOrder(side, quantity);
    }

    private Command place(LimitOrder order) {
        long id = ++placed;
        if (liveCount < live.length) {
            live[liveCount++] = id;
        } else {
            live[random.nextInt(live.length)] = id;
        }
        return order;
    }
}
