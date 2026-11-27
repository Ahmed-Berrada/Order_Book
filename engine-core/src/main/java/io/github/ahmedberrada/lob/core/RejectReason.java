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
    INVALID_DISPLAY_QUANTITY,
    /** A stop order's stop price has already been reached by the last trade (ST-002). */
    STOP_PRICE_ALREADY_REACHED,
    /** An order's self-trade prevention group is negative (SP-001). */
    INVALID_SELF_TRADE_GROUP,
    /** The order, or the replace, is not allowed in the current trading phase (TP-004, TP-005). */
    NOT_ALLOWED_IN_PHASE,
    /** A phase change asked for the phase the instrument is already in (TP-002). */
    INVALID_PHASE_TRANSITION,
    /** The price is not a multiple of the step of its band (TK-002). */
    INVALID_TICK_SIZE,
    /** The member is suspended (RK-002, RK-004). */
    MEMBER_SUSPENDED,
    /** The quantity exceeds the member's limit (RK-002). */
    RISK_ORDER_QUANTITY,
    /** Price × quantity exceeds the member's limit (RK-002). */
    RISK_ORDER_NOTIONAL,
    /** The price is too far from the last trade price (RK-002). */
    RISK_PRICE_COLLAR,
    /** The member has too many open orders (RK-002). */
    RISK_OPEN_ORDERS,
    /** The order could take the member's position past its limit (RK-002). */
    RISK_POSITION,
    /** A member limit is negative, or the command names member 0 (RK-001). */
    INVALID_MEMBER_LIMITS,
    /** Suspending member 0 or a suspended member, or resuming one that is not suspended (RK-004, RK-005). */
    INVALID_MEMBER
}
