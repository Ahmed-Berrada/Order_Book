package io.github.ahmedberrada.lob.core;

/** The instrument moved from one trading phase to another (TP-003). */
public record PhaseChanged(long sequence, TradingPhase from, TradingPhase to) implements Event {
}
