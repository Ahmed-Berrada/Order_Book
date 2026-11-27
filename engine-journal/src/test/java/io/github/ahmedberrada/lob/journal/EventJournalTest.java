package io.github.ahmedberrada.lob.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventJournalTest {

    private static final JournalOptions NO_SNAPSHOTS = new JournalOptions(FsyncPolicy.OS, 0);

    @TempDir
    Path directory;

    @TempDir
    Path other;

    private void write(Path dir, Instrument instrument, int orders) throws IOException {
        try (JournaledEngine engine = JournaledEngine.open(dir, instrument, NO_SNAPSHOTS, new ManualClock(1_000))) {
            for (int i = 0; i < orders; i++) {
                engine.process(new LimitOrder(i % 2 == 0 ? Side.SELL : Side.BUY, 100, 2), 5 + i % 2);
            }
        }
    }

    private static void copy(Path from, Path to, String file) throws IOException {
        Files.copy(from.resolve(file), to.resolve(file), StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    @Rulebook("RS-007")
    void readsEveryBatchInCommandOrderWithItsMember() throws IOException {
        write(directory, CommandStream.INSTRUMENT, 2);
        try (EventJournal journal = EventJournal.open(directory)) {
            assertThat(journal.instrument()).isEqualTo(CommandStream.INSTRUMENT);
            assertThat(journal.commands()).isEqualTo(2);
            EventBatch first = journal.next();
            EventBatch second = journal.next();
            assertThat(first.commandSequence()).isEqualTo(1);
            assertThat(first.memberId()).isEqualTo(5);
            assertThat(second.commandSequence()).isEqualTo(2);
            assertThat(second.events()).filteredOn(TradeExecuted.class::isInstance).singleElement()
                    .isEqualTo(new TradeExecuted(4, 2, 1, Side.BUY, 100, 2, 0, 0, 6, 5));
            assertThat(journal.next()).isNull();
            assertThat(journal.next()).isNull();
        }
    }

    @Test
    void anEmptyDirectoryHasNothingToRead() throws IOException {
        assertThatThrownBy(() -> EventJournal.open(directory)).isInstanceOf(JournalCorruptedException.class)
                .hasMessageContaining("no command journal");
        write(directory, CommandStream.INSTRUMENT, 0);
        try (EventJournal journal = EventJournal.open(directory)) {
            assertThat(journal.commands()).isZero();
            assertThat(journal.next()).isNull();
        }
    }

    @Test
    @Rulebook("RS-007")
    void anEventJournalBehindItsCommandsIsRefusedAtItsEnd() throws IOException {
        write(other, CommandStream.INSTRUMENT, 1);
        write(directory, CommandStream.INSTRUMENT, 3);
        copy(other, directory, JournaledEngine.EVENTS_FILE);                // a crash lost the last batches
        try (EventJournal journal = EventJournal.open(directory)) {
            assertThat(journal.next().commandSequence()).isEqualTo(1);
            assertThatThrownBy(journal::next).isInstanceOf(JournalCorruptedException.class)
                    .hasMessageContaining("ends at command 1 of 3").hasMessageContaining("recover");
        }
    }

    @Test
    void anEventJournalBeyondItsCommandsIsRefused() throws IOException {
        write(other, CommandStream.INSTRUMENT, 3);
        write(directory, CommandStream.INSTRUMENT, 1);
        copy(other, directory, JournaledEngine.EVENTS_FILE);
        try (EventJournal journal = EventJournal.open(directory)) {
            assertThat(journal.next().commandSequence()).isEqualTo(1);
            assertThatThrownBy(journal::next).isInstanceOf(JournalCorruptedException.class)
                    .hasMessageContaining("holds command 2 but the command journal ends at 1");
        }
    }

    @Test
    void journalsOfTwoInstrumentsAreRefused() throws IOException {
        write(other, new Instrument("OTHER", 1_000, 10_000), 1);
        write(directory, CommandStream.INSTRUMENT, 1);
        copy(other, directory, JournaledEngine.EVENTS_FILE);
        assertThatThrownBy(() -> EventJournal.open(directory)).isInstanceOf(JournalCorruptedException.class)
                .hasMessageContaining("belongs to");
    }
}
