package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.OrderReplaced;
import io.github.ahmedberrada.lob.core.OrderRested;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of a successful replace (OT-008). {@code filledQuantity} and {@code trades} are the fills
 * of an aggressive replace (OT-007); {@code restingQuantity} is what rests afterwards.
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
        long filled = 0;
        long resting = replaced.priorityKept() ? replaced.quantity() : 0;
        List<OrderResponse.Trade> trades = new ArrayList<>();
        for (Event event : batch.events().subList(1, batch.events().size())) {
            switch (event) {
                case TradeExecuted e -> {
                    filled += e.quantity();
                    trades.add(new OrderResponse.Trade(e.sequence(), e.makerOrderId(),
                            Prices.format(instrument, e.priceTicks()), e.quantity()));
                }
                case OrderRested e -> resting = e.quantity();
                default -> throw new IllegalArgumentException("unexpected event after a replace: " + event);
            }
        }
        return new ReplaceResponse(replaced.orderId(), Prices.format(instrument, replaced.priceTicks()),
                replaced.priorityKept(), batch.commandSequence(), Timestamps.of(batch), filled, resting, trades);
    }
}
