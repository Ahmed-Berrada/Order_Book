package io.github.ahmedberrada.lob.core;

/** An accepted stop order now waits for its trigger, outside the book (ST-003). */
public record OrderParked(long sequence, long orderId, Side side, long stopPriceTicks, long quantity)
        implements Event {
}
