package io.github.ahmedberrada.lob.posttrade;

import io.github.ahmedberrada.lob.core.Side;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** The balance check of PT-008: per trade date, as much bought as sold, in quantity and notional. */
final class Balance {

    private static final class Day {
        long bought;
        long sold;
        BigDecimal boughtNotional = BigDecimal.ZERO;
        BigDecimal soldNotional = BigDecimal.ZERO;
    }

    private Balance() {
    }

    /** The first imbalance, earliest date first, or empty if the records balance. */
    static Optional<String> check(List<TradeRecord> records) {
        Map<LocalDate, Day> days = new TreeMap<>();
        for (TradeRecord record : records) {
            Day day = days.computeIfAbsent(record.tradeDate(), d -> new Day());
            if (record.side() == Side.BUY) {
                day.bought = Math.addExact(day.bought, record.quantity());
                day.boughtNotional = day.boughtNotional.add(record.notional());
            } else {
                day.sold = Math.addExact(day.sold, record.quantity());
                day.soldNotional = day.soldNotional.add(record.notional());
            }
        }
        for (Map.Entry<LocalDate, Day> entry : days.entrySet()) {
            Day day = entry.getValue();
            if (day.bought != day.sold) {
                return Optional.of(entry.getKey() + ": " + day.bought + " lots bought but " + day.sold + " sold");
            }
            if (day.boughtNotional.compareTo(day.soldNotional) != 0) {
                return Optional.of(entry.getKey() + ": " + day.boughtNotional.toPlainString() + " bought but "
                        + day.soldNotional.toPlainString() + " sold");
            }
        }
        return Optional.empty();
    }
}
