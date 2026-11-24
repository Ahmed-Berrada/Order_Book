package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/**
 * A resting order as captured in an {@link EngineSnapshot}.
 *
 * @param displayQuantity   an iceberg's peak size, or 0 for a fully visible order
 * @param displayedQuantity the quantity currently visible: the remainder, or the current peak
 * @param selfTrade         the instruction the order came with, applied if it becomes incoming again (SP-008)
 */
public record RestingOrder(long orderId, Side side, long priceTicks, long remainingQuantity, boolean postOnly,
        long displayQuantity, long displayedQuantity, SelfTradeInstruction selfTrade) {

    public RestingOrder {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(selfTrade, "selfTrade");
    }

    /** A resting order with no self-trade prevention. */
    public RestingOrder(long orderId, Side side, long priceTicks, long remainingQuantity, boolean postOnly,
            long displayQuantity, long displayedQuantity) {
        this(orderId, side, priceTicks, remainingQuantity, postOnly, displayQuantity, displayedQuantity,
                SelfTradeInstruction.NONE);
    }

    /** A fully visible resting order. */
    public RestingOrder(long orderId, Side side, long priceTicks, long remainingQuantity, boolean postOnly) {
        this(orderId, side, priceTicks, remainingQuantity, postOnly, 0, remainingQuantity);
    }

    /** A fully visible resting order without the post-only attribute. */
    public RestingOrder(long orderId, Side side, long priceTicks, long remainingQuantity) {
        this(orderId, side, priceTicks, remainingQuantity, false);
    }
}
