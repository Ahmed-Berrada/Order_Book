package io.github.ahmedberrada.lob.fix;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.CancelRejected;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.OrderAccepted;
import io.github.ahmedberrada.lob.core.OrderCancelled;
import io.github.ahmedberrada.lob.core.OrderRejected;
import io.github.ahmedberrada.lob.core.OrderReplaced;
import io.github.ahmedberrada.lob.core.OrderRested;
import io.github.ahmedberrada.lob.core.RejectReason;
import io.github.ahmedberrada.lob.core.ReplaceOrder;
import io.github.ahmedberrada.lob.core.ReplaceRejected;
import io.github.ahmedberrada.lob.core.SelfTradeInstruction;
import io.github.ahmedberrada.lob.core.StopOrder;
import io.github.ahmedberrada.lob.core.StopTriggered;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.fix.OrderRegistry.FixOrder;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.service.EventListener;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Application;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.UnsupportedMessageType;
import quickfix.field.BusinessRejectReason;
import quickfix.field.ClOrdID;
import quickfix.field.CopyMsgIndicator;
import quickfix.field.CxlRejReason;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.ExecInst;
import quickfix.field.ExecType;
import quickfix.field.MaxFloor;
import quickfix.field.MsgSeqNum;
import quickfix.field.MsgType;
import quickfix.field.OnBehalfOfCompID;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.RefMsgType;
import quickfix.field.RefSeqNum;
import quickfix.field.Side;
import quickfix.field.StopPx;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TimeInForce;
import quickfix.fix44.BusinessMessageReject;

/**
 * Application layer of the gateway: application messages become commands, events become execution
 * reports (ADR-0006, Rules of Engagement). Session-level messages are handled by QuickFIX/J.
 *
 * <p>Requests arrive on QuickFIX/J threads. Events arrive through {@link #onEvents} on the
 * instrument's writer thread, for every command whoever sent it (ADR-0004 §7), so the reports of one
 * order are sent in event order and makers are told of their fills even when REST caused the trade.
 */
final class FixApplication implements Application, EventListener {

    private static final Logger LOG = LoggerFactory.getLogger(FixApplication.class);

    /** ExecInst {@code 6}, Participate don't initiate: the only supported value, meaning post-only (OT-004). */
    private static final String POST_ONLY = String.valueOf(ExecInst.PARTICIPATE_DONT_INITIATE);

    /** Delivers a message to a session. Replaced in unit tests. */
    @FunctionalInterface
    interface Sender {
        void send(Message message, SessionID session);
    }

    private final MatchingService service;
    private final Sender sender;
    private final List<SessionID> dropCopies;
    /** The self-trade instruction of each member session; a session not listed has none (SP-001). */
    private final Map<SessionID, SelfTradeInstruction> selfTrade;
    private final OrderRegistry registry = new OrderRegistry();

    /** Attached to a NewOrderSingle's command, so its events can be reported to its sender. */
    private record NewOrderContext(SessionID session, String clOrdId, InstrumentConfig instrument, char side,
            long lots) {
    }

    /** Attached to an OrderCancelRequest's command: the request's ClOrdID and the order it targets. */
    private record CancelContext(String clOrdId, FixOrder order) {
    }

    /** Attached to an OrderCancelReplaceRequest's command. */
    private record ReplaceContext(String clOrdId, String origClOrdId, FixOrder order) {
    }

    FixApplication(MatchingService service, List<SessionID> dropCopies, Map<SessionID, SelfTradeInstruction> selfTrade) {
        this(service, FixApplication::sendToTarget, dropCopies, selfTrade);
    }

    FixApplication(MatchingService service, Sender sender) {
        this(service, sender, List.of(), Map.of());
    }

    FixApplication(MatchingService service, Sender sender, List<SessionID> dropCopies,
            Map<SessionID, SelfTradeInstruction> selfTrade) {
        this.service = service;
        this.sender = sender;
        this.dropCopies = List.copyOf(dropCopies);
        this.selfTrade = Map.copyOf(selfTrade);
    }

