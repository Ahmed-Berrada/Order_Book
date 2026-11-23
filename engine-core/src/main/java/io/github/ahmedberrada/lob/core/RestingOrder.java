package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/**
 * A resting order as captured in an {@link EngineSnapshot}.
 *
 * @param displayQuantity   an iceberg's peak size, or 0 for a fully visible order
 * @param displayedQuantity the quantity currently visible: the remainder, or the current peak
 */
public record RestingOrder(long orderId, Side side, long priceTicks, long remainingQuantity, boolean postOnly,
        long displayQuantity, long displayedQuantity) {

    public RestingOrder {
        Objects.requireNonNull(side, "side");
    }

    /** A fully visible resting order. */
    public RestingOrder(long orderId, Side side, long priceTicks, long remainingQuantity, boolean postOnly) {
        this(orderId, side, priceTicks, remainingQuantity, postOnly, 0, remainingQuantity);
    }

    /** A fully visible resting order without the post-only attribute. */
    public RestingOrder(long orderId, Side side, long priceTicks, long remainingQuantity) {
        this(orderId, side, priceTicks, remainingQuantity, false);
    }
}
