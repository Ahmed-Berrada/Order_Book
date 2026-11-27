package io.github.ahmedberrada.lob.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Deliberately naive implementation of the rulebook, used as an oracle (ADR-0002 §7).
 *
 * <p>All resting orders live in one list in arrival order. Every fill scans the whole list for the
 * best-priced crossing order; on equal prices the first one found, which is the oldest, wins. It
 * shares no code with {@link MatchingEngine}, so it cannot share its bugs.
 */
final class ReferenceEngine {

    private final class Resting {
        final long id;
        final Side side;
        final long price;
        final boolean postOnly;
        final long display;            // 0: fully visible
        final SelfTradeInstruction stp;
        boolean day;
        final long member = takerMember;
        long remaining;
        long visible;

        Resting(long id, Side side, long price, long remaining, boolean postOnly, long display,
                SelfTradeInstruction stp) {
            this.stp = stp;
            this.id = id;
            this.side = side;
            this.price = price;
            this.remaining = remaining;
            this.postOnly = postOnly;
            this.display = display;
            this.visible = display == 0 ? remaining : Math.min(display, remaining);
        }
    }

    private record Stop(long id, Side side, long stop, long limit, long quantity, SelfTradeInstruction stp,
            long member) {
    }

    private final Instrument instrument;
    private final List<Resting> resting = new ArrayList<>();
    private final List<Stop> stops = new ArrayList<>();        // arrival order
    private long lastTrade;
    private final List<Long> commandTrades = new ArrayList<>();
    private long lastOrderId;
    private long lastSequence;
    private boolean stoppedBySelfTrade;
    private TradingPhase phase = TradingPhase.CONTINUOUS;
    private long reference;
    private boolean breached;
    private long breachPrice;
    private boolean pending;
    private long actor;                // who sent the command
    private long takerMember;          // whose order is entering, which differs for a released stop
    private final java.util.Map<Long, MemberLimits> limits = new java.util.HashMap<>();
    private final java.util.Set<Long> suspended = new java.util.HashSet<>();
    private final java.util.Map<Long, Long> net = new java.util.HashMap<>();

    long netPosition(long member) {
        return net.getOrDefault(member, 0L);
    }

    ReferenceEngine(Instrument instrument) {
        this.instrument = instrument;
    }

    List<Event> process(Command command) {
        return process(command, 0);
    }

    List<Event> process(Command command, long member) {
        actor = member;
        takerMember = member;
        List<Event> events = new ArrayList<>();
        commandTrades.clear();
        switch (command) {
            case CancelOrder cancel -> cancel(cancel.orderId(), events);
            case LimitOrder order -> newOrder(order.side(), order.priceTicks(), true, order.quantity(),
                    order.timeInForce(), order.postOnly(), order.displayQuantity(), order.selfTrade(), events);
            case MarketOrder order -> newOrder(order.side(), 0, false, order.quantity(),
                    order.timeInForce(), false, 0, order.selfTrade(), events);
            case ReplaceOrder replace -> replace(replace, events);
            case StopOrder stop -> stop(stop, events);
            case SetPhase setPhase -> setPhase(setPhase.phase(), events);
            case SetMemberLimits set -> setLimits(set, events);
            case SuspendMember suspend -> suspend(suspend.memberId(), events);
            case ResumeMember resume -> resume(resume.memberId(), events);
        }
        if (phase == TradingPhase.CONTINUOUS && !pending) {
            release(events);
        }
        if (pending) {
            pending = false;
            events.add(new VolatilityInterruption(++lastSequence, reference, breachPrice));
            changePhase(TradingPhase.CALL, events);
        }
        return events;
    }

    private boolean triggers(Stop stop, long price) {
        return stop.side() == Side.BUY ? price >= stop.stop() : price <= stop.stop();
    }

