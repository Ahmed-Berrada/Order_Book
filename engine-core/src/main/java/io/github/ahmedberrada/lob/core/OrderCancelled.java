package io.github.ahmedberrada.lob.core;

/** Quantity of an order was removed from, or never added to, the book (CT-006, CT-008). */
public record OrderCancelled(long sequence, long orderId, long cancelledQuantity, CancelReason reason)
        implements Event {
}
