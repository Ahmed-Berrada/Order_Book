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
        long filled = 0;
        long resting = replaced.priorityKept() ? replaced.quantity() : 0;
        List<OrderResponse.Trade> trades = new ArrayList<>();
        long orderId = replaced.orderId();
        for (Event event : batch.events().subList(1, batch.events().size())) {
            // attributed by order ID: icebergs' refills and released stops' events can follow (ST-005)
            switch (event) {
                case TradeExecuted e when e.takerOrderId() == orderId || e.makerOrderId() == orderId -> {
                    boolean taker = e.takerOrderId() == orderId;
                    filled += e.quantity();
                    trades.add(new OrderResponse.Trade(e.sequence(), taker ? e.makerOrderId() : e.takerOrderId(),
                            Prices.format(instrument, e.priceTicks()), e.quantity()));
                    if (!taker) {
                        resting = e.makerRemainingQuantity();
                    }
                }
                case OrderRested e when e.orderId() == orderId -> resting = e.quantity();
                default -> {
                    // another order's event
                }
            }
        }
        return new ReplaceResponse(replaced.orderId(), Prices.format(instrument, replaced.priceTicks()),
                replaced.priorityKept(), batch.commandSequence(), Timestamps.of(batch), filled, resting, trades);
    }
}
