package io.github.ahmedberrada.lob.journal;

import static io.github.ahmedberrada.lob.journal.CommandStream.INSTRUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import io.github.ahmedberrada.lob.core.OrderAccepted;
import io.github.ahmedberrada.lob.core.OrderRested;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournaledEngineTest {

    private static final JournalOptions NO_SNAPSHOTS = new JournalOptions(FsyncPolicy.EVERY_COMMAND, 0);
    private static final JournalOptions EVERY_20 = new JournalOptions(FsyncPolicy.EVERY_COMMAND, 20);

    @TempDir
    Path directory;

    private final ManualClock clock = new ManualClock(1_700_000_000_000_000L);

    private JournaledEngine open(JournalOptions options) throws IOException {
        return JournaledEngine.open(directory, INSTRUMENT, options, clock);
    }

    /** Opens, processes the commands, closes; returns the events each command produced. */
    private List<EventBatch> session(JournalOptions options, List<Command> commands) throws IOException {
        List<EventBatch> batches = new ArrayList<>();
        try (JournaledEngine engine = open(options)) {
            commands.forEach(command -> batches.add(engine.process(command)));
        }
        return batches;
    }

    private Path commandsFile() {
        return directory.resolve(JournaledEngine.COMMANDS_FILE);
    }

    private Path eventsFile() {
        return directory.resolve(JournaledEngine.EVENTS_FILE);
    }

    private List<Path> snapshotFiles() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().startsWith("snapshot-"))
                    .sorted(Comparator.reverseOrder()).toList();
        }
    }

    // ---- Durability --------------------------------------------------------------------------------

    @Test
    @Rulebook({"RS-001", "RS-006"})
    void journalsEveryCommandAndStampsItsEvents() throws IOException {
        try (JournaledEngine engine = open(NO_SNAPSHOTS)) {
            assertThat(engine.recovery()).isEqualTo(new RecoveryReport(0, 0, 0, 0, 0));
            assertThat(engine.instrument()).isEqualTo(INSTRUMENT);

            EventBatch batch = engine.process(new LimitOrder(Side.BUY, 100, 5));

            assertThat(batch).isEqualTo(new EventBatch(1, 1_700_000_000_000_000L, List.of(
                    new OrderAccepted(1, 1), new OrderRested(2, 1, Side.BUY, 100, 5))));
            assertThat(engine.lastCommandSequence()).isEqualTo(1);
            // write-ahead: the command is in the journal as soon as process() returns
            try (JournalReader<JournalEntry> reader = JournalReader.commands(commandsFile())) {
                assertThat(reader.next()).isEqualTo(
                        new JournalEntry(1, 1_700_000_000_000_000L, new LimitOrder(Side.BUY, 100, 5)));
            }
        }
    }

    @Test
    @Rulebook("RS-002")
    void restartRestoresTheBookAndContinuesNumbering() throws IOException {
        List<Command> commands = CommandStream.generate(7, 300);
        List<EventBatch> uninterrupted = session(NO_SNAPSHOTS, commands);
        Files.delete(commandsFile());
        Files.delete(eventsFile());

        List<EventBatch> interrupted = new ArrayList<>(session(NO_SNAPSHOTS, commands.subList(0, 120)));
        try (JournaledEngine engine = open(NO_SNAPSHOTS)) {
            assertThat(engine.recovery()).isEqualTo(new RecoveryReport(0, 120, 120, 0, 0));
            assertThat(engine.book().depth(Side.BUY))
                    .isEqualTo(CommandStream.expectedAfter(commands, 120).book().depth(Side.BUY));
            commands.subList(120, 300).forEach(c -> interrupted.add(engine.process(c)));
        }

        assertThat(eventsOnly(interrupted)).isEqualTo(eventsOnly(uninterrupted));
    }

    @Test
    @Rulebook("RS-006")
    void timestampsNeverGoBackwards() throws IOException {
        try (JournaledEngine engine = open(NO_SNAPSHOTS)) {
            clock.set(500);
            assertThat(engine.process(new LimitOrder(Side.BUY, 100, 1)).timestampMicros()).isEqualTo(500);
            clock.set(200);
            assertThat(engine.process(new LimitOrder(Side.BUY, 100, 1)).timestampMicros()).isEqualTo(500);
        }
        clock.set(100);
        try (JournaledEngine engine = open(NO_SNAPSHOTS)) {
            assertThat(engine.process(new LimitOrder(Side.BUY, 100, 1)).timestampMicros()).isEqualTo(500);
        }
    }

    @Test
    @Rulebook("RS-008")
    void reopeningNeverRewritesExistingRecords() throws IOException {
        List<Command> commands = CommandStream.generate(3, 100);
        session(JournalOptions.defaults(), commands.subList(0, 50));
        byte[] commandsBefore = Files.readAllBytes(commandsFile());
        byte[] eventsBefore = Files.readAllBytes(eventsFile());

        session(JournalOptions.defaults(), commands.subList(50, 100));

        assertThat(Files.readAllBytes(commandsFile())).startsWith(commandsBefore);
        assertThat(Files.readAllBytes(eventsFile())).startsWith(eventsBefore);
    }

    @Test
    @Rulebook("RS-001")
    void osSyncPolicyAlsoJournalsEveryCommand() throws IOException {
        List<Command> commands = CommandStream.generate(11, 40);
        session(new JournalOptions(FsyncPolicy.OS, 0), commands);

        try (JournaledEngine engine = open(NO_SNAPSHOTS)) {
            assertThat(engine.lastCommandSequence()).isEqualTo(40);
        }
    }

    // ---- Torn Tails And Corruption -----------------------------------------------------------------

    @Test
    @Rulebook("RS-003")
    void tornTailIsDiscardedAndJournalingResumes() throws IOException {
        List<Command> commands = CommandStream.generate(5, 60);
        session(NO_SNAPSHOTS, commands.subList(0, 50));
        // crash while writing command 50: its record is torn and its events were never written
        Files2.truncate(commandsFile(), Files.size(commandsFile()) - 3);
        try (JournalReader<EventBatch> reader = JournalReader.events(eventsFile())) {
            for (int i = 0; i < 49; i++) {
                reader.next();
            }
            Files2.truncate(eventsFile(), reader.validLength());
        }
        Files2.append(eventsFile(), new byte[] {0, 0, 0, 40, 1, 2});

        try (JournaledEngine engine = open(NO_SNAPSHOTS)) {
            RecoveryReport report = engine.recovery();
            assertThat(report.lastCommandSequence()).isEqualTo(49);
            assertThat(report.tornBytesDiscarded()).isPositive();
            commands.subList(50, 60).forEach(engine::process);
        }
        try (JournaledEngine engine = open(NO_SNAPSHOTS)) {
            assertThat(engine.recovery().lastCommandSequence()).isEqualTo(59);
            assertThat(engine.recovery().tornBytesDiscarded()).isZero();
        }
    }

    @Test
    @Rulebook("RS-003")
    void tornHeaderIsRewritten() throws IOException {
        Files.write(commandsFile(), new byte[] {0, 0, 0});

        try (JournaledEngine engine = open(NO_SNAPSHOTS)) {
            assertThat(engine.recovery().tornBytesDiscarded()).isEqualTo(3);
            engine.process(new LimitOrder(Side.SELL, 101, 1));
        }
        try (JournaledEngine engine = open(NO_SNAPSHOTS)) {
            assertThat(engine.book().bestAsk()).hasValue(101);
        }
    }

    @Test
    @Rulebook("RS-004")
    void damagedRecordInTheMiddleStopsRecovery() throws IOException {
        session(NO_SNAPSHOTS, CommandStream.generate(5, 20));
        Files2.flipByte(commandsFile(), Files.size(commandsFile()) / 2);

        assertThatThrownBy(() -> open(NO_SNAPSHOTS)).isInstanceOf(JournalCorruptedException.class);
    }

    @Test
    @Rulebook("RS-004")
    void journalOfAnotherInstrumentIsRefused() throws IOException {
        session(NO_SNAPSHOTS, CommandStream.generate(5, 5));
        Instrument other = new Instrument("OTHER", 100, 1_000);

        assertThatThrownBy(() -> JournaledEngine.open(directory, other, NO_SNAPSHOTS, clock))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("OTHER");
    }

    @Test
    @Rulebook("RS-004")
    void eachJournalChecksItsInstrumentOnItsOwn() throws IOException {
        Instrument other = new Instrument("OTHER", 100, 1_000);
        session(NO_SNAPSHOTS, CommandStream.generate(5, 5));
        Files.delete(eventsFile());
        assertThatThrownBy(() -> JournaledEngine.open(directory, other, NO_SNAPSHOTS, clock))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("commands.journal belongs to");

        Files.delete(commandsFile());
        session(NO_SNAPSHOTS, CommandStream.generate(5, 5));
        Files.delete(commandsFile());
        assertThatThrownBy(() -> JournaledEngine.open(directory, other, NO_SNAPSHOTS, clock))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("events.journal belongs to");
    }

    @Test
    @Rulebook("RS-004")
    void gapInCommandSequenceIsCorruption() throws IOException {
        try (FrameWriter writer = new FrameWriter(commandsFile(), 0)) {
            writer.append(Codec.encodeHeader(new Codec.Header(Codec.Kind.COMMANDS, INSTRUMENT)));
            writer.append(Codec.encodeEntry(new JournalEntry(1, 10, new LimitOrder(Side.BUY, 100, 1))));
            writer.append(Codec.encodeEntry(new JournalEntry(3, 10, new LimitOrder(Side.BUY, 100, 1))));
        }

        assertThatThrownBy(() -> open(NO_SNAPSHOTS))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("sequence 3 follows 1");
    }

    @Test
    @Rulebook("RS-004")
    void decreasingTimestampIsCorruption() throws IOException {
        try (FrameWriter writer = new FrameWriter(commandsFile(), 0)) {
            writer.append(Codec.encodeHeader(new Codec.Header(Codec.Kind.COMMANDS, INSTRUMENT)));
            writer.append(Codec.encodeEntry(new JournalEntry(1, 10, new LimitOrder(Side.BUY, 100, 1))));
            writer.append(Codec.encodeEntry(new JournalEntry(2, 9, new LimitOrder(Side.BUY, 100, 1))));
        }

        assertThatThrownBy(() -> open(NO_SNAPSHOTS))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("timestamp");
    }

    @Test
    @Rulebook("RS-004")
    void swappedJournalFilesAreRefused() throws IOException {
        session(NO_SNAPSHOTS, CommandStream.generate(5, 5));
        Path swap = directory.resolve("swap");
        Files.move(commandsFile(), swap);
        Files.move(eventsFile(), commandsFile());
        Files.move(swap, eventsFile());

        assertThatThrownBy(() -> open(NO_SNAPSHOTS))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("expected");
    }

    @Test
    @Rulebook("RS-004")
    void eventJournalAheadOfCommandJournalIsCorruption() throws IOException {
        session(NO_SNAPSHOTS, CommandStream.generate(5, 10));
        Files.delete(commandsFile());

        assertThatThrownBy(() -> open(NO_SNAPSHOTS))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("ahead");
    }

    // ---- Snapshots ---------------------------------------------------------------------------------

    @Test
    @Rulebook("RS-005")
    void recoveryStartsFromTheNewestSnapshotAndKeepsTwo() throws IOException {
        List<Command> commands = CommandStream.generate(9, 90);
        session(EVERY_20, commands);

        assertThat(snapshotFiles()).extracting(p -> p.getFileName().toString()).containsExactly(
                "snapshot-00000000000000000080.snap", "snapshot-00000000000000000060.snap");
        try (JournaledEngine engine = open(EVERY_20)) {
            assertThat(engine.recovery()).isEqualTo(new RecoveryReport(80, 10, 90, 0, 0));
            assertSameState(engine, CommandStream.expectedAfter(commands, 90));
        }
    }

    @Test
    @Rulebook("RS-005")
    void snapshotOfTheLastCommandNeedsNoReplay() throws IOException {
        List<Command> commands = CommandStream.generate(9, 60);
        session(EVERY_20, commands);

        try (JournaledEngine engine = open(EVERY_20)) {
            assertThat(engine.recovery()).isEqualTo(new RecoveryReport(60, 0, 60, 0, 0));
            assertSameState(engine, CommandStream.expectedAfter(commands, 60));
        }
    }

    @Test
    @Rulebook("RS-005")
    void damagedSnapshotFallsBackToTheOlderOne() throws IOException {
        List<Command> commands = CommandStream.generate(9, 50);
        session(EVERY_20, commands);
        Path newest = snapshotFiles().getFirst();
        Files2.flipByte(newest, Files.size(newest) - 1);

        try (JournaledEngine engine = open(EVERY_20)) {
            assertThat(engine.recovery().snapshotSequence()).isEqualTo(20);
            assertSameState(engine, CommandStream.expectedAfter(commands, 50));
        }
    }

    @Test
    @Rulebook("RS-005")
    void untrustworthySnapshotsAreIgnored() throws IOException {
        List<Command> commands = CommandStream.generate(9, 30);
        session(EVERY_20, commands);
        Path snapshot = snapshotFiles().getFirst();
        byte[] valid = Files.readAllBytes(snapshot);

        Files.write(snapshot, new byte[0]);                                     // empty
        assertRecoversFully(commands);
        Files.write(snapshot, valid);
        Files2.append(snapshot, valid);                                          // two frames
        assertRecoversFully(commands);

        MatchingEngine other = new MatchingEngine(new Instrument("OTHER", 100, 1_000));
        new SnapshotStore(directory, other.instrument()).write(20, other.snapshot());  // another instrument
        assertRecoversFully(commands);

        Files.write(snapshot, frame(Codec.encodeHeader(new Codec.Header(Codec.Kind.SNAPSHOT, INSTRUMENT))));
        assertRecoversFully(commands);                                           // undecodable
    }

    @Test
    @Rulebook("RS-005")
    void snapshotAheadOfTheJournalIsIgnored() throws IOException {
        List<Command> commands = CommandStream.generate(9, 50);
        session(EVERY_20, commands);
        List<Path> snapshots = snapshotFiles();
        Path keep = Files.copy(snapshots.getFirst(), directory.resolve("keep"));   // at 40
        for (Path p : snapshots) {
            Files.delete(p);
        }
        Files.delete(commandsFile());
        Files.delete(eventsFile());
        session(NO_SNAPSHOTS, commands.subList(0, 30));
        Files.move(keep, directory.resolve("snapshot-00000000000000000040.snap"));

        try (JournaledEngine engine = open(NO_SNAPSHOTS)) {
            assertThat(engine.recovery().snapshotSequence()).isZero();
            assertSameState(engine, CommandStream.expectedAfter(commands, 30));
        }
    }

    @Test
    @Rulebook("RS-005")
    void leftoverTemporarySnapshotIsCleanedUp() throws IOException {
        session(EVERY_20, CommandStream.generate(9, 25));
        Path temporary = directory.resolve("snapshot-00000000000000000025.snap.tmp");
        Files.write(temporary, new byte[] {1, 2, 3});

        try (JournaledEngine engine = open(EVERY_20)) {
            assertThat(engine.recovery().snapshotSequence()).isEqualTo(20);
        }
        assertThat(temporary).doesNotExist();
    }

    private void assertRecoversFully(List<Command> commands) throws IOException {
        try (JournaledEngine engine = open(NO_SNAPSHOTS)) {
            assertThat(engine.recovery().snapshotSequence()).isZero();
            assertSameState(engine, CommandStream.expectedAfter(commands, commands.size()));
        }
    }

    // ---- Event Journal -----------------------------------------------------------------------------

    @Test
    @Rulebook("RS-007")
    void missingEventBatchesAreRegeneratedOnRecovery() throws IOException {
        List<Command> commands = CommandStream.generate(13, 40);
        List<EventBatch> produced = session(NO_SNAPSHOTS, commands);
        long fullSize = Files.size(eventsFile());
        long cut = 0;
        try (JournalReader<EventBatch> reader = JournalReader.events(eventsFile())) {
            for (int i = 0; i < 30; i++) {
                reader.next();
            }
            cut = reader.validLength();
        }
        Files2.truncate(eventsFile(), cut);

        try (JournaledEngine engine = open(NO_SNAPSHOTS)) {
            assertThat(engine.recovery().eventBatchesRestored()).isEqualTo(10);
        }
        assertThat(Files.size(eventsFile())).isEqualTo(fullSize);
        assertThat(readEvents()).isEqualTo(produced);
    }

    @Test
    @Rulebook({"RS-005", "RS-007"})
    void snapshotIsNotUsedWhenOlderEventsAreMissing() throws IOException {
        List<Command> commands = CommandStream.generate(13, 45);
        List<EventBatch> produced = session(new JournalOptions(FsyncPolicy.EVERY_COMMAND, 20), commands);
        Files.delete(eventsFile());

        try (JournaledEngine engine = open(NO_SNAPSHOTS)) {
            assertThat(engine.recovery()).isEqualTo(new RecoveryReport(0, 45, 45, 45, 0));
        }
        assertThat(readEvents()).isEqualTo(produced);
    }

    private List<EventBatch> readEvents() throws IOException {
        List<EventBatch> batches = new ArrayList<>();
        try (JournalReader<EventBatch> reader = JournalReader.events(eventsFile())) {
            for (EventBatch b = reader.next(); b != null; b = reader.next()) {
                batches.add(b);
            }
        }
        return batches;
    }

    // ---- Lifecycle ---------------------------------------------------------------------------------

    @Test
    void stopsAfterAnIoFailure() throws IOException {
        JournaledEngine engine = open(NO_SNAPSHOTS);
        engine.process(new LimitOrder(Side.BUY, 100, 1));
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(directory);

        assertThatThrownBy(engine::snapshot).isInstanceOf(UncheckedIOException.class);
        assertThatIllegalStateException().isThrownBy(() -> engine.process(new LimitOrder(Side.BUY, 100, 1)))
                .withMessageContaining("stopped");
        engine.close();
    }

    @Test
    void refusesCommandsOnceClosed() throws IOException {
        JournaledEngine engine = open(NO_SNAPSHOTS);
        engine.close();
        engine.close();

        assertThatIllegalStateException().isThrownBy(() -> engine.process(new LimitOrder(Side.BUY, 100, 1)))
                .withMessageContaining("closed");
        assertThatIllegalStateException().isThrownBy(engine::snapshot).withMessageContaining("closed");
    }

    @Test
    void validatesArguments() {
        assertThatNullPointerException().isThrownBy(() -> JournaledEngine.open(directory, null, NO_SNAPSHOTS, clock));
        assertThatNullPointerException().isThrownBy(() -> JournaledEngine.open(directory, INSTRUMENT, null, clock));
        assertThatNullPointerException().isThrownBy(() -> JournaledEngine.open(directory, INSTRUMENT, NO_SNAPSHOTS, null));
        assertThatNullPointerException().isThrownBy(() -> new JournalOptions(null, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new JournalOptions(FsyncPolicy.OS, -1));
        assertThatNullPointerException().isThrownBy(() -> new JournalEntry(1, 1, null));
        assertThat(JournalOptions.defaults()).isEqualTo(new JournalOptions(FsyncPolicy.EVERY_COMMAND, 10_000));
    }

    @Test
    @Rulebook("RS-006")
    void systemTimeSourceIsUtcMicroseconds() {
        long before = System.currentTimeMillis() * 1_000;
        long now = TimeSource.system().nowMicros();
        long after = System.currentTimeMillis() * 1_000 + 1_000;

        assertThat(now).isBetween(before, after);
    }

    private static byte[] frame(byte[] payload) throws IOException {
        Path file = Files.createTempFile("frame", ".bin");
        try (FrameWriter writer = new FrameWriter(file, 0)) {
            writer.append(payload);
        }
        byte[] bytes = Files.readAllBytes(file);
        Files.delete(file);
        return bytes;
    }

    private static List<List<Event>> eventsOnly(List<EventBatch> batches) {
        return batches.stream().map(EventBatch::events).toList();
    }

    /** Same resting orders and queues, same next order ID, same next event sequence. */
    static void assertSameState(JournaledEngine recovered, MatchingEngine expected) {
        assertThat(recovered.state()).isEqualTo(expected.snapshot());
    }
}
