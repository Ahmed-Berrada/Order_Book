package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/**
 * New order that trades at its limit price or better (rulebook CT-004). What happens to its remainder
 * depends on its time in force (OT-001); a post-only order never takes liquidity (OT-004). A non-zero
 * display quantity below the quantity makes it an iceberg (IC-001). Its self-trade instruction says
 * whom it must not trade with (SP-001).
 *
 * <p>Price and quantity are not validated here: an invalid value is a business outcome, reported by
 * the engine as {@link OrderRejected} (CT-009).
 */
public record LimitOrder(
        Side side, long priceTicks, long quantity, TimeInForce timeInForce, boolean postOnly, long displayQuantity,
        SelfTradeInstruction selfTrade) implements Command {

    public LimitOrder {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(timeInForce, "timeInForce");
        Objects.requireNonNull(selfTrade, "selfTrade");
    }

    /** An order with no self-trade prevention. */
    public LimitOrder(
            Side side, long priceTicks, long quantity, TimeInForce timeInForce, boolean postOnly, long displayQuantity) {
        this(side, priceTicks, quantity, timeInForce, postOnly, displayQuantity, SelfTradeInstruction.NONE);
    }

    /** A fully visible order. */
    public LimitOrder(Side side, long priceTicks, long quantity, TimeInForce timeInForce, boolean postOnly) {
        this(side, priceTicks, quantity, timeInForce, postOnly, 0);
    }

    /** A good-till-cancelled, fully visible order that may take liquidity. */
    public LimitOrder(Side side, long priceTicks, long quantity) {
        this(side, priceTicks, quantity, TimeInForce.GTC, false);
    }
}
