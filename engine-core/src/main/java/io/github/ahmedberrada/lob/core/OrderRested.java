package io.github.ahmedberrada.lob.core;

/**
 * The unfilled remainder of a limit order now rests in the book (CT-005). {@code displayedQuantity} is
 * the part the market may see: the whole remainder, or an iceberg's peak (IC-002).
 */
public record OrderRested(
        long sequence, long orderId, Side side, long priceTicks, long quantity, long displayedQuantity)
        implements Event {

    /** A fully visible order. */
    public OrderRested(long sequence, long orderId, Side side, long priceTicks, long quantity) {
        this(sequence, orderId, side, priceTicks, quantity, quantity);
    }
}
