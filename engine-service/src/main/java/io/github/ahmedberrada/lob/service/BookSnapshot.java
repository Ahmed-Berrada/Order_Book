package io.github.ahmedberrada.lob.service;

import io.github.ahmedberrada.lob.core.OrderBook;
import io.github.ahmedberrada.lob.core.TradingPhase;
import java.util.List;
import java.util.Objects;

/**
 * Copy of the best levels of a book, taken on the instrument's thread. Prices are in ticks.
 *
 * @param lastCommandSequence the last command applied when the copy was taken
 * @param phase               the trading phase; the book may be crossed during a call phase (AU-003)
 * @param bids                best (highest) bid first
 * @param asks                best (lowest) ask first
 */
public record BookSnapshot(long lastCommandSequence, TradingPhase phase, List<OrderBook.Level> bids, List<OrderBook.Level> asks) {

    public BookSnapshot {
        Objects.requireNonNull(phase, "phase");
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }
}
