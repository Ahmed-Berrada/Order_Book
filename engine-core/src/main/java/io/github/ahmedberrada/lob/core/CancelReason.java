package io.github.ahmedberrada.lob.core;

/** Why quantity was cancelled. */
public enum CancelReason {
    /** The order's owner asked for the cancel. */
    CLIENT_REQUEST,
    /** A market order ran out of liquidity before being fully filled. */
    UNFILLED_MARKET_REMAINDER
}
