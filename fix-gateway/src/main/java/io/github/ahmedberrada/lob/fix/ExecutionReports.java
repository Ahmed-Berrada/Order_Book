package io.github.ahmedberrada.lob.fix;

import io.github.ahmedberrada.lob.fix.OrderRegistry.FixOrder;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import quickfix.UtcTimestampPrecision;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.CxlRejReason;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.OrderCancelReject;

/**
 * Builds ExecutionReports (Rules of Engagement, "Execution reports"). Decimal fields are written by tag
 * as {@code BigDecimal}: QuickFIX/J's typed price and quantity fields are {@code double}.
 */
final class ExecutionReports {

    /** No order exists: refused before the engine, or outcome unknown. */
    static final String NO_ORDER_ID = "NONE";

    /** ExecIDs for reports with no engine event: unique for this process start. */
    private static final String GATEWAY_EXEC_PREFIX = "GW-" + System.currentTimeMillis() + "-";
    private static final AtomicLong GATEWAY_EXEC_IDS = new AtomicLong();

    private ExecutionReports() {
    }

    /** A report on an order's current state, for an engine event with this sequence number. */
    static ExecutionReport of(FixOrder order, char execType, long eventSequence, long timestampMicros) {
        return of(order, execType, order.instrument.symbol() + "-" + eventSequence, timestampMicros);
    }

    private static ExecutionReport of(FixOrder order, char execType, String execId, long timestampMicros) {
        ExecutionReport report = base(order.fixOrderId(), execId, execType, order.ordStatus(), order.clOrdId,
                order.instrument.symbol(), order.side, order.orderQuantity, timestampMicros);
        report.setDecimal(CumQty.FIELD, BigDecimal.valueOf(order.cumulativeQuantity()));
        report.setDecimal(LeavesQty.FIELD, BigDecimal.valueOf(order.leavesQuantity()));
        report.setDecimal(AvgPx.FIELD, order.averagePrice());
        return report;
    }

    /**
     * A fill: the order's state after it, plus the fill's price and quantity. Taker and maker are told
     * of the same trade event; the maker's ExecID gets a {@code -M} suffix so both stay unique.
     */
    static ExecutionReport trade(FixOrder order, long eventSequence, long timestampMicros, long priceTicks,
            long quantity, boolean maker) {
        String execId = order.instrument.symbol() + "-" + eventSequence + (maker ? "-M" : "");
        ExecutionReport report = of(order, ExecType.TRADE, execId, timestampMicros);
        report.setDecimal(LastPx.FIELD, order.instrument.toPrice(priceTicks));
        report.setDecimal(LastQty.FIELD, BigDecimal.valueOf(quantity));
        return report;
    }

    /** An engine rejection: the order consumed an ID but never rested. */
    static ExecutionReport engineRejection(FixOrder order, long eventSequence, long timestampMicros,
            int ordRejReason, String text) {
        ExecutionReport report = of(order, ExecType.REJECTED, eventSequence, timestampMicros);
        report.setInt(OrdRejReason.FIELD, ordRejReason);
        report.setString(Text.FIELD, text);
        return report;
    }

    /**
     * A report with no engine event behind it: a refusal before the engine ({@code ExecType=Rejected}),
     * or an unknown outcome ({@code ExecType=PendingNew}).
     */
    static ExecutionReport withoutOrder(char execType, String clOrdId, String symbol, char side,
            BigDecimal orderQuantity, Integer ordRejReason, String text) {
        return withoutOrder(execType, execType, clOrdId, symbol, side, orderQuantity, ordRejReason, text);
    }

