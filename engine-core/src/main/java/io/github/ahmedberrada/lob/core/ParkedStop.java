package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/** A stop order waiting for its trigger, as captured in an {@link EngineSnapshot} (ST-008). */
public record ParkedStop(
        long orderId, Side side, long stopPriceTicks, long limitPriceTicks, long quantity, SelfTradeInstruction selfTrade) {

    public ParkedStop {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(selfTrade, "selfTrade");
    }

    /** A stop with no self-trade prevention. */
    public ParkedStop(long orderId, Side side, long stopPriceTicks, long limitPriceTicks, long quantity) {
        this(orderId, side, stopPriceTicks, limitPriceTicks, quantity, SelfTradeInstruction.NONE);
    }

    /** Whether a trade at this price triggers the stop (ST-004). */
    boolean isTriggeredBy(long tradePriceTicks) {
        return side == Side.BUY ? tradePriceTicks >= stopPriceTicks : tradePriceTicks <= stopPriceTicks;
    }
}
