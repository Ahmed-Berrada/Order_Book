package io.github.ahmedberrada.lob.core;

/**
 * One band of a tick-size regime (TK-001): prices from {@code fromPriceTicks} up to the next band's start
 * must be multiples of {@code stepTicks}.
 */
public record TickBand(long fromPriceTicks, long stepTicks) {

    public TickBand {
        if (fromPriceTicks < 1) {
            throw new IllegalArgumentException("a band starts at price 1 or more: " + fromPriceTicks);
        }
        if (stepTicks < 1) {
            throw new IllegalArgumentException("a step is at least 1 tick: " + stepTicks);
        }
    }
}
