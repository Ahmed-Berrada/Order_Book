package io.github.ahmedberrada.lob.core;

/**
 * A fill would have executed outside the volatility band, so it did not happen and the instrument moves
 * to {@code CALL} (VI-002, VI-003). The {@link PhaseChanged} follows.
 *
 * @param referencePriceTicks the reference price compared with (VI-001)
 * @param priceTicks          the price that breached the band
 */
public record VolatilityInterruption(long sequence, long referencePriceTicks, long priceTicks) implements Event {
}