    private void stop(StopOrder order, List<Event> events) {
        long id = ++lastOrderId;
        RejectReason reason = phase == TradingPhase.CLOSED ? RejectReason.NOT_ALLOWED_IN_PHASE : null;
        if (reason == null) {
            reason = validate(order.stopPriceTicks(), true, order.quantity());
        }
        if (reason == null && order.limitPriceTicks() != 0) {
            reason = validate(order.limitPriceTicks(), true, order.quantity());
        }
        if (reason == null && order.selfTrade().group() < 0) {
            reason = RejectReason.INVALID_SELF_TRADE_GROUP;
        }
        Stop stop = new Stop(id, order.side(), order.stopPriceTicks(), order.limitPriceTicks(), order.quantity(),
                order.selfTrade(), actor);
        if (reason == null && lastTrade != 0 && triggers(stop, lastTrade)) {
            reason = RejectReason.STOP_PRICE_ALREADY_REACHED;
        }
        if (reason == null) {
            boolean isLimit = order.limitPriceTicks() != 0;
            reason = risk(order.side(), isLimit ? order.limitPriceTicks() : order.stopPriceTicks(),
                    isLimit ? order.limitPriceTicks() : 0, order.quantity(), 0, false);
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
        while (!pending) {
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
            takerMember = next.member();
            events.add(new StopTriggered(++lastSequence, next.id()));
            boolean isLimit = next.limit() != 0;
            complete(next.id(), next.side(), next.limit(), isLimit, next.quantity(),
                    isLimit ? TimeInForce.GTC : TimeInForce.IOC, false, 0, next.stp(), events);
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
        } else if (isLimit && price % instrument.stepAt(price) != 0) {
            return RejectReason.INVALID_TICK_SIZE;
        }
        return null;
    }

    private void replace(ReplaceOrder replace, List<Event> events) {
        Resting order = find(replace.orderId());
        RejectReason reason;
        if (phase == TradingPhase.CLOSED) {
            reason = RejectReason.NOT_ALLOWED_IN_PHASE;
        } else if (order == null || (actor != 0 && order.member != actor)) {
            reason = RejectReason.UNKNOWN_ORDER;
        } else {
            reason = validate(replace.priceTicks(), true, replace.quantity());
            if (reason == null && order.postOnly
                    && resting.stream().anyMatch(o -> crosses(o, order.side, replace.priceTicks(), true))) {
                reason = RejectReason.POST_ONLY_WOULD_TRADE;
            }
            if (reason == null) {
                reason = risk(order.side, replace.priceTicks(), replace.priceTicks(), replace.quantity(),
                        order.remaining, true);
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
        takerMember = order.member;
        if (phase == TradingPhase.CALL) {
            Resting entering = new Resting(order.id, order.side, replace.priceTicks(), replace.quantity(),
                    order.postOnly, order.display, order.stp);
            entering.day = order.day;
            enterCall(entering, events);
            return;
        }
        long left = fill(order.id, order.side, replace.priceTicks(), true, replace.quantity(), order.stp, events);
        if (stoppedBySelfTrade) {
            events.add(new OrderCancelled(++lastSequence, order.id, left, CancelReason.SELF_TRADE_PREVENTION));
        } else if (left > 0) {
            Resting remainder = new Resting(order.id, order.side, replace.priceTicks(), left, order.postOnly,
                    order.display, order.stp);
            remainder.day = order.day;
            if (breached) {
                enterCall(remainder, events);
            } else {
                rest(remainder, events);
            }
        }
    }

    private void cancel(long orderId, List<Event> events) {
        for (Stop stop : stops) {
            if (stop.id() == orderId && (actor == 0 || stop.member() == actor)) {
                stops.remove(stop);
                events.add(new OrderCancelled(++lastSequence, orderId, stop.quantity(), CancelReason.CLIENT_REQUEST));
                return;
            }
        }
        for (Resting order : resting) {
            if (order.id == orderId && (actor == 0 || order.member == actor)) {
                resting.remove(order);
                events.add(new OrderCancelled(++lastSequence, orderId, order.remaining, CancelReason.CLIENT_REQUEST));
                return;
            }
        }
        events.add(new CancelRejected(++lastSequence, orderId, RejectReason.UNKNOWN_ORDER));
    }

    private RejectReason risk(Side side, long notionalPrice, long collarPrice, long quantity, long replaced,
            boolean replacing) {
        if (actor == 0 || !(limits.containsKey(actor) || suspended.contains(actor))) {
            return null;
        }
        if (suspended.contains(actor)) {
            return RejectReason.MEMBER_SUSPENDED;
        }
        MemberLimits l = limits.getOrDefault(actor, MemberLimits.NONE);
        if (l.maxOrderQuantity() > 0 && quantity > l.maxOrderQuantity()) {
            return RejectReason.RISK_ORDER_QUANTITY;
        }
        long price = notionalPrice != 0 ? notionalPrice : lastTrade;
        if (l.maxOrderNotional() > 0 && price != 0 && price * quantity > l.maxOrderNotional()) {
            return RejectReason.RISK_ORDER_NOTIONAL;
        }
        if (l.priceCollarBps() > 0 && collarPrice != 0 && lastTrade != 0
                && java.math.BigInteger.valueOf(Math.abs(collarPrice - lastTrade)).multiply(
                        java.math.BigInteger.valueOf(10_000)).compareTo(java.math.BigInteger.valueOf(lastTrade)
                        .multiply(java.math.BigInteger.valueOf(l.priceCollarBps()))) > 0) {
            return RejectReason.RISK_PRICE_COLLAR;
        }
        long open = resting.stream().filter(o -> o.member == actor).count()
                + stops.stream().filter(s -> s.member() == actor).count();
        if (!replacing && l.maxOpenOrders() > 0 && open >= l.maxOpenOrders()) {
            return RejectReason.RISK_OPEN_ORDERS;
        }
        if (l.maxNetPosition() > 0) {
            long openSide = resting.stream().filter(o -> o.member == actor && o.side == side)
                    .mapToLong(o -> o.remaining).sum()
                    + stops.stream().filter(s -> s.member() == actor && s.side() == side)
                    .mapToLong(Stop::quantity).sum() - replaced;
            long position = netPosition(actor);
            long worst = (side == Side.BUY ? position : -position) + openSide + quantity;
            if (worst > l.maxNetPosition()) {
                return RejectReason.RISK_POSITION;
            }
        }
        return null;
    }

    private void setLimits(SetMemberLimits set, List<Event> events) {
        if (set.memberId() <= 0 || !set.limits().isValid()) {
            events.add(new MemberCommandRejected(++lastSequence, set.memberId(), RejectReason.INVALID_MEMBER_LIMITS));
            return;
        }
        limits.put(set.memberId(), set.limits());
        events.add(new MemberLimitsChanged(++lastSequence, set.memberId(), set.limits()));
    }

    private void suspend(long member, List<Event> events) {
        if (member <= 0 || suspended.contains(member)) {
            events.add(new MemberCommandRejected(++lastSequence, member, RejectReason.INVALID_MEMBER));
            return;
        }
        suspended.add(member);
        List<Resting> orders = resting.stream().filter(o -> o.member == member).toList();
        List<Stop> parked = stops.stream().filter(s -> s.member() == member).toList();
        events.add(new MemberSuspended(++lastSequence, member, orders.size() + parked.size()));
        java.util.TreeMap<Long, Event> cancels = new java.util.TreeMap<>();
        orders.forEach(o -> cancels.put(o.id, new OrderCancelled(0, o.id, o.remaining, CancelReason.KILL_SWITCH)));
        parked.forEach(s -> cancels.put(s.id(), new OrderCancelled(0, s.id(), s.quantity(), CancelReason.KILL_SWITCH)));
        resting.removeAll(orders);
        stops.removeAll(parked);
        cancels.values().forEach(c -> {
            OrderCancelled e = (OrderCancelled) c;
            events.add(new OrderCancelled(++lastSequence, e.orderId(), e.cancelledQuantity(), e.reason()));
        });
    }

    private void resume(long member, List<Event> events) {
        if (!suspended.remove(member)) {
            events.add(new MemberCommandRejected(++lastSequence, member, RejectReason.INVALID_MEMBER));
            return;
        }
        events.add(new MemberResumed(++lastSequence, member));
    }

    private void enterCall(Resting order, List<Event> events) {
        List<Resting> conflicts = order.stp.group() == 0 ? List.of() : resting.stream()
                .filter(o -> crosses(o, order.side, order.price, true) && o.stp.group() == order.stp.group())
                .sorted(Comparator.comparingLong((Resting o) -> order.side == Side.BUY ? o.price : -o.price))
                .toList();
        if (!conflicts.isEmpty()) {
            if (order.stp.mode() != SelfTradeMode.CANCEL_NEWEST) {
                for (Resting o : conflicts) {
                    resting.remove(o);
                    events.add(new OrderCancelled(++lastSequence, o.id, o.remaining,
                            CancelReason.SELF_TRADE_PREVENTION));
                }
            }
            if (order.stp.mode() != SelfTradeMode.CANCEL_OLDEST) {
                events.add(new OrderCancelled(++lastSequence, order.id, order.remaining,
                        CancelReason.SELF_TRADE_PREVENTION));
                return;
            }
        }
        order.visible = order.remaining;
        rest(order, events);
    }

    private void setPhase(TradingPhase target, List<Event> events) {
        if (target == phase) {
            events.add(new PhaseChangeRejected(++lastSequence, RejectReason.INVALID_PHASE_TRANSITION));
            return;
        }
        TradingPhase from = phase;
        if (from == TradingPhase.CALL) {
            uncross(events);
        }
        changePhase(target, events);
    }

    private boolean bandOn() {
        return phase == TradingPhase.CONTINUOUS && instrument.volatilityBandBps() > 0 && reference != 0;
    }

    private boolean outside(long price) {
        return bandOn() && Math.abs(price - reference) > reference * instrument.volatilityBandBps() / 10_000;
    }

    private long clamp(Side side, long price, boolean isLimit) {
        long limit = isLimit ? price : side == Side.BUY ? Long.MAX_VALUE : Long.MIN_VALUE;
        if (!bandOn()) {
            return limit;
        }
        long offset = reference * instrument.volatilityBandBps() / 10_000;
        return side == Side.BUY ? Math.min(limit, reference + offset) : Math.max(limit, reference - offset);
    }

    private void changePhase(TradingPhase target, List<Event> events) {
        TradingPhase from = phase;
        phase = target;
        events.add(new PhaseChanged(++lastSequence, from, target));
        if (target == TradingPhase.CLOSED) {
            resting.stream().filter(o -> o.day).sorted(Comparator.comparingLong(o -> o.id)).toList().forEach(o -> {
                resting.remove(o);
                events.add(new OrderCancelled(++lastSequence, o.id, o.remaining, CancelReason.DAY_EXPIRED));
            });
        }
        if (target == TradingPhase.CALL || from == TradingPhase.CALL) {
            resting.stream().filter(o -> o.display != 0).sorted(Comparator.comparingLong(o -> o.id)).toList()
                    .forEach(o -> {
                        long shown = target == TradingPhase.CALL ? o.remaining : Math.min(o.display, o.remaining);
                        if (o.visible != shown) {
                            o.visible = shown;
                            events.add(new OrderReplenished(++lastSequence, o.id, o.side, o.price, shown));
                        }
                    });
        }
    }

    private long buyVolume(long p) {
        return resting.stream().filter(o -> o.side == Side.BUY && o.price >= p).mapToLong(o -> o.remaining).sum();
    }

    private long sellVolume(long p) {
        return resting.stream().filter(o -> o.side == Side.SELL && o.price <= p).mapToLong(o -> o.remaining).sum();
    }

    private void uncross(List<Event> events) {
        TreeSet<Long> prices = new TreeSet<>();
        resting.forEach(o -> prices.add(o.price));
        long best = prices.stream().mapToLong(p -> Math.min(buyVolume(p), sellVolume(p))).max().orElse(0);
        if (best == 0) {
            return;
        }
        List<Long> tied = prices.stream().filter(p -> Math.min(buyVolume(p), sellVolume(p)) == best).toList();
        long minSurplus = tied.stream().mapToLong(p -> Math.abs(buyVolume(p) - sellVolume(p))).min().getAsLong();
        List<Long> finalists = tied.stream()
                .filter(p -> Math.abs(buyVolume(p) - sellVolume(p)) == minSurplus).toList();
        long price;
        if (finalists.stream().allMatch(p -> buyVolume(p) > sellVolume(p))) {
            price = finalists.stream().mapToLong(p -> p).max().getAsLong();
        } else if (finalists.stream().allMatch(p -> buyVolume(p) < sellVolume(p))) {
            price = finalists.stream().mapToLong(p -> p).min().getAsLong();
        } else if (lastTrade == 0) {
            price = finalists.stream().mapToLong(p -> p).max().getAsLong();
        } else {
            price = finalists.stream().min(Comparator.comparingLong((Long p) -> Math.abs(p - lastTrade))
                    .thenComparing(Comparator.reverseOrder())).get();
        }
        events.add(new AuctionPriceDetermined(++lastSequence, price, best));
        List<Resting> buys = resting.stream().filter(o -> o.side == Side.BUY && o.price >= price)
                .sorted(Comparator.comparingLong((Resting o) -> -o.price)).toList();   // stable: arrival order
        List<Resting> sells = resting.stream().filter(o -> o.side == Side.SELL && o.price <= price)
                .sorted(Comparator.comparingLong((Resting o) -> o.price)).toList();
        int b = 0;
        int s = 0;
        long left = best;
        while (left > 0) {
            Resting buy = buys.get(b);
            Resting sell = sells.get(s);
            long fill = Math.min(left, Math.min(buy.remaining, sell.remaining));
            buy.remaining -= fill;
            sell.remaining -= fill;
            buy.visible = buy.remaining;
            sell.visible = sell.remaining;
            left -= fill;
            Resting taker = buy.id > sell.id ? buy : sell;
            Resting maker = taker == buy ? sell : buy;
            events.add(new TradeExecuted(++lastSequence, taker.id, maker.id, taker.side, price, fill,
                    taker.remaining, maker.remaining, taker.member, maker.member));
            long signed = taker.side == Side.BUY ? fill : -fill;
            net.merge(taker.member, signed, Long::sum);
            net.merge(maker.member, -signed, Long::sum);
            if (buy.remaining == 0) {
                resting.remove(buy);
                b++;
            }
            if (sell.remaining == 0) {
                resting.remove(sell);
                s++;
            }
        }
        lastTrade = price;
        reference = price;
        commandTrades.add(price);
    }

    private void rest(Resting order, List<Event> events) {
        resting.add(order);
        events.add(new OrderRested(++lastSequence, order.id, order.side, order.price, order.remaining, order.visible));
    }

    private void newOrder(Side side, long price, boolean isLimit, long quantity, TimeInForce tif, boolean postOnly,
            long display, SelfTradeInstruction stp, List<Event> events) {
        long id = ++lastOrderId;
        RejectReason reason = null;
        if (phase == TradingPhase.CLOSED
                || (phase == TradingPhase.CALL && (!isLimit || !tif.rests() || postOnly))) {
            reason = RejectReason.NOT_ALLOWED_IN_PHASE;
        }
        if (reason == null) {
            reason = validate(price, isLimit, quantity);
        }
        boolean anyCross = resting.stream().anyMatch(o -> crosses(o, side, price, isLimit));
        if (reason == null) {
            reason = attributeFailure(side, isLimit, tif, postOnly, anyCross, stp);
        }
        if (reason == null && isLimit && (display < 0 || display > quantity || (display != 0 && !tif.rests()))) {
            reason = RejectReason.INVALID_DISPLAY_QUANTITY;
        }
        if (reason == null && isLimit && stp.group() < 0) {
            reason = RejectReason.INVALID_SELF_TRADE_GROUP;
        }
        if (reason == null) {
            reason = risk(side, isLimit ? price : 0, isLimit ? price : 0, quantity, 0, false);
        }
        if (reason != null) {
            events.add(new OrderRejected(++lastSequence, id, reason));
            return;
        }
        events.add(new OrderAccepted(++lastSequence, id));
        if (phase == TradingPhase.CALL) {
            Resting entering = new Resting(id, side, price, quantity, false, display == quantity ? 0 : display, stp);
            entering.day = tif == TimeInForce.DAY;
            enterCall(entering, events);
            return;
        }
        complete(id, side, price, isLimit, quantity, tif, postOnly, display == quantity ? 0 : display, stp, events);
    }

    private RejectReason attributeFailure(Side side, boolean isLimit, TimeInForce tif, boolean postOnly,
            boolean anyCross, SelfTradeInstruction stp) {
        RejectReason reason = null;
        if (isLimit && postOnly && !tif.rests()) {
            reason = RejectReason.INVALID_TIME_IN_FORCE;
        } else if (isLimit && postOnly && anyCross) {
            reason = RejectReason.POST_ONLY_WOULD_TRADE;
        } else if (!isLimit && tif.rests()) {
            reason = RejectReason.INVALID_TIME_IN_FORCE;
        } else if (!isLimit && stp.group() < 0) {
            reason = RejectReason.INVALID_SELF_TRADE_GROUP;
        } else if (!isLimit && resting.stream().noneMatch(o -> o.side != side)) {
            reason = RejectReason.NO_LIQUIDITY;
        }
        return reason;
    }

    /**
     * What a fill or kill order may count on (SP-006): everything it crosses, or, if it crosses an order
     * of its own group, only the orders priced strictly better than the best such order.
     */
    private long fillableByFok(Side side, long price, boolean isLimit, SelfTradeInstruction stp) {
        List<Resting> crossing = resting.stream().filter(o -> crosses(o, side, price, isLimit)).toList();
        List<Resting> own = stp.group() > 0
                ? crossing.stream().filter(o -> o.stp.group() == stp.group()).toList() : List.of();
        if (own.isEmpty()) {
            return crossing.stream().mapToLong(o -> o.remaining).sum();
        }
        long cut = own.stream().mapToLong(o -> o.price).reduce(side == Side.BUY ? Math::min : Math::max).getAsLong();
        return crossing.stream().filter(o -> side == Side.BUY ? o.price < cut : o.price > cut)
                .mapToLong(o -> o.remaining).sum();
    }

    private void complete(long id, Side side, long price, boolean isLimit, long quantity, TimeInForce tif,
            boolean postOnly, long display, SelfTradeInstruction stp, List<Event> events) {
        if (tif == TimeInForce.FOK && fillableByFok(side, clamp(side, price, isLimit), true, stp) < quantity) {
            events.add(new OrderCancelled(++lastSequence, id, quantity, CancelReason.FILL_OR_KILL));
            return;
        }
        long left = fill(id, side, price, isLimit, quantity, stp, events);
        if (left == 0) {
            return;
        }
        if (stoppedBySelfTrade) {
            events.add(new OrderCancelled(++lastSequence, id, left, CancelReason.SELF_TRADE_PREVENTION));
        } else if (!isLimit) {
            events.add(new OrderCancelled(++lastSequence, id, left,
                    breached ? CancelReason.VOLATILITY_INTERRUPTION : CancelReason.UNFILLED_MARKET_REMAINDER));
        } else if (tif.rests()) {
            Resting remainder = new Resting(id, side, price, left, postOnly, display, stp);
            remainder.day = tif == TimeInForce.DAY;
            if (breached) {
                enterCall(remainder, events);
            } else {
                rest(remainder, events);
            }
        } else {
            events.add(new OrderCancelled(++lastSequence, id, left,
                    breached ? CancelReason.VOLATILITY_INTERRUPTION : CancelReason.IMMEDIATE_OR_CANCEL));
        }
    }

    private long fill(long id, Side side, long price, boolean isLimit, long quantity, SelfTradeInstruction stp,
            List<Event> events) {
        long left = quantity;
        stoppedBySelfTrade = false;
        breached = false;
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
            if (stp.group() > 0 && best.stp.group() == stp.group()) {
                if (stp.mode() != SelfTradeMode.CANCEL_NEWEST) {
                    resting.remove(best);
                    events.add(new OrderCancelled(++lastSequence, best.id, best.remaining,
                            CancelReason.SELF_TRADE_PREVENTION));
                }
                if (stp.mode() != SelfTradeMode.CANCEL_OLDEST) {
                    stoppedBySelfTrade = true;
                    break;
                }
                continue;
            }
            if (outside(best.price)) {
                breached = true;
                breachPrice = best.price;
                pending = true;
                break;
            }
            long fill = Math.min(left, best.visible);
            left -= fill;
            best.remaining -= fill;
            best.visible -= fill;
            if (best.remaining == 0) {
                resting.remove(best);
            }
            events.add(new TradeExecuted(++lastSequence, id, best.id, side, best.price, fill, left, best.remaining,
                    takerMember, best.member));
            long signed = side == Side.BUY ? fill : -fill;
            net.merge(takerMember, signed, Long::sum);
            net.merge(best.member, -signed, Long::sum);
            lastTrade = best.price;
            if (reference == 0) {
                reference = lastTrade;
            }
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