    @Override
    public void fromApp(Message message, SessionID session) throws FieldNotFound, UnsupportedMessageType {
        if (dropCopies.contains(session)) {
            rejectFromDropCopy(message, session);                     // DC-002: read only
            return;
        }
        switch (message.getHeader().getString(MsgType.FIELD)) {
            case MsgType.NEW_ORDER_SINGLE -> onNewOrder(message, session);
            case MsgType.ORDER_CANCEL_REQUEST -> onCancel(message, session);
            case MsgType.ORDER_CANCEL_REPLACE_REQUEST -> onReplace(message, session);
            case MsgType.ORDER_STATUS_REQUEST -> onStatus(message, session);
            default -> throw new UnsupportedMessageType();
        }
    }

    // ---- NewOrderSingle ---------------------------------------------------------------------------

    private void onNewOrder(Message order, SessionID session) throws FieldNotFound {
        String clOrdId = order.getString(ClOrdID.FIELD);
        String symbol = order.getString(Symbol.FIELD);
        char side = order.getChar(Side.FIELD);
        BigDecimal quantity = order.getDecimal(OrderQty.FIELD);
        if (!registry.reserve(session, clOrdId)) {
            refuse(session, clOrdId, symbol, side, quantity, OrdRejReason.DUPLICATE_ORDER, "DUPLICATE_CLORDID", false);
            return;
        }
        Optional<String> unsupported = unsupported(order, side);
        if (unsupported.isPresent()) {
            refuse(session, clOrdId, symbol, side, quantity, OrdRejReason.UNSUPPORTED_ORDER_CHARACTERISTIC,
                    unsupported.get(), true);
            return;
        }
        long lots;
        try {
            lots = quantity.longValueExact();
            if (order.isSetField(MaxFloor.FIELD)) {
                order.getDecimal(MaxFloor.FIELD).longValueExact();
            }
        } catch (ArithmeticException e) {
            refuse(session, clOrdId, symbol, side, quantity, OrdRejReason.INCORRECT_QUANTITY, "FRACTIONAL_QUANTITY", true);
            return;
        }
        Optional<InstrumentConfig> instrument = service.instrument(symbol);
        if (instrument.isEmpty()) {
            refuse(session, clOrdId, symbol, side, quantity, OrdRejReason.UNKNOWN_SYMBOL, "UNKNOWN_INSTRUMENT", true);
            return;
        }
        Command command;
        try {
            command = toCommand(order, instrument.get(), side, lots,
                    selfTrade.getOrDefault(session, SelfTradeInstruction.NONE));
        } catch (RequestRefusedException e) {
            refuse(session, clOrdId, symbol, side, quantity, OrdRejReason.OTHER, e.reason().name(), true);
            return;
        }
        NewOrderContext context = new NewOrderContext(session, clOrdId, instrument.get(), side, lots);
        service.submit(symbol, command, context).whenComplete((batch, failure) -> {
            if (failure != null) {
                onFailure(unwrap(failure), session, clOrdId, symbol, side, quantity);
            }
        });
    }

    // ---- OrderCancelRequest -----------------------------------------------------------------------

    /**
     * Cancels one of the session's own orders, found by OrigClOrdID: another member's ClOrdIDs are not
     * visible, so they read as unknown (Rules of Engagement, OrderCancelRequest).
     */
    private void onCancel(Message request, SessionID session) throws FieldNotFound {
        String clOrdId = request.getString(ClOrdID.FIELD);
        String origClOrdId = request.getString(OrigClOrdID.FIELD);
        if (!registry.reserve(session, clOrdId)) {
            rejectCancel(session, null, clOrdId, origClOrdId, CxlRejReason.DUPLICATE_CLORDID_RECEIVED, "DUPLICATE_CLORDID");
            return;
        }
        registry.refused(session, clOrdId);           // a cancel request never becomes an order
        OrderRegistry.Slot slot = registry.slot(session, origClOrdId);
        if (slot != null && slot.isPending()) {
            rejectCancel(session, null, clOrdId, origClOrdId, CxlRejReason.ORDER_ALREADY_IN_PENDING_CANCEL_OR_PENDING_REPLACE_STATUS, "PENDING");
            return;
        }
        FixOrder order = slot == null ? null : slot.order();
        if (order == null || order.ordStatus() == OrdStatus.REJECTED
                || !order.instrument.symbol().equals(request.getString(Symbol.FIELD))
                || order.side != request.getChar(Side.FIELD)) {
            rejectCancel(session, order, clOrdId, origClOrdId, CxlRejReason.UNKNOWN_ORDER, "UNKNOWN_ORDER");
            return;
        }
        service.submit(order.instrument.symbol(), new CancelOrder(order.orderId), new CancelContext(clOrdId, order))
                .whenComplete((batch, failure) -> {
                    if (failure instanceof Throwable f && unwrap(f) instanceof RequestRefusedException refused) {
                        rejectCancel(session, order, clOrdId, origClOrdId, CxlRejReason.OTHER, refused.reason().name());
                    } else if (failure != null) {
                        LOG.error("Outcome unknown for cancel {} {}", session, clOrdId, failure);
                        report(ExecutionReports.pendingCancel(order, clOrdId), session);
                    }
                });
    }

