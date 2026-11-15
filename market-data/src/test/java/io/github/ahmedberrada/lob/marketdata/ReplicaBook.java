package io.github.ahmedberrada.lob.marketdata;

import io.github.ahmedberrada.lob.marketdata.MarketDataMessage.Snapshot;
import io.github.ahmedberrada.lob.marketdata.MarketDataMessage.Update;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/** What a subscriber does with the feed: start from a snapshot, apply updates, check sequences (MD-004 to MD-006). */
final class ReplicaBook {

    private final NavigableMap<Long, BookLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, BookLevel> asks = new TreeMap<>();
    private long feedSequence;

    ReplicaBook(Snapshot snapshot) {
        snapshot.bids().forEach(l -> bids.put(l.priceTicks(), l));
        snapshot.asks().forEach(l -> asks.put(l.priceTicks(), l));
        feedSequence = snapshot.feedSequence();
    }

    void apply(Update update) {
        if (update.feedSequence() != feedSequence + 1) {
            throw new AssertionError("gap: " + feedSequence + " then " + update.feedSequence());
        }
        feedSequence = update.feedSequence();
        update.bids().forEach(l -> put(bids, l));
        update.asks().forEach(l -> put(asks, l));
    }

    List<BookLevel> bids() {
        return new ArrayList<>(bids.values());
    }

    List<BookLevel> asks() {
        return new ArrayList<>(asks.values());
    }

    private static void put(NavigableMap<Long, BookLevel> side, BookLevel level) {
        if (level.quantity() == 0) {
            side.remove(level.priceTicks());
        } else {
            side.put(level.priceTicks(), level);
        }
    }
}
