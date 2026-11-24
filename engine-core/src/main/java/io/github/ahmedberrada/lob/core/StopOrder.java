package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/**
 * New order that waits outside the book until a trade reaches its stop price, then enters as a MARKET
 * IOC order or, with a limit price, as a LIMIT GTC order (rulebook ST-001 to ST-006).
 *
 * @param limitPriceTicks the limit of a stop-limit order, or 0 for a stop-market order
 * @param selfTrade       applied when the stop is released (SP-008)
 */
public record StopOrder(
        Side side, long stopPriceTicks, long limitPriceTicks, long quantity, SelfTradeInstruction selfTrade)
        implements Command {

    public StopOrder {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(selfTrade, "selfTrade");
    }

    /** A stop order with no self-trade prevention. */
    public StopOrder(Side side, long stopPriceTicks, long limitPriceTicks, long quantity) {
        this(side, stopPriceTicks, limitPriceTicks, quantity, SelfTradeInstruction.NONE);
    }

    /** A stop-market order. */
    public StopOrder(Side side, long stopPriceTicks, long quantity) {
        this(side, stopPriceTicks, 0, quantity);
    }
}
