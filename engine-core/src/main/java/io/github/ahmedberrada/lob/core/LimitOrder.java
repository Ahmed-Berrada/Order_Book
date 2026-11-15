package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/**
 * New order that trades at its limit price or better (rulebook CT-004). What happens to its remainder
 * depends on its time in force (OT-001); a post-only order never takes liquidity (OT-004).
 *
 * <p>Price and quantity are not validated here: an invalid value is a business outcome, reported by
 * the engine as {@link OrderRejected} (CT-009).
 */
public record LimitOrder(Side side, long priceTicks, long quantity, TimeInForce timeInForce, boolean postOnly)
        implements Command {

    public LimitOrder {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(timeInForce, "timeInForce");
    }

    /** A good-till-cancelled order that may take liquidity. */
    public LimitOrder(Side side, long priceTicks, long quantity) {
        this(side, priceTicks, quantity, TimeInForce.GTC, false);
    }
}
