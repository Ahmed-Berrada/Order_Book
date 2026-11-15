package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.marketdata.BookLevel;
import io.github.ahmedberrada.lob.marketdata.MarketDataMessage;
import io.github.ahmedberrada.lob.marketdata.MarketDataMessage.Snapshot;
import io.github.ahmedberrada.lob.marketdata.MarketDataMessage.Update;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import java.time.Instant;
import java.util.List;

/**
 * The WebSocket wire format (docs/interfaces/market-data-websocket.md): the feed's messages with
 * decimal-string prices and ISO-8601 timestamps, as in the REST API.
 */
final class MarketDataJson {

    record Level(String price, long quantity, int orders) {
    }

    record Trade(String price, long quantity, Side aggressor) {
    }

    record SnapshotMessage(String type, String symbol, long feedSequence, long commandSequence,
            List<Level> bids, List<Level> asks) {
    }

    record UpdateMessage(String type, String symbol, long feedSequence, long commandSequence, Instant timestamp,
            List<Level> bids, List<Level> asks, List<Trade> trades, Level bestBid, Level bestAsk) {
    }

    private MarketDataJson() {
    }

    static Object of(MarketDataMessage message, InstrumentConfig instrument) {
        return switch (message) {
            case Snapshot s -> new SnapshotMessage("snapshot", s.symbol(), s.feedSequence(), s.commandSequence(),
                    levels(s.bids(), instrument), levels(s.asks(), instrument));
            case Update u -> new UpdateMessage("update", u.symbol(), u.feedSequence(), u.commandSequence(),
                    Timestamps.of(new EventBatch(u.commandSequence(), u.timestampMicros(), List.of())),
                    levels(u.bids(), instrument), levels(u.asks(), instrument),
                    u.trades().stream().map(t -> new Trade(Prices.format(instrument, t.priceTicks()), t.quantity(),
                            t.aggressor())).toList(),
                    level(u.bestBid(), instrument), level(u.bestAsk(), instrument));
        };
    }

    private static List<Level> levels(List<BookLevel> levels, InstrumentConfig instrument) {
        return levels.stream().map(l -> level(l, instrument)).toList();
    }

    private static Level level(BookLevel level, InstrumentConfig instrument) {
        return level == null ? null : new Level(Prices.format(instrument, level.priceTicks()), level.quantity(), level.orders());
    }
}