    private void rejectCancel(SessionID session, FixOrder order, String clOrdId, String origClOrdId, int reason,
            String text) {
        String orderId = order == null ? ExecutionReports.NO_ORDER_ID : order.fixOrderId();
        char status = order == null ? OrdStatus.REJECTED : order.ordStatus();
        sender.send(ExecutionReports.cancelReject(orderId, clOrdId, origClOrdId, status, reason, text), session);
    }

    // ---- OrderCancelReplaceRequest ----------------------------------------------------------------

    /**
     * Replaces the price and quantity of one of the session's resting limit orders (OT-005). OrderQty is
     * the new total, fills included, so the engine is sent {@code OrderQty - CumQty}.
     */
    private void onReplace(Message request, SessionID session) throws FieldNotFound {
        String clOrdId = request.getString(ClOrdID.FIELD);
        String origClOrdId = request.getString(OrigClOrdID.FIELD);
        if (!registry.reserve(session, clOrdId)) {
            rejectReplace(session, null, clOrdId, origClOrdId, CxlRejReason.DUPLICATE_CLORDID_RECEIVED,
                    "DUPLICATE_CLORDID");
            return;
        }
        OrderRegistry.Slot slot = registry.slot(session, origClOrdId);
        if (slot != null && slot.isPending()) {
            refuseReplace(session, null, clOrdId, origClOrdId,
                    CxlRejReason.ORDER_ALREADY_IN_PENDING_CANCEL_OR_PENDING_REPLACE_STATUS, "PENDING");
            return;
        }
        FixOrder order = slot == null ? null : slot.order();
        if (order == null || order.ordStatus() == OrdStatus.REJECTED
                || !order.instrument.symbol().equals(request.getString(Symbol.FIELD))
                || order.side != request.getChar(Side.FIELD)) {
            refuseReplace(session, order, clOrdId, origClOrdId, CxlRejReason.UNKNOWN_ORDER, "UNKNOWN_ORDER");
            return;
        }
        if (request.getChar(OrdType.FIELD) != OrdType.LIMIT || !request.isSetField(Price.FIELD)) {
            refuseReplace(session, order, clOrdId, origClOrdId, CxlRejReason.OTHER, "ONLY_LIMIT_PRICE_AND_QUANTITY");
            return;
        }
        long remaining;
        long ticks;
        try {
            remaining = Math.subtractExact(request.getDecimal(OrderQty.FIELD).longValueExact(), order.cumulativeQuantity());
            ticks = order.instrument.toTicks(request.getDecimal(Price.FIELD));
        } catch (ArithmeticException e) {
            refuseReplace(session, order, clOrdId, origClOrdId, CxlRejReason.OTHER, "FRACTIONAL_QUANTITY");
            return;
        } catch (RequestRefusedException e) {
            refuseReplace(session, order, clOrdId, origClOrdId, CxlRejReason.OTHER, e.reason().name());
            return;
        }
        if (remaining <= 0) {
            refuseReplace(session, order, clOrdId, origClOrdId, CxlRejReason.OTHER, "QUANTITY_NOT_ABOVE_FILLED");
            return;
        }
        service.submit(order.instrument.symbol(), new ReplaceOrder(order.orderId, ticks, remaining),
                        new ReplaceContext(clOrdId, origClOrdId, order))
                .whenComplete((batch, failure) -> {
                    if (failure instanceof Throwable f && unwrap(f) instanceof RequestRefusedException refused) {
                        refuseReplace(session, order, clOrdId, origClOrdId, CxlRejReason.OTHER,
                                refused.reason().name());
                    } else if (failure != null) {
                        LOG.error("Outcome unknown for replace {} {}", session, clOrdId, failure);
                        report(ExecutionReports.pendingReplace(order, clOrdId), session);
                    }
                });
    }

