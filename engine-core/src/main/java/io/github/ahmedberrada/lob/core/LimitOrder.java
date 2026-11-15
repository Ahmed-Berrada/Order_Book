package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/**
 * New order that trades at its limit price or better, and rests in the book for any remainder
 * (rulebook CT-004, CT-005).
 *
 * <p>Price and quantity are not validated here: an invalid value is a business outcome, reported by
 * the engine as {@link OrderRejected} (CT-009).
 */
public record LimitOrder(Side side, long priceTicks, long quantity) implements Command {

    public LimitOrder {
        Objects.requireNonNull(side, "side");
    }
}
