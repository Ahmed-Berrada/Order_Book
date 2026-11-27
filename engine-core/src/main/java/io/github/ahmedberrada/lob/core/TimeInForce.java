package io.github.ahmedberrada.lob.core;

/** How long the unfilled part of an order stays active (rulebook OT-001). */
public enum TimeInForce {
    /** Good till cancelled: a LIMIT remainder rests in the book. Not valid for MARKET orders. */
    GTC,
    /** Immediate or cancel: whatever does not trade on arrival is cancelled (OT-002). */
    IOC,
    /** Fill or kill: the order trades in full on arrival, or not at all (OT-003). */
    FOK,
    /** Good for the trading day: rests like GTC and expires when the instrument closes (DY-001, DY-002). */
    DAY;

    /** Whether the unfilled remainder of a limit order with this time in force rests in the book. */
    public boolean rests() {
        return this == GTC || this == DAY;
    }
}
