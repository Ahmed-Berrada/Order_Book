package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.TradingPhase;
import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /api/v1/instruments/{symbol}/phase}: the phase to move the instrument to (TP-002). */
public record PhaseRequest(@NotNull TradingPhase phase) {
}
