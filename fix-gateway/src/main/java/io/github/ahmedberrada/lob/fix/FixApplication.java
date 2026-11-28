package io.github.ahmedberrada.lob.fix;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.CancelReason;
import io.github.ahmedberrada.lob.core.CancelRejected;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.MassCancel;
import io.github.ahmedberrada.lob.core.MassCancelled;
import io.github.ahmedberrada.lob.core.OrderAccepted;
import io.github.ahmedberrada.lob.core.OrderCancelled;
import io.github.ahmedberrada.lob.core.OrderRejected;
import io.github.ahmedberrada.lob.core.OrderReplaced;
import io.github.ahmedberrada.lob.core.OrderRested;
import io.github.ahmedberrada.lob.core.PhaseChanged;
import io.github.ahmedberrada.lob.core.RejectReason;
import io.github.ahmedberrada.lob.core.ReplaceOrder;
import io.github.ahmedberrada.lob.core.ReplaceRejected;
import io.github.ahmedberrada.lob.core.SelfTradeInstruction;
import io.github.ahmedberrada.lob.core.StopOrder;
import io.github.ahmedberrada.lob.core.StopTriggered;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.core.TradingPhase;
import io.github.ahmedberrada.lob.core.VolatilityInterruption;
import io.github.ahmedberrada.lob.fix.OrderRegistry.FixOrder;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.service.EventListener;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Application;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.Responder;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.UnsupportedMessageType;
import quickfix.UtcTimestampPrecision;
import quickfix.field.BusinessRejectReason;
import quickfix.field.ClOrdID;
import quickfix.field.CopyMsgIndicator;
import quickfix.field.CxlRejReason;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.ExecInst;
import quickfix.field.ExecType;
import quickfix.field.MassCancelRejectReason;
import quickfix.field.MassCancelRequestType;
import quickfix.field.MassCancelResponse;
import quickfix.field.MaxFloor;
import quickfix.field.MsgSeqNum;
import quickfix.field.MsgType;
import quickfix.field.OnBehalfOfCompID;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Password;
import quickfix.field.Price;
import quickfix.field.RefMsgType;
import quickfix.field.RefSeqNum;
import quickfix.field.Side;
import quickfix.field.StopPx;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TimeInForce;
import quickfix.field.TotalAffectedOrders;
import quickfix.field.TradSesReqID;
import quickfix.field.TradSesStatus;
import quickfix.field.TradSesStatusRejReason;
import quickfix.field.TradingSessionID;
import quickfix.field.TransactTime;
import quickfix.field.UnsolicitedIndicator;
import quickfix.fix44.BusinessMessageReject;
import quickfix.fix44.OrderMassCancelReport;
import quickfix.fix44.TradingSessionStatus;

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
    /** Sessions logged on now: told of phase changes (FS-001). */
    private final Set<SessionID> loggedOn = ConcurrentHashMap.newKeySet();
    private final Map<Audience, Announced> announced = new ConcurrentHashMap<>();
    private final List<SessionID> dropCopies;
    /** The self-trade instruction of each member session; a session not listed has none (SP-001). */
    private final Map<SessionID, SelfTradeInstruction> selfTrade;
    private final LogonAuthenticator authenticator;
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
        this(service, dropCopies, selfTrade, LogonAuthenticator.ALLOW_ALL);
    }

    FixApplication(MatchingService service, List<SessionID> dropCopies, Map<SessionID, SelfTradeInstruction> selfTrade,
            LogonAuthenticator authenticator) {
        this(service, FixApplication::sendToTarget, dropCopies, selfTrade, authenticator);
    }

    FixApplication(MatchingService service, Sender sender) {
        this(service, sender, List.of(), Map.of());
    }

    FixApplication(MatchingService service, Sender sender, List<SessionID> dropCopies,
            Map<SessionID, SelfTradeInstruction> selfTrade) {
        this(service, sender, dropCopies, selfTrade, LogonAuthenticator.ALLOW_ALL);
    }

    FixApplication(MatchingService service, Sender sender, List<SessionID> dropCopies,
            Map<SessionID, SelfTradeInstruction> selfTrade, LogonAuthenticator authenticator) {
        this.authenticator = authenticator;
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
            case MsgType.ORDER_MASS_CANCEL_REQUEST -> onMassCancel(message, session);
            case MsgType.TRADING_SESSION_STATUS_REQUEST -> onTradingStatusRequest(message, session);
            default -> throw new UnsupportedMessageType();
        }
    }

    // ---- OrderMassCancelRequest ---------------------------------------------------------------------

    /**
     * Cancels all the session's member's open orders in one instrument (MassCancelRequestType 1) or in all of them
     * (7), on one side or both (RK-006). Each order is reported cancelled with {@code Text=MASS_CANCEL}; then one
     * OrderMassCancelReport gives the number of orders cancelled (Rules of Engagement, OrderMassCancelRequest).
     */
    private void onMassCancel(Message request, SessionID session) throws FieldNotFound {
        String clOrdId = request.getString(ClOrdID.FIELD);
        char type = request.getChar(MassCancelRequestType.FIELD);
        String symbol = request.isSetField(Symbol.FIELD) ? request.getString(Symbol.FIELD) : null;
        Character fixSide = request.isSetField(Side.FIELD) ? request.getChar(Side.FIELD) : null;
        if (!registry.reserve(session, clOrdId)) {
            rejectMassCancel(session, clOrdId, type, symbol, fixSide, null, "DUPLICATE_CLORDID");
            return;
        }
        registry.refused(session, clOrdId);                  // a mass cancel never becomes an order
        io.github.ahmedberrada.lob.core.Side side;
        if (fixSide == null) {
            side = null;
        } else if (fixSide == Side.BUY) {
            side = io.github.ahmedberrada.lob.core.Side.BUY;
        } else if (fixSide == Side.SELL) {
            side = io.github.ahmedberrada.lob.core.Side.SELL;
        } else {
            rejectMassCancel(session, clOrdId, type, symbol, fixSide, null, "INVALID_SIDE");
            return;
        }
        List<String> symbols;
        if (type == MassCancelRequestType.CANCEL_ORDERS_FOR_A_SECURITY) {
            if (symbol == null || service.instrument(symbol).isEmpty()) {
                rejectMassCancel(session, clOrdId, type, symbol, fixSide,
                        '1', "UNKNOWN_INSTRUMENT");
                return;
            }
            symbols = List.of(symbol);
        } else if (type == MassCancelRequestType.CANCEL_ALL_ORDERS) {
            symbols = service.instruments().stream().map(InstrumentConfig::symbol).toList();
        } else {
            rejectMassCancel(session, clOrdId, type, symbol, fixSide,
                    '0', "UNSUPPORTED_MASS_CANCEL_REQUEST_TYPE");
            return;
        }
        Map<String, CompletableFuture<EventBatch>> results = new LinkedHashMap<>();
        for (String s : symbols) {
            results.put(s, service.submit(s, new MassCancel(side), memberOf(session), null));
        }
        CompletableFuture.allOf(results.values().toArray(CompletableFuture[]::new)).whenComplete((ignored, f) -> {
            try {
                reportMassCancel(session, clOrdId, type, symbol, fixSide, results);
            } catch (RuntimeException e) {
                LOG.error("Could not report mass cancel {} {}", session, clOrdId, e);
            }
        });
    }

    /**
     * One OrderMassCancelReport for the instruments' outcomes. Rejected only if every instrument refused the command
     * before journaling it: an instrument whose outcome is unknown may have cancelled (ADR-0006 §5).
     */
    private void reportMassCancel(SessionID session, String clOrdId, char type, String symbol, Character fixSide,
            Map<String, CompletableFuture<EventBatch>> results) {
        long cancelled = 0;
        long latestMicros = 0;
        List<String> refused = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, CompletableFuture<EventBatch>> result : results.entrySet()) {
            try {
                EventBatch batch = result.getValue().join();
                cancelled += ((MassCancelled) batch.events().getFirst()).cancelledOrders();
                latestMicros = Math.max(latestMicros, batch.timestampMicros());
            } catch (CompletionException | java.util.concurrent.CancellationException e) {
                Throwable cause = unwrap(e);
                if (cause instanceof RequestRefusedException r) {
                    refused.add(result.getKey() + " " + r.reason().name());
                } else {
                    unknown.add(result.getKey());
                    LOG.error("Outcome unknown for mass cancel {} {} on {}", session, clOrdId, result.getKey(), cause);
                }
            }
        }
        if (!results.isEmpty() && refused.size() == results.size()) {
            rejectMassCancel(session, clOrdId, type, symbol, fixSide, null, String.join(", ", refused));
            return;
        }
        OrderMassCancelReport report = massCancelReport(clOrdId, type, symbol, fixSide);
        if (latestMicros > 0) {
            report.setUtcTimeStamp(TransactTime.FIELD, LocalDateTime.ofEpochSecond(
                    Math.floorDiv(latestMicros, 1_000_000), (int) Math.floorMod(latestMicros, 1_000_000) * 1_000,
                    ZoneOffset.UTC), UtcTimestampPrecision.MICROS);
        }
        report.setChar(MassCancelResponse.FIELD, type);
        report.setInt(TotalAffectedOrders.FIELD, Math.toIntExact(Math.min(Integer.MAX_VALUE, cancelled)));
        List<String> text = new ArrayList<>();
        if (!refused.isEmpty()) {
            text.add("NOT_CANCELLED_IN " + String.join(", ", refused));
        }
        if (!unknown.isEmpty()) {                          // may have cancelled: OrderStatusRequest each order
            text.add("OUTCOME_UNKNOWN_IN " + String.join(", ", unknown));
        }
        if (!text.isEmpty()) {
            report.setString(Text.FIELD, String.join("; ", text));
        }
        sender.send(report, session);
    }

    private void rejectMassCancel(SessionID session, String clOrdId, char type, String symbol, Character side,
            Character reason, String text) {
        OrderMassCancelReport report = massCancelReport(clOrdId, type, symbol, side);
        report.setChar(MassCancelResponse.FIELD, MassCancelResponse.CANCEL_REQUEST_REJECTED_SEE_MASSCANCELREJECTREASON);
        if (reason != null) {
            report.setChar(MassCancelRejectReason.FIELD, reason);
        }
        report.setString(Text.FIELD, text);
        sender.send(report, session);
    }

    private static OrderMassCancelReport massCancelReport(String clOrdId, char type, String symbol, Character side) {
        OrderMassCancelReport report = new OrderMassCancelReport();
        report.setString(ClOrdID.FIELD, clOrdId);
        report.setString(OrderID.FIELD, "NONE");
        report.setChar(MassCancelRequestType.FIELD, type);
        if (symbol != null) {
            report.setString(Symbol.FIELD, symbol);
        }
        if (side != null) {
            report.setChar(Side.FIELD, side);
        }
        report.setUtcTimeStamp(TransactTime.FIELD, LocalDateTime.now(ZoneOffset.UTC));
        return report;
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
        service.submit(symbol, command, memberOf(session), context).whenComplete((batch, failure) -> {
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
        service.submit(order.instrument.symbol(), new CancelOrder(order.orderId), memberOf(session),
                new CancelContext(clOrdId, order))
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
                        memberOf(session), new ReplaceContext(clOrdId, origClOrdId, order))
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

    /** FIX TimeInForce to the engine's, or {@code null} if unsupported. Day is FIX's default (DY-001). */
    private static io.github.ahmedberrada.lob.core.TimeInForce timeInForce(char fix) {
        return switch (fix) {
            case TimeInForce.DAY -> io.github.ahmedberrada.lob.core.TimeInForce.DAY;
            case TimeInForce.GOOD_TILL_CANCEL -> io.github.ahmedberrada.lob.core.TimeInForce.GTC;
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
        TradingPhase newPhase = null;
        boolean interrupted = false;
        for (Event event : batch.events()) {
            switch (event) {
                case PhaseChanged e -> newPhase = e.to();
                case VolatilityInterruption e -> interrupted = true;
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
                    if (cancelled != null && e.reason() == CancelReason.DAY_EXPIRED) {
                        cancelled.expire();
                        report(ExecutionReports.expired(cancelled, e.sequence(), time), cancelled.session);
                    } else if (cancelled != null) {
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
        if (newPhase != null) {                            // FS-001: after the command's own reports
            for (SessionID session : loggedOn) {
                announce(session, symbol, batch.commandSequence(), status(newPhase),
                        interrupted ? "VOLATILITY_INTERRUPTION" : null, null);
            }
        }
    }

    // ---- TradingSessionStatus ---------------------------------------------------------------------

    /** FS-002: the status of the instrument named in 336, or of every instrument. */
    private void onTradingStatusRequest(Message request, SessionID session) throws FieldNotFound {
        String reqId = request.getString(TradSesReqID.FIELD);
        if (request.isSetField(TradingSessionID.FIELD)) {
            String symbol = request.getString(TradingSessionID.FIELD);
            if (service.instrument(symbol).isEmpty()) {
                TradingSessionStatus rejected = tradingStatus(symbol, TradSesStatus.REQUEST_REJECTED, false, reqId,
                        "UNKNOWN_INSTRUMENT");
                rejected.setInt(TradSesStatusRejReason.FIELD, TradSesStatusRejReason.UNKNOWN_OR_INVALID_TRADINGSESSIONID);
                sender.send(rejected, session);
                return;
            }
            sendStatus(symbol, session, reqId);
        } else {
            for (InstrumentConfig instrument : service.instruments()) {
                sendStatus(instrument.symbol(), session, reqId);
            }
        }
    }

    /**
     * Sends an instrument's current status: its phase as of a command sequence, read in turn with its commands. The
     * read can complete on either thread, so a phase change may be announced before the read's answer is sent;
     * {@link #announce} then keeps the newer status (FS-001).
     */
    private void sendStatus(String symbol, SessionID session, String reqId) {
        if (service.isHalted(symbol)) {
            announce(session, symbol, Long.MAX_VALUE, TradSesStatus.HALTED, "HALTED", reqId);   // until the restart
            return;
        }
        service.book(symbol, 0).whenComplete((book, failure) -> {
            Throwable cause = failure == null ? null : unwrap(failure);
            if (cause == null) {
                announce(session, symbol, book.lastCommandSequence(), status(book.phase()), null, reqId);
            } else if (cause instanceof RequestRefusedException r && r.reason() == RequestRefusedException.Reason.HALTED) {
                announce(session, symbol, Long.MAX_VALUE, TradSesStatus.HALTED, "HALTED", reqId);
            } else {                                       // overloaded or stopping: the phase is not known now
                sender.send(tradingStatus(symbol, TradSesStatus.UNKNOWN, reqId == null, reqId,
                        cause instanceof RequestRefusedException r ? r.reason().name() : "UNKNOWN"), session);
            }
        });
    }

    /** The newest status sent to a session for an instrument, by the command sequence it reflects. */
    private record Announced(long commandSequence, int status, String text) {
    }

    private record Audience(SessionID session, String symbol) {
    }

    /**
     * Sends a status unless the session already has a newer one for the instrument. An unsolicited status that is
     * older is dropped; an answer to a request is sent all the same, with the newer status, so the request is
     * answered with what is true.
     */
    private void announce(SessionID session, String symbol, long commandSequence, int status, String text,
            String reqId) {
        announced.compute(new Audience(session, symbol), (key, previous) -> {
            boolean stale = previous != null && previous.commandSequence() > commandSequence;
            if (stale && reqId == null) {
                return previous;
            }
            Announced current = stale ? previous : new Announced(commandSequence, status, text);
            sender.send(tradingStatus(symbol, current.status(), reqId == null, reqId, current.text()), session);
            return current;
        });
    }

    private static int status(TradingPhase phase) {
        return switch (phase) {
            case CONTINUOUS -> TradSesStatus.OPEN;
            case CALL -> TradSesStatus.PRE_OPEN;
            case CLOSED -> TradSesStatus.CLOSED;
        };
    }

    private static TradingSessionStatus tradingStatus(String symbol, int status, boolean unsolicited, String reqId,
            String text) {
        TradingSessionStatus message = new TradingSessionStatus();
        message.setString(TradingSessionID.FIELD, symbol);
        message.setInt(TradSesStatus.FIELD, status);
        message.setBoolean(UnsolicitedIndicator.FIELD, unsolicited);
        if (reqId != null) {
            message.setString(TradSesReqID.FIELD, reqId);
        }
        if (text != null) {
            message.setString(Text.FIELD, text);
        }
        return message;
    }

    /** The command never produced events: refused before the engine, or its outcome is unknown. */
    private void onFailure(Throwable failure, SessionID session, String clOrdId, String symbol, char side,
            BigDecimal quantity) {
        if (failure instanceof RequestRefusedException refused) {
            int reason = switch (refused.reason()) {
                case UNKNOWN_INSTRUMENT -> OrdRejReason.UNKNOWN_SYMBOL;
                case STOPPED -> OrdRejReason.EXCHANGE_CLOSED;
                case OFF_TICK_PRICE, OVERLOADED, HALTED, THROTTLED, ORDER_TO_TRADE_LIMIT -> OrdRejReason.OTHER;
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

    /** The member a session acts for: its self-trade group, which is its member ID (MB-002). */
    private long memberOf(SessionID session) {
        return selfTrade.getOrDefault(session, SelfTradeInstruction.NONE).group();
    }

    private static int ordRejReason(RejectReason reason) {
        return switch (reason) {
            case INVALID_QUANTITY -> OrdRejReason.INCORRECT_QUANTITY;
            case QUANTITY_ABOVE_MAXIMUM, PRICE_ABOVE_MAXIMUM -> OrdRejReason.ORDER_EXCEEDS_LIMIT;
            case INVALID_TIME_IN_FORCE -> OrdRejReason.UNSUPPORTED_ORDER_CHARACTERISTIC;
            case INVALID_DISPLAY_QUANTITY -> OrdRejReason.INCORRECT_QUANTITY;
            case INVALID_SELF_TRADE_GROUP -> OrdRejReason.OTHER;
            case NOT_ALLOWED_IN_PHASE -> OrdRejReason.EXCHANGE_CLOSED;                   // TP-004, TP-005
            case RISK_ORDER_QUANTITY, RISK_ORDER_NOTIONAL, RISK_PRICE_COLLAR, RISK_OPEN_ORDERS, RISK_POSITION ->
                    OrdRejReason.ORDER_EXCEEDS_LIMIT;                                         // RK-002
            case INVALID_TICK_SIZE, MEMBER_SUSPENDED, INVALID_MEMBER_LIMITS, INVALID_MEMBER -> OrdRejReason.OTHER;
            case INVALID_PHASE_TRANSITION -> OrdRejReason.OTHER;
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
        loggedOn.add(session);
        for (InstrumentConfig instrument : service.instruments()) {      // FS-002
            sendStatus(instrument.symbol(), session, null);
        }
    }

    @Override
    public void onLogout(SessionID session) {
        LOG.info("FIX session logged out: {}", session);
        loggedOn.remove(session);
        announced.keySet().removeIf(audience -> audience.session().equals(session));
    }

    @Override
    public void toAdmin(Message message, SessionID session) {
    }

    /** A Logon must carry a password the authenticator accepts; nothing about the attempt but its CompID is logged. */
    @Override
    public void fromAdmin(Message message, SessionID session) throws FieldNotFound, RejectLogon {
        if (!MsgType.LOGON.equals(message.getHeader().getString(MsgType.FIELD))) {
            return;
        }
        String password = message.isSetField(Password.FIELD) ? message.getString(Password.FIELD) : null;
        if (!authenticator.authenticate(session.getTargetCompID(), password, remoteAddress(session))) {
            LOG.warn("FIX logon refused for {}: authentication failed", session.getTargetCompID());
            throw new RejectLogon("authentication failed");
        }
    }

    @Override
    public void toApp(Message message, SessionID session) {
    }

    /** Where a session's connection comes from, or {@code null} if it is not connected through a transport. */
    private static String remoteAddress(SessionID id) {
        Session session = Session.lookupSession(id);
        Responder responder = session == null ? null : session.getResponder();
        return responder == null ? null : responder.getRemoteAddress();
    }
}
