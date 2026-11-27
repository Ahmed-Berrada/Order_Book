package io.github.ahmedberrada.lob.journal;

import io.github.ahmedberrada.lob.core.Instrument;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Read-only access to the events an instrument recorded, for tools outside this module (post-trade,
 * ADR-0019). It reads the journals as they are and never writes to them.
 *
 * <p>The event journal can lag behind the command journal after a crash: recovery regenerates the missing
 * batches the next time the venue opens the directory (RS-007). Reading such a journal would silently miss
 * trades, so {@link #next()} fails at its end instead, telling the operator to recover first.
 */
public final class EventJournal implements Closeable {

    private final Path directory;
    private final JournalReader<EventBatch> events;
    private final Instrument instrument;
    private final long commands;
    private long lastCommandSequence;

    private EventJournal(Path directory, JournalReader<EventBatch> events, Instrument instrument, long commands) {
        this.directory = directory;
        this.events = events;
        this.instrument = instrument;
        this.commands = commands;
    }

    /**
     * Opens the journals of an instrument's directory for reading.
     *
     * @throws JournalCorruptedException if there is no command journal, or the two journals name different
     *                                   instruments
     */
    public static EventJournal open(Path instrumentDirectory) throws IOException {
        Path commandsFile = instrumentDirectory.resolve(JournaledEngine.COMMANDS_FILE);
        if (!Files.exists(commandsFile)) {
            throw new JournalCorruptedException("no command journal in " + instrumentDirectory);
        }
        Instrument instrument;
        long commands;
        try (JournalReader<JournalEntry> reader = JournalReader.commands(commandsFile)) {
            instrument = reader.instrument();
            while (reader.next() != null) {
                // read to the end: the number of commands is what the event journal must cover
            }
            commands = reader.lastSequence();
        }
        JournalReader<EventBatch> events = JournalReader.events(instrumentDirectory.resolve(JournaledEngine.EVENTS_FILE));
        if (events.instrument() != null && !events.instrument().equals(instrument)) {
            Instrument found = events.instrument();
            events.close();
            throw new JournalCorruptedException("the event journal of " + instrumentDirectory + " belongs to "
                    + found + ", not " + instrument);
        }
        return new EventJournal(instrumentDirectory, events, instrument, commands);
    }

    /** The instrument named in the command journal, or {@code null} if it has no complete header. */
    public Instrument instrument() {
        return instrument;
    }

    /** The number of commands in the command journal: the batches {@link #next()} returns. */
    public long commands() {
        return commands;
    }

    /**
     * The next batch of events, in command order, or {@code null} once every command's batch has been read.
     *
     * @throws JournalCorruptedException if the event journal ends before the command journal does, or goes
     *                                   beyond it
     */
    public EventBatch next() throws IOException {
        EventBatch batch = events.next();
        if (batch != null) {
            if (batch.commandSequence() > commands) {
                throw new JournalCorruptedException("the event journal of " + directory + " holds command "
                        + batch.commandSequence() + " but the command journal ends at " + commands);
            }
            lastCommandSequence = batch.commandSequence();
            return batch;
        }
        if (lastCommandSequence < commands) {
            throw new JournalCorruptedException("the event journal of " + directory + " ends at command "
                    + lastCommandSequence + " of " + commands + "; open the venue once to recover it (RS-007)");
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        events.close();
    }
}
