package io.github.ahmedberrada.lob.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

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
    /** Whether the last {@link #match} stopped because of self-trade prevention (SP-002, SP-004). */
    private boolean selfTradeStopped;
    /** Price of the most recent trade, 0 before the first one. */
    private long lastTradePrice;
    /** Stop orders waiting for their trigger, by order ID, hence in arrival order (ST-005). */
    private final TreeMap<Long, ParkedStop> parkedStops = new TreeMap<>();
    /** Lowest and highest trade prices of the command being processed, cascades included (ST-004). */
    private long commandLowTrade;
    private long commandHighTrade;

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
                    || order.priceTicks() <= 0 || order.priceTicks() > snapshot.instrument().maxPriceTicks()
                    || !hasValidPeak(order) || order.selfTrade().group() < 0) {
                throw new IllegalArgumentException("invalid resting order: " + order);
            }
            Order restored = new Order(order.orderId(), order.side(), order.priceTicks(), order.remainingQuantity(),
                    order.postOnly(), order.displayQuantity(), order.selfTrade());
            restored.displayed = order.displayedQuantity();
            engine.book.rest(restored);
        }
        OrderBook book = engine.book;
        if (book.bestBid().isPresent() && book.bestAsk().isPresent()
                && book.bestBid().getAsLong() >= book.bestAsk().getAsLong()) {
            throw new IllegalArgumentException("snapshot book is crossed");
        }
        Instrument instrument = snapshot.instrument();
        if (snapshot.lastTradePriceTicks() < 0 || snapshot.lastTradePriceTicks() > instrument.maxPriceTicks()) {
            throw new IllegalArgumentException("invalid last trade price: " + snapshot.lastTradePriceTicks());
        }
        engine.lastTradePrice = snapshot.lastTradePriceTicks();
        for (ParkedStop stop : snapshot.parkedStops()) {
            boolean valid = stop.orderId() >= 1 && stop.orderId() < snapshot.nextOrderId()
                    && book.restingQuantity(stop.orderId()) == 0 && !engine.parkedStops.containsKey(stop.orderId())
                    && stop.quantity() > 0 && stop.quantity() <= instrument.maxOrderQuantity()
                    && stop.stopPriceTicks() > 0 && stop.stopPriceTicks() <= instrument.maxPriceTicks()
                    && stop.limitPriceTicks() >= 0 && stop.limitPriceTicks() <= instrument.maxPriceTicks()
                    && stop.selfTrade().group() >= 0;
            if (!valid) {
                throw new IllegalArgumentException("invalid parked stop: " + stop);
            }
            engine.parkedStops.put(stop.orderId(), stop);
        }
        return engine;
    }

    /** A fully visible order shows everything; an iceberg a peak between 1 and its display size. */
    private static boolean hasValidPeak(RestingOrder order) {
        if (order.displayQuantity() == 0) {
            return order.displayedQuantity() == order.remainingQuantity();
        }
        // a negative display size fails here too: no positive peak is below it
        return order.displayedQuantity() > 0
                && order.displayedQuantity() <= Math.min(order.displayQuantity(), order.remainingQuantity());
    }

    /** Captures the complete engine state (ADR-0003). */
    public EngineSnapshot snapshot() {
        return new EngineSnapshot(instrument, nextOrderId, nextSequence, book.restingOrders(), lastTradePrice,
                List.copyOf(parkedStops.values()));
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
        commandLowTrade = Long.MAX_VALUE;
        commandHighTrade = Long.MIN_VALUE;
        switch (command) {
            case LimitOrder order -> onLimitOrder(order, events);
            case MarketOrder order -> onMarketOrder(order, events);
            case CancelOrder cancel -> onCancel(cancel, events);
            case ReplaceOrder replace -> onReplace(replace, events);
            case StopOrder stop -> onStop(stop, events);
        }
        releaseTriggeredStops(events);
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
        if (reason == null && (order.displayQuantity() < 0 || order.displayQuantity() > order.quantity()
                || (order.displayQuantity() != 0 && order.timeInForce() != TimeInForce.GTC))) {
            reason = RejectReason.INVALID_DISPLAY_QUANTITY;
        }
        if (reason == null && order.selfTrade().group() < 0) {
            reason = RejectReason.INVALID_SELF_TRADE_GROUP;
        }
        if (reason != null) {
            events.add(new OrderRejected(nextSequence++, orderId, reason));
            return;
        }
        events.add(new OrderAccepted(nextSequence++, orderId));

        if (order.timeInForce() == TimeInForce.FOK
                && book.availableQuantity(order.side(), order.priceTicks(), order.quantity(),
                        order.selfTrade().group()) < order.quantity()) {
            events.add(new OrderCancelled(nextSequence++, orderId, order.quantity(), CancelReason.FILL_OR_KILL));
            return;
        }
        long remaining = match(orderId, order.side(), order.priceTicks(), order.quantity(), order.selfTrade(), events);
        if (selfTradeStopped) {
            cancelForSelfTrade(orderId, remaining, events);
            return;
        }
        if (remaining == 0) {
            return;
        }
        if (order.timeInForce() == TimeInForce.GTC) {
            // a display quantity equal to the quantity means fully visible (IC-001)
            long display = order.displayQuantity() == order.quantity() ? 0 : order.displayQuantity();
            rest(new Order(orderId, order.side(), order.priceTicks(), remaining, order.postOnly(), display,
                    order.selfTrade()), events);
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
        if (reason == null && order.selfTrade().group() < 0) {
            reason = RejectReason.INVALID_SELF_TRADE_GROUP;
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
                && book.availableQuantity(order.side(), limit, order.quantity(), order.selfTrade().group())
                        < order.quantity()) {
            events.add(new OrderCancelled(nextSequence++, orderId, order.quantity(), CancelReason.FILL_OR_KILL));
            return;
        }
        long remaining = match(orderId, order.side(), limit, order.quantity(), order.selfTrade(), events);
        if (selfTradeStopped) {
            cancelForSelfTrade(orderId, remaining, events);
        } else if (remaining > 0) {
            events.add(new OrderCancelled(
                    nextSequence++, orderId, remaining, CancelReason.UNFILLED_MARKET_REMAINDER));
        }
    }

    private void onCancel(CancelOrder cancel, List<Event> events) {
        Order order = book.remove(cancel.orderId());
        ParkedStop stop = order == null ? parkedStops.remove(cancel.orderId()) : null;
        if (stop != null) {
            events.add(new OrderCancelled(nextSequence++, stop.orderId(), stop.quantity(), CancelReason.CLIENT_REQUEST));
        } else if (order == null) {
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
        long remaining = match(order.orderId, order.side, replace.priceTicks(), replace.quantity(), order.selfTrade,
                events);
        if (selfTradeStopped) {
            cancelForSelfTrade(order.orderId, remaining, events);
        } else if (remaining > 0) {
            rest(new Order(order.orderId, order.side, replace.priceTicks(), remaining, order.postOnly,
                    order.displayQuantity, order.selfTrade), events);
        }
    }

    private void rest(Order order, List<Event> events) {
        book.rest(order);
        events.add(new OrderRested(nextSequence++, order.orderId, order.side, order.priceTicks,
                order.remainingQuantity, order.displayed));
    }

    private void onStop(StopOrder stop, List<Event> events) {
        long orderId = nextOrderId++;
        RejectReason reason = validateQuantity(stop.quantity());
        if (reason == null) {
            reason = validatePrice(stop.stopPriceTicks());
        }
        if (reason == null && stop.limitPriceTicks() != 0) {
            reason = validatePrice(stop.limitPriceTicks());
        }
        if (reason == null && stop.selfTrade().group() < 0) {
            reason = RejectReason.INVALID_SELF_TRADE_GROUP;
        }
        ParkedStop parked = new ParkedStop(
                orderId, stop.side(), stop.stopPriceTicks(), stop.limitPriceTicks(), stop.quantity(),
                stop.selfTrade());
        if (reason == null && lastTradePrice != 0 && parked.isTriggeredBy(lastTradePrice)) {
            reason = RejectReason.STOP_PRICE_ALREADY_REACHED;
        }
        if (reason != null) {
            events.add(new OrderRejected(nextSequence++, orderId, reason));
            return;
        }
        events.add(new OrderAccepted(nextSequence++, orderId));
        parkedStops.put(orderId, parked);
        events.add(new OrderParked(nextSequence++, orderId, stop.side(), stop.stopPriceTicks(), stop.quantity()));
    }

    /**
     * Releases every stop the command's trades triggered, lowest order ID first, each processed in full
     * before the next; their own trades may trigger more (ST-005, ST-006).
     */
    private void releaseTriggeredStops(List<Event> events) {
        ParkedStop next;
        while ((next = nextTriggered()) != null) {
            parkedStops.remove(next.orderId());
            events.add(new StopTriggered(nextSequence++, next.orderId()));
            boolean isLimit = next.limitPriceTicks() != 0;
            long limit = isLimit ? next.limitPriceTicks() : marketLimit(next.side());
            long remaining = match(next.orderId(), next.side(), limit, next.quantity(), next.selfTrade(), events);
            if (selfTradeStopped) {
                cancelForSelfTrade(next.orderId(), remaining, events);
            } else if (remaining > 0 && isLimit) {
                rest(new Order(next.orderId(), next.side(), limit, remaining, false, 0, next.selfTrade()), events);
            } else if (remaining > 0) {
                events.add(new OrderCancelled(
                        nextSequence++, next.orderId(), remaining, CancelReason.UNFILLED_MARKET_REMAINDER));
            }
        }
    }

    private ParkedStop nextTriggered() {
        if (commandHighTrade == Long.MIN_VALUE) {
            return null;                                         // no trade in this command
        }
        for (ParkedStop stop : parkedStops.values()) {
            if (stop.isTriggeredBy(stop.side() == Side.BUY ? commandHighTrade : commandLowTrade)) {
                return stop;
            }
        }
        return null;
    }

    /** Whether an order at this price would trade with the opposite side right now (OT-004). */
    private boolean wouldTrade(Side side, long priceTicks) {
        PriceLevel best = book.bestLevel(side.opposite());
        return best != null && isWithinLimit(side, priceTicks, best.priceTicks());
    }

    /**
     * Matches a taker against the opposite side, best price then oldest order first (CT-001, CT-002),
     * at the makers' prices (CT-003), while prices satisfy the limit (CT-004). A resting order of the
     * taker's own self-trade group is never traded with (SP-001): it is cancelled, or matching stops,
     * according to the mode, and {@link #selfTradeStopped} tells which.
     *
     * @return the taker's unfilled quantity
     */
    private long match(long takerOrderId, Side takerSide, long limitPriceTicks, long quantity,
            SelfTradeInstruction selfTrade, List<Event> events) {
        Side makerSide = takerSide.opposite();
        long remaining = quantity;
        selfTradeStopped = false;
        while (remaining > 0) {
            PriceLevel level = book.bestLevel(makerSide);
            if (level == null || !isWithinLimit(takerSide, limitPriceTicks, level.priceTicks())) {
                break;
            }
            Order maker = level.first();
            if (selfTrade.conflictsWith(maker.selfTrade)) {
                if (selfTrade.mode() != SelfTradeMode.CANCEL_NEWEST) {                // SP-003, SP-004
                    book.remove(maker.orderId);
                    events.add(new OrderCancelled(nextSequence++, maker.orderId, maker.remainingQuantity,
                            CancelReason.SELF_TRADE_PREVENTION));
                }
                if (selfTrade.mode() != SelfTradeMode.CANCEL_OLDEST) {                // SP-002, SP-004
                    selfTradeStopped = true;
                    break;
                }
                continue;
            }
            long fill = Math.min(remaining, maker.displayed);           // never into hidden quantity (IC-003)
            remaining -= fill;
            boolean replenished = book.fillFirst(level, fill);
            lastTradePrice = level.priceTicks();
            commandLowTrade = Math.min(commandLowTrade, lastTradePrice);
            commandHighTrade = Math.max(commandHighTrade, lastTradePrice);
            events.add(new TradeExecuted(
                    nextSequence++, takerOrderId, maker.orderId, takerSide,
                    level.priceTicks(), fill, remaining, maker.remainingQuantity));
            if (replenished) {
                events.add(new OrderReplenished(
                        nextSequence++, maker.orderId, maker.side, maker.priceTicks, maker.displayed));
            }
        }
        return remaining;
    }

    /** Cancels an incoming order's remainder because it met an order of its own group (SP-002). */
    private void cancelForSelfTrade(long orderId, long remaining, List<Event> events) {
        events.add(new OrderCancelled(nextSequence++, orderId, remaining, CancelReason.SELF_TRADE_PREVENTION));
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
