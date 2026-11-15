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
    /** Cancel or replace targeted an order that is not resting. */
    UNKNOWN_ORDER,
    /** Time in force not allowed for this order (OT-001, OT-004). */
    INVALID_TIME_IN_FORCE,
    /** A post-only order, or the replace of one, would have traded on arrival (OT-004, OT-005). */
    POST_ONLY_WOULD_TRADE,
    /** Display quantity negative, above the quantity, or on an order that cannot rest (IC-001). */
    INVALID_DISPLAY_QUANTITY
}
