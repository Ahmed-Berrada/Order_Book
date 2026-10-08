package io.github.ahmedberrada.lob.service;

import io.github.ahmedberrada.lob.core.OrderBook;
import java.util.List;

/**
 * Copy of the best levels of a book, taken on the instrument's thread. Prices are in ticks.
 *
 * @param lastCommandSequence the last command applied when the copy was taken
 * @param bids                best (highest) bid first
 * @param asks                best (lowest) ask first
 */
public record BookSnapshot(long lastCommandSequence, List<OrderBook.Level> bids, List<OrderBook.Level> asks) {

    public BookSnapshot {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }
}
