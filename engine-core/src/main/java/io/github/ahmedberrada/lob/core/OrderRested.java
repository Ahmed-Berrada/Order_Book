package io.github.ahmedberrada.lob.core;

/** The unfilled remainder of a limit order now rests in the book (CT-005). */
public record OrderRested(long sequence, long orderId, Side side, long priceTicks, long quantity)
        implements Event {
}
