package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.OrderAccepted;
import io.github.ahmedberrada.lob.core.OrderCancelled;
import io.github.ahmedberrada.lob.core.OrderRested;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Outcome of an accepted order, as it stands once its command is processed. */
public record OrderResponse(
        long orderId,
        Status status,
        long commandSequence,
        Instant timestamp,
        long filledQuantity,
        long restingQuantity,
        long cancelledQuantity,
        List<Trade> trades) {

    /** FIX {@code OrdStatus} after the command. */
    public enum Status { NEW, PARTIALLY_FILLED, FILLED, CANCELLED }

    /** One fill of this order against a resting order. Prices are decimal strings. */
    public record Trade(long sequence, long makerOrderId, String price, long quantity) {
    }

    /** Builds the response from the events of an accepted order (CT-012 event order). */
    static OrderResponse from(EventBatch batch, InstrumentConfig instrument) {
        long orderId = 0;
        long filled = 0;
        long resting = 0;
        long cancelled = 0;
        List<Trade> trades = new ArrayList<>();
        for (Event event : batch.events()) {
            switch (event) {
                case OrderAccepted e -> orderId = e.orderId();
                case TradeExecuted e -> {
                    filled += e.quantity();
                    trades.add(new Trade(e.sequence(), e.makerOrderId(),
                            Prices.format(instrument, e.priceTicks()), e.quantity()));
                }
                case OrderRested e -> resting = e.quantity();
                case OrderCancelled e -> cancelled = e.cancelledQuantity();
                default -> throw new IllegalArgumentException("unexpected event for an accepted order: " + event);
            }
        }
        Status status;
        if (resting > 0) {
            status = filled > 0 ? Status.PARTIALLY_FILLED : Status.NEW;
        } else {
            status = cancelled > 0 ? Status.CANCELLED : Status.FILLED;
        }
        return new OrderResponse(orderId, status, batch.commandSequence(), Timestamps.of(batch),
                filled, resting, cancelled, trades);
    }
}
