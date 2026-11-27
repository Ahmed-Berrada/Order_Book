package io.github.ahmedberrada.lob.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    private TradingPhase phase = TradingPhase.CONTINUOUS;
    /** What the engine keeps per member (MB-004), created on first use. */
    private final TreeMap<Long, MemberRecord> members = new TreeMap<>();
    /** What members have parked as stop orders, for their open-order and position limits (RK-002). */
    private final java.util.Map<Long, Exposure> parkedExposures = new java.util.HashMap<>();
    /** The member the command being processed is for (MB-001). */
    private long currentMember;
    /** The price continuous trades are compared with for volatility, 0 before the first trade (VI-001). */
    private long referencePrice;
    /** Whether the last {@link #match} stopped at the volatility band (VI-002), and where. */
    private boolean breached;
    private long breachPrice;
    /** A breach of the command being processed, to be followed by the move to CALL (VI-003). */
    private boolean interruptionPending;
    /** Lowest and highest trade prices of the command being processed, cascades included (ST-004). */
    private long commandLowTrade;
    private long commandHighTrade;

    /** A member's limits, suspension and net position. */
    private static final class MemberRecord {
        MemberLimits limits = MemberLimits.NONE;
        boolean suspended;
        long netPosition;

        boolean isDefault() {
            return limits.equals(MemberLimits.NONE) && !suspended && netPosition == 0;
        }
    }

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
        engine.phase = snapshot.phase();
        boolean call = snapshot.phase() == TradingPhase.CALL;
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
                    || !hasValidPeak(order, call) || order.selfTrade().group() < 0 || order.memberId() < 0) {
                throw new IllegalArgumentException("invalid resting order: " + order);
            }
            Order restored = new Order(order.orderId(), order.side(), order.priceTicks(), order.remainingQuantity(),
                    order.postOnly(), order.displayQuantity(), order.selfTrade(), order.day(), order.memberId());
            restored.displayed = order.displayedQuantity();
            engine.book.rest(restored);
        }
        OrderBook book = engine.book;
        if (!call && book.bestBid().isPresent() && book.bestAsk().isPresent()
                && book.bestBid().getAsLong() >= book.bestAsk().getAsLong()) {
            throw new IllegalArgumentException("snapshot book is crossed");
        }
        Instrument instrument = snapshot.instrument();
        if (snapshot.lastTradePriceTicks() < 0 || snapshot.lastTradePriceTicks() > instrument.maxPriceTicks()) {
            throw new IllegalArgumentException("invalid last trade price: " + snapshot.lastTradePriceTicks());
        }
        engine.lastTradePrice = snapshot.lastTradePriceTicks();
        if (snapshot.referencePriceTicks() < 0 || snapshot.referencePriceTicks() > instrument.maxPriceTicks()) {
            throw new IllegalArgumentException("invalid reference price: " + snapshot.referencePriceTicks());
        }
        engine.referencePrice = snapshot.referencePriceTicks();
        long previous = -1;
        for (MemberState state : snapshot.members()) {
            if (state.memberId() < 0 || state.memberId() <= previous || !state.limits().isValid()
                    || (state.memberId() == 0 && state.suspended())) {
                throw new IllegalArgumentException("invalid member state: " + state);
            }
            previous = state.memberId();
            MemberRecord record = engine.members.computeIfAbsent(state.memberId(), id -> new MemberRecord());
            record.limits = state.limits();
            record.suspended = state.suspended();
            record.netPosition = state.netPosition();
        }
        for (ParkedStop stop : snapshot.parkedStops()) {
            boolean valid = stop.orderId() >= 1 && stop.orderId() < snapshot.nextOrderId()
                    && book.restingQuantity(stop.orderId()) == 0 && !engine.parkedStops.containsKey(stop.orderId())
                    && stop.quantity() > 0 && stop.quantity() <= instrument.maxOrderQuantity()
                    && stop.stopPriceTicks() > 0 && stop.stopPriceTicks() <= instrument.maxPriceTicks()
                    && stop.limitPriceTicks() >= 0 && stop.limitPriceTicks() <= instrument.maxPriceTicks()
                    && stop.selfTrade().group() >= 0 && stop.memberId() >= 0;
            if (!valid) {
                throw new IllegalArgumentException("invalid parked stop: " + stop);
            }
            engine.parkedStops.put(stop.orderId(), stop);
            engine.parkedExposure(stop.memberId()).add(stop.side(), stop.quantity());
        }
        return engine;
    }

    /** A fully visible order shows everything; an iceberg a peak between 1 and its display size. */
    private static boolean hasValidPeak(RestingOrder order, boolean call) {
        if (call) {
            return order.displayedQuantity() == order.remainingQuantity();   // shown in full (TP-008)
        }
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
                List.copyOf(parkedStops.values()), phase, referencePrice, memberStates());
    }

    private List<MemberState> memberStates() {
        List<MemberState> states = new ArrayList<>();
        members.forEach((id, record) -> {
            if (!record.isDefault()) {
                states.add(new MemberState(id, record.limits, record.suspended, record.netPosition));
            }
        });
        return states;
    }

    /** A member's net position in lots: bought minus sold (RK-003). */
    public long netPosition(long memberId) {
        MemberRecord record = members.get(memberId);
        return record == null ? 0 : record.netPosition;
    }

    private MemberRecord member(long memberId) {
        return members.computeIfAbsent(memberId, id -> new MemberRecord());
    }

    private Exposure parkedExposure(long memberId) {
        return parkedExposures.computeIfAbsent(memberId, id -> new Exposure());
    }

    /** The current trading phase (TP-001). */
    public TradingPhase phase() {
        return phase;
    }

    public Instrument instrument() {
        return instrument;
    }

    /** Read-only access to the resting orders. */
    public OrderBook book() {
        return book;
    }

    /** Applies one command for member 0 and returns its events, in the order of rulebook CT-012 and OT-008. */
    public List<Event> process(Command command) {
        return process(command, 0);
    }

    /**
     * Applies one command on behalf of a member (MB-001).
     *
     * @throws IllegalArgumentException if the member ID is negative
     */
    public List<Event> process(Command command, long memberId) {
        Objects.requireNonNull(command, "command");
        if (memberId < 0) {
            throw new IllegalArgumentException("member ID must not be negative: " + memberId);
        }
        currentMember = memberId;
        List<Event> events = new ArrayList<>();
        commandLowTrade = Long.MAX_VALUE;
        commandHighTrade = Long.MIN_VALUE;
        switch (command) {
            case LimitOrder order -> onLimitOrder(order, events);
            case MarketOrder order -> onMarketOrder(order, events);
            case CancelOrder cancel -> onCancel(cancel, events);
            case ReplaceOrder replace -> onReplace(replace, events);
            case StopOrder stop -> onStop(stop, events);
            case SetPhase setPhase -> onSetPhase(setPhase, events);
            case SetMemberLimits set -> onSetMemberLimits(set, events);
            case SuspendMember suspend -> onSuspend(suspend, events);
            case ResumeMember resume -> onResume(resume, events);
        }
        if (phase == TradingPhase.CONTINUOUS && !interruptionPending) {
            releaseTriggeredStops(events);                       // TP-007
        }
        if (interruptionPending) {                               // VI-003
            interruptionPending = false;
            events.add(new VolatilityInterruption(nextSequence++, referencePrice, breachPrice));
            changePhase(TradingPhase.CALL, events);
        }
        return Collections.unmodifiableList(events);
    }

    private void onLimitOrder(LimitOrder order, List<Event> events) {
        long orderId = nextOrderId++;
        RejectReason reason = phase == TradingPhase.CLOSED
                || (phase == TradingPhase.CALL && (!order.timeInForce().rests() || order.postOnly()))
                ? RejectReason.NOT_ALLOWED_IN_PHASE : null;                                  // TP-004, TP-005
        if (reason == null) {
            reason = validateQuantity(order.quantity());
        }
        if (reason == null) {
            reason = validatePrice(order.priceTicks());
        }
        if (reason == null && order.postOnly() && !order.timeInForce().rests()) {
            reason = RejectReason.INVALID_TIME_IN_FORCE;
        }
        if (reason == null && order.postOnly() && wouldTrade(order.side(), order.priceTicks())) {
            reason = RejectReason.POST_ONLY_WOULD_TRADE;
        }
        if (reason == null && (order.displayQuantity() < 0 || order.displayQuantity() > order.quantity()
                || (order.displayQuantity() != 0 && !order.timeInForce().rests()))) {
            reason = RejectReason.INVALID_DISPLAY_QUANTITY;
        }
        if (reason == null && order.selfTrade().group() < 0) {
            reason = RejectReason.INVALID_SELF_TRADE_GROUP;
        }
        if (reason == null) {
            reason = riskCheck(order.side(), order.priceTicks(), order.priceTicks(), order.quantity(), 0, false);
        }
        if (reason != null) {
            events.add(new OrderRejected(nextSequence++, orderId, reason));
            return;
        }
        events.add(new OrderAccepted(nextSequence++, orderId));
        if (phase == TradingPhase.CALL) {
            long display = order.displayQuantity() == order.quantity() ? 0 : order.displayQuantity();
            enterCall(new Order(orderId, order.side(), order.priceTicks(), order.quantity(), false, display,
                    order.selfTrade(), order.timeInForce() == TimeInForce.DAY, currentMember), events);
            return;
        }

        if (order.timeInForce() == TimeInForce.FOK
                && book.availableQuantity(order.side(), withinBand(order.side(), order.priceTicks()),
                        order.quantity(), order.selfTrade().group()) < order.quantity()) {
            events.add(new OrderCancelled(nextSequence++, orderId, order.quantity(), CancelReason.FILL_OR_KILL));
            return;
        }
        long remaining = match(orderId, currentMember, order.side(), order.priceTicks(), order.quantity(),
                order.selfTrade(), events);
        if (selfTradeStopped) {
            cancelForSelfTrade(orderId, remaining, events);
            return;
        }
        if (remaining == 0) {
            return;
        }
        if (order.timeInForce().rests()) {
            // a display quantity equal to the quantity means fully visible (IC-001)
            long display = order.displayQuantity() == order.quantity() ? 0 : order.displayQuantity();
            Order rested = new Order(orderId, order.side(), order.priceTicks(), remaining, order.postOnly(), display,
                    order.selfTrade(), order.timeInForce() == TimeInForce.DAY, currentMember);
            if (breached) {
                enterCall(rested, events);                                       // VI-003
            } else {
                rest(rested, events);
            }
        } else if (breached) {
            events.add(new OrderCancelled(nextSequence++, orderId, remaining, CancelReason.VOLATILITY_INTERRUPTION));
        } else {
            // A FOK order passed its check, so only an IOC order can have a remainder here.
            events.add(new OrderCancelled(nextSequence++, orderId, remaining, CancelReason.IMMEDIATE_OR_CANCEL));
        }
    }

    private void onMarketOrder(MarketOrder order, List<Event> events) {
        long orderId = nextOrderId++;
        RejectReason reason = phase == TradingPhase.CONTINUOUS ? validateQuantity(order.quantity())
                : RejectReason.NOT_ALLOWED_IN_PHASE;                                         // TP-004, TP-005
        if (reason == null && order.timeInForce().rests()) {
            reason = RejectReason.INVALID_TIME_IN_FORCE;
        }
        if (reason == null && order.selfTrade().group() < 0) {
            reason = RejectReason.INVALID_SELF_TRADE_GROUP;
        }
        if (reason == null && book.bestLevel(order.side().opposite()) == null) {
            reason = RejectReason.NO_LIQUIDITY;
        }
        if (reason == null) {
            reason = riskCheck(order.side(), 0, 0, order.quantity(), 0, false);
        }
        if (reason != null) {
            events.add(new OrderRejected(nextSequence++, orderId, reason));
            return;
        }
        events.add(new OrderAccepted(nextSequence++, orderId));

        long limit = marketLimit(order.side());
        if (order.timeInForce() == TimeInForce.FOK
                && book.availableQuantity(order.side(), withinBand(order.side(), limit), order.quantity(),
                        order.selfTrade().group())
                        < order.quantity()) {
            events.add(new OrderCancelled(nextSequence++, orderId, order.quantity(), CancelReason.FILL_OR_KILL));
            return;
        }
        long remaining = match(orderId, currentMember, order.side(), limit, order.quantity(), order.selfTrade(),
                events);
        if (selfTradeStopped) {
            cancelForSelfTrade(orderId, remaining, events);
        } else if (breached) {
            events.add(new OrderCancelled(nextSequence++, orderId, remaining, CancelReason.VOLATILITY_INTERRUPTION));
        } else if (remaining > 0) {
            events.add(new OrderCancelled(
                    nextSequence++, orderId, remaining, CancelReason.UNFILLED_MARKET_REMAINDER));
        }
    }

    private void onCancel(CancelOrder cancel, List<Event> events) {
        Order resting = book.find(cancel.orderId());
        ParkedStop parked = resting == null ? parkedStops.get(cancel.orderId()) : null;
        long owner = resting != null ? resting.memberId : parked != null ? parked.memberId() : -1;
        if (currentMember != 0 && owner != currentMember) {                  // MB-003: absent or someone else's
            events.add(new CancelRejected(nextSequence++, cancel.orderId(), RejectReason.UNKNOWN_ORDER));
            return;
        }
        Order order = book.remove(cancel.orderId());
        ParkedStop stop = order == null ? removeParked(cancel.orderId()) : null;
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
        RejectReason reason = phase == TradingPhase.CLOSED ? RejectReason.NOT_ALLOWED_IN_PHASE
                : order == null || (currentMember != 0 && order.memberId != currentMember)
                ? RejectReason.UNKNOWN_ORDER : validateQuantity(replace.quantity());       // MB-003
        if (reason == null) {
            reason = validatePrice(replace.priceTicks());
        }
        if (reason == null && order.postOnly && wouldTrade(order.side, replace.priceTicks())) {
            reason = RejectReason.POST_ONLY_WOULD_TRADE;
        }
        if (reason == null) {
            reason = riskCheck(order.side, replace.priceTicks(), replace.priceTicks(), replace.quantity(),
                    order.remainingQuantity, true);
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
        if (phase == TradingPhase.CALL) {                       // TP-006
            enterCall(new Order(order.orderId, order.side, replace.priceTicks(), replace.quantity(), order.postOnly,
                    order.displayQuantity, order.selfTrade, order.day, order.memberId), events);
            return;
        }
        long remaining = match(order.orderId, order.memberId, order.side, replace.priceTicks(), replace.quantity(), order.selfTrade,
                events);
        if (selfTradeStopped) {
            cancelForSelfTrade(order.orderId, remaining, events);
        } else if (remaining > 0) {
            Order rested = new Order(order.orderId, order.side, replace.priceTicks(), remaining, order.postOnly,
                    order.displayQuantity, order.selfTrade, order.day, order.memberId);
            if (breached) {
                enterCall(rested, events);                                       // VI-003
            } else {
                rest(rested, events);
            }
        }
    }

    private void rest(Order order, List<Event> events) {
        book.rest(order);
        events.add(new OrderRested(nextSequence++, order.orderId, order.side, order.priceTicks,
                order.remainingQuantity, order.displayed));
    }

    private void onStop(StopOrder stop, List<Event> events) {
        long orderId = nextOrderId++;
        RejectReason reason = phase == TradingPhase.CLOSED ? RejectReason.NOT_ALLOWED_IN_PHASE
                : validateQuantity(stop.quantity());
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
                stop.selfTrade(), currentMember);
        if (reason == null && lastTradePrice != 0 && parked.isTriggeredBy(lastTradePrice)) {
            reason = RejectReason.STOP_PRICE_ALREADY_REACHED;
        }
        if (reason == null) {
            boolean isLimit = stop.limitPriceTicks() != 0;
            reason = riskCheck(stop.side(), isLimit ? stop.limitPriceTicks() : stop.stopPriceTicks(),
                    isLimit ? stop.limitPriceTicks() : 0, stop.quantity(), 0, false);
        }
        if (reason != null) {
            events.add(new OrderRejected(nextSequence++, orderId, reason));
            return;
        }
        events.add(new OrderAccepted(nextSequence++, orderId));
        parkedStops.put(orderId, parked);
        parkedExposure(currentMember).add(parked.side(), parked.quantity());
        events.add(new OrderParked(nextSequence++, orderId, stop.side(), stop.stopPriceTicks(), stop.quantity()));
    }

    /**
     * An order entering a call phase: self-trade prevention against the orders it crosses (AU-004),
     * then it rests, shown in full, without matching (TP-005, TP-008).
     */
    private void enterCall(Order order, List<Event> events) {
        List<Order> conflicts = book.crossingGroup(order.side, order.priceTicks, order.selfTrade);
        if (!conflicts.isEmpty()) {
            if (order.selfTrade.mode() != SelfTradeMode.CANCEL_NEWEST) {
                for (Order resting : conflicts) {
                    book.remove(resting.orderId);
                    events.add(new OrderCancelled(nextSequence++, resting.orderId, resting.remainingQuantity,
                            CancelReason.SELF_TRADE_PREVENTION));
                }
            }
            if (order.selfTrade.mode() != SelfTradeMode.CANCEL_OLDEST) {
                cancelForSelfTrade(order.orderId, order.remainingQuantity, events);
                return;
            }
        }
        order.displayed = order.remainingQuantity;
        rest(order, events);
    }

    private void onSetPhase(SetPhase setPhase, List<Event> events) {
        if (setPhase.phase() == phase) {
            events.add(new PhaseChangeRejected(nextSequence++, RejectReason.INVALID_PHASE_TRANSITION));
            return;
        }
        TradingPhase from = phase;
        if (from == TradingPhase.CALL) {
            uncross(events);
        }
        changePhase(setPhase.phase(), events);
    }

    /** The events that follow the end of an uncross, if any, up to the new phase's own (TP-003, DY-002). */
    private void changePhase(TradingPhase to, List<Event> events) {
        TradingPhase from = phase;
        phase = to;
        events.add(new PhaseChanged(nextSequence++, from, phase));
        if (phase == TradingPhase.CLOSED) {
            for (Order expired : book.dayOrders()) {                             // DY-002
                book.remove(expired.orderId);
                events.add(new OrderCancelled(
                        nextSequence++, expired.orderId, expired.remainingQuantity, CancelReason.DAY_EXPIRED));
            }
        }
        if (phase == TradingPhase.CALL || from == TradingPhase.CALL) {
            for (Order iceberg : book.icebergs()) {
                long shown = phase == TradingPhase.CALL ? iceberg.remainingQuantity
                        : iceberg.peak(iceberg.remainingQuantity);
                if (iceberg.displayed != shown) {
                    book.show(iceberg, shown);
                    events.add(new OrderReplenished(
                            nextSequence++, iceberg.orderId, iceberg.side, iceberg.priceTicks, shown));
                }
            }
        }
    }

    /** Determines the auction price and trades at it (AU-001 to AU-003, AU-005). */
    private void uncross(List<Event> events) {
        Optional<AuctionPricer.Result> result = AuctionPricer.determine(
                book.depth(Side.BUY), book.depth(Side.SELL), lastTradePrice);
        if (result.isEmpty()) {
            return;
        }
        long price = result.get().priceTicks();
        long left = result.get().volume();
        events.add(new AuctionPriceDetermined(nextSequence++, price, left));
        while (left > 0) {
            PriceLevel buyLevel = book.bestLevel(Side.BUY);
            PriceLevel sellLevel = book.bestLevel(Side.SELL);
            Order buy = buyLevel.first();
            Order sell = sellLevel.first();
            long fill = Math.min(left, Math.min(buy.remainingQuantity, sell.remainingQuantity));
            book.fillFirst(buyLevel, fill);
            book.fillFirst(sellLevel, fill);
            left -= fill;
            Order taker = buy.orderId > sell.orderId ? buy : sell;       // the later order, AU-002
            Order maker = taker == buy ? sell : buy;
            events.add(new TradeExecuted(nextSequence++, taker.orderId, maker.orderId, taker.side, price, fill,
                    taker.remainingQuantity, maker.remainingQuantity, taker.memberId, maker.memberId));
            traded(taker.memberId, taker.side, maker.memberId, fill);
        }
        lastTradePrice = price;
        referencePrice = price;                                                  // VI-001
        commandLowTrade = Math.min(commandLowTrade, price);
        commandHighTrade = Math.max(commandHighTrade, price);
    }

    /**
     * Releases every stop the command's trades triggered, lowest order ID first, each processed in full
     * before the next; their own trades may trigger more (ST-005, ST-006).
     */
    private void releaseTriggeredStops(List<Event> events) {
        ParkedStop next;
        while (!interruptionPending && (next = nextTriggered()) != null) {
            removeParked(next.orderId());
            events.add(new StopTriggered(nextSequence++, next.orderId()));
            boolean isLimit = next.limitPriceTicks() != 0;
            long limit = isLimit ? next.limitPriceTicks() : marketLimit(next.side());
            long remaining = match(next.orderId(), next.memberId(), next.side(), limit, next.quantity(),
                    next.selfTrade(), events);
            if (selfTradeStopped) {
                cancelForSelfTrade(next.orderId(), remaining, events);
            } else if (remaining > 0 && isLimit) {
                Order rested = new Order(next.orderId(), next.side(), limit, remaining, false, 0, next.selfTrade(),
                        false, next.memberId());
                if (breached) {
                    enterCall(rested, events);                                   // VI-003
                } else {
                    rest(rested, events);
                }
            } else if (remaining > 0) {
                events.add(new OrderCancelled(nextSequence++, next.orderId(), remaining,
                        breached ? CancelReason.VOLATILITY_INTERRUPTION : CancelReason.UNFILLED_MARKET_REMAINDER));
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

    /** Each side's net position changes by the quantity (RK-003). */
    private void traded(long takerMember, Side takerSide, long makerMember, long quantity) {
        long signed = takerSide == Side.BUY ? quantity : -quantity;
        member(takerMember).netPosition += signed;
        member(makerMember).netPosition -= signed;
    }

    private ParkedStop removeParked(long orderId) {
        ParkedStop stop = parkedStops.remove(orderId);
        if (stop != null) {
            parkedExposure(stop.memberId()).remove(stop.side(), stop.quantity());
        }
        return stop;
    }

    /** The pre-trade checks of RK-002; {@code null} if the order passes. */
    private RejectReason riskCheck(Side side, long notionalPrice, long collarPrice, long quantity,
            long replacedQuantity, boolean replacing) {
        MemberRecord record = currentMember == 0 ? null : members.get(currentMember);
        if (record == null) {
            return null;
        }
        if (record.suspended) {
            return RejectReason.MEMBER_SUSPENDED;
        }
        MemberLimits limits = record.limits;
        if (limits.maxOrderQuantity() > 0 && quantity > limits.maxOrderQuantity()) {
            return RejectReason.RISK_ORDER_QUANTITY;
        }
        long price = notionalPrice != 0 ? notionalPrice : lastTradePrice;
        if (limits.maxOrderNotional() > 0 && price != 0 && price * quantity > limits.maxOrderNotional()) {
            return RejectReason.RISK_ORDER_NOTIONAL;
        }
        if (limits.priceCollarBps() > 0 && collarPrice != 0 && lastTradePrice != 0
                && exceedsBps(Math.abs(collarPrice - lastTradePrice), lastTradePrice, limits.priceCollarBps())) {
            return RejectReason.RISK_PRICE_COLLAR;
        }
        Exposure resting = book.exposureOf(currentMember);
        Exposure parked = parkedExposures.get(currentMember);
        long openOrders = (resting == null ? 0 : resting.orders) + (parked == null ? 0 : parked.orders);
        if (!replacing && limits.maxOpenOrders() > 0 && openOrders >= limits.maxOpenOrders()) {
            return RejectReason.RISK_OPEN_ORDERS;
        }
        if (limits.maxNetPosition() > 0) {
            boolean buy = side == Side.BUY;
            long open = (resting == null ? 0 : buy ? resting.buyQuantity : resting.sellQuantity)
                    + (parked == null ? 0 : buy ? parked.buyQuantity : parked.sellQuantity) - replacedQuantity;
            long worst = (buy ? record.netPosition : -record.netPosition) + open + quantity;
            if (worst > limits.maxNetPosition()) {
                return RejectReason.RISK_POSITION;
            }
        }
        return null;
    }

    /** Whether {@code distance} is more than {@code bps} basis points of {@code base}, without overflow. */
    private static boolean exceedsBps(long distance, long base, long bps) {
        return java.math.BigInteger.valueOf(distance).multiply(java.math.BigInteger.valueOf(10_000))
                .compareTo(java.math.BigInteger.valueOf(base).multiply(java.math.BigInteger.valueOf(bps))) > 0;
    }

    private void onSetMemberLimits(SetMemberLimits set, List<Event> events) {
        if (set.memberId() <= 0 || !set.limits().isValid()) {
            events.add(new MemberCommandRejected(nextSequence++, set.memberId(), RejectReason.INVALID_MEMBER_LIMITS));
            return;
        }
        member(set.memberId()).limits = set.limits();
        events.add(new MemberLimitsChanged(nextSequence++, set.memberId(), set.limits()));
    }

    private void onSuspend(SuspendMember suspend, List<Event> events) {
        MemberRecord record = members.get(suspend.memberId());
        if (suspend.memberId() <= 0 || (record != null && record.suspended)) {
            events.add(new MemberCommandRejected(nextSequence++, suspend.memberId(), RejectReason.INVALID_MEMBER));
            return;
        }
        member(suspend.memberId()).suspended = true;
        List<Order> resting = book.ordersOf(suspend.memberId());
        List<ParkedStop> stops = parkedStops.values().stream().filter(s -> s.memberId() == suspend.memberId()).toList();
        events.add(new MemberSuspended(nextSequence++, suspend.memberId(), resting.size() + stops.size()));
        int r = 0;
        int s = 0;
        while (r < resting.size() || s < stops.size()) {                           // in order of order ID
            boolean takeResting = s >= stops.size()
                    || (r < resting.size() && resting.get(r).orderId < stops.get(s).orderId());
            if (takeResting) {
                Order order = resting.get(r++);
                book.remove(order.orderId);
                events.add(new OrderCancelled(
                        nextSequence++, order.orderId, order.remainingQuantity, CancelReason.KILL_SWITCH));
            } else {
                ParkedStop stop = removeParked(stops.get(s++).orderId());
                events.add(new OrderCancelled(nextSequence++, stop.orderId(), stop.quantity(), CancelReason.KILL_SWITCH));
            }
        }
    }

    private void onResume(ResumeMember resume, List<Event> events) {
        MemberRecord record = members.get(resume.memberId());
        if (record == null || !record.suspended) {
            events.add(new MemberCommandRejected(nextSequence++, resume.memberId(), RejectReason.INVALID_MEMBER));
            return;
        }
        record.suspended = false;
        events.add(new MemberResumed(nextSequence++, resume.memberId()));
    }

    /** Whether the volatility band applies right now (VI-002). */
    private boolean bandActive() {
        return phase == TradingPhase.CONTINUOUS && instrument.volatilityBandBps() > 0 && referencePrice != 0;
    }

    /** The widest distance from the reference price still inside the band, in ticks. */
    private long bandOffset() {
        return referencePrice * instrument.volatilityBandBps() / 10_000;
    }

    private boolean outsideBand(long priceTicks) {
        return bandActive() && Math.abs(priceTicks - referencePrice) > bandOffset();
    }

    /** A taker's limit, narrowed to the band: what a fill or kill order may count on (VI-004). */
    private long withinBand(Side takerSide, long limitPriceTicks) {
        if (!bandActive()) {
            return limitPriceTicks;
        }
        return takerSide == Side.BUY
                ? Math.min(limitPriceTicks, referencePrice + bandOffset())
                : Math.max(limitPriceTicks, referencePrice - bandOffset());
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
    private long match(long takerOrderId, long takerMember, Side takerSide, long limitPriceTicks, long quantity,
            SelfTradeInstruction selfTrade, List<Event> events) {
        Side makerSide = takerSide.opposite();
        long remaining = quantity;
        selfTradeStopped = false;
        breached = false;
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
            if (outsideBand(level.priceTicks())) {                           // VI-002
                breached = true;
                breachPrice = level.priceTicks();
                interruptionPending = true;
                break;
            }
            long fill = Math.min(remaining, maker.displayed);           // never into hidden quantity (IC-003)
            remaining -= fill;
            boolean replenished = book.fillFirst(level, fill);
            lastTradePrice = level.priceTicks();
            if (referencePrice == 0) {
                referencePrice = lastTradePrice;                              // VI-001
            }
            commandLowTrade = Math.min(commandLowTrade, lastTradePrice);
            commandHighTrade = Math.max(commandHighTrade, lastTradePrice);
            events.add(new TradeExecuted(
                    nextSequence++, takerOrderId, maker.orderId, takerSide,
                    level.priceTicks(), fill, remaining, maker.remainingQuantity, takerMember, maker.memberId));
            traded(takerMember, takerSide, maker.memberId, fill);
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
        if (priceTicks % instrument.stepAt(priceTicks) != 0) {
            return RejectReason.INVALID_TICK_SIZE;                               // TK-002
        }
        return null;
    }
}
