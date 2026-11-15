package io.github.ahmedberrada.lob.core;

/**
 * An instruction to the matching engine. The hierarchy is sealed so that every {@code switch} over
 * commands is checked for exhaustiveness by the compiler.
 */
public sealed interface Command permits LimitOrder, MarketOrder, CancelOrder {
}
