package io.github.ahmedberrada.lob.journal;

import io.github.ahmedberrada.lob.core.AuctionPriceDetermined;
import io.github.ahmedberrada.lob.core.CancelRejected;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.MemberCommandRejected;
import io.github.ahmedberrada.lob.core.MassCancelled;
import io.github.ahmedberrada.lob.core.MemberLimitsChanged;
import io.github.ahmedberrada.lob.core.MemberResumed;
import io.github.ahmedberrada.lob.core.MemberSuspended;
import io.github.ahmedberrada.lob.core.OrderAccepted;
import io.github.ahmedberrada.lob.core.OrderCancelled;
import io.github.ahmedberrada.lob.core.OrderParked;
import io.github.ahmedberrada.lob.core.OrderRejected;
import io.github.ahmedberrada.lob.core.OrderReplaced;
import io.github.ahmedberrada.lob.core.OrderReplenished;
import io.github.ahmedberrada.lob.core.OrderRested;
import io.github.ahmedberrada.lob.core.PhaseChangeRejected;
import io.github.ahmedberrada.lob.core.PhaseChanged;
import io.github.ahmedberrada.lob.core.ReplaceRejected;
import io.github.ahmedberrada.lob.core.StopOrder;
import io.github.ahmedberrada.lob.core.StopTriggered;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.core.VolatilityInterruption;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.StringJoiner;

/**
 * Turns an instrument's journals into flat order records, one line per event, for record keeping and
 * reporting ({@code docs/compliance/order-records.md}). Read-only.
 *
 * <pre>
 * lob-records &lt;instrument-directory&gt;
 * </pre>
 *
 * Prints CSV on standard output. Prices are in ticks of the instrument and quantities in lots, as the
 * journal holds them; the tick size is a property of the instrument's configuration, not of the journal.
 * Exit codes: 0 success, 1 unreadable journal, 2 usage error.
 */
public final class RecordsExport {

    /** The columns, in order. */
    public static final String HEADER = "timestamp_utc,instrument,command_sequence,event_sequence,member_id,order_id,"
            + "record,side,order_type,time_in_force,price_ticks,quantity,counterparty_order_id,"
            + "counterparty_member_id,detail";

