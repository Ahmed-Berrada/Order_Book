package io.github.ahmedberrada.lob.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.OrderAccepted;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayToolTest {

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void journalSomeOrders() throws IOException {
        try (JournaledEngine engine = JournaledEngine.open(directory, CommandStream.INSTRUMENT,
                new JournalOptions(FsyncPolicy.OS, 0), new ManualClock(1_759_910_400_000_000L))) {
            engine.process(new LimitOrder(Side.BUY, 100, 5));
            engine.process(new LimitOrder(Side.BUY, 99, 3));
            engine.process(new LimitOrder(Side.SELL, 102, 7));
            engine.process(new LimitOrder(Side.SELL, 104, 2));
        }
    }

    private int run(String... args) {
        return ReplayTool.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String errors() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @Rulebook("RS-009")
    void printsTheBookAfterTheLastCommand() {
        assertThat(run(directory.toString())).isZero();

        assertThat(output())
                .contains("JRNL (max quantity 100 lots, max price 1000 ticks)")
                .contains("4 replayed, last at 2025-10-08T08:00:00.000003Z")
                .contains("4 resting orders")
                // asks highest first, then bids highest first: the spread is in the middle
                .containsPattern("(?s)SELL\\s+104\\s+2\\s+1.*SELL\\s+102\\s+7\\s+1.*BUY\\s+100\\s+5\\s+1.*BUY\\s+99\\s+3\\s+1")
                .doesNotContain("Verification");
    }

    @Test
    @Rulebook("RS-009")
    void rebuildsTheBookAtAnEarlierSequence() {
        assertThat(run(directory.toString(), "--to", "1")).isZero();
        assertThat(output()).contains("1 replayed").contains("1 resting orders").doesNotContain("SELL ");

        assertThat(run(directory.toString(), "--to", "0")).isZero();
        assertThat(output()).contains("none replayed");
    }

    @Test
    @Rulebook("RS-009")
    void verifiesTheEventJournal() {
        assertThat(run(directory.toString(), "--verify")).isZero();
        assertThat(output()).contains("Verification    OK: 4 event batches identical");
    }

    @Test
    @Rulebook("RS-009")
    void reportsTheFirstDifferenceWithTheEventJournal() throws IOException {
        Path events = directory.resolve(JournaledEngine.EVENTS_FILE);
        Files.delete(events);
        try (FrameWriter writer = new FrameWriter(events, 0)) {
            writer.append(Codec.encodeHeader(new Codec.Header(Codec.Kind.EVENTS, CommandStream.INSTRUMENT)));
            writer.append(Codec.encodeBatch(new EventBatch(1, 1_759_910_400_000_000L, List.of(new OrderAccepted(1, 99)))));
        }

        assertThat(run(directory.toString(), "--verify")).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("MISMATCH at command 1")
                .contains("recorded:    EventBatch[commandSequence=1").contains("regenerated: EventBatch[commandSequence=1");
    }

    @Test
    void neverModifiesTheJournals() throws IOException {
        byte[] commands = Files.readAllBytes(directory.resolve(JournaledEngine.COMMANDS_FILE));
        Files2.append(directory.resolve(JournaledEngine.COMMANDS_FILE), new byte[] {9, 9});

        assertThat(run(directory.toString(), "--verify")).isZero();
        assertThat(Files.readAllBytes(directory.resolve(JournaledEngine.COMMANDS_FILE)))
                .hasSize(commands.length + 2);
        assertThat(output()).contains("4 replayed");
    }

    @Test
    void reportsUsageAndReadErrors() throws IOException {
        assertThat(run()).isEqualTo(2);
        assertThat(run("--to")).isEqualTo(2);
        assertThat(run(directory.toString(), "--to", "x")).isEqualTo(2);
        assertThat(run(directory.toString(), "--to", "-1")).isEqualTo(2);
        assertThat(run(directory.toString(), "--bogus")).isEqualTo(2);
        assertThat(run(directory.toString(), directory.toString())).isEqualTo(2);
        assertThat(errors()).contains("error: unexpected argument: --bogus").contains("usage: lob-replay");

        assertThat(run(directory.resolve("missing").toString())).isEqualTo(1);
        assertThat(errors()).contains("error: no command journal in");

        Path headerless = Files.createDirectory(directory.resolve("headerless"));
        Files.write(headerless.resolve(JournaledEngine.COMMANDS_FILE), new byte[0]);
        assertThat(run(headerless.toString())).isEqualTo(1);
        assertThat(errors()).contains("error: command journal has no header");

        Files2.flipByte(directory.resolve(JournaledEngine.COMMANDS_FILE), 20);
        assertThat(run(directory.toString())).isEqualTo(1);
        assertThat(errors()).contains("error: checksum mismatch");
    }
}
