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
        final boolean postOnly;
        final long display;            // 0: fully visible
        long remaining;
        long visible;

        Resting(long id, Side side, long price, long remaining, boolean postOnly, long display) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.remaining = remaining;
            this.postOnly = postOnly;
            this.display = display;
            this.visible = display == 0 ? remaining : Math.min(display, remaining);
        }
    }

    private record Stop(long id, Side side, long stop, long limit, long quantity) {
    }

    private final Instrument instrument;
    private final List<Resting> resting = new ArrayList<>();
    private final List<Stop> stops = new ArrayList<>();        // arrival order
    private long lastTrade;
    private final List<Long> commandTrades = new ArrayList<>();
    private long lastOrderId;
    private long lastSequence;

    ReferenceEngine(Instrument instrument) {
        this.instrument = instrument;
    }

    List<Event> process(Command command) {
        List<Event> events = new ArrayList<>();
        commandTrades.clear();
        switch (command) {
            case CancelOrder cancel -> cancel(cancel.orderId(), events);
            case LimitOrder order -> newOrder(order.side(), order.priceTicks(), true, order.quantity(),
                    order.timeInForce(), order.postOnly(), order.displayQuantity(), events);
            case MarketOrder order -> newOrder(order.side(), 0, false, order.quantity(),
                    order.timeInForce(), false, 0, events);
            case ReplaceOrder replace -> replace(replace, events);
            case StopOrder stop -> stop(stop, events);
        }
        release(events);
        return events;
    }

    private boolean triggers(Stop stop, long price) {
        return stop.side() == Side.BUY ? price >= stop.stop() : price <= stop.stop();
    }

    private void stop(StopOrder order, List<Event> events) {
        long id = ++lastOrderId;
        RejectReason reason = validate(order.stopPriceTicks(), true, order.quantity());
        if (reason == null && order.limitPriceTicks() != 0) {
            reason = validate(order.limitPriceTicks(), true, order.quantity());
        }
        Stop stop = new Stop(id, order.side(), order.stopPriceTicks(), order.limitPriceTicks(), order.quantity());
        if (reason == null && lastTrade != 0 && triggers(stop, lastTrade)) {
            reason = RejectReason.STOP_PRICE_ALREADY_REACHED;
        }
        if (reason != null) {
            events.add(new OrderRejected(++lastSequence, id, reason));
            return;
        }
        events.add(new OrderAccepted(++lastSequence, id));
        stops.add(stop);
        events.add(new OrderParked(++lastSequence, id, stop.side(), stop.stop(), stop.quantity()));
    }

    private void release(List<Event> events) {
        while (true) {
            Stop next = null;
            for (Stop stop : stops) {                            // arrival order is order ID order
                if (commandTrades.stream().anyMatch(price -> triggers(stop, price))) {
                    next = stop;
                    break;
                }
            }
            if (next == null) {
                return;
            }
            stops.remove(next);
            events.add(new StopTriggered(++lastSequence, next.id()));
            boolean isLimit = next.limit() != 0;
            complete(next.id(), next.side(), next.limit(), isLimit, next.quantity(),
                    isLimit ? TimeInForce.GTC : TimeInForce.IOC, false, 0, events);
        }
    }

    private Resting find(long orderId) {
        return resting.stream().filter(o -> o.id == orderId).findFirst().orElse(null);
    }

    private boolean crosses(Resting candidate, Side side, long price, boolean isLimit) {
        return candidate.side != side
                && (!isLimit || (side == Side.BUY ? candidate.price <= price : candidate.price >= price));
    }

    private RejectReason validate(long price, boolean isLimit, long quantity) {
        if (quantity <= 0) {
            return RejectReason.INVALID_QUANTITY;
        } else if (quantity > instrument.maxOrderQuantity()) {
            return RejectReason.QUANTITY_ABOVE_MAXIMUM;
        } else if (isLimit && price <= 0) {
            return RejectReason.INVALID_PRICE;
        } else if (isLimit && price > instrument.maxPriceTicks()) {
            return RejectReason.PRICE_ABOVE_MAXIMUM;
        }
        return null;
    }

    private void replace(ReplaceOrder replace, List<Event> events) {
        Resting order = find(replace.orderId());
        RejectReason reason;
        if (order == null) {
            reason = RejectReason.UNKNOWN_ORDER;
        } else {
            reason = validate(replace.priceTicks(), true, replace.quantity());
            if (reason == null && order.postOnly
                    && resting.stream().anyMatch(o -> crosses(o, order.side, replace.priceTicks(), true))) {
                reason = RejectReason.POST_ONLY_WOULD_TRADE;
            }
        }
        if (reason != null) {
            events.add(new ReplaceRejected(++lastSequence, replace.orderId(), reason));
            return;
        }
        boolean kept = replace.priceTicks() == order.price && replace.quantity() <= order.remaining;
        events.add(new OrderReplaced(++lastSequence, order.id, order.side, replace.priceTicks(),
                replace.quantity(), kept));
        if (kept) {
            order.remaining = replace.quantity();
            order.visible = order.display == 0 ? replace.quantity() : Math.min(order.visible, replace.quantity());
            return;
        }
        resting.remove(order);
        long left = fill(order.id, order.side, replace.priceTicks(), true, replace.quantity(), events);
        if (left > 0) {
            rest(new Resting(order.id, order.side, replace.priceTicks(), left, order.postOnly, order.display), events);
        }
    }

    private void cancel(long orderId, List<Event> events) {
        for (Stop stop : stops) {
            if (stop.id() == orderId) {
                stops.remove(stop);
                events.add(new OrderCancelled(++lastSequence, orderId, stop.quantity(), CancelReason.CLIENT_REQUEST));
                return;
            }
        }
        for (Resting order : resting) {
            if (order.id == orderId) {
                resting.remove(order);
                events.add(new OrderCancelled(++lastSequence, orderId, order.remaining, CancelReason.CLIENT_REQUEST));
                return;
            }
        }
        events.add(new CancelRejected(++lastSequence, orderId, RejectReason.UNKNOWN_ORDER));
    }

    private void rest(Resting order, List<Event> events) {
        resting.add(order);
        events.add(new OrderRested(++lastSequence, order.id, order.side, order.price, order.remaining, order.visible));
    }

    private void newOrder(Side side, long price, boolean isLimit, long quantity, TimeInForce tif, boolean postOnly,
            long display, List<Event> events) {
        long id = ++lastOrderId;
        RejectReason reason = validate(price, isLimit, quantity);
        boolean anyCross = resting.stream().anyMatch(o -> crosses(o, side, price, isLimit));
        if (reason == null) {
            reason = attributeFailure(side, isLimit, tif, postOnly, anyCross);
        }
        if (reason == null && isLimit && (display < 0 || display > quantity || (display != 0 && tif != TimeInForce.GTC))) {
            reason = RejectReason.INVALID_DISPLAY_QUANTITY;
        }
        if (reason != null) {
            events.add(new OrderRejected(++lastSequence, id, reason));
            return;
        }
        events.add(new OrderAccepted(++lastSequence, id));
        complete(id, side, price, isLimit, quantity, tif, postOnly, display == quantity ? 0 : display, events);
    }

    private RejectReason attributeFailure(Side side, boolean isLimit, TimeInForce tif, boolean postOnly,
            boolean anyCross) {
        RejectReason reason = null;
        if (isLimit && postOnly && tif != TimeInForce.GTC) {
            reason = RejectReason.INVALID_TIME_IN_FORCE;
        } else if (isLimit && postOnly && anyCross) {
            reason = RejectReason.POST_ONLY_WOULD_TRADE;
        } else if (!isLimit && tif == TimeInForce.GTC) {
            reason = RejectReason.INVALID_TIME_IN_FORCE;
        } else if (!isLimit && resting.stream().noneMatch(o -> o.side != side)) {
            reason = RejectReason.NO_LIQUIDITY;
        }
        return reason;
    }

    private void complete(long id, Side side, long price, boolean isLimit, long quantity, TimeInForce tif,
            boolean postOnly, long display, List<Event> events) {
        if (tif == TimeInForce.FOK) {
            long available = resting.stream().filter(o -> crosses(o, side, price, isLimit))
                    .mapToLong(o -> o.remaining).sum();
            if (available < quantity) {
                events.add(new OrderCancelled(++lastSequence, id, quantity, CancelReason.FILL_OR_KILL));
                return;
            }
        }
        long left = fill(id, side, price, isLimit, quantity, events);
        if (left == 0) {
            return;
        }
        if (!isLimit) {
            events.add(new OrderCancelled(++lastSequence, id, left, CancelReason.UNFILLED_MARKET_REMAINDER));
        } else if (tif == TimeInForce.GTC) {
            rest(new Resting(id, side, price, left, postOnly, display), events);
        } else {
            events.add(new OrderCancelled(++lastSequence, id, left, CancelReason.IMMEDIATE_OR_CANCEL));
        }
    }

    private long fill(long id, Side side, long price, boolean isLimit, long quantity, List<Event> events) {
        long left = quantity;
        while (left > 0) {
            Resting best = null;
            for (Resting candidate : resting) {
                boolean better = best == null
                        || (side == Side.BUY ? candidate.price < best.price : candidate.price > best.price);
                if (crosses(candidate, side, price, isLimit) && better) {
                    best = candidate;
                }
            }
            if (best == null) {
                break;
            }
            long fill = Math.min(left, best.visible);
            left -= fill;
            best.remaining -= fill;
            best.visible -= fill;
            if (best.remaining == 0) {
                resting.remove(best);
            }
            events.add(new TradeExecuted(++lastSequence, id, best.id, side, best.price, fill, left, best.remaining));
            lastTrade = best.price;
            commandTrades.add(best.price);
            if (best.remaining > 0 && best.visible == 0) {
                // a new peak, behind everything else: last in arrival order
                best.visible = Math.min(best.display, best.remaining);
                resting.remove(best);
                resting.add(best);
                events.add(new OrderReplenished(++lastSequence, best.id, best.side, best.price, best.visible));
            }
        }
        return left;
    }
}