    private RecordsExport() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 1 || args[0].startsWith("--")) {
            err.println("usage: lob-records <instrument-directory>");
            return ReplayTool.USAGE;
        }
        Path directory = Path.of(args[0]);
        Path commandsFile = directory.resolve(JournaledEngine.COMMANDS_FILE);
        if (!Files.exists(commandsFile)) {
            err.println("error: no command journal in " + directory);
            return ReplayTool.FAILED;
        }
        try (JournalReader<JournalEntry> commands = JournalReader.commands(commandsFile);
                JournalReader<EventBatch> events = JournalReader.events(directory.resolve(JournaledEngine.EVENTS_FILE))) {
            String symbol = commands.instrument() == null ? "" : commands.instrument().symbol();
            out.println(HEADER);
            for (EventBatch batch = events.next(); batch != null; batch = events.next()) {
                JournalEntry entry = commands.next();
                if (entry == null || entry.sequence() != batch.commandSequence()) {
                    err.println("error: command " + batch.commandSequence() + " is missing from the command journal");
                    return ReplayTool.FAILED;
                }
                for (Event event : batch.events()) {
                    String line = record(symbol, entry, batch, event);
                    if (line != null) {
                        out.println(line);
                    }
                }
            }
            return ReplayTool.OK;
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return ReplayTool.FAILED;
        }
    }

    /** One record, or {@code null} for an event that is only a consequence of another record. */
    static String record(String symbol, JournalEntry entry, EventBatch batch, Event event) {
        Row row = new Row(symbol, entry, event.sequence());
        Command command = entry.command();
        switch (event) {
            case OrderAccepted e -> {
                row.order(e.orderId()).record("NEW_ORDER");
                switch (command) {
                    case LimitOrder c -> row.side(c.side().name()).type("LIMIT").tif(c.timeInForce().name())
                            .price(c.priceTicks()).quantity(c.quantity());
                    case MarketOrder c -> row.side(c.side().name()).type("MARKET").tif(c.timeInForce().name())
                            .quantity(c.quantity());
                    case StopOrder c -> row.side(c.side().name()).type(c.limitPriceTicks() == 0 ? "STOP" : "STOP_LIMIT")
                            .price(c.limitPriceTicks() == 0 ? c.stopPriceTicks() : c.limitPriceTicks())
                            .quantity(c.quantity()).detail("stop price " + c.stopPriceTicks());
                    default -> { }
                }
            }
            case OrderRejected e -> row.order(e.orderId()).record("NEW_ORDER_REJECTED").detail(e.reason().name());
            case OrderRested e -> row.order(e.orderId()).record("ORDER_RESTED").side(e.side().name())
                    .price(e.priceTicks()).quantity(e.quantity());
            case OrderCancelled e -> row.order(e.orderId()).record("ORDER_CANCELLED").quantity(e.cancelledQuantity())
                    .detail(e.reason().name());
            case CancelRejected e -> row.order(e.orderId()).record("CANCEL_REJECTED").detail(e.reason().name());
            case OrderReplaced e -> row.order(e.orderId()).record("ORDER_REPLACED").side(e.side().name())
                    .price(e.priceTicks()).quantity(e.quantity())
                    .detail(e.priorityKept() ? "priority kept" : "priority lost");
            case ReplaceRejected e -> row.order(e.orderId()).record("REPLACE_REJECTED").detail(e.reason().name());
            case OrderParked e -> row.order(e.orderId()).record("STOP_PARKED").side(e.side().name())
                    .price(e.stopPriceTicks()).quantity(e.quantity());
            case StopTriggered e -> row.order(e.orderId()).record("STOP_TRIGGERED");
            case OrderReplenished e -> row.order(e.orderId()).record("ICEBERG_REPLENISHED").side(e.side().name())
                    .price(e.priceTicks()).quantity(e.displayedQuantity());
            case TradeExecuted e -> row.member(e.takerMemberId()).order(e.takerOrderId()).record("TRADE")
                    .side(e.aggressorSide().name()).price(e.priceTicks()).quantity(e.quantity())
                    .counterparty(e.makerOrderId(), e.makerMemberId());
            case PhaseChanged e -> row.record("PHASE_CHANGED").detail(e.from() + " to " + e.to());
            case PhaseChangeRejected e -> row.record("PHASE_CHANGE_REJECTED").detail(e.reason().name());
            case AuctionPriceDetermined e -> row.record("AUCTION_PRICE").price(e.priceTicks()).quantity(e.volume());
            case VolatilityInterruption e -> row.record("VOLATILITY_INTERRUPTION").price(e.priceTicks())
                    .detail("reference " + e.referencePriceTicks());
            case MemberLimitsChanged e -> row.member(e.memberId()).record("MEMBER_LIMITS_CHANGED")
                    .detail(e.limits().toString());
            case MemberSuspended e -> row.member(e.memberId()).record("MEMBER_SUSPENDED")
                    .quantity(e.cancelledOrders());
            case MemberResumed e -> row.member(e.memberId()).record("MEMBER_RESUMED");
            case MassCancelled e -> row.member(e.memberId()).record("MASS_CANCELLED")
                    .side(e.side() == null ? "BOTH" : e.side().name()).quantity(e.cancelledOrders());
            case MemberCommandRejected e -> row.member(e.memberId()).record("MEMBER_COMMAND_REJECTED")
                    .detail(e.reason().name());
        }
        return row.toString();
    }

    /** One line of the export. */
    private static final class Row {
        private final String[] fields = new String[15];

        Row(String symbol, JournalEntry entry, long eventSequence) {
            fields[0] = Instant.EPOCH.plus(entry.timestampMicros(), ChronoUnit.MICROS).toString();
            fields[1] = symbol;
            fields[2] = Long.toString(entry.sequence());
            fields[3] = Long.toString(eventSequence);
            fields[4] = Long.toString(entry.memberId());
        }

        Row member(long id) {
            fields[4] = Long.toString(id);
            return this;
        }

        Row order(long id) {
            fields[5] = Long.toString(id);
            return this;
        }

        Row record(String name) {
            fields[6] = name;
            return this;
        }

        Row side(String side) {
            fields[7] = side;
            return this;
        }

        Row type(String type) {
            fields[8] = type;
            return this;
        }

        Row tif(String tif) {
            fields[9] = tif;
            return this;
        }

        Row price(long ticks) {
            fields[10] = Long.toString(ticks);
            return this;
        }

        Row quantity(long lots) {
            fields[11] = Long.toString(lots);
            return this;
        }

        Row counterparty(long orderId, long memberId) {
            fields[12] = Long.toString(orderId);
            fields[13] = Long.toString(memberId);
            return this;
        }

        Row detail(String text) {
            fields[14] = text;
            return this;
        }

        @Override
        public String toString() {
            StringJoiner line = new StringJoiner(",");
            for (String field : fields) {
                line.add(field == null ? "" : field.contains(",") ? '"' + field + '"' : field);
            }
            return line.toString();
        }
    }
}
