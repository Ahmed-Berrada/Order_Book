package io.github.ahmedberrada.lob.core;

/**
 * A resting order now has a new price and remaining quantity (OT-008). If {@code priorityKept} is
 * false, the order has left the book: trades and an {@link OrderRested} for its remainder may follow.
 */
public record OrderReplaced(
        long sequence, long orderId, Side side, long priceTicks, long quantity, boolean priorityKept)
        implements Event {
}
