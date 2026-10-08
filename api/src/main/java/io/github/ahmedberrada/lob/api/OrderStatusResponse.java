package io.github.ahmedberrada.lob.api;

/** Whether an order rests in the book, and for how much. Not resting covers filled, cancelled and unknown. */
public record OrderStatusResponse(long orderId, boolean resting, long restingQuantity) {
}
