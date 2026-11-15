package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/**
 * Static definition of a tradable instrument, as seen by the core.
 *
 * <p>Prices are integer ticks and quantities integer lots: converting from decimals is the access
 * layer's job. The two maxima bound every order (rulebook CT-009) and are chosen so that
 * {@code price × quantity} can never overflow a {@code long} (ADR-0002 §6).
 *
 * @param symbol           instrument identifier, e.g. {@code AAPL}
 * @param maxOrderQuantity largest accepted order quantity, in lots
 * @param maxPriceTicks    largest accepted limit price, in ticks
 */
public record Instrument(String symbol, long maxOrderQuantity, long maxPriceTicks) {

    public Instrument {
        Objects.requireNonNull(symbol, "symbol");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (maxOrderQuantity <= 0) {
            throw new IllegalArgumentException("maxOrderQuantity must be positive: " + maxOrderQuantity);
        }
        if (maxPriceTicks <= 0) {
            throw new IllegalArgumentException("maxPriceTicks must be positive: " + maxPriceTicks);
        }
        try {
            Math.multiplyExact(maxOrderQuantity, maxPriceTicks);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("maxOrderQuantity × maxPriceTicks overflows a long", e);
        }
    }
}
