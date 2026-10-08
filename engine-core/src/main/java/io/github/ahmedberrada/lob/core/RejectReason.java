package io.github.ahmedberrada.lob.core;

/** Why a command was refused. Rejections are business outcomes, not exceptions (ADR-0001 §7). */
public enum RejectReason {
    /** Quantity is zero or negative. */
    INVALID_QUANTITY,
    /** Quantity exceeds {@link Instrument#maxOrderQuantity()}. */
    QUANTITY_ABOVE_MAXIMUM,
    /** Limit price is zero or negative. */
    INVALID_PRICE,
    /** Limit price exceeds {@link Instrument#maxPriceTicks()}. */
    PRICE_ABOVE_MAXIMUM,
    /** Market order arrived while the opposite side of the book was empty. */
    NO_LIQUIDITY,
    /** Cancel targeted an order that is not resting. */
    UNKNOWN_ORDER
}