    /** Rejects a replace and frees nothing: the request's ClOrdID stays used. */
    private void refuseReplace(SessionID session, FixOrder order, String clOrdId, String origClOrdId, int reason,
            String text) {
        registry.refused(session, clOrdId);
        rejectReplace(session, order, clOrdId, origClOrdId, reason, text);
    }

    private void rejectReplace(SessionID session, FixOrder order, String clOrdId, String origClOrdId, int reason,
            String text) {
        String orderId = order == null ? ExecutionReports.NO_ORDER_ID : order.fixOrderId();
        char status = order == null ? OrdStatus.REJECTED : order.ordStatus();
        sender.send(ExecutionReports.cancelReject(orderId, clOrdId, origClOrdId, status, reason, text,
                CxlRejResponseTo.ORDER_CANCEL_REPLACE_REQUEST), session);
    }

    // ---- OrderStatusRequest -----------------------------------------------------------------------

    /** Reports the current state of one of the session's orders, found by its ClOrdID. */
    private void onStatus(Message request, SessionID session) throws FieldNotFound {
        String clOrdId = request.getString(ClOrdID.FIELD);
        OrderRegistry.Slot slot = registry.slot(session, clOrdId);
        FixOrder order = slot == null ? null : slot.order();
        if (order != null) {
            report(ExecutionReports.status(order), session);
            return;
        }
        char status = slot != null && slot.isPending() ? OrdStatus.PENDING_NEW : OrdStatus.REJECTED;
        String text = slot == null ? "UNKNOWN_ORDER" : slot.isPending() ? "PENDING" : "REFUSED";
        Integer reason = slot == null ? OrdRejReason.UNKNOWN_ORDER : null;
        report(ExecutionReports.withoutOrder(ExecType.ORDER_STATUS, status, clOrdId,
                request.getString(Symbol.FIELD), request.getChar(Side.FIELD), null, reason, text), session);
    }

    /** Field values the venue does not support yet (Rules of Engagement, NewOrderSingle). */
    private static Optional<String> unsupported(Message order, char side) throws FieldNotFound {
        if (side != Side.BUY && side != Side.SELL) {
            return Optional.of("UNSUPPORTED_SIDE");
        }
        if (order.isSetField(TimeInForce.FIELD) && timeInForce(order.getChar(TimeInForce.FIELD)) == null) {
            return Optional.of("UNSUPPORTED_TIME_IN_FORCE");
        }
        if (order.isSetField(ExecInst.FIELD) && !order.getString(ExecInst.FIELD).equals(POST_ONLY)) {
            return Optional.of("UNSUPPORTED_EXEC_INST");
        }
        char type = order.getChar(OrdType.FIELD);
        if (type != OrdType.LIMIT && type != OrdType.MARKET && type != OrdType.STOP && type != OrdType.STOP_LIMIT) {
            return Optional.of("UNSUPPORTED_ORD_TYPE");
        }
        boolean limited = type == OrdType.LIMIT || type == OrdType.STOP_LIMIT;
        boolean stop = type == OrdType.STOP || type == OrdType.STOP_LIMIT;
        if (limited && !order.isSetField(Price.FIELD)) {
            return Optional.of("PRICE_REQUIRED_FOR_LIMIT");
        }
        if (!limited && order.isSetField(Price.FIELD)) {
            return Optional.of("PRICE_NOT_ALLOWED_FOR_MARKET");
        }
        if (stop != order.isSetField(StopPx.FIELD)) {
            return Optional.of(stop ? "STOP_PRICE_REQUIRED" : "STOP_PRICE_NOT_ALLOWED");
        }
        if (stop && (order.isSetField(TimeInForce.FIELD) || order.isSetField(ExecInst.FIELD)
                || order.isSetField(MaxFloor.FIELD))) {
            return Optional.of("STOP_ORDER_ATTRIBUTES");
        }
        if (type == OrdType.MARKET && order.isSetField(ExecInst.FIELD)) {
            return Optional.of("POST_ONLY_NOT_ALLOWED_FOR_MARKET");
        }
        if (type == OrdType.MARKET && order.isSetField(MaxFloor.FIELD)) {
            return Optional.of("MAX_FLOOR_NOT_ALLOWED_FOR_MARKET");
        }
        return Optional.empty();
    }

