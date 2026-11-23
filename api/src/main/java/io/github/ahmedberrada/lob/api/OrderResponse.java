package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.OrderAccepted;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import java.time.Instant;
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

    /**
     * FIX {@code OrdStatus} after the command, plus {@code PARKED} for a stop order waiting for its
     * trigger (ST-003).
     */
    public enum Status { NEW, PARTIALLY_FILLED, FILLED, CANCELLED, PARKED }

    /** One fill of this order. {@code makerOrderId} is the other order's ID. Prices are decimal strings. */
    public record Trade(long sequence, long makerOrderId, String price, long quantity) {
    }

    /** Builds the response from the events of an accepted order's command (see {@link OrderFills}). */
    static OrderResponse from(EventBatch batch, InstrumentConfig instrument) {
        long orderId = ((OrderAccepted) batch.events().getFirst()).orderId();
        OrderFills fills = OrderFills.of(orderId, 0, batch.events(), 1, instrument);
        Status status;
        if (fills.parked) {
            status = Status.PARKED;
        } else if (fills.resting > 0) {
            status = fills.filled > 0 ? Status.PARTIALLY_FILLED : Status.NEW;
        } else {
            status = fills.cancelled > 0 ? Status.CANCELLED : Status.FILLED;
        }
        return new OrderResponse(orderId, status, batch.commandSequence(), Timestamps.of(batch),
                fills.filled, fills.resting, fills.cancelled, fills.trades);
    }
}
