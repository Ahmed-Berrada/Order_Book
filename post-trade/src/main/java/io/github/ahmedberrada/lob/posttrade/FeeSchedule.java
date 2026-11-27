package io.github.ahmedberrada.lob.posttrade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Fee rates per liquidity, in basis points of the notional, and the currency's decimal places (PT-006).
 * A negative rate is a rebate.
 *
 * @param makerBps      rate of {@link Liquidity#MAKER} records
 * @param takerBps      rate of {@link Liquidity#TAKER} records
 * @param auctionBps    rate of {@link Liquidity#AUCTION} records
 * @param currencyScale decimal places fees are rounded to, 0 to 8
 */
public record FeeSchedule(BigDecimal makerBps, BigDecimal takerBps, BigDecimal auctionBps, int currencyScale) {

    /** The largest rate, in either direction: 1% of the notional. */
    public static final BigDecimal MAX_RATE_BPS = BigDecimal.valueOf(100);

    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);

    public FeeSchedule {
        for (BigDecimal rate : new BigDecimal[] {makerBps, takerBps, auctionBps}) {
            Objects.requireNonNull(rate, "rate");
            if (rate.abs().compareTo(MAX_RATE_BPS) > 0) {
                throw new IllegalArgumentException("a fee rate must be between -100 and 100 basis points: " + rate);
            }
        }
        makerBps = makerBps.stripTrailingZeros();            // 0.30 and 0.3 are one schedule, printed alike
        takerBps = takerBps.stripTrailingZeros();
        auctionBps = auctionBps.stripTrailingZeros();
        if (currencyScale < 0 || currencyScale > 8) {
            throw new IllegalArgumentException("currency scale must be 0 to 8: " + currencyScale);
        }
    }

    /**
     * Parses {@code maker=-0.2,taker=0.3,auction=0.1}: every liquidity exactly once, in any order.
     *
     * @throws IllegalArgumentException if a liquidity is missing, repeated or unknown, or a rate is invalid
     */
    public static FeeSchedule parse(String rates, int currencyScale) {
        Map<Liquidity, BigDecimal> parsed = new EnumMap<>(Liquidity.class);
        for (String part : rates.split(",", -1)) {
            int equals = part.indexOf('=');
            if (equals < 0) {
                throw new IllegalArgumentException("expected liquidity=rate, got '" + part + "'");
            }
            Liquidity liquidity;
            try {
                liquidity = Liquidity.valueOf(part.substring(0, equals).strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown liquidity '" + part.substring(0, equals) + "'", e);
            }
            BigDecimal rate;
            try {
                rate = new BigDecimal(part.substring(equals + 1).strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a rate: '" + part.substring(equals + 1) + "'", e);
            }
            if (parsed.put(liquidity, rate) != null) {
                throw new IllegalArgumentException(liquidity + " is given twice");
            }
        }
        for (Liquidity liquidity : Liquidity.values()) {
            if (!parsed.containsKey(liquidity)) {
                throw new IllegalArgumentException("no rate for " + liquidity);
            }
        }
        return new FeeSchedule(parsed.get(Liquidity.MAKER), parsed.get(Liquidity.TAKER),
                parsed.get(Liquidity.AUCTION), currencyScale);
    }

    /** The rate of a liquidity, in basis points. */
    public BigDecimal rate(Liquidity liquidity) {
        return switch (liquidity) {
            case MAKER -> makerBps;
            case TAKER -> takerBps;
            case AUCTION -> auctionBps;
        };
    }

    /** The fee of one record: notional times rate over 10,000, rounded half to even once (PT-007). */
    public BigDecimal fee(BigDecimal notional, Liquidity liquidity) {
        return notional.multiply(rate(liquidity)).divide(BPS).setScale(currencyScale, RoundingMode.HALF_EVEN);
    }
}
