package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.OrderReplaced;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import java.time.Instant;
import java.util.List;

/**
 * Outcome of a successful replace (OT-008). {@code filledQuantity} and {@code trades} are the fills
 * of an aggressive replace (OT-007), or by stops it released; {@code restingQuantity} is what rests
 * afterwards.
 */
public record ReplaceResponse(
        long orderId,
        String price,
        boolean priorityKept,
        long commandSequence,
        Instant timestamp,
        long filledQuantity,
        long restingQuantity,
        List<OrderResponse.Trade> trades) {

    static ReplaceResponse from(EventBatch batch, InstrumentConfig instrument) {
        OrderReplaced replaced = (OrderReplaced) batch.events().getFirst();
        // kept priority: the order rests with its new quantity unless a released stop fills it
        OrderFills fills = OrderFills.of(replaced.orderId(), replaced.priorityKept() ? replaced.quantity() : 0,
                batch.events(), 1, instrument);
        return new ReplaceResponse(replaced.orderId(), Prices.format(instrument, replaced.priceTicks()),
                replaced.priorityKept(), batch.commandSequence(), Timestamps.of(batch), fills.filled, fills.resting,
                fills.trades);
    }
}
