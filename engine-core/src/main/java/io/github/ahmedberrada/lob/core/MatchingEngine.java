package io.github.ahmedberrada.lob.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Price-time priority matching engine for one instrument, as specified in
 * {@code docs/rulebook/continuous-trading.md} and {@code docs/rulebook/order-types.md}.
 *
 * <p>Not thread-safe by contract: an instance is owned by exactly one thread (ADR-0001 §2).
 * Deterministic: the same commands always produce the same events (CT-015).
 */
public final class MatchingEngine {

    private final Instrument instrument;
    private final OrderBook book = new OrderBook();
    private long nextOrderId = 1;
    private long nextSequence = 1;

    public MatchingEngine(Instrument instrument) {
        this.instrument = Objects.requireNonNull(instrument, "instrument");
    }

    /**
     * Rebuilds an engine from a snapshot. The result is indistinguishable from the engine the snapshot
     * was taken from: same queues, same next order ID, same next sequence number.
     *
     * @throws IllegalArgumentException if the snapshot is inconsistent
     */
    public static MatchingEngine restore(EngineSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.nextOrderId() < 1 || snapshot.nextSequence() < 1) {
            throw new IllegalArgumentException("next order ID and next sequence must be positive");
        }
        MatchingEngine engine = new MatchingEngine(snapshot.instrument());
        engine.nextOrderId = snapshot.nextOrderId();
        engine.nextSequence = snapshot.nextSequence();
        for (RestingOrder order : snapshot.restingOrders()) {
            if (order.orderId() < 1 || order.orderId() >= snapshot.nextOrderId()) {
                throw new IllegalArgumentException("order ID out of range: " + order);
            }
            if (engine.book.restingQuantity(order.orderId()) != 0) {
                throw new IllegalArgumentException("duplicate order ID: " + order);
            }
            if (order.remainingQuantity() <= 0 || order.remainingQuantity() > snapshot.instrument().maxOrderQuantity()
                    || order.priceTicks() <= 0 || order.priceTicks() > snapshot.instrument().maxPriceTicks()) {
                throw new IllegalArgumentException("invalid resting order: " + order);
            }
            engine.book.rest(new Order(order.orderId(), order.side(), order.priceTicks(), order.remainingQuantity(),
                    order.postOnly()));
        }
        OrderBook book = engine.book;
        if (book.bestBid().isPresent() && book.bestAsk().isPresent()
                && book.bestBid().getAsLong() >= book.bestAsk().getAsLong()) {
            throw new IllegalArgumentException("snapshot book is crossed");
        }
        return engine;
    }

    /** Captures the complete engine state (ADR-0003). */
    public EngineSnapshot snapshot() {
        return new EngineSnapshot(instrument, nextOrderId, nextSequence, book.restingOrders());
    }

    public Instrument instrument() {
        return instrument;
    }

    /** Read-only access to the resting orders. */
    public OrderBook book() {
        return book;
    }

    /** Applies one command and returns its events, in the order of rulebook CT-012 and OT-008. */
    public List<Event> process(Command command) {
        Objects.requireNonNull(command, "command");
        List<Event> events = new ArrayList<>();
        switch (command) {
            case LimitOrder order -> onLimitOrder(order, events);
            case MarketOrder order -> onMarketOrder(order, events);
            case CancelOrder cancel -> onCancel(cancel, events);
            case ReplaceOrder replace -> onReplace(replace, events);
        }
        return Collections.unmodifiableList(events);
    }

    private void onLimitOrder(LimitOrder order, List<Event> events) {
        long orderId = nextOrderId++;
        RejectReason reason = validateQuantity(order.quantity());
        if (reason == null) {
            reason = validatePrice(order.priceTicks());
        }
        if (reason == null && order.postOnly() && order.timeInForce() != TimeInForce.GTC) {
            reason = RejectReason.INVALID_TIME_IN_FORCE;
        }
        if (reason == null && order.postOnly() && wouldTrade(order.side(), order.priceTicks())) {
            reason = RejectReason.POST_ONLY_WOULD_TRADE;
        }
        if (reason != null) {
            events.add(new OrderRejected(nextSequence++, orderId, reason));
            return;
        }
        events.add(new OrderAccepted(nextSequence++, orderId));

        if (order.timeInForce() == TimeInForce.FOK
                && book.availableQuantity(order.side(), order.priceTicks(), order.quantity()) < order.quantity()) {
            events.add(new OrderCancelled(nextSequence++, orderId, order.quantity(), CancelReason.FILL_OR_KILL));
            return;
        }
        long remaining = match(orderId, order.side(), order.priceTicks(), order.quantity(), events);
        if (remaining == 0) {
            return;
        }
        if (order.timeInForce() == TimeInForce.GTC) {
            book.rest(new Order(orderId, order.side(), order.priceTicks(), remaining, order.postOnly()));
            events.add(new OrderRested(nextSequence++, orderId, order.side(), order.priceTicks(), remaining));
        } else {
            // A FOK order passed its check, so only an IOC order can have a remainder here.
            events.add(new OrderCancelled(nextSequence++, orderId, remaining, CancelReason.IMMEDIATE_OR_CANCEL));
        }
    }

    private void onMarketOrder(MarketOrder order, List<Event> events) {
        long orderId = nextOrderId++;
        RejectReason reason = validateQuantity(order.quantity());
        if (reason == null && order.timeInForce() == TimeInForce.GTC) {
            reason = RejectReason.INVALID_TIME_IN_FORCE;
        }
        if (reason == null && book.bestLevel(order.side().opposite()) == null) {
            reason = RejectReason.NO_LIQUIDITY;
        }
        if (reason != null) {
            events.add(new OrderRejected(nextSequence++, orderId, reason));
            return;
        }
        events.add(new OrderAccepted(nextSequence++, orderId));

        long limit = marketLimit(order.side());
        if (order.timeInForce() == TimeInForce.FOK
                && book.availableQuantity(order.side(), limit, order.quantity()) < order.quantity()) {
            events.add(new OrderCancelled(nextSequence++, orderId, order.quantity(), CancelReason.FILL_OR_KILL));
            return;
        }
        long remaining = match(orderId, order.side(), limit, order.quantity(), events);
        if (remaining > 0) {
            events.add(new OrderCancelled(
                    nextSequence++, orderId, remaining, CancelReason.UNFILLED_MARKET_REMAINDER));
        }
    }

    private void onCancel(CancelOrder cancel, List<Event> events) {
        Order order = book.remove(cancel.orderId());
        if (order == null) {
            events.add(new CancelRejected(nextSequence++, cancel.orderId(), RejectReason.UNKNOWN_ORDER));
        } else {
            events.add(new OrderCancelled(
                    nextSequence++, order.orderId, order.remainingQuantity, CancelReason.CLIENT_REQUEST));
        }
    }

    private void onReplace(ReplaceOrder replace, List<Event> events) {
        Order order = book.find(replace.orderId());
        RejectReason reason = order == null ? RejectReason.UNKNOWN_ORDER : validateQuantity(replace.quantity());
        if (reason == null) {
            reason = validatePrice(replace.priceTicks());
        }
        if (reason == null && order.postOnly && wouldTrade(order.side, replace.priceTicks())) {
            reason = RejectReason.POST_ONLY_WOULD_TRADE;
        }
        if (reason != null) {
            events.add(new ReplaceRejected(nextSequence++, replace.orderId(), reason));
            return;
        }

        boolean keepsPriority = replace.priceTicks() == order.priceTicks
                && replace.quantity() <= order.remainingQuantity;
        events.add(new OrderReplaced(nextSequence++, order.orderId, order.side,
                replace.priceTicks(), replace.quantity(), keepsPriority));
        if (keepsPriority) {
            book.reduce(order, replace.quantity());
            return;
        }
        book.remove(order.orderId);
        long remaining = match(order.orderId, order.side, replace.priceTicks(), replace.quantity(), events);
        if (remaining > 0) {
            book.rest(new Order(order.orderId, order.side, replace.priceTicks(), remaining, order.postOnly));
            events.add(new OrderRested(nextSequence++, order.orderId, order.side, replace.priceTicks(), remaining));
        }
    }

    /** Whether an order at this price would trade with the opposite side right now (OT-004). */
    private boolean wouldTrade(Side side, long priceTicks) {
        PriceLevel best = book.bestLevel(side.opposite());
        return best != null && isWithinLimit(side, priceTicks, best.priceTicks());
    }

    /**
     * Matches a taker against the opposite side, best price then oldest order first (CT-001, CT-002),
     * at the makers' prices (CT-003), while prices satisfy the limit (CT-004).
     *
     * @return the taker's unfilled quantity
     */
    private long match(long takerOrderId, Side takerSide, long limitPriceTicks, long quantity, List<Event> events) {
        Side makerSide = takerSide.opposite();
        long remaining = quantity;
        while (remaining > 0) {
            PriceLevel level = book.bestLevel(makerSide);
            if (level == null || !isWithinLimit(takerSide, limitPriceTicks, level.priceTicks())) {
                break;
            }
            Order maker = level.first();
            long fill = Math.min(remaining, maker.remainingQuantity);
            remaining -= fill;
            book.fillFirst(level, fill);
            events.add(new TradeExecuted(
                    nextSequence++, takerOrderId, maker.orderId, takerSide,
                    level.priceTicks(), fill, remaining, maker.remainingQuantity));
        }
        return remaining;
    }

    private static boolean isWithinLimit(Side takerSide, long limitPriceTicks, long makerPriceTicks) {
        return takerSide == Side.BUY ? makerPriceTicks <= limitPriceTicks : makerPriceTicks >= limitPriceTicks;
    }

    /** A limit that every resting price satisfies, so a market order is bounded by liquidity only. */
    private static long marketLimit(Side takerSide) {
        return takerSide == Side.BUY ? Long.MAX_VALUE : Long.MIN_VALUE;
    }

    private RejectReason validateQuantity(long quantity) {
        if (quantity <= 0) {
            return RejectReason.INVALID_QUANTITY;
        }
        if (quantity > instrument.maxOrderQuantity()) {
            return RejectReason.QUANTITY_ABOVE_MAXIMUM;
        }
        return null;
    }

    private RejectReason validatePrice(long priceTicks) {
        if (priceTicks <= 0) {
            return RejectReason.INVALID_PRICE;
        }
        if (priceTicks > instrument.maxPriceTicks()) {
            return RejectReason.PRICE_ABOVE_MAXIMUM;
        }
        return null;
    }
}
