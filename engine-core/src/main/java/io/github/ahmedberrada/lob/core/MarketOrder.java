package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/**
 * New order that trades at any price and never rests (rulebook CT-006, CT-007). It has no price
 * field on purpose (ADR-0002 §2). Its time in force is IOC or FOK (OT-001).
 */
public record MarketOrder(Side side, long quantity, TimeInForce timeInForce) implements Command {

    public MarketOrder {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(timeInForce, "timeInForce");
    }

    /** An immediate-or-cancel market order. */
    public MarketOrder(Side side, long quantity) {
        this(side, quantity, TimeInForce.IOC);
    }
}
