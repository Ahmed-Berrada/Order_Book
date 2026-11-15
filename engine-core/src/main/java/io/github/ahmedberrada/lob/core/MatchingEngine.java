package io.github.ahmedberrada.lob.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Price-time priority matching engine for one instrument, as specified in
 * {@code docs/rulebook/continuous-trading.md}.
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

    public Instrument instrument() {
        return instrument;
    }

    /** Read-only access to the resting orders. */
    public OrderBook book() {
        return book;
    }

    /** Applies one command and returns its events, in the order of rulebook CT-012. */
    public List<Event> process(Command command) {
        Objects.requireNonNull(command, "command");
        List<Event> events = new ArrayList<>();
        switch (command) {
            case LimitOrder order -> onLimitOrder(order, events);
            case MarketOrder order -> onMarketOrder(order, events);
            case CancelOrder cancel -> onCancel(cancel, events);
        }
        return Collections.unmodifiableList(events);
    }

    private void onLimitOrder(LimitOrder order, List<Event> events) {
        long orderId = nextOrderId++;
        RejectReason reason = validateQuantity(order.quantity());
        if (reason == null) {
            reason = validatePrice(order.priceTicks());
        }
        if (reason != null) {
            events.add(new OrderRejected(nextSequence++, orderId, reason));
            return;
        }
        events.add(new OrderAccepted(nextSequence++, orderId));

        long remaining = match(orderId, order.side(), order.priceTicks(), order.quantity(), events);
        if (remaining > 0) {
            book.rest(new Order(orderId, order.side(), order.priceTicks(), remaining));
            events.add(new OrderRested(nextSequence++, orderId, order.side(), order.priceTicks(), remaining));
        }
    }

    private void onMarketOrder(MarketOrder order, List<Event> events) {
        long orderId = nextOrderId++;
        RejectReason reason = validateQuantity(order.quantity());
        if (reason == null && book.bestLevel(order.side().opposite()) == null) {
            reason = RejectReason.NO_LIQUIDITY;
        }
        if (reason != null) {
            events.add(new OrderRejected(nextSequence++, orderId, reason));
            return;
        }
        events.add(new OrderAccepted(nextSequence++, orderId));

        long remaining = match(orderId, order.side(), marketLimit(order.side()), order.quantity(), events);
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
