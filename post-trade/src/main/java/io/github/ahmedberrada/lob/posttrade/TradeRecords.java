package io.github.ahmedberrada.lob.posttrade;

import io.github.ahmedberrada.lob.core.AuctionPriceDetermined;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.PhaseChanged;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.journal.EventJournal;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Turns an instrument's recorded events into trade records, two per trade (PT-001 to PT-007). */
public final class TradeRecords {

    private final String instrument;
    private final BigDecimal tickSize;
    private final FeeSchedule fees;

    /**
     * @param instrument the symbol, as the journal names it
     * @param tickSize   the currency value of one tick: the instrument's configuration, not in the journal
     * @param fees       the schedule to charge
     */
    public TradeRecords(String instrument, BigDecimal tickSize, FeeSchedule fees) {
        this.instrument = Objects.requireNonNull(instrument, "instrument");
        this.tickSize = Objects.requireNonNull(tickSize, "tickSize");
        this.fees = Objects.requireNonNull(fees, "fees");
        if (tickSize.signum() <= 0) {
            throw new IllegalArgumentException("tick size must be positive: " + tickSize);
        }
    }

    /** Every record of a journal, in the order of its trades, buyer before seller. */
    public List<TradeRecord> read(EventJournal journal) throws IOException {
        List<TradeRecord> records = new ArrayList<>();
        for (EventBatch batch = journal.next(); batch != null; batch = journal.next()) {
            records.addAll(of(batch));
        }
        return records;
    }

    /** The records of one command's events. */
    public List<TradeRecord> of(EventBatch batch) {
        List<TradeRecord> records = new ArrayList<>();
        LocalDate date = LocalDate.ofInstant(Instant.EPOCH.plus(batch.timestampMicros(), ChronoUnit.MICROS),
                ZoneOffset.UTC);                                                    // PT-005
        boolean uncross = false;
        for (Event event : batch.events()) {
            switch (event) {
                case AuctionPriceDetermined ignored -> uncross = true;              // PT-003
                case PhaseChanged ignored -> uncross = false;
                case TradeExecuted trade -> {
                    boolean buyerTakes = trade.aggressorSide() == Side.BUY;
                    records.add(side(batch, date, trade, Side.BUY, buyerTakes, uncross));
                    records.add(side(batch, date, trade, Side.SELL, !buyerTakes, uncross));
                }
                default -> { }
            }
        }
        return records;
    }

    private TradeRecord side(EventBatch batch, LocalDate date, TradeExecuted trade, Side side, boolean taker,
            boolean uncross) {
        Liquidity liquidity = uncross ? Liquidity.AUCTION : taker ? Liquidity.TAKER : Liquidity.MAKER;
        BigDecimal price = tickSize.multiply(BigDecimal.valueOf(trade.priceTicks()));
        BigDecimal notional = price.multiply(BigDecimal.valueOf(trade.quantity()));
        return new TradeRecord(instrument + "-" + trade.sequence(), date, batch.timestampMicros(), instrument,
                taker ? trade.takerMemberId() : trade.makerMemberId(),
                taker ? trade.takerOrderId() : trade.makerOrderId(), side, liquidity, trade.priceTicks(), price,
                trade.quantity(), notional, fees.fee(notional, liquidity));
    }
}
