package io.github.ahmedberrada.lob.journal;

import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.EngineSnapshot;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import io.github.ahmedberrada.lob.core.OrderBook;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link MatchingEngine} made durable: every command is journaled before it is processed, its events
 * are recorded, and the state survives restarts (rulebook chapter {@code resilience.md}, ADR-0003).
 *
 * <p>One directory per instrument holds {@code commands.journal}, {@code events.journal} and the
 * snapshots. Like the engine it wraps, an instance belongs to a single thread.
 *
 * <p>Fail-stop: if writing a journal or snapshot fails, the instance refuses every further command,
 * because it can no longer prove what it has recorded. Recovery then happens through {@link #open}.
 */
public final class JournaledEngine implements AutoCloseable {

    static final String COMMANDS_FILE = "commands.journal";
    static final String EVENTS_FILE = "events.journal";

    private final MatchingEngine engine;
    private final JournalOptions options;
    private final TimeSource timeSource;
    private final FrameWriter commands;
    private final FrameWriter events;
    private final SnapshotStore snapshots;
    private final RecoveryReport recovery;
    private long lastCommandSequence;
    private long lastTimestampMicros;
    private long commandsSinceSnapshot;
    private boolean failed;
    private boolean closed;

    private JournaledEngine(MatchingEngine engine, JournalOptions options, TimeSource timeSource,
            FrameWriter commands, FrameWriter events, SnapshotStore snapshots, RecoveryReport recovery,
            long lastTimestampMicros) {
        this.engine = engine;
        this.options = options;
        this.timeSource = timeSource;
        this.commands = commands;
        this.events = events;
        this.snapshots = snapshots;
        this.recovery = recovery;
        this.lastCommandSequence = recovery.lastCommandSequence();
        this.lastTimestampMicros = lastTimestampMicros;
    }

    /**
     * Opens the journals of an instrument, creating them if needed, and rebuilds the engine from them
     * (RS-002 to RS-005, RS-007).
     *
     * @throws JournalCorruptedException if the journals cannot be read completely or belong to another
     *                                   instrument
     */
    public static JournaledEngine open(Path directory, Instrument instrument, JournalOptions options,
            TimeSource timeSource) throws IOException {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(timeSource, "timeSource");
        Files.createDirectories(directory);
        Path commandsFile = directory.resolve(COMMANDS_FILE);
        Path eventsFile = directory.resolve(EVENTS_FILE);
        SnapshotStore snapshots = new SnapshotStore(directory, instrument);

        // 1. The event journal: only how far it goes is needed.
        long lastEventSequence;
        long eventsValidLength;
        long tornBytes;
        try (JournalReader<EventBatch> reader = JournalReader.events(eventsFile)) {
            checkInstrument(reader.instrument(), instrument, eventsFile);
            while (reader.next() != null) {
                // validated while reading
            }
            lastEventSequence = reader.lastSequence();
            eventsValidLength = reader.validLength();
            tornBytes = reader.tornBytes();
        }

        // 2. The newest usable snapshot. It must not be ahead of the event journal, whose older batches
        //    could not be regenerated from it; this also rules out a snapshot ahead of the commands.
        Codec.StoredSnapshot snapshot = null;
        for (Path candidate : snapshots.newestFirst()) {
            Optional<Codec.StoredSnapshot> loaded = snapshots.load(candidate);
            if (loaded.isPresent() && loaded.get().lastCommandSequence() <= lastEventSequence) {
                snapshot = loaded.get();
                break;
            }
        }
        long snapshotSequence = snapshot == null ? 0 : snapshot.lastCommandSequence();
        MatchingEngine engine = snapshot == null
                ? new MatchingEngine(instrument)
                : MatchingEngine.restore(snapshot.state());

        // 3. The commands after the snapshot, regenerating the event batches the event journal lacks.
        List<EventBatch> missingBatches = new ArrayList<>();
        long replayed = 0;
        try (JournalReader<JournalEntry> reader = JournalReader.commands(commandsFile)) {
            checkInstrument(reader.instrument(), instrument, commandsFile);
            for (JournalEntry entry = reader.next(); entry != null; entry = reader.next()) {
                if (entry.sequence() <= snapshotSequence) {
                    continue;
                }
                List<Event> produced = engine.process(entry.command());
                replayed++;
                if (entry.sequence() > lastEventSequence) {
                    missingBatches.add(new EventBatch(entry.sequence(), entry.timestampMicros(), produced));
                }
            }
            if (lastEventSequence > reader.lastSequence()) {
                throw new JournalCorruptedException("event journal is ahead of the command journal: "
                        + lastEventSequence + " > " + reader.lastSequence());
            }
            tornBytes += reader.tornBytes();

            // 4. Resume appending after the valid data, and complete the event journal.
            FrameWriter commandWriter = openWriter(commandsFile, reader.validLength(), Codec.Kind.COMMANDS, instrument);
            FrameWriter eventWriter;
            try {
                eventWriter = openWriter(eventsFile, eventsValidLength, Codec.Kind.EVENTS, instrument);
                for (EventBatch batch : missingBatches) {
                    eventWriter.append(Codec.encodeBatch(batch));
                }
                eventWriter.force();
            } catch (IOException e) {
                commandWriter.close();
                throw e;
            }
            RecoveryReport report = new RecoveryReport(
                    snapshotSequence, replayed, reader.lastSequence(), missingBatches.size(), tornBytes);
            return new JournaledEngine(engine, options, timeSource, commandWriter, eventWriter, snapshots,
                    report, reader.lastTimestampMicros());
        }
    }

    /**
     * Journals a command, processes it, and records its events (RS-001, RS-006, RS-007).
     *
     * @throws UncheckedIOException  if the journal cannot be written; the instance then stops
     * @throws IllegalStateException if the instance has stopped or is closed
     */
    public EventBatch process(Command command) {
        Objects.requireNonNull(command, "command");
        requireUsable();
        long sequence = lastCommandSequence + 1;
        long timestamp = Math.max(timeSource.nowMicros(), lastTimestampMicros);
        try {
            commands.append(Codec.encodeEntry(new JournalEntry(sequence, timestamp, command)));
            if (options.fsync() == FsyncPolicy.EVERY_COMMAND) {
                commands.force();
            }
        } catch (IOException e) {
            throw stop("command journal write failed", e);
        }
        lastCommandSequence = sequence;
        lastTimestampMicros = timestamp;

        EventBatch batch = new EventBatch(sequence, timestamp, engine.process(command));
        try {
            events.append(Codec.encodeBatch(batch));
        } catch (IOException e) {
            throw stop("event journal write failed", e);
        }
        if (options.snapshotInterval() > 0 && ++commandsSinceSnapshot >= options.snapshotInterval()) {
            snapshot();
        }
        return batch;
    }

    /** Writes a snapshot of the current state now (RS-005). */
    public void snapshot() {
        requireUsable();
        try {
            snapshots.write(lastCommandSequence, engine.snapshot());
        } catch (IOException e) {
            throw stop("snapshot write failed", e);
        }
        commandsSinceSnapshot = 0;
    }

    /** Complete current state of the engine, as a snapshot would record it. */
    EngineSnapshot state() {
        return engine.snapshot();
    }

    /** Read-only view of the resting orders. */
    public OrderBook book() {
        return engine.book();
    }

    public Instrument instrument() {
        return engine.instrument();
    }

    /** What recovery did when this instance was opened. */
    public RecoveryReport recovery() {
        return recovery;
    }

    public long lastCommandSequence() {
        return lastCommandSequence;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try (commands; events) {
            if (!failed) {
                commands.force();
                events.force();
            }
        }
    }

    private void requireUsable() {
        if (closed) {
            throw new IllegalStateException("journaled engine is closed");
        }
        if (failed) {
            throw new IllegalStateException("journaled engine stopped after an I/O failure; reopen to recover");
        }
    }

    private UncheckedIOException stop(String message, IOException cause) {
        failed = true;
        return new UncheckedIOException(message, cause);
    }

    private static void checkInstrument(Instrument found, Instrument expected, Path file)
            throws JournalCorruptedException {
        if (found != null && !found.equals(expected)) {
            throw new JournalCorruptedException(file + " belongs to " + found + ", not " + expected);
        }
    }

    /** Opens a journal for appending; writes its header first if it has none (empty or torn). */
    private static FrameWriter openWriter(Path file, long validLength, Codec.Kind kind, Instrument instrument)
            throws IOException {
        FrameWriter writer = new FrameWriter(file, validLength);
        if (validLength == 0) {
            writer.append(Codec.encodeHeader(new Codec.Header(kind, instrument)));
            writer.force();
        }
        return writer;
    }
}
