package io.github.ahmedberrada.lob.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Deliberately naive implementation of the rulebook, used as an oracle (ADR-0002 §7).
 *
 * <p>All resting orders live in one list in arrival order. Every fill scans the whole list for the
 * best-priced crossing order; on equal prices the first one found, which is the oldest, wins. It
 * shares no code with {@link MatchingEngine}, so it cannot share its bugs.
 */
final class ReferenceEngine {

    private static final class Resting {
        final long id;
        final Side side;
        final long price;
        long remaining;

        Resting(long id, Side side, long price, long remaining) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.remaining = remaining;
        }
    }

    private final Instrument instrument;
    private final List<Resting> resting = new ArrayList<>();
    private long lastOrderId;
    private long lastSequence;

    ReferenceEngine(Instrument instrument) {
        this.instrument = instrument;
    }

    List<Event> process(Command command) {
        List<Event> events = new ArrayList<>();
        switch (command) {
            case CancelOrder cancel -> cancel(cancel.orderId(), events);
            case LimitOrder order -> newOrder(order.side(), order.priceTicks(), true, order.quantity(), events);
            case MarketOrder order -> newOrder(order.side(), 0, false, order.quantity(), events);
        }
        return events;
    }

    private void cancel(long orderId, List<Event> events) {
        for (Resting order : resting) {
            if (order.id == orderId) {
                resting.remove(order);
                events.add(new OrderCancelled(++lastSequence, orderId, order.remaining, CancelReason.CLIENT_REQUEST));
                return;
            }
        }
        events.add(new CancelRejected(++lastSequence, orderId, RejectReason.UNKNOWN_ORDER));
    }

    private void newOrder(Side side, long price, boolean isLimit, long quantity, List<Event> events) {
        long id = ++lastOrderId;
        RejectReason reason = null;
        if (quantity <= 0) {
            reason = RejectReason.INVALID_QUANTITY;
        } else if (quantity > instrument.maxOrderQuantity()) {
            reason = RejectReason.QUANTITY_ABOVE_MAXIMUM;
        } else if (isLimit && price <= 0) {
            reason = RejectReason.INVALID_PRICE;
        } else if (isLimit && price > instrument.maxPriceTicks()) {
            reason = RejectReason.PRICE_ABOVE_MAXIMUM;
        } else if (!isLimit && resting.stream().noneMatch(o -> o.side != side)) {
            reason = RejectReason.NO_LIQUIDITY;
        }
        if (reason != null) {
            events.add(new OrderRejected(++lastSequence, id, reason));
            return;
        }
        events.add(new OrderAccepted(++lastSequence, id));

        long left = quantity;
        while (left > 0) {
            Resting best = null;
            for (Resting candidate : resting) {
                boolean crosses = candidate.side != side
                        && (!isLimit || (side == Side.BUY ? candidate.price <= price : candidate.price >= price));
                boolean better = best == null
                        || (side == Side.BUY ? candidate.price < best.price : candidate.price > best.price);
                if (crosses && better) {
                    best = candidate;
                }
            }
            if (best == null) {
                break;
            }
            long fill = Math.min(left, best.remaining);
            left -= fill;
            best.remaining -= fill;
            if (best.remaining == 0) {
                resting.remove(best);
            }
            events.add(new TradeExecuted(++lastSequence, id, best.id, side, best.price, fill, left, best.remaining));
        }

        if (left > 0 && isLimit) {
            resting.add(new Resting(id, side, price, left));
            events.add(new OrderRested(++lastSequence, id, side, price, left));
        } else if (left > 0) {
            events.add(new OrderCancelled(++lastSequence, id, left, CancelReason.UNFILLED_MARKET_REMAINDER));
        }
    }
}
