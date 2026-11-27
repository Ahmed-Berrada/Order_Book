package io.github.ahmedberrada.lob.posttrade;

import io.github.ahmedberrada.lob.core.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * One side of a trade, as clearing and billing see it (PT-002): a line of the trade file.
 *
 * @param tradeId          the instrument and the trade's event sequence number (PT-001)
 * @param tradeDate        UTC date of the command's timestamp (PT-005)
 * @param timestampMicros  the command's timestamp, microseconds since the epoch
 * @param instrument       the symbol
 * @param memberId         the member this side belongs to
 * @param orderId          the member's order
 * @param side             what the member did
 * @param liquidity        how the order took part (PT-003)
 * @param priceTicks       the price as the journal holds it
 * @param price            the price in currency (PT-004)
 * @param quantity         lots traded
 * @param notional         price times quantity, exact (PT-004)
 * @param fee              the member's fee for this side; negative for a rebate (PT-007)
 */
public record TradeRecord(String tradeId, LocalDate tradeDate, long timestampMicros, String instrument, long memberId,
        long orderId, Side side, Liquidity liquidity, long priceTicks, BigDecimal price, long quantity,
        BigDecimal notional, BigDecimal fee) {

    /** The command's timestamp as an instant. */
    public Instant timestamp() {
        return Instant.EPOCH.plus(timestampMicros, ChronoUnit.MICROS);
    }
}
