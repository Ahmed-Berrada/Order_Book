package io.github.ahmedberrada.lob.service;

import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.TickBand;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Definition of a listed instrument in venue units: decimal prices and a tick size. Converts prices
 * to and from the integer ticks of the core, exactly (rulebook OE-002, ADR-0004 §5).
 *
 * @param symbol           identifier, {@code [A-Z0-9][A-Z0-9._-]{0,15}}; it also names the journal directory
 * @param tickSize         smallest price increment, e.g. {@code 0.01}
 * @param maxOrderQuantity largest accepted order, in lots
 * @param maxPrice         largest accepted price; a multiple of the tick size
 * @param tickRegime       bands of coarser steps at higher prices, lowest first; empty for the tick size
 *                         everywhere (TK-001)
 * @param volatilityBandBps largest move from the reference price, in basis points; 0 for none (VI-002)
 */
public record InstrumentConfig(String symbol, BigDecimal tickSize, long maxOrderQuantity, BigDecimal maxPrice,
        List<PriceBand> tickRegime, long volatilityBandBps) {

    /**
     * One band of the tick-size regime (TK-001): prices from {@code fromPrice} up to the next band's start
     * move in steps of {@code step}. Both are multiples of the instrument's tick size.
     */
    public record PriceBand(BigDecimal fromPrice, BigDecimal step) {
        public PriceBand {
            Objects.requireNonNull(fromPrice, "fromPrice");
            Objects.requireNonNull(step, "step");
        }
    }

    /** An instrument with one tick size everywhere and no volatility band. */
    public InstrumentConfig(String symbol, BigDecimal tickSize, long maxOrderQuantity, BigDecimal maxPrice) {
        this(symbol, tickSize, maxOrderQuantity, maxPrice, List.of(), 0);
    }

    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9][A-Z0-9._-]{0,15}");

    public InstrumentConfig {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(tickSize, "tickSize");
        Objects.requireNonNull(maxPrice, "maxPrice");
        Objects.requireNonNull(tickRegime, "tickRegime");
        tickRegime = List.copyOf(tickRegime);
        if (!SYMBOL.matcher(symbol).matches() || symbol.equals(".") || symbol.equals("..")) {
            throw new IllegalArgumentException("symbol must match " + SYMBOL + ": " + symbol);
        }
        if (tickSize.signum() <= 0) {
            throw new IllegalArgumentException("tickSize must be positive: " + tickSize);
        }
        if (maxPrice.signum() <= 0 || maxPrice.remainder(tickSize).signum() != 0) {
            throw new IllegalArgumentException("maxPrice must be a positive multiple of the tick size: " + maxPrice);
        }
    }

    /** The instrument as the core sees it: limits in lots and ticks. */
    public Instrument instrument() {
        long maxPriceTicks;
        try {
            maxPriceTicks = maxPrice.divide(tickSize).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("maxPrice is too large for the tick size: " + maxPrice, e);
        }
        List<TickBand> bands = new ArrayList<>();
        for (PriceBand band : tickRegime) {
            bands.add(new TickBand(ticks(band.fromPrice()), ticks(band.step())));
        }
        if (bands.isEmpty()) {
            bands.add(new TickBand(1, 1));
        }
        return new Instrument(symbol, maxOrderQuantity, maxPriceTicks, bands, volatilityBandBps);
    }

    /** A price or step that must be a whole number of ticks, as configuration. */
    private long ticks(BigDecimal value) {
        try {
            if (value.remainder(tickSize).signum() != 0) {
                throw new IllegalArgumentException("not a multiple of the tick size " + tickSize + ": " + value);
            }
            return value.divide(tickSize).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("too large for the tick size: " + value, e);
        }
    }

    /**
     * Converts a decimal price to ticks.
     *
     * @throws RequestRefusedException {@code OFF_TICK_PRICE} if the price is not an exact multiple of
     *                                 the tick size or does not fit in a {@code long} of ticks
     */
    public long toTicks(BigDecimal price) {
        Objects.requireNonNull(price, "price");
        if (price.remainder(tickSize).signum() != 0) {
            throw new RequestRefusedException(RequestRefusedException.Reason.OFF_TICK_PRICE, symbol);
        }
        try {
            return price.divide(tickSize).longValueExact();
        } catch (ArithmeticException e) {
            throw new RequestRefusedException(RequestRefusedException.Reason.OFF_TICK_PRICE, symbol);
        }
    }

    /** Converts ticks back to a decimal price, with the tick size's number of decimals. */
    public BigDecimal toPrice(long ticks) {
        return tickSize.multiply(BigDecimal.valueOf(ticks));
    }
}
