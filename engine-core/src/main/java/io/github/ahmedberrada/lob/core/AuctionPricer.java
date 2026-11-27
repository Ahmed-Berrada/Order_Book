package io.github.ahmedberrada.lob.core;

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/** The auction price rules of AU-001, as a pure function of the two sides of the book. */
final class AuctionPricer {

    /** The chosen price and the volume that trades at it. */
    record Result(long priceTicks, long volume) {
    }

    private AuctionPricer() {
    }

    /**
     * @param bids      buy levels, best (highest) price first
     * @param asks      sell levels, best (lowest) price first
     * @param reference price of the last trade, or 0 if there is none
     * @return the auction price, or empty if no volume can trade
     */
    static Optional<Result> determine(
            List<OrderBook.Level> bids, List<OrderBook.Level> asks, long reference) {
        TreeSet<Long> candidates = new TreeSet<>();
        bids.forEach(level -> candidates.add(level.priceTicks()));
        asks.forEach(level -> candidates.add(level.priceTicks()));

        long bestVolume = 0;
        for (long p : candidates) {
            bestVolume = Math.max(bestVolume, volume(bids, asks, p));
        }
        if (bestVolume == 0) {
            return Optional.empty();
        }
        // rule 1 is the filter on bestVolume; rule 2 keeps the lowest surplus
        long lowestSurplus = Long.MAX_VALUE;
        for (long p : candidates) {
            if (volume(bids, asks, p) == bestVolume) {
                lowestSurplus = Math.min(lowestSurplus, Math.abs(surplus(bids, asks, p)));
            }
        }
        TreeSet<Long> remaining = new TreeSet<>();
        boolean allBuySurplus = true;
        boolean allSellSurplus = true;
        for (long p : candidates) {
            if (volume(bids, asks, p) == bestVolume && Math.abs(surplus(bids, asks, p)) == lowestSurplus) {
                remaining.add(p);
                long s = surplus(bids, asks, p);
                allBuySurplus &= s > 0;
                allSellSurplus &= s < 0;
            }
        }
        long price;
        if (allBuySurplus) {
            price = remaining.last();
        } else if (allSellSurplus) {
            price = remaining.first();
        } else {
            price = closestToReference(remaining, reference);
        }
        return Optional.of(new Result(price, bestVolume));
    }

    /** Executable volume at a price: the smaller of the buy and sell totals that accept it. */
    private static long volume(List<OrderBook.Level> bids, List<OrderBook.Level> asks, long p) {
        return Math.min(buyTotal(bids, p), sellTotal(asks, p));
    }

    private static long surplus(List<OrderBook.Level> bids, List<OrderBook.Level> asks, long p) {
        return buyTotal(bids, p) - sellTotal(asks, p);
    }

    private static long buyTotal(List<OrderBook.Level> bids, long p) {
        long total = 0;
        for (OrderBook.Level level : bids) {
            if (level.priceTicks() >= p) {
                total += level.quantity();
            }
        }
        return total;
    }

    private static long sellTotal(List<OrderBook.Level> asks, long p) {
        long total = 0;
        for (OrderBook.Level level : asks) {
            if (level.priceTicks() <= p) {
                total += level.quantity();
            }
        }
        return total;
    }

    /** The candidate closest to the reference; the highest of two equally close, and of all without one. */
    private static long closestToReference(TreeSet<Long> candidates, long reference) {
        if (reference == 0) {
            return candidates.last();
        }
        long best = candidates.last();
        for (long p : candidates.descendingSet()) {     // highest first, so a tie keeps the higher price
            if (Math.abs(p - reference) < Math.abs(best - reference)) {
                best = p;
            }
        }
        return best;
    }
}
