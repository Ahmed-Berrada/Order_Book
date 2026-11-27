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
    /** Open resting orders and their quantities by member (RK-002). */
    private final Map<Long, Exposure> exposures = new HashMap<>();

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

    /** Levels of one side as the market may see them: peaks only, hidden quantity excluded (IC-002). */
    public List<Level> displayedDepth(Side side) {
        List<Level> levels = new ArrayList<>();
        for (PriceLevel level : levels(side).values()) {
            levels.add(new Level(level.priceTicks(), level.displayedQuantity(), level.orderCount()));
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

    /** Every resting order in priority order: bids best first, then asks best first, FIFO within a price. */
    List<RestingOrder> restingOrders() {
        List<RestingOrder> orders = new ArrayList<>(ordersById.size());
        for (Side side : Side.values()) {
            for (PriceLevel level : levels(side).values()) {
                for (Order order : level.orders()) {
                    orders.add(new RestingOrder(order.orderId, order.side, order.priceTicks,
                            order.remainingQuantity, order.postOnly, order.displayQuantity, order.displayed,
                            order.selfTrade, order.day, order.memberId));
                }
            }
        }
        return orders;
    }

    /** Best level of a side, or {@code null} if the side is empty. */
    PriceLevel bestLevel(Side side) {
        var best = levels(side).firstEntry();
        return best == null ? null : best.getValue();
    }

    void rest(Order order) {
        levels(order.side).computeIfAbsent(order.priceTicks, PriceLevel::new).add(order);
        ordersById.put(order.orderId, order);
        exposures.computeIfAbsent(order.memberId, m -> new Exposure()).add(order.side, order.remainingQuantity);
    }

    /**
     * Fills the first order of a best level, dropping the order and the level once exhausted.
     *
     * @return whether the order was an iceberg that refilled its peak (IC-004)
     */
    boolean fillFirst(PriceLevel level, long quantity) {
        Order maker = level.first();
        boolean replenished = level.fillFirst(quantity);
        Exposure exposure = exposures.get(maker.memberId);
        exposure.change(maker.side, -quantity);
        if (maker.remainingQuantity == 0) {
            ordersById.remove(maker.orderId);
            exposure.orders--;
        }
        if (level.isEmpty()) {
            levels(maker.side).remove(level.priceTicks());
        }
        return replenished;
    }

    /** A resting order, or {@code null} if it is not resting. */
    Order find(long orderId) {
        return ordersById.get(orderId);
    }

    /** Reduces a resting order's quantity in place, keeping its place in the queue (OT-006). */
    void reduce(Order order, long newQuantity) {
        exposures.get(order.memberId).change(order.side, newQuantity - order.remainingQuantity);
        levels(order.side).get(order.priceTicks).reduce(order, newQuantity);
    }

    /**
     * Opposite quantity available to a taker within its limit, counted from the best level and capped
     * at {@code needed} (OT-003). With a self-trade prevention group, counting stops before the first
     * level that holds an order of that group (SP-006).
     */
    long availableQuantity(Side takerSide, long limitPriceTicks, long needed, long group) {
        long available = 0;
        for (PriceLevel level : levels(takerSide.opposite()).values()) {
            boolean withinLimit = takerSide == Side.BUY
                    ? level.priceTicks() <= limitPriceTicks
                    : level.priceTicks() >= limitPriceTicks;
            if (!withinLimit || available >= needed || (group > 0 && level.holdsGroup(group))) {
                break;
            }
            available += level.totalQuantity();
        }
        return Math.min(available, needed);
    }

    /** Shows an order's quantity as {@code displayed} (TP-003, TP-008). */
    void show(Order order, long displayed) {
        levels(order.side).get(order.priceTicks).show(order, displayed);
    }

    /** What a member has resting, or {@code null} if nothing has ever rested (RK-002). */
    Exposure exposureOf(long memberId) {
        return exposures.get(memberId);
    }

    /** Every resting order of a member, by order ID (RK-004). */
    List<Order> ordersOf(long memberId) {
        List<Order> found = new ArrayList<>();
        for (Order order : ordersById.values()) {
            if (order.memberId == memberId) {
                found.add(order);
            }
        }
        found.sort(Comparator.comparingLong(order -> order.orderId));
        return found;
    }

    /** Every resting DAY order, by order ID (DY-002). */
    List<Order> dayOrders() {
        List<Order> found = new ArrayList<>();
        for (Order order : ordersById.values()) {
            if (order.day) {
                found.add(order);
            }
        }
        found.sort(Comparator.comparingLong(order -> order.orderId));
        return found;
    }

    /** Every resting iceberg order, by order ID. */
    List<Order> icebergs() {
        List<Order> icebergs = new ArrayList<>();
        for (Order order : ordersById.values()) {
            if (order.displayQuantity != 0) {
                icebergs.add(order);
            }
        }
        icebergs.sort(Comparator.comparingLong(order -> order.orderId));
        return icebergs;
    }

    /**
     * Resting orders of the opposite side that a new order at this price would cross and that belong to
     * its self-trade group, best price first, oldest first (AU-004).
     */
    List<Order> crossingGroup(Side side, long priceTicks, SelfTradeInstruction selfTrade) {
        List<Order> found = new ArrayList<>();
        if (selfTrade.group() <= 0) {
            return found;
        }
        for (PriceLevel level : levels(side.opposite()).values()) {
            boolean crosses = side == Side.BUY ? level.priceTicks() <= priceTicks : level.priceTicks() >= priceTicks;
            if (!crosses) {
                break;
            }
            for (Order order : level.orders()) {
                if (selfTrade.conflictsWith(order.selfTrade)) {
                    found.add(order);
                }
            }
        }
        return found;
    }

    /** Removes a resting order, or returns {@code null} if it is not resting. */
    Order remove(long orderId) {
        Order order = ordersById.remove(orderId);
        if (order == null) {
            return null;
        }
        exposures.get(order.memberId).remove(order.side, order.remainingQuantity);
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
