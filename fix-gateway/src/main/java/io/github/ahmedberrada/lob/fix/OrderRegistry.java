package io.github.ahmedberrada.lob.fix;

import io.github.ahmedberrada.lob.service.InstrumentConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import quickfix.SessionID;

/**
 * Which session owns which order, under which ClOrdID (ADR-0006 §3). In memory: see the limitation in
 * the Rules of Engagement.
 *
 * <p>An order's fills are applied on its instrument's writer thread; status and cancel requests read it
 * from QuickFIX/J threads, hence the synchronized accessors.
 */
final class OrderRegistry {

    /** What a session's ClOrdID refers to. */
    static final class Slot {
        private volatile FixOrder order;
        private volatile boolean refused;

        /** The order, or {@code null} while its acknowledgement is pending or if it was refused. */
        FixOrder order() {
            return order;
        }

        boolean isPending() {
            return order == null && !refused;
        }
    }

    /** A FIX order: its owner and its cumulative execution state. */
    static final class FixOrder {
        final SessionID session;
        final String clOrdId;
        final InstrumentConfig instrument;
        final char side;
        final long orderId;
        final long orderQuantity;
        private long cumulativeQuantity;
        private long notionalTicks;
        private long leavesQuantity;
        private char ordStatus;

        FixOrder(SessionID session, String clOrdId, InstrumentConfig instrument, char side, long orderId,
                long orderQuantity, char ordStatus) {
            this.session = session;
            this.clOrdId = clOrdId;
            this.instrument = instrument;
            this.side = side;
            this.orderId = orderId;
            this.orderQuantity = orderQuantity;
            this.leavesQuantity = ordStatus == quickfix.field.OrdStatus.REJECTED ? 0 : orderQuantity;
            this.ordStatus = ordStatus;
        }

        String fixOrderId() {
            return instrument.symbol() + "-" + orderId;
        }

        synchronized void fill(long priceTicks, long quantity, long remaining) {
            cumulativeQuantity += quantity;
            notionalTicks = Math.addExact(notionalTicks, Math.multiplyExact(priceTicks, quantity));
            leavesQuantity = remaining;
            ordStatus = remaining == 0 ? quickfix.field.OrdStatus.FILLED : quickfix.field.OrdStatus.PARTIALLY_FILLED;
        }

        synchronized void cancel() {
            leavesQuantity = 0;
            ordStatus = quickfix.field.OrdStatus.CANCELED;
        }

        synchronized long cumulativeQuantity() {
            return cumulativeQuantity;
        }

        synchronized long leavesQuantity() {
            return leavesQuantity;
        }

        synchronized char ordStatus() {
            return ordStatus;
        }

        /** Volume-weighted average price, exact to ten decimals; zero before the first fill. */
        synchronized BigDecimal averagePrice() {
            if (cumulativeQuantity == 0) {
                return BigDecimal.ZERO;
            }
            return instrument.toPrice(notionalTicks)
                    .divide(BigDecimal.valueOf(cumulativeQuantity), 10, RoundingMode.HALF_EVEN)
                    .stripTrailingZeros();
        }
    }

    private record OrderKey(String symbol, long orderId) {
    }

    private final ConcurrentMap<SessionID, ConcurrentMap<String, Slot>> slots = new ConcurrentHashMap<>();
    private final ConcurrentMap<OrderKey, FixOrder> orders = new ConcurrentHashMap<>();

    /** Reserves a ClOrdID for a session; {@code false} if the session has already used it. */
    boolean reserve(SessionID session, String clOrdId) {
        return slots.computeIfAbsent(session, s -> new ConcurrentHashMap<>()).putIfAbsent(clOrdId, new Slot()) == null;
    }

    /** The order was refused before reaching the engine; the ClOrdID stays used. */
    void refused(SessionID session, String clOrdId) {
        slot(session, clOrdId).refused = true;
    }

    /** Records an order the engine accepted or rejected, under its owner's ClOrdID. */
    void created(FixOrder order) {
        orders.put(new OrderKey(order.instrument.symbol(), order.orderId), order);
        slot(order.session, order.clOrdId).order = order;
    }

    /** The order the engine knows as {@code orderId}, if a FIX session created it. */
    FixOrder byOrderId(String symbol, long orderId) {
        return orders.get(new OrderKey(symbol, orderId));
    }

    /** What a session's ClOrdID refers to, or {@code null} if the session never used it. */
    Slot slot(SessionID session, String clOrdId) {
        ConcurrentMap<String, Slot> bySession = slots.get(session);
        return bySession == null ? null : bySession.get(clOrdId);
    }
}