    /**
     * FIX TimeInForce to the engine's, or {@code null} if unsupported. Day, FIX's default, is accepted
     * as GTC until the venue has a trading day (Rules of Engagement, limitations).
     */
    private static io.github.ahmedberrada.lob.core.TimeInForce timeInForce(char fix) {
        return switch (fix) {
            case TimeInForce.DAY, TimeInForce.GOOD_TILL_CANCEL -> io.github.ahmedberrada.lob.core.TimeInForce.GTC;
            case TimeInForce.IMMEDIATE_OR_CANCEL -> io.github.ahmedberrada.lob.core.TimeInForce.IOC;
            case TimeInForce.FILL_OR_KILL -> io.github.ahmedberrada.lob.core.TimeInForce.FOK;
            default -> null;
        };
    }

    private static Command toCommand(Message order, InstrumentConfig instrument, char fixSide, long lots,
            SelfTradeInstruction selfTrade)
            throws FieldNotFound {
        io.github.ahmedberrada.lob.core.Side side =
                fixSide == Side.BUY ? io.github.ahmedberrada.lob.core.Side.BUY : io.github.ahmedberrada.lob.core.Side.SELL;
        char type = order.getChar(OrdType.FIELD);
        if (type == OrdType.STOP || type == OrdType.STOP_LIMIT) {
            long limit = type == OrdType.STOP_LIMIT ? instrument.toTicks(order.getDecimal(Price.FIELD)) : 0;
            return new StopOrder(side, instrument.toTicks(order.getDecimal(StopPx.FIELD)), limit, lots, selfTrade);
        }
        boolean tifSet = order.isSetField(TimeInForce.FIELD);
        char tif = tifSet ? order.getChar(TimeInForce.FIELD) : TimeInForce.DAY;
        if (type == OrdType.MARKET) {
            // a market order never rests: Day, the default, means immediate or cancel; GTC is rejected (OT-001)
            return new MarketOrder(side, lots, tif == TimeInForce.DAY
                    ? io.github.ahmedberrada.lob.core.TimeInForce.IOC : timeInForce(tif), selfTrade);
        }
        long display = order.isSetField(MaxFloor.FIELD) ? order.getDecimal(MaxFloor.FIELD).longValueExact() : 0;
        return new LimitOrder(side, instrument.toTicks(order.getDecimal(Price.FIELD)), lots, timeInForce(tif),
                order.isSetField(ExecInst.FIELD), display, selfTrade);
    }

