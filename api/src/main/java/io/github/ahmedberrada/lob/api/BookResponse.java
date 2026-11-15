package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.OrderBook;
import io.github.ahmedberrada.lob.service.BookSnapshot;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import java.util.List;

/**
 * The best levels of a book, as of {@code commandSequence}.
 *
 * @param bids best (highest) price first
 * @param asks best (lowest) price first
 */
public record BookResponse(String symbol, long commandSequence, List<Level> bids, List<Level> asks) {

    /** Aggregated quantity and number of orders at one price. */
    public record Level(String price, long quantity, int orders) {
    }

    static BookResponse of(InstrumentConfig instrument, BookSnapshot book) {
        return new BookResponse(instrument.symbol(), book.lastCommandSequence(),
                levels(instrument, book.bids()), levels(instrument, book.asks()));
    }

    private static List<Level> levels(InstrumentConfig instrument, List<OrderBook.Level> levels) {
        return levels.stream()
                .map(l -> new Level(Prices.format(instrument, l.priceTicks()), l.quantity(), l.orderCount()))
                .toList();
    }
}
