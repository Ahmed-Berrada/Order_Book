package io.github.ahmedberrada.lob.core;

/**
 * A resting order. Mutable and internal to the core: only its remaining quantity changes, and only
 * through {@link OrderBook}. A replace that changes the price creates a new instance.
 *
 * <p>Equality is identity on purpose, so that removing an order from a queue removes that exact
 * order.
 */
final class Order {

    final long orderId;
    final Side side;
    final long priceTicks;
    final boolean postOnly;
    long remainingQuantity;

    Order(long orderId, Side side, long priceTicks, long remainingQuantity, boolean postOnly) {
        this.orderId = orderId;
        this.side = side;
        this.priceTicks = priceTicks;
        this.remainingQuantity = remainingQuantity;
        this.postOnly = postOnly;
    }
}