    /**
     * Turns events into reports, on the instrument's writer thread, in event order (CT-012): the new
     * order's own reports if a FIX session sent it, and fill or cancel reports to the FIX owners of the
     * resting orders it touched.
     */
    @Override
    public void onEvents(String symbol, EventBatch batch, Object context) {
        long time = batch.timestampMicros();
        FixOrder taker = null;
        for (Event event : batch.events()) {
            switch (event) {
                case OrderRejected e when context instanceof NewOrderContext c -> {
                    taker = new FixOrder(c.session(), c.clOrdId(), c.instrument(), c.side(), e.orderId(), c.lots(),
                            OrdStatus.REJECTED);
                    registry.created(taker);
                    report(ExecutionReports.engineRejection(taker, e.sequence(), time,
                            ordRejReason(e.reason()), e.reason().name()), taker.session);
                }
                case OrderAccepted e when context instanceof NewOrderContext c -> {
                    taker = new FixOrder(c.session(), c.clOrdId(), c.instrument(), c.side(), e.orderId(), c.lots(),
                            OrdStatus.NEW);
                    registry.created(taker);
                    report(ExecutionReports.of(taker, ExecType.NEW, e.sequence(), time), taker.session);
                }
                case TradeExecuted e -> {
                    // the taker is the order this command sent, or a stop it released (ST-005)
                    if (taker == null || taker.orderId != e.takerOrderId()) {
                        taker = registry.byOrderId(symbol, e.takerOrderId());
                    }
                    if (taker != null) {
                        taker.fill(e.priceTicks(), e.quantity(), e.takerRemainingQuantity());
                        report(ExecutionReports.trade(taker, e.sequence(), time, e.priceTicks(), e.quantity(),
                                false), taker.session);
                    }
                    FixOrder maker = registry.byOrderId(symbol, e.makerOrderId());
                    if (maker != null) {
                        maker.fill(e.priceTicks(), e.quantity(), e.makerRemainingQuantity());
                        report(ExecutionReports.trade(maker, e.sequence(), time, e.priceTicks(), e.quantity(),
                                true), maker.session);
                    }
                }
                case OrderCancelled e when context instanceof CancelContext c -> {
                    c.order().cancel();
                    report(ExecutionReports.cancelled(c.order(), c.clOrdId(), e.sequence(), time),
                            c.order().session);
                }
                case CancelRejected e when context instanceof CancelContext c ->
                    rejectCancel(c.order().session, c.order(), c.clOrdId(), c.order().clOrdId(),
                            CxlRejReason.TOO_LATE_TO_CANCEL, "NOT_RESTING");
                case OrderCancelled e -> {
                    FixOrder cancelled = taker != null && taker.orderId == e.orderId()
                            ? taker : registry.byOrderId(symbol, e.orderId());
                    if (cancelled != null) {
                        cancelled.cancel();
                        report(ExecutionReports.cancelled(cancelled, e.sequence(), time, e.reason().name()),
                                cancelled.session);
                    }
                }
                case OrderReplaced e -> {
                    // the replaced order is the taker of any trade that follows (OT-007)
                    ReplaceContext c = context instanceof ReplaceContext r ? r : null;
                    taker = c != null ? c.order() : registry.byOrderId(symbol, e.orderId());
                    if (taker != null) {
                        String previous = taker.clOrdId();
                        taker.replaced(c != null ? c.clOrdId() : previous, e.quantity());
                        if (c != null) {
                            registry.renamed(taker, c.clOrdId());
                        }
                        report(ExecutionReports.replaced(taker, c != null ? c.origClOrdId() : previous,
                                e.sequence(), time, e.priceTicks()), taker.session);
                    }
                }
                case StopTriggered e -> {
                    FixOrder triggered = registry.byOrderId(symbol, e.orderId());
                    if (triggered != null) {
                        report(ExecutionReports.triggered(triggered, e.sequence(), time), triggered.session);
                    }
                }
                case ReplaceRejected e when context instanceof ReplaceContext c -> {
                    registry.refused(c.order().session, c.clOrdId());
                    boolean resting = e.reason() == RejectReason.UNKNOWN_ORDER;
                    rejectReplace(c.order().session, c.order(), c.clOrdId(), c.origClOrdId(),
                            resting ? CxlRejReason.TOO_LATE_TO_CANCEL : CxlRejReason.OTHER,
                            resting ? "NOT_RESTING" : e.reason().name());
                }
                default -> {
                    // OrderRested is already reported as New or a fill; an iceberg's refill (OrderReplenished)
                    // changes nothing a member is told; other commands' events concern no FIX order
                }
            }
        }
    }

