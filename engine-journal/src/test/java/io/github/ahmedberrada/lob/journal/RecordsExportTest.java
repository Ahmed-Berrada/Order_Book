package io.github.ahmedberrada.lob.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.MemberLimits;
import io.github.ahmedberrada.lob.core.ReplaceOrder;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.SetMemberLimits;
import io.github.ahmedberrada.lob.core.SetPhase;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.core.StopOrder;
import io.github.ahmedberrada.lob.core.SuspendMember;
import io.github.ahmedberrada.lob.core.TradingPhase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The order-record export: one line per event, with the member and the microsecond UTC time. */
class RecordsExportTest {

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return RecordsExport.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private List<String> lines() {
        return out.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    @Rulebook({"RS-001", "RS-006", "MB-001", "MB-002"})
    void everyEventBecomesARecordWithItsMemberAndTime() throws IOException {
        try (JournaledEngine engine = JournaledEngine.open(directory, CommandStream.INSTRUMENT,
                new JournalOptions(FsyncPolicy.OS, 0), new ManualClock(1_759_910_400_000_000L))) {
            engine.process(new SetMemberLimits(7, new MemberLimits(50, 0, 0, 0, 0)));
            engine.process(new LimitOrder(Side.SELL, 100, 5), 8);                          // order 1
            engine.process(new LimitOrder(Side.BUY, 100, 3), 7);                           // order 2, trades
            engine.process(new LimitOrder(Side.BUY, 90, 60), 7);                           // order 3, over the limit
            engine.process(new ReplaceOrder(1, 101, 2), 8);
            engine.process(new CancelOrder(1), 8);
            engine.process(new CancelOrder(99), 8);
            engine.process(new MarketOrder(Side.BUY, 1), 7);                               // no liquidity: rejected
            engine.process(new StopOrder(Side.BUY, 120, 130, 2), 7);
            engine.process(new SuspendMember(7));
            engine.process(new SetPhase(TradingPhase.CALL));
            engine.process(new SetPhase(TradingPhase.CALL));
        }

        assertThat(run(directory.toString())).isZero();

        List<String> lines = lines();
        assertThat(lines.getFirst()).isEqualTo(RecordsExport.HEADER);
        assertThat(lines).contains(
                "2025-10-08T08:00:00Z,JRNL,1,1,7,,MEMBER_LIMITS_CHANGED,,,,,,,,\"MemberLimits[maxOrderQuantity=50,"
                        + " maxOrderNotional=0, priceCollarBps=0, maxOpenOrders=0, maxNetPosition=0]\"",
                "2025-10-08T08:00:00.000001Z,JRNL,2,2,8,1,NEW_ORDER,SELL,LIMIT,GTC,100,5,,,",
                "2025-10-08T08:00:00.000001Z,JRNL,2,3,8,1,ORDER_RESTED,SELL,,,100,5,,,",
                "2025-10-08T08:00:00.000002Z,JRNL,3,4,7,2,NEW_ORDER,BUY,LIMIT,GTC,100,3,,,",
                "2025-10-08T08:00:00.000002Z,JRNL,3,5,7,2,TRADE,BUY,,,100,3,1,8,",
                "2025-10-08T08:00:00.000003Z,JRNL,4,6,7,3,NEW_ORDER_REJECTED,,,,,,,,RISK_ORDER_QUANTITY",
                "2025-10-08T08:00:00.000004Z,JRNL,5,7,8,1,ORDER_REPLACED,SELL,,,101,2,,,priority lost",
                "2025-10-08T08:00:00.000005Z,JRNL,6,9,8,1,ORDER_CANCELLED,,,,,2,,,CLIENT_REQUEST",
                "2025-10-08T08:00:00.000006Z,JRNL,7,10,8,99,CANCEL_REJECTED,,,,,,,,UNKNOWN_ORDER",
                "2025-10-08T08:00:00.000007Z,JRNL,8,11,7,4,NEW_ORDER_REJECTED,,,,,,,,NO_LIQUIDITY");
        assertThat(lines).anyMatch(l -> l.contains(",STOP_PARKED,BUY,,,120,2,"));
        assertThat(lines).anyMatch(l -> l.contains(",NEW_ORDER,BUY,STOP_LIMIT,,130,2,,,stop price 120"));
        assertThat(lines).anyMatch(l -> l.contains(",7,,MEMBER_SUSPENDED,,,,,1,,,"));
        assertThat(lines).anyMatch(l -> l.contains(",ORDER_CANCELLED,,,,,2,,,KILL_SWITCH"));
        assertThat(lines).anyMatch(l -> l.contains(",PHASE_CHANGED,,,,,,,,CONTINUOUS to CALL"));
        assertThat(lines).anyMatch(l -> l.contains(",PHASE_CHANGE_REJECTED,,,,,,,,INVALID_PHASE_TRANSITION"));
        assertThat(lines).allSatisfy(l -> assertThat(l).startsWith(l.startsWith("timestamp") ? "timestamp" : "2025-"));
    }

    @Test
    @Rulebook("RS-001")
    void marketOrdersAndAuctionsAreRecorded() throws IOException {
        try (JournaledEngine engine = JournaledEngine.open(directory, CommandStream.INSTRUMENT,
                new JournalOptions(FsyncPolicy.OS, 0), new ManualClock(1_759_910_400_000_000L))) {
            engine.process(new LimitOrder(Side.SELL, 100, 5));
            engine.process(new MarketOrder(Side.BUY, 2));
            engine.process(new SetPhase(TradingPhase.CALL));
            engine.process(new LimitOrder(Side.BUY, 100, 2));
            engine.process(new LimitOrder(Side.SELL, 100, 2));
            engine.process(new SetPhase(TradingPhase.CONTINUOUS));
        }

        assertThat(run(directory.toString())).isZero();

        assertThat(lines()).anyMatch(l -> l.contains(",NEW_ORDER,BUY,MARKET,IOC,,2,"))
                .anyMatch(l -> l.contains(",AUCTION_PRICE,,,,100,2,"));
    }

    @Test
    void refusesBadUsageMissingJournalsAndMismatches() throws IOException {
        assertThat(run()).isEqualTo(ReplayTool.USAGE);
        assertThat(run("--help")).isEqualTo(ReplayTool.USAGE);
        assertThat(run(directory.toString())).isEqualTo(ReplayTool.FAILED);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("no command journal");

        try (JournaledEngine engine = JournaledEngine.open(directory, CommandStream.INSTRUMENT,
                new JournalOptions(FsyncPolicy.OS, 0), new ManualClock(1))) {
            engine.process(new LimitOrder(Side.BUY, 100, 5));
            engine.process(new LimitOrder(Side.BUY, 99, 5));
        }
        // an event journal that runs past the command journal cannot be exported
        Path commands = directory.resolve(JournaledEngine.COMMANDS_FILE);
        byte[] bytes = Files.readAllBytes(commands);
        Files.write(commands, java.util.Arrays.copyOf(bytes, headerAndFirstFrame(bytes)));
        err.reset();
        assertThat(run(directory.toString())).isEqualTo(ReplayTool.FAILED);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("missing from the command journal");
    }

    /** The length of the header frame plus the first command frame: frames are length, CRC, payload. */
    private static int headerAndFirstFrame(byte[] bytes) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        int first = 8 + buffer.getInt(0);
        return first + 8 + buffer.getInt(first);
    }
}
