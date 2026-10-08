package io.github.ahmedberrada.lob.core;

/**
 * Outcome emitted by the matching engine. The hierarchy is sealed so that every {@code switch} over
 * events is checked for exhaustiveness by the compiler.
 */
public sealed interface Event
        permits OrderAccepted, OrderRejected, TradeExecuted, OrderRested, OrderCancelled, CancelRejected {

    /** Position of this event in the instrument's event stream: 1, 2, 3… with no gaps (CT-011). */
    long sequence();
}
