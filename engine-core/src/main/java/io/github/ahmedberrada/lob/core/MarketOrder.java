package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/**
 * New order that trades at any price and never rests (rulebook CT-006, CT-007). It has no price
 * field on purpose (ADR-0002 §2).
 */
public record MarketOrder(Side side, long quantity) implements Command {

    public MarketOrder {
        Objects.requireNonNull(side, "side");
    }
}
