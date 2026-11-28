package io.github.ahmedberrada.lob.service;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

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
        STOPPED,
        /** The member is over its message rate (OE-008). */
        THROTTLED,
        /** The member is over its order-to-trade ratio for the day (OE-009). */
        ORDER_TO_TRADE_LIMIT
    }

    private final Reason reason;
    private final String symbol;
    private final Duration retryAfter;

    public RequestRefusedException(Reason reason, String symbol) {
        this(reason, symbol, null);
    }

    /**
     * @param retryAfter how long until the same request may be accepted, or {@code null} if not known
     */
    public RequestRefusedException(Reason reason, String symbol, Duration retryAfter) {
        super(reason + ": " + symbol);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.symbol = symbol;
        this.retryAfter = retryAfter;
    }

    public Reason reason() {
        return reason;
    }

    public String symbol() {
        return symbol;
    }

    /** How long until the same request may be accepted, if known (OE-008, OE-009). */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