    static ExecutionReport withoutOrder(char execType, char ordStatus, String clOrdId, String symbol, char side,
            BigDecimal orderQuantity, Integer ordRejReason, String text) {
        ExecutionReport report = base(NO_ORDER_ID, gatewayExecId(), execType, ordStatus, clOrdId, symbol, side, 0,
                nowMicros());
        if (orderQuantity != null) {
            report.setDecimal(OrderQty.FIELD, orderQuantity);
        }
        report.setDecimal(CumQty.FIELD, BigDecimal.ZERO);
        report.setDecimal(LeavesQty.FIELD, BigDecimal.ZERO);
        report.setDecimal(AvgPx.FIELD, BigDecimal.ZERO);
        if (ordRejReason != null) {
            report.setInt(OrdRejReason.FIELD, ordRejReason);
        }
        if (text != null) {
            report.setString(Text.FIELD, text);
        }
        return report;
    }

    /** A confirmed cancel requested over FIX: ClOrdID is the request's, OrigClOrdID the order's. */
    static ExecutionReport cancelled(FixOrder order, String cancelClOrdId, long eventSequence, long timestampMicros) {
        ExecutionReport report = of(order, ExecType.CANCELED, eventSequence, timestampMicros);
        report.setString(ClOrdID.FIELD, cancelClOrdId);
        report.setString(OrigClOrdID.FIELD, order.clOrdId);
        return report;
    }

    /** A cancel whose journal write failed: it may or may not have happened (ADR-0006 §5). */
    static ExecutionReport pendingCancel(FixOrder order, String cancelClOrdId) {
        ExecutionReport report = of(order, ExecType.PENDING_CANCEL, gatewayExecId(), nowMicros());
        report.setChar(OrdStatus.FIELD, OrdStatus.PENDING_CANCEL);
        report.setString(ClOrdID.FIELD, cancelClOrdId);
        report.setString(OrigClOrdID.FIELD, order.clOrdId);
        report.setString(Text.FIELD, "OUTCOME_UNKNOWN");
        return report;
    }

    /** Answer to an OrderStatusRequest on a known order. */
    static ExecutionReport status(FixOrder order) {
        return of(order, ExecType.ORDER_STATUS, gatewayExecId(), nowMicros());
    }

    static OrderCancelReject cancelReject(String orderId, String clOrdId, String origClOrdId, char ordStatus,
            int reason, String text) {
        OrderCancelReject reject = new OrderCancelReject();
        reject.setString(OrderID.FIELD, orderId);
        reject.setString(ClOrdID.FIELD, clOrdId);
        reject.setString(OrigClOrdID.FIELD, origClOrdId);
        reject.setChar(OrdStatus.FIELD, ordStatus);
        reject.setChar(CxlRejResponseTo.FIELD, CxlRejResponseTo.ORDER_CANCEL_REQUEST);
        reject.setInt(CxlRejReason.FIELD, reason);
        reject.setString(Text.FIELD, text);
        return reject;
    }

    private static String gatewayExecId() {
        return GATEWAY_EXEC_PREFIX + GATEWAY_EXEC_IDS.incrementAndGet();
    }

    private static long nowMicros() {
        return Math.multiplyExact(System.currentTimeMillis(), 1_000L);
    }

    private static ExecutionReport base(String orderId, String execId, char execType, char ordStatus,
            String clOrdId, String symbol, char side, long orderQuantity, long timestampMicros) {
        ExecutionReport report = new ExecutionReport();
        report.setString(OrderID.FIELD, orderId);
        report.setString(ExecID.FIELD, execId);
        report.setChar(ExecType.FIELD, execType);
        report.setChar(OrdStatus.FIELD, ordStatus);
        report.setString(ClOrdID.FIELD, clOrdId);
        report.setString(Symbol.FIELD, symbol);
        report.setChar(Side.FIELD, side);
        if (orderQuantity > 0) {
            report.setDecimal(OrderQty.FIELD, BigDecimal.valueOf(orderQuantity));
        }
        report.setUtcTimeStamp(TransactTime.FIELD, utc(timestampMicros), UtcTimestampPrecision.MICROS);
        return report;
    }

    /** Microseconds since the epoch (RS-006) as a UTC date-time. */
    static LocalDateTime utc(long micros) {
        return LocalDateTime.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                (int) Math.floorMod(micros, 1_000_000L) * 1_000, ZoneOffset.UTC);
    }
}
