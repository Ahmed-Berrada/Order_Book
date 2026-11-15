package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/**
 * New order that waits outside the book until a trade reaches its stop price, then enters as a MARKET
 * IOC order or, with a limit price, as a LIMIT GTC order (rulebook ST-001 to ST-006).
 *
 * @param limitPriceTicks the limit of a stop-limit order, or 0 for a stop-market order
 */
public record StopOrder(Side side, long stopPriceTicks, long limitPriceTicks, long quantity) implements Command {

    public StopOrder {
        Objects.requireNonNull(side, "side");
    }

    /** A stop-market order. */
    public StopOrder(Side side, long stopPriceTicks, long quantity) {
        this(side, stopPriceTicks, 0, quantity);
    }
}
