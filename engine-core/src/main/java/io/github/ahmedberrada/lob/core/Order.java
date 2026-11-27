package io.github.ahmedberrada.lob.core;

/**
 * A resting order. Mutable and internal to the core: only its quantities change, and only through
 * {@link OrderBook}. A replace that changes the price creates a new instance.
 *
 * <p>A fully visible order has {@code displayQuantity == 0} and {@code displayed == remainingQuantity}.
 * An iceberg shows {@code displayed <= displayQuantity} and hides the rest (IC-002).
 *
 * <p>Equality is identity on purpose, so that removing an order from a queue removes that exact
 * order.
 */
final class Order {

    final long orderId;
    final Side side;
    final long priceTicks;
    final boolean postOnly;
    final long displayQuantity;
    final SelfTradeInstruction selfTrade;
    final boolean day;
    final long memberId;
    long remainingQuantity;
    long displayed;
    /** Neighbours in the price level's queue, oldest first; {@code null} at the ends (O(1) removal). */
    Order previous;
    Order next;

    Order(long orderId, Side side, long priceTicks, long remainingQuantity, boolean postOnly, long displayQuantity,
            SelfTradeInstruction selfTrade, boolean day, long memberId) {
        this.day = day;
        this.memberId = memberId;
        this.orderId = orderId;
        this.side = side;
        this.priceTicks = priceTicks;
        this.remainingQuantity = remainingQuantity;
        this.postOnly = postOnly;
        this.displayQuantity = displayQuantity;
        this.selfTrade = selfTrade;
        this.displayed = peak(remainingQuantity);
    }

    /** What this order shows when it holds {@code quantity}: all of it, or a full peak. */
    long peak(long quantity) {
        return displayQuantity == 0 ? quantity : Math.min(displayQuantity, quantity);
    }
}
