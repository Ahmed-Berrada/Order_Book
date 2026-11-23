package io.github.ahmedberrada.lob.core;

/**
 * An iceberg's peak was filled and a new one is shown; the order is now last in its price's queue
 * (IC-004, IC-006).
 */
public record OrderReplenished(long sequence, long orderId, Side side, long priceTicks, long displayedQuantity)
        implements Event {
}
