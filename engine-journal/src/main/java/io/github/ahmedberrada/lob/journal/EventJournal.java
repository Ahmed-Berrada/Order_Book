package io.github.ahmedberrada.lob.journal;

import io.github.ahmedberrada.lob.core.Instrument;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Read-only access to the events an instrument recorded, for tools outside this module (post-trade,
 * ADR-0019). It reads the journals as they are and never writes to them.
 *
 * <p>Every batch must belong to the command of the same sequence number: the same timestamp and the same
 * member. An event journal copied from another directory or another day is refused, not read.
 *
 * <p>The event journal can lag behind the command journal: after a crash, until recovery regenerates the
 * missing batches (RS-007), or for an instant while the venue appends. Reading it then would silently miss
 * trades, so {@link #next()} fails at its end instead. The extent of the event journal is fixed before the
 * command journal is read, so a venue that appends meanwhile can only make the events look behind, never ahead.
 */
public final class EventJournal implements Closeable {

    private final Path directory;
    private final JournalReader<EventBatch> events;
    private final Instrument instrument;
    private final long[] timestamps;
    private final long[] members;
    private final long commands;
    private long lastCommandSequence;

    private EventJournal(Path directory, JournalReader<EventBatch> events, Instrument instrument, long[] timestamps,
            long[] members, long commands) {
        this.directory = directory;
        this.events = events;
        this.instrument = instrument;
        this.timestamps = timestamps;
        this.members = members;
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
        // The events first: their extent is fixed before the commands are read (see the class comment).
        JournalReader<EventBatch> events = JournalReader.events(instrumentDirectory.resolve(JournaledEngine.EVENTS_FILE));
        try {
            Instrument instrument;
            long[] timestamps = new long[1024];
            long[] members = new long[1024];
            int count = 0;
            try (JournalReader<JournalEntry> reader = JournalReader.commands(commandsFile)) {
                instrument = reader.instrument();
                for (JournalEntry entry = reader.next(); entry != null; entry = reader.next()) {
                    if (count == timestamps.length) {
                        timestamps = Arrays.copyOf(timestamps, count * 2);
                        members = Arrays.copyOf(members, count * 2);
                    }
                    timestamps[count] = entry.timestampMicros();
                    members[count] = entry.memberId();
                    count++;
                }
            }
            if (events.instrument() != null && !events.instrument().equals(instrument)) {
                throw new JournalCorruptedException("the event journal of " + instrumentDirectory + " belongs to "
                        + events.instrument() + ", not " + instrument);
            }
            return new EventJournal(instrumentDirectory, events, instrument, timestamps, members, count);
        } catch (IOException | RuntimeException e) {
            events.close();
            throw e;
        }
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
     * @throws JournalCorruptedException if the event journal ends before the command journal does, goes beyond
     *                                   it, or holds a batch of another command
     */
    public EventBatch next() throws IOException {
        EventBatch batch = events.next();
        if (batch != null) {
            long sequence = batch.commandSequence();
            if (sequence > commands) {
                throw new JournalCorruptedException("the event journal of " + directory + " holds command "
                        + sequence + " but the command journal ends at " + commands);
            }
            int index = (int) (sequence - 1);
            if (batch.timestampMicros() != timestamps[index] || batch.memberId() != members[index]) {
                throw new JournalCorruptedException("the event batch of command " + sequence + " in " + directory
                        + " does not belong to that command: its timestamp or member differs");
            }
            lastCommandSequence = sequence;
            return batch;
        }
        if (lastCommandSequence < commands) {
            throw new JournalCorruptedException("the event journal of " + directory + " ends at command "
                    + lastCommandSequence + " of " + commands + ": the venue is writing it, or stopped before "
                    + "recording every command's events. Run again when no commands arrive, or open the venue "
                    + "once to recover it (RS-007)");
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        events.close();
    }
}
