package io.github.ahmedberrada.lob.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Resting orders of one instrument. Readable by anyone; mutated only by {@link MatchingEngine}
 * (the mutators are package-private).
 *
 * <p>Each side is sorted best price first: highest bid, lowest ask.
 */
public final class OrderBook {

    /** Aggregated view of one price level, for market data and tests. */
    public record Level(long priceTicks, long quantity, int orderCount) {
    }

    private final NavigableMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, PriceLevel> asks = new TreeMap<>();
    private final Map<Long, Order> ordersById = new HashMap<>();

    OrderBook() {
    }

    public OptionalLong bestBid() {
        return bestPrice(bids);
    }

    public OptionalLong bestAsk() {
        return bestPrice(asks);
    }

    /** Levels of one side, best price first. */
    public List<Level> depth(Side side) {
        List<Level> levels = new ArrayList<>();
        for (PriceLevel level : levels(side).values()) {
            levels.add(new Level(level.priceTicks(), level.totalQuantity(), level.orderCount()));
        }
        return Collections.unmodifiableList(levels);
    }

    /** Number of resting orders on both sides. */
    public int orderCount() {
        return ordersById.size();
    }

    /** Remaining quantity of a resting order, or 0 if the order is not resting. */
    public long restingQuantity(long orderId) {
        Order order = ordersById.get(orderId);
        return order == null ? 0 : order.remainingQuantity;
    }

    /** Best level of a side, or {@code null} if the side is empty. */
    PriceLevel bestLevel(Side side) {
        var best = levels(side).firstEntry();
        return best == null ? null : best.getValue();
    }

    void rest(Order order) {
        levels(order.side).computeIfAbsent(order.priceTicks, PriceLevel::new).add(order);
        ordersById.put(order.orderId, order);
    }

    /** Fills the first order of a best level, dropping the order and the level once exhausted. */
    void fillFirst(PriceLevel level, long quantity) {
        Order maker = level.first();
        level.fillFirst(quantity);
        if (maker.remainingQuantity == 0) {
            ordersById.remove(maker.orderId);
        }
        if (level.isEmpty()) {
            levels(maker.side).remove(level.priceTicks());
        }
    }

    /** Removes a resting order, or returns {@code null} if it is not resting. */
    Order remove(long orderId) {
        Order order = ordersById.remove(orderId);
        if (order == null) {
            return null;
        }
        NavigableMap<Long, PriceLevel> side = levels(order.side);
        PriceLevel level = side.get(order.priceTicks);
        level.remove(order);
        if (level.isEmpty()) {
            side.remove(order.priceTicks);
        }
        return order;
    }

    private NavigableMap<Long, PriceLevel> levels(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private static OptionalLong bestPrice(NavigableMap<Long, PriceLevel> side) {
        return side.isEmpty() ? OptionalLong.empty() : OptionalLong.of(side.firstKey());
    }
}
