package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.OrderCancelled;
import io.github.ahmedberrada.lob.core.OrderParked;
import io.github.ahmedberrada.lob.core.OrderRested;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * What one command's events did to one order. A batch can hold other orders' events, from icebergs
 * refilling to stops released by the command's trades (ST-005), so every event is attributed by order
 * ID; the order can even be filled as a maker by a stop it released.
 */
final class OrderFills {

    final long orderId;
    long filled;
    long resting;
    long cancelled;
    boolean parked;
    final List<OrderResponse.Trade> trades = new ArrayList<>();

    private OrderFills(long orderId, long resting) {
        this.orderId = orderId;
        this.resting = resting;
    }

    /** Applies the events, skipping the first {@code skip}, starting from a resting quantity. */
    static OrderFills of(long orderId, long resting, List<Event> events, int skip, InstrumentConfig instrument) {
        OrderFills fills = new OrderFills(orderId, resting);
        for (Event event : events.subList(skip, events.size())) {
            switch (event) {
                case TradeExecuted e when e.takerOrderId() == orderId || e.makerOrderId() == orderId -> {
                    boolean taker = e.takerOrderId() == orderId;
                    fills.filled += e.quantity();
                    fills.trades.add(new OrderResponse.Trade(e.sequence(), taker ? e.makerOrderId() : e.takerOrderId(),
                            Prices.format(instrument, e.priceTicks()), e.quantity()));
                    if (!taker) {
                        fills.resting = e.makerRemainingQuantity();
                    }
                }
                case OrderRested e when e.orderId() == orderId -> fills.resting = e.quantity();
                case OrderCancelled e when e.orderId() == orderId -> fills.cancelled = e.cancelledQuantity();
                case OrderParked e when e.orderId() == orderId -> fills.parked = true;
                default -> {
                    // another order's event
                }
            }
        }
        return fills;
    }
}