    /** The command never produced events: refused before the engine, or its outcome is unknown. */
    private void onFailure(Throwable failure, SessionID session, String clOrdId, String symbol, char side,
            BigDecimal quantity) {
        if (failure instanceof RequestRefusedException refused) {
            int reason = switch (refused.reason()) {
                case UNKNOWN_INSTRUMENT -> OrdRejReason.UNKNOWN_SYMBOL;
                case STOPPED -> OrdRejReason.EXCHANGE_CLOSED;
                case OFF_TICK_PRICE, OVERLOADED, HALTED -> OrdRejReason.OTHER;
            };
            refuse(session, clOrdId, symbol, side, quantity, reason, refused.reason().name(), true);
        } else {
            // ADR-0006 §5: the order may exist, so it must not be reported as rejected.
            LOG.error("Outcome unknown for {} {} on {}", session, clOrdId, symbol, failure);
            report(ExecutionReports.withoutOrder(ExecType.PENDING_NEW, clOrdId, symbol, side, quantity, null,
                    "OUTCOME_UNKNOWN"), session);
        }
    }

    private void refuse(SessionID session, String clOrdId, String symbol, char side, BigDecimal quantity,
            int reason, String text, boolean markRefused) {
        if (markRefused) {
            registry.refused(session, clOrdId);
        }
        report(ExecutionReports.withoutOrder(ExecType.REJECTED, clOrdId, symbol, side, quantity, reason, text), session);
    }

    private static int ordRejReason(RejectReason reason) {
        return switch (reason) {
            case INVALID_QUANTITY -> OrdRejReason.INCORRECT_QUANTITY;
            case QUANTITY_ABOVE_MAXIMUM, PRICE_ABOVE_MAXIMUM -> OrdRejReason.ORDER_EXCEEDS_LIMIT;
            case INVALID_TIME_IN_FORCE -> OrdRejReason.UNSUPPORTED_ORDER_CHARACTERISTIC;
            case INVALID_DISPLAY_QUANTITY -> OrdRejReason.INCORRECT_QUANTITY;
            case INVALID_SELF_TRADE_GROUP -> OrdRejReason.OTHER;
            case INVALID_PRICE, NO_LIQUIDITY, UNKNOWN_ORDER, POST_ONLY_WOULD_TRADE, STOP_PRICE_ALREADY_REACHED ->
                    OrdRejReason.OTHER;
        };
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    /** Sends an execution report to a member, and a marked copy to each drop copy session (DC-001). */
    private void report(Message report, SessionID member) {
        sender.send(report, member);
        for (SessionID dropCopy : dropCopies) {
            Message copy = (Message) report.clone();
            copy.setBoolean(CopyMsgIndicator.FIELD, true);                 // a body field of ExecutionReport in FIX 4.4
            copy.getHeader().setString(OnBehalfOfCompID.FIELD, member.getTargetCompID());
            sender.send(copy, dropCopy);
        }
    }

    private void rejectFromDropCopy(Message message, SessionID session) throws FieldNotFound {
        BusinessMessageReject reject = new BusinessMessageReject();
        reject.setString(RefMsgType.FIELD, message.getHeader().getString(MsgType.FIELD));
        reject.setInt(RefSeqNum.FIELD, message.getHeader().getInt(MsgSeqNum.FIELD));
        reject.setInt(BusinessRejectReason.FIELD, BusinessRejectReason.NOT_AUTHORIZED);
        reject.setString(Text.FIELD, "DROP_COPY_SESSION_IS_READ_ONLY");
        sender.send(reject, session);
    }

    /** Sends to a session; if it is disconnected, QuickFIX/J stores the message for resend at logon. */
    private static void sendToTarget(Message message, SessionID session) {
        try {
            Session.sendToTarget(message, session);
        } catch (SessionNotFound e) {
            LOG.error("No FIX session {} for {}", session, message, e);
        }
    }

    @Override
    public void onCreate(SessionID session) {
    }

    @Override
    public void onLogon(SessionID session) {
        LOG.info("FIX session logged on: {}", session);
    }

    @Override
    public void onLogout(SessionID session) {
        LOG.info("FIX session logged out: {}", session);
    }

    @Override
    public void toAdmin(Message message, SessionID session) {
    }

    @Override
    public void fromAdmin(Message message, SessionID session) {
    }

    @Override
    public void toApp(Message message, SessionID session) {
    }
}
