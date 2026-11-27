package io.github.ahmedberrada.lob.posttrade;

import io.github.ahmedberrada.lob.journal.EventJournal;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Produces the clearing trade file or the members' statements of one instrument from its journals
 * ({@code docs/interfaces/trade-file.md}). Read-only (PT-010).
 *
 * <pre>
 * lob-post-trade trades|statement &lt;instrument-directory&gt; --tick-size &lt;value&gt;
 *     --fees maker=&lt;bps&gt;,taker=&lt;bps&gt;,auction=&lt;bps&gt; [--currency-scale &lt;digits&gt;]
 * </pre>
 *
 * Prints CSV on standard output, only once every record has been computed and the trades balance (PT-008).
 * Exit codes: 0 success, 1 unreadable or unbalanced journal, 2 usage error.
 */
public final class PostTradeTool {

    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;

    /** Columns of the trade file. */
    public static final String TRADES_HEADER = "trade_id,trade_date,timestamp_utc,instrument,member_id,order_id,side,"
            + "liquidity,price_ticks,price,quantity,notional,fee_rate_bps,fee";

    /** Columns of the statement. */
    public static final String STATEMENT_HEADER = "trade_date,instrument,member_id,records,bought_quantity,"
            + "bought_notional,sold_quantity,sold_notional,fees";

    private static final String USAGE_TEXT = "usage: lob-post-trade trades|statement <instrument-directory> "
            + "--tick-size <value> --fees maker=<bps>,taker=<bps>,auction=<bps> [--currency-scale <digits>]";

    private PostTradeTool() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    private record Arguments(boolean statement, Path directory, BigDecimal tickSize, FeeSchedule fees) {
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Arguments arguments;
        try {
            arguments = parse(args);
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println(USAGE_TEXT);
            return USAGE;
        }
        List<TradeRecord> records;
        try (EventJournal journal = EventJournal.open(arguments.directory())) {
            String symbol = journal.instrument() == null ? "" : journal.instrument().symbol();
            records = new TradeRecords(symbol, arguments.tickSize(), arguments.fees()).read(journal);
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return FAILED;
        }
        Optional<String> imbalance = Balance.check(records);
        if (imbalance.isPresent()) {
            err.println("error: the trades do not balance on " + imbalance.get() + " (PT-008)");
            return FAILED;
        }
        if (arguments.statement()) {
            out.println(STATEMENT_HEADER);
            for (Statement line : Statement.of(records)) {
                out.println(String.join(",", line.tradeDate().toString(), line.instrument(),
                        Long.toString(line.memberId()), Long.toString(line.records()),
                        Long.toString(line.boughtQuantity()), line.boughtNotional().toPlainString(),
                        Long.toString(line.soldQuantity()), line.soldNotional().toPlainString(),
                        line.fees().setScale(arguments.fees().currencyScale()).toPlainString()));
            }
        } else {
            out.println(TRADES_HEADER);
            for (TradeRecord r : records) {
                out.println(String.join(",", r.tradeId(), r.tradeDate().toString(), r.timestamp().toString(),
                        r.instrument(), Long.toString(r.memberId()), Long.toString(r.orderId()), r.side().name(),
                        r.liquidity().name(), Long.toString(r.priceTicks()), r.price().toPlainString(),
                        Long.toString(r.quantity()), r.notional().toPlainString(),
                        arguments.fees().rate(r.liquidity()).toPlainString(), r.fee().toPlainString()));
            }
        }
        return OK;
    }

    private static Arguments parse(String[] args) {
        List<String> positional = new ArrayList<>();
        String tickSize = null;
        String fees = null;
        String scale = "2";
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                positional.add(arg);
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(arg + " needs a value");
            }
            String value = args[++i];
            switch (arg) {
                case "--tick-size" -> tickSize = value;
                case "--fees" -> fees = value;
                case "--currency-scale" -> scale = value;
                default -> throw new IllegalArgumentException("unknown option " + arg);
            }
        }
        if (positional.size() != 2 || !List.of("trades", "statement").contains(positional.get(0))) {
            throw new IllegalArgumentException("expected trades or statement, then one instrument directory");
        }
        if (tickSize == null || fees == null) {
            throw new IllegalArgumentException("--tick-size and --fees are required");
        }
        BigDecimal tick;
        int digits;
        try {
            tick = new BigDecimal(tickSize);
            digits = Integer.parseInt(scale);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + e.getMessage(), e);
        }
        if (tick.signum() <= 0) {
            throw new IllegalArgumentException("tick size must be positive: " + tickSize);
        }
        return new Arguments(positional.get(0).equals("statement"), Path.of(positional.get(1)), tick,
                FeeSchedule.parse(fees, digits));
    }
}
