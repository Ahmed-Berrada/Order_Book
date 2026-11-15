package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.OrderAccepted;
import io.github.ahmedberrada.lob.core.OrderCancelled;
import io.github.ahmedberrada.lob.core.OrderParked;
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

    /**
     * FIX {@code OrdStatus} after the command, plus {@code PARKED} for a stop order waiting for its
     * trigger (ST-003).
     */
    public enum Status { NEW, PARTIALLY_FILLED, FILLED, CANCELLED, PARKED }

    /** One fill of this order. {@code makerOrderId} is the other order's ID. Prices are decimal strings. */
    public record Trade(long sequence, long makerOrderId, String price, long quantity) {
    }

    /**
     * Builds the response from the events of an accepted order's command. The batch can also hold other
     * orders' events, from icebergs refilling to stops released by this order's trades (ST-005), so every
     * event is attributed by order ID. The order itself can even be filled as a maker by a released stop.
     */
    static OrderResponse from(EventBatch batch, InstrumentConfig instrument) {
        long orderId = ((OrderAccepted) batch.events().getFirst()).orderId();
        long filled = 0;
        long resting = 0;
        long cancelled = 0;
        boolean parked = false;
        List<Trade> trades = new ArrayList<>();
        for (Event event : batch.events()) {
            switch (event) {
                case TradeExecuted e when e.takerOrderId() == orderId || e.makerOrderId() == orderId -> {
                    boolean taker = e.takerOrderId() == orderId;
                    filled += e.quantity();
                    trades.add(new Trade(e.sequence(), taker ? e.makerOrderId() : e.takerOrderId(),
                            Prices.format(instrument, e.priceTicks()), e.quantity()));
                    if (!taker) {
                        resting = e.makerRemainingQuantity();
                    }
                }
                case OrderRested e when e.orderId() == orderId -> resting = e.quantity();
                case OrderCancelled e when e.orderId() == orderId -> cancelled = e.cancelledQuantity();
                case OrderParked e when e.orderId() == orderId -> parked = true;
                default -> {
                    // another order's event
                }
            }
        }
        Status status;
        if (parked) {
            status = Status.PARKED;
        } else if (resting > 0) {
            status = filled > 0 ? Status.PARTIALLY_FILLED : Status.NEW;
        } else {
            status = cancelled > 0 ? Status.CANCELLED : Status.FILLED;
        }
        return new OrderResponse(orderId, status, batch.commandSequence(), Timestamps.of(batch),
                filled, resting, cancelled, trades);
    }
}
