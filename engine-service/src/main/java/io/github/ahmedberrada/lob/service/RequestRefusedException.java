package io.github.ahmedberrada.lob.service;

import java.util.Objects;

/**
 * A request that never reached the matching engine, and was therefore not journaled (ADR-0004 §3).
 * Engine rejections, such as an invalid quantity, are {@code OrderRejected} events instead.
 */
public final class RequestRefusedException extends RuntimeException {

    /** Why the request was refused. Each access protocol maps these to its own codes. */
    public enum Reason {
        /** The symbol is not a listed instrument (OE-001). */
        UNKNOWN_INSTRUMENT,
        /** The price is not a multiple of the instrument's tick size (OE-002). */
        OFF_TICK_PRICE,
        /** The instrument's queue is full (OE-005). */
        OVERLOADED,
        /** The instrument stopped after a journal failure (OE-006). */
        HALTED,
        /** The venue is shutting down (OE-007). */
        STOPPED
    }

    private final Reason reason;
    private final String symbol;

    public RequestRefusedException(Reason reason, String symbol) {
        super(reason + ": " + symbol);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.symbol = symbol;
    }

    public Reason reason() {
        return reason;
    }

    public String symbol() {
        return symbol;
    }
}
