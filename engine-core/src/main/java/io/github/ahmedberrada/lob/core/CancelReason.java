package io.github.ahmedberrada.lob.core;

/** Why quantity was cancelled. */
public enum CancelReason {
    /** The order's owner asked for the cancel. */
    CLIENT_REQUEST,
    /** A market order ran out of liquidity before being fully filled. */
    UNFILLED_MARKET_REMAINDER,
    /** The remainder of a LIMIT IOC order (OT-002). */
    IMMEDIATE_OR_CANCEL,
    /** A FOK order that could not be filled in full on arrival (OT-003). */
    FILL_OR_KILL
}
