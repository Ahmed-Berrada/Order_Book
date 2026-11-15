package io.github.ahmedberrada.lob.marketdata;

import java.util.List;

/** What a subscriber receives: one snapshot, then updates (MD-004). Prices are in ticks. */
public sealed interface MarketDataMessage {

    String symbol();

    /** Gap-free per instrument; the snapshot carries the last update it includes (MD-005). */
    long feedSequence();

    /** The journal's command sequence the message reflects. */
    long commandSequence();

    /** The whole book, best price first on each side. */
    record Snapshot(String symbol, long feedSequence, long commandSequence, List<BookLevel> bids,
            List<BookLevel> asks) implements MarketDataMessage {

        public Snapshot {
            bids = List.copyOf(bids);
            asks = List.copyOf(asks);
        }
    }

    /**
     * The effect of one command: changed levels (best first), its trades in execution order, and the
     * top of book afterwards ({@code null} for an empty side).
     */
    record Update(String symbol, long feedSequence, long commandSequence, long timestampMicros,
            List<BookLevel> bids, List<BookLevel> asks, List<Trade> trades, BookLevel bestBid, BookLevel bestAsk)
            implements MarketDataMessage {

        public Update {
            bids = List.copyOf(bids);
            asks = List.copyOf(asks);
            trades = List.copyOf(trades);
        }
    }
}
