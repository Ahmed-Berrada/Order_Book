package io.github.ahmedberrada.lob.core;

/** The trading phase of an instrument (TP-001). */
public enum TradingPhase {
    /** Orders match on arrival (CT-001 to CT-015). */
    CONTINUOUS,
    /** Orders queue up and nothing trades until the uncross (TP-005, AU-001 to AU-006). */
    CALL,
    /** No new order is accepted; cancels are (TP-004). */
    CLOSED
}
