package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/** A resting order as captured in an {@link EngineSnapshot}. */
public record RestingOrder(long orderId, Side side, long priceTicks, long remainingQuantity) {

    public RestingOrder {
        Objects.requireNonNull(side, "side");
    }
}
