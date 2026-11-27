package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/**
 * Moves the instrument to another trading phase (TP-002). The engine never reads a clock, so the venue
 * or an operator decides when; the change is journaled and replayed like any command.
 */
public record SetPhase(TradingPhase phase) implements Command {
    public SetPhase {
        Objects.requireNonNull(phase, "phase");
    }
}
