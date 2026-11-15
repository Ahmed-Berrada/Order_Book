package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/** A resting order as captured in an {@link EngineSnapshot}. */
public record RestingOrder(long orderId, Side side, long priceTicks, long remainingQuantity, boolean postOnly) {

    public RestingOrder {
        Objects.requireNonNull(side, "side");
    }

    /** A resting order without the post-only attribute. */
    public RestingOrder(long orderId, Side side, long priceTicks, long remainingQuantity) {
        this(orderId, side, priceTicks, remainingQuantity, false);
    }
}
