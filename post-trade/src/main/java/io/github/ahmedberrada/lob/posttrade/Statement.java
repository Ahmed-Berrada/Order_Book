package io.github.ahmedberrada.lob.posttrade;

import io.github.ahmedberrada.lob.core.Side;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A member's activity per trade date and instrument (PT-009), summed from the same records as the trade
 * file, so the fees of a statement are the sum of the fees of its lines.
 *
 * @param tradeDate      the trade date
 * @param instrument     the symbol
 * @param memberId       the member
 * @param records        trade records of the member that day
 * @param boughtQuantity lots bought
 * @param boughtNotional notional bought
 * @param soldQuantity   lots sold
 * @param soldNotional   notional sold
 * @param fees           the sum of the records' fees; negative if rebates exceed fees
 */
public record Statement(LocalDate tradeDate, String instrument, long memberId, long records, long boughtQuantity,
        BigDecimal boughtNotional, long soldQuantity, BigDecimal soldNotional, BigDecimal fees) {

    private record Key(LocalDate tradeDate, String instrument, long memberId) {
    }

    private static final Comparator<Key> ORDER = Comparator.comparing(Key::tradeDate)
            .thenComparing(Key::instrument).thenComparingLong(Key::memberId);

    /** One statement line per trade date, instrument and member, in that order. */
    public static List<Statement> of(List<TradeRecord> records) {
        Map<Key, Statement> lines = new TreeMap<>(ORDER);
        for (TradeRecord record : records) {
            Key key = new Key(record.tradeDate(), record.instrument(), record.memberId());
            Statement line = lines.computeIfAbsent(key, k -> new Statement(k.tradeDate(), k.instrument(),
                    k.memberId(), 0, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO));
            lines.put(key, line.plus(record));
        }
        return new ArrayList<>(lines.values());
    }

    private Statement plus(TradeRecord record) {
        boolean buy = record.side() == Side.BUY;
        return new Statement(tradeDate, instrument, memberId, records + 1,
                buy ? Math.addExact(boughtQuantity, record.quantity()) : boughtQuantity,
                buy ? boughtNotional.add(record.notional()) : boughtNotional,
                buy ? soldQuantity : Math.addExact(soldQuantity, record.quantity()),
                buy ? soldNotional : soldNotional.add(record.notional()),
                fees.add(record.fee()));
    }
}
