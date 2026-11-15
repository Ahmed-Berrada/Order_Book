package io.github.ahmedberrada.lob.marketdata;

import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.OrderCancelled;
import io.github.ahmedberrada.lob.core.OrderReplaced;
import io.github.ahmedberrada.lob.core.OrderRested;
import io.github.ahmedberrada.lob.core.RestingOrder;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.marketdata.MarketDataMessage.Snapshot;
import io.github.ahmedberrada.lob.marketdata.MarketDataMessage.Update;
import io.github.ahmedberrada.lob.service.InstrumentState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One instrument's book as the market sees it, rebuilt from events alone (ADR-0007 §1). It keeps the
 * resting orders it has seen, to apply fills and cancels to the right level, and aggregates them into
 * levels. Not thread-safe: {@link MarketDataPublisher} serialises access.
 */
final class BookImage {

    /** A resting order as far as the feed is concerned: where it rests and how much is left. */
    private static final class Order {
        final Side side;
        final long priceTicks;
        long quantity;

        Order(Side side, long priceTicks, long quantity) {
            this.side = side;
            this.priceTicks = priceTicks;
            this.quantity = quantity;
        }
    }

    private static final class Level {
        long quantity;
        int orders;
    }

    private final String symbol;
    private final Map<Long, Order> orders = new HashMap<>();
    private final NavigableMap<Long, Level> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, Level> asks = new TreeMap<>();
    private long commandSequence;
    private long feedSequence;

    /** Starts from an instrument's recovered or current state (MD-007). */
    BookImage(String symbol, InstrumentState state) {
        this.symbol = symbol;
        this.commandSequence = state.lastCommandSequence();
        for (RestingOrder order : state.engine().restingOrders()) {
            add(order.orderId(), order.side(), order.priceTicks(), order.remainingQuantity());
        }
    }

    /**
     * Applies a command's events and returns the update to publish, or empty if the command changed
     * neither the book nor trades, or if the starting state already included it (MD-005, MD-007).
     */
    Optional<Update> apply(EventBatch batch) {
        if (batch.commandSequence() <= commandSequence) {
            return Optional.empty();
        }
        commandSequence = batch.commandSequence();
        TreeSet<Long> changedBids = new TreeSet<>(bids.comparator());
        TreeSet<Long> changedAsks = new TreeSet<>();
        List<Trade> trades = new ArrayList<>();
        for (Event event : batch.events()) {
            switch (event) {
                case OrderRested e -> {
                    add(e.orderId(), e.side(), e.priceTicks(), e.quantity());
                    changed(e.side(), changedBids, changedAsks).add(e.priceTicks());
                }
                case TradeExecuted e -> {
                    trades.add(new Trade(e.priceTicks(), e.quantity(), e.aggressorSide()));
                    Order maker = orders.get(e.makerOrderId());
                    reduce(e.makerOrderId(), maker, e.quantity());
                    changed(maker.side, changedBids, changedAsks).add(maker.priceTicks);
                }
                case OrderCancelled e -> {
                    Order cancelled = orders.get(e.orderId());
                    if (cancelled != null) {                 // a market order's remainder never rested
                        reduce(e.orderId(), cancelled, cancelled.quantity);
                        changed(cancelled.side, changedBids, changedAsks).add(cancelled.priceTicks);
                    }
                }
                case OrderReplaced e -> {
                    Order replaced = orders.get(e.orderId());
                    // priority kept: the quantity shrinks in place; otherwise the order leaves the book
                    // and an OrderRested event re-enters whatever remains after its trades (OT-008)
                    long removed = e.priorityKept() ? replaced.quantity - e.quantity() : replaced.quantity;
                    if (removed > 0) {
                        reduce(e.orderId(), replaced, removed);
                        changed(replaced.side, changedBids, changedAsks).add(replaced.priceTicks);
                    }
                }
                default -> {
                    // acceptances and rejections do not change the book
                }
            }
        }
        if (changedBids.isEmpty() && changedAsks.isEmpty() && trades.isEmpty()) {
            return Optional.empty();
        }
        feedSequence++;
        return Optional.of(new Update(symbol, feedSequence, commandSequence, batch.timestampMicros(),
                levels(bids, changedBids), levels(asks, changedAsks), trades,
                best(bids), best(asks)));
    }

    /** The whole book as of the last applied command (MD-004). */
    Snapshot snapshot() {
        return new Snapshot(symbol, feedSequence, commandSequence, levels(bids), levels(asks));
    }

    List<BookLevel> levels(Side side) {
        return levels(side == Side.BUY ? bids : asks);
    }

    long commandSequence() {
        return commandSequence;
    }

    private void add(long orderId, Side side, long priceTicks, long quantity) {
        orders.put(orderId, new Order(side, priceTicks, quantity));
        Level level = book(side).computeIfAbsent(priceTicks, p -> new Level());
        level.quantity += quantity;
        level.orders++;
    }

    private void reduce(long orderId, Order order, long quantity) {
        NavigableMap<Long, Level> side = book(order.side);
        Level level = side.get(order.priceTicks);
        order.quantity -= quantity;
        level.quantity -= quantity;
        if (order.quantity == 0) {
            orders.remove(orderId);
            level.orders--;
        }
        if (level.orders == 0) {
            side.remove(order.priceTicks);
        }
    }

    private NavigableMap<Long, Level> book(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private static TreeSet<Long> changed(Side side, TreeSet<Long> bids, TreeSet<Long> asks) {
        return side == Side.BUY ? bids : asks;
    }

    /** The new state of each changed price, best first; 0 if the level is gone. */
    private static List<BookLevel> levels(NavigableMap<Long, Level> side, TreeSet<Long> changed) {
        List<BookLevel> levels = new ArrayList<>(changed.size());
        for (long price : changed) {
            Level level = side.get(price);
            levels.add(level == null ? new BookLevel(price, 0, 0) : new BookLevel(price, level.quantity, level.orders));
        }
        return levels;
    }

    private static List<BookLevel> levels(NavigableMap<Long, Level> side) {
        List<BookLevel> levels = new ArrayList<>(side.size());
        side.forEach((price, level) -> levels.add(new BookLevel(price, level.quantity, level.orders)));
        return levels;
    }

    private static BookLevel best(NavigableMap<Long, Level> side) {
        var best = side.firstEntry();
        return best == null ? null : new BookLevel(best.getKey(), best.getValue().quantity, best.getValue().orders);
    }
}
