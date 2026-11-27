package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.AuctionPriceDetermined;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.PhaseChanged;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.core.TradingPhase;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import java.time.Instant;

/**
 * Outcome of a phase change. When it ended a call phase with an uncross, {@code auctionPrice} and
 * {@code auctionVolume} describe it and {@code trades} counts its trades (AU-002); otherwise they are
 * {@code null}, {@code 0} and {@code 0}.
 */
public record PhaseResponse(
        String symbol, TradingPhase from, TradingPhase to, String auctionPrice, long auctionVolume, int trades,
        long commandSequence, Instant timestamp) {

    static PhaseResponse from(EventBatch batch, InstrumentConfig instrument, PhaseChanged changed) {
        String price = null;
        long volume = 0;
        int trades = 0;
        for (Event event : batch.events()) {
            if (event instanceof AuctionPriceDetermined auction) {
                price = Prices.format(instrument, auction.priceTicks());
                volume = auction.volume();
            } else if (event instanceof TradeExecuted) {
                trades++;
            }
        }
        return new PhaseResponse(instrument.symbol(), changed.from(), changed.to(), price, volume, trades,
                batch.commandSequence(), Timestamps.of(batch));
    }
}
