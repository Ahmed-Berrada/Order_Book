package io.github.ahmedberrada.lob.core;

import java.util.List;
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
 * @param tickBands        the tick-size regime (TK-001)
 * @param volatilityBandBps largest accepted move from the reference price, in basis points; 0 for none (VI-002)
 */
public record Instrument(String symbol, long maxOrderQuantity, long maxPriceTicks, List<TickBand> tickBands,
        long volatilityBandBps) {

    /** The largest volatility band, in basis points: 100% (VI-002). */
    public static final long MAX_VOLATILITY_BAND_BPS = 10_000;

    public Instrument {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(tickBands, "tickBands");
        tickBands = List.copyOf(tickBands);
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
            if (volatilityBandBps > 0) {
                Math.multiplyExact(maxPriceTicks, MAX_VOLATILITY_BAND_BPS);   // the band arithmetic of VI-002
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("maxOrderQuantity × maxPriceTicks overflows a long", e);
        }
        if (tickBands.isEmpty() || tickBands.getFirst().fromPriceTicks() != 1) {
            throw new IllegalArgumentException("the tick table must start at price 1: " + tickBands);
        }
        for (int i = 1; i < tickBands.size(); i++) {
            if (tickBands.get(i).fromPriceTicks() <= tickBands.get(i - 1).fromPriceTicks()) {
                throw new IllegalArgumentException("tick bands must start at increasing prices: " + tickBands);
            }
        }
        if (volatilityBandBps < 0 || volatilityBandBps > MAX_VOLATILITY_BAND_BPS) {
            throw new IllegalArgumentException("volatility band must be 0 to 10000 bps: " + volatilityBandBps);
        }
    }

    /** An instrument with a single tick step of 1 and no volatility band. */
    public Instrument(String symbol, long maxOrderQuantity, long maxPriceTicks) {
        this(symbol, maxOrderQuantity, maxPriceTicks, List.of(new TickBand(1, 1)), 0);
    }

    /** The step a price must be a multiple of (TK-002). */
    public long stepAt(long priceTicks) {
        long step = tickBands.getFirst().stepTicks();
        for (TickBand band : tickBands) {
            if (band.fromPriceTicks() > priceTicks) {
                break;
            }
            step = band.stepTicks();
        }
        return step;
    }
}
