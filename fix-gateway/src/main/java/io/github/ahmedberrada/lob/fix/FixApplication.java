package io.github.ahmedberrada.lob.fix;

import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.OrderAccepted;
import io.github.ahmedberrada.lob.core.OrderCancelled;
import io.github.ahmedberrada.lob.core.OrderRejected;
import io.github.ahmedberrada.lob.core.OrderRested;
import io.github.ahmedberrada.lob.core.RejectReason;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.fix.OrderRegistry.FixOrder;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.service.EventListener;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import java.math.BigDecimal;
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
import quickfix.field.ClOrdID;
import quickfix.field.ExecType;
import quickfix.field.MsgType;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TimeInForce;

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

    /** Delivers a message to a session. Replaced in unit tests. */
    @FunctionalInterface
    interface Sender {
        void send(Message message, SessionID session);
    }

    private final MatchingService service;
    private final Sender sender;
    private final OrderRegistry registry = new OrderRegistry();

    /** Attached to a NewOrderSingle's command, so its events can be reported to its sender. */
    private record NewOrderContext(SessionID session, String clOrdId, InstrumentConfig instrument, char side,
            long lots) {
    }

    FixApplication(MatchingService service) {
        this(service, FixApplication::sendToTarget);
    }

    FixApplication(MatchingService service, Sender sender) {
        this.service = service;
        this.sender = sender;
    }

    @Override
    public void fromApp(Message message, SessionID session) throws FieldNotFound, UnsupportedMessageType {
        switch (message.getHeader().getString(MsgType.FIELD)) {
            case MsgType.NEW_ORDER_SINGLE -> onNewOrder(message, session);
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
            command = toCommand(order, instrument.get(), side, lots);
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

    /** Field values the venue does not support yet (Rules of Engagement, NewOrderSingle). */
    private static Optional<String> unsupported(Message order, char side) throws FieldNotFound {
        if (side != Side.BUY && side != Side.SELL) {
            return Optional.of("UNSUPPORTED_SIDE");
        }
        if (order.isSetField(TimeInForce.FIELD) && order.getChar(TimeInForce.FIELD) != TimeInForce.DAY) {
            return Optional.of("UNSUPPORTED_TIME_IN_FORCE");
        }
        char type = order.getChar(OrdType.FIELD);
        if (type != OrdType.LIMIT && type != OrdType.MARKET) {
            return Optional.of("UNSUPPORTED_ORD_TYPE");
        }
        if (type == OrdType.LIMIT && !order.isSetField(Price.FIELD)) {
            return Optional.of("PRICE_REQUIRED_FOR_LIMIT");
        }
        if (type == OrdType.MARKET && order.isSetField(Price.FIELD)) {
            return Optional.of("PRICE_NOT_ALLOWED_FOR_MARKET");
        }
        return Optional.empty();
    }

    private static Command toCommand(Message order, InstrumentConfig instrument, char fixSide, long lots)
            throws FieldNotFound {
        io.github.ahmedberrada.lob.core.Side side =
                fixSide == Side.BUY ? io.github.ahmedberrada.lob.core.Side.BUY : io.github.ahmedberrada.lob.core.Side.SELL;
        if (order.getChar(OrdType.FIELD) == OrdType.MARKET) {
            return new MarketOrder(side, lots);
        }
        return new LimitOrder(side, instrument.toTicks(order.getDecimal(Price.FIELD)), lots);
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
                    sender.send(ExecutionReports.engineRejection(taker, e.sequence(), time,
                            ordRejReason(e.reason()), e.reason().name()), taker.session);
                }
                case OrderAccepted e when context instanceof NewOrderContext c -> {
                    taker = new FixOrder(c.session(), c.clOrdId(), c.instrument(), c.side(), e.orderId(), c.lots(),
                            OrdStatus.NEW);
                    registry.created(taker);
                    sender.send(ExecutionReports.of(taker, ExecType.NEW, e.sequence(), time), taker.session);
                }
                case TradeExecuted e -> {
                    if (taker != null) {
                        taker.fill(e.priceTicks(), e.quantity(), e.takerRemainingQuantity());
                        sender.send(ExecutionReports.trade(taker, e.sequence(), time, e.priceTicks(), e.quantity(),
                                false), taker.session);
                    }
                    FixOrder maker = registry.byOrderId(symbol, e.makerOrderId());
                    if (maker != null) {
                        maker.fill(e.priceTicks(), e.quantity(), e.makerRemainingQuantity());
                        sender.send(ExecutionReports.trade(maker, e.sequence(), time, e.priceTicks(), e.quantity(),
                                true), maker.session);
                    }
                }
                case OrderCancelled e -> {
                    FixOrder cancelled = taker != null && taker.orderId == e.orderId()
                            ? taker : registry.byOrderId(symbol, e.orderId());
                    if (cancelled != null) {
                        cancelled.cancel();
                        sender.send(ExecutionReports.of(cancelled, ExecType.CANCELED, e.sequence(), time),
                                cancelled.session);
                    }
                }
                default -> {
                    // OrderRested is already reported as New or a fill; other commands' events concern no FIX order
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
            sender.send(ExecutionReports.withoutOrder(ExecType.PENDING_NEW, clOrdId, symbol, side, quantity, null,
                    "OUTCOME_UNKNOWN"), session);
        }
    }

    private void refuse(SessionID session, String clOrdId, String symbol, char side, BigDecimal quantity,
            int reason, String text, boolean markRefused) {
        if (markRefused) {
            registry.refused(session, clOrdId);
        }
        sender.send(ExecutionReports.withoutOrder(ExecType.REJECTED, clOrdId, symbol, side, quantity, reason, text), session);
    }

    private static int ordRejReason(RejectReason reason) {
        return switch (reason) {
            case INVALID_QUANTITY -> OrdRejReason.INCORRECT_QUANTITY;
            case QUANTITY_ABOVE_MAXIMUM, PRICE_ABOVE_MAXIMUM -> OrdRejReason.ORDER_EXCEEDS_LIMIT;
            case INVALID_PRICE, NO_LIQUIDITY, UNKNOWN_ORDER -> OrdRejReason.OTHER;
        };
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
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
