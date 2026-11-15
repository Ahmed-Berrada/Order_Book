package io.github.ahmedberrada.lob.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Rulebook;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * A crash can stop writing at any byte. Whatever the cut point in either journal, and whatever
 * snapshots exist, recovery must rebuild the state of exactly the complete commands.
 */
class RecoveryPropertyTest {

    private Path directory;

    @BeforeTry
    void createDirectory() throws IOException {
        directory = Files.createTempDirectory("recovery");
    }

    @AfterTry
    void deleteDirectory() throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Property(tries = 200)
    @Rulebook({"RS-002", "RS-003", "RS-005", "RS-007"})
    void recoveryRebuildsTheStateOfTheCompleteCommands(
            @ForAll long seed,
            @ForAll @IntRange(min = 1, max = 300) int count,
            @ForAll @IntRange(max = 50) int snapshotInterval,
            @ForAll @IntRange(max = 1_000) int commandCut,
            @ForAll @IntRange(max = 5_000) int eventCut) throws IOException {
        List<Command> commands = CommandStream.generate(seed, count);
        JournalOptions options = new JournalOptions(FsyncPolicy.OS, snapshotInterval);
        ManualClock clock = new ManualClock(1);
        try (JournaledEngine engine = JournaledEngine.open(directory, CommandStream.INSTRUMENT, options, clock)) {
            commands.forEach(engine::process);
        }
        Path commandsFile = directory.resolve(JournaledEngine.COMMANDS_FILE);
        Path eventsFile = directory.resolve(JournaledEngine.EVENTS_FILE);
        Files2.truncate(commandsFile, Math.max(0, Files.size(commandsFile) - commandCut));
        long completeCommands = countRecords(JournalReader.commands(commandsFile));
        Files2.truncate(eventsFile, Math.max(0, Files.size(eventsFile) - eventCut));
        long eventBatches = Math.min(completeCommands, countRecords(JournalReader.events(eventsFile)));
        if (countRecords(JournalReader.events(eventsFile)) > completeCommands) {
            Files2.truncate(eventsFile, validLengthAfter(eventsFile, completeCommands));
        }

        try (JournaledEngine engine = JournaledEngine.open(directory, CommandStream.INSTRUMENT, options, clock)) {
            assertThat(engine.recovery().lastCommandSequence()).isEqualTo(completeCommands);
            assertThat(engine.recovery().eventBatchesRestored()).isEqualTo(completeCommands - eventBatches);
            JournaledEngineTest.assertSameState(engine, CommandStream.expectedAfter(commands, completeCommands));
        }
        assertThat(ReplayTool.run(new String[] {directory.toString(), "--verify"},
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()), System.err)).isZero();
    }

    private static long countRecords(JournalReader<?> reader) throws IOException {
        try (reader) {
            while (reader.next() != null) {
                // count via lastSequence
            }
            return reader.lastSequence();
        }
    }

    private static long validLengthAfter(Path eventsFile, long batches) throws IOException {
        try (JournalReader<EventBatch> reader = JournalReader.events(eventsFile)) {
            for (long i = 0; i < batches; i++) {
                reader.next();
            }
            return reader.validLength();
        }
    }
}
