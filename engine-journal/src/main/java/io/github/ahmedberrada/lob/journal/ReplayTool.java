package io.github.ahmedberrada.lob.journal;

import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import io.github.ahmedberrada.lob.core.OrderBook;
import io.github.ahmedberrada.lob.core.Side;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Command-line replay of an instrument's journals, for audit and incident analysis (rulebook RS-009).
 * Read-only: it never modifies the journals.
 *
 * <pre>
 * lob-replay &lt;instrument-directory&gt; [--to &lt;sequence&gt;] [--verify]
 * </pre>
 *
 * <ul>
 *   <li>Rebuilds the book as it was after command {@code --to} (default: the last one) and prints it.</li>
 *   <li>{@code --verify} regenerates the events of every replayed command and checks that they equal
 *       the event journal, which proves that the current code reproduces the recorded history.</li>
 * </ul>
 *
 * Exit codes: 0 success, 1 verification mismatch or unreadable journal, 2 usage error.
 */
public final class ReplayTool {

    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;

    private ReplayTool() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Path directory = null;
        long to = Long.MAX_VALUE;
        boolean verify = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--verify" -> verify = true;
                    case "--to" -> to = Long.parseLong(args[++i]);
                    default -> {
                        if (directory != null || args[i].startsWith("--")) {
                            throw new IllegalArgumentException("unexpected argument: " + args[i]);
                        }
                        directory = Path.of(args[i]);
                    }
                }
            }
            if (directory == null || to < 0) {
                throw new IllegalArgumentException("missing instrument directory");
            }
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            err.println("error: " + e.getMessage());
            err.println("usage: lob-replay <instrument-directory> [--to <sequence>] [--verify]");
            return USAGE;
        }

        try {
            return replay(directory, to, verify, out, err);
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return FAILED;
        }
    }

    private static int replay(Path directory, long to, boolean verify, PrintStream out, PrintStream err)
            throws IOException {
        Path commandsFile = directory.resolve(JournaledEngine.COMMANDS_FILE);
        if (!Files.exists(commandsFile)) {
            err.println("error: no command journal in " + directory);
            return FAILED;
        }
        try (JournalReader<JournalEntry> commands = JournalReader.commands(commandsFile);
                JournalReader<EventBatch> events = JournalReader.events(directory.resolve(JournaledEngine.EVENTS_FILE))) {
            Instrument instrument = commands.instrument();
            if (instrument == null) {
                err.println("error: command journal has no header");
                return FAILED;
            }
            MatchingEngine engine = new MatchingEngine(instrument);
            long verified = 0;
            JournalEntry last = null;
            for (JournalEntry entry = commands.next(); entry != null && entry.sequence() <= to; entry = commands.next()) {
                List<Event> produced = engine.process(entry.command());
                last = entry;
                if (verify) {
                    EventBatch expected = new EventBatch(entry.sequence(), entry.timestampMicros(), produced);
                    EventBatch recorded = events.next();
                    if (!expected.equals(recorded)) {
                        err.println("MISMATCH at command " + entry.sequence());
                        err.println("  recorded:    " + recorded);
                        err.println("  regenerated: " + expected);
                        return FAILED;
                    }
                    verified++;
                }
            }

            out.printf("Instrument      %s (max quantity %d lots, max price %d ticks)%n",
                    instrument.symbol(), instrument.maxOrderQuantity(), instrument.maxPriceTicks());
            if (last == null) {
                out.println("Commands        none replayed");
            } else {
                out.printf("Commands        %d replayed, last at %s%n", last.sequence(), formatMicros(last.timestampMicros()));
            }
            printBook(engine.book(), out);
            if (verify) {
                out.printf("Verification    OK: %d event batches identical to the event journal%n", verified);
            }
            return OK;
        }
    }

    private static void printBook(OrderBook book, PrintStream out) {
        out.printf("Book            %d resting orders%n", book.orderCount());
        out.printf("  %-6s %12s %12s %8s%n", "side", "price", "quantity", "orders");
        for (Side side : List.of(Side.SELL, Side.BUY)) {
            List<OrderBook.Level> levels = book.depth(side);
            for (int i = 0; i < levels.size(); i++) {
                // asks printed highest first, so the spread sits in the middle of the table
                OrderBook.Level level = side == Side.SELL ? levels.get(levels.size() - 1 - i) : levels.get(i);
                out.printf("  %-6s %12d %12d %8d%n", side, level.priceTicks(), level.quantity(), level.orderCount());
            }
        }
    }

    private static String formatMicros(long micros) {
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS).toString();
    }
}
