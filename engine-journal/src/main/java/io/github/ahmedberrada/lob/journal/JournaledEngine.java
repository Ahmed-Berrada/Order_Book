package io.github.ahmedberrada.lob.journal;

import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.EngineSnapshot;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import io.github.ahmedberrada.lob.core.OrderBook;
import io.github.ahmedberrada.lob.core.TradingPhase;
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
    private IOException failure;
    private boolean closed;
    private boolean unsynced;
    private volatile long flushes;          // written by the engine's thread only; read by metrics
    private final List<byte[]> unflushedBatches = new ArrayList<>();   // GROUP: event batches awaiting the flush
    private final DirectoryLock lock;
    private final JournalSealer sealer;                 // null without sealing (RS-013)

    private JournaledEngine(MatchingEngine engine, JournalOptions options, TimeSource timeSource,
            FrameWriter commands, FrameWriter events, SnapshotStore snapshots, RecoveryReport recovery,
            long lastTimestampMicros, DirectoryLock lock, JournalSealer sealer) {
        this.lock = lock;
        this.sealer = sealer;
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
        DirectoryLock lock = DirectoryLock.acquire(directory);                 // RS-011
        try {
            return recover(directory, instrument, options, timeSource, lock);
        } catch (IOException | RuntimeException e) {
            try {
                lock.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private static JournaledEngine recover(Path directory, Instrument instrument, JournalOptions options,
            TimeSource timeSource, DirectoryLock lock) throws IOException {
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
                List<Event> produced = engine.process(entry.command(), entry.memberId());
                replayed++;
                if (entry.sequence() > lastEventSequence) {
                    missingBatches.add(new EventBatch(entry.sequence(), entry.timestampMicros(), produced,
                            entry.memberId()));
                }
            }
            if (lastEventSequence > reader.lastSequence()) {
                throw new JournalCorruptedException("event journal is ahead of the command journal: "
                        + lastEventSequence + " > " + reader.lastSequence());
            }
            tornBytes += reader.tornBytes();

            // 4. Resume appending after the valid data, and complete the event journal.
            // RS-013: every seal is checked against the journal as recovered, before anything is appended to it.
            JournalSealer sealer = options.sealing() == null ? null : JournalSealer.open(commandsFile,
                    reader.validLength(), directory.resolve(JournalSealer.FILE), options.sealing(), instrument.symbol());
            FrameWriter commandWriter;
            try {
                commandWriter = openWriter(commandsFile, reader.validLength(), Codec.Kind.COMMANDS, instrument, sealer);
            } catch (IOException e) {
                if (sealer != null) {
                    sealer.close();
                }
                throw e;
            }
            FrameWriter eventWriter;
            try {
                eventWriter = openWriter(eventsFile, eventsValidLength, Codec.Kind.EVENTS, instrument);
                for (EventBatch batch : missingBatches) {
                    eventWriter.append(Codec.encodeBatch(batch));
                }
                eventWriter.force();
            } catch (IOException e) {
                commandWriter.close();
                if (sealer != null) {
                    sealer.close();
                }
                throw e;
            }
            RecoveryReport report = new RecoveryReport(
                    snapshotSequence, replayed, reader.lastSequence(), missingBatches.size(), tornBytes);
            return new JournaledEngine(engine, options, timeSource, commandWriter, eventWriter, snapshots,
                    report, reader.lastTimestampMicros(), lock, sealer);
        }
    }

    /**
     * Journals a command, processes it, and records its events (RS-001, RS-006, RS-007).
     *
     * <p>Once the command record is written, the command is part of the instrument's history and its
     * events are returned, even if writing the events or a snapshot then fails. In that case the
     * instance stops ({@link #isStopped()}) and recovery completes the event journal.
     *
     * @throws UncheckedIOException  if the command journal cannot be written. The command may or may
     *                               not have been recorded; the instance stops
     * @throws IllegalStateException if the instance has stopped or is closed
     */
    public EventBatch process(Command command) {
        return process(command, 0);
    }

    /**
     * Like {@link #process(Command)}, on behalf of a member, whom the journal records with the command
     * (MB-001).
     */
    public EventBatch process(Command command, long memberId) {
        Objects.requireNonNull(command, "command");
        if (memberId < 0) {
            throw new IllegalArgumentException("member ID must not be negative: " + memberId);
        }
        requireUsable();
        long sequence = lastCommandSequence + 1;
        long timestamp = Math.max(timeSource.nowMicros(), lastTimestampMicros);
        try {
            commands.append(Codec.encodeEntry(new JournalEntry(sequence, timestamp, command, memberId)));
            if (options.fsync() == FsyncPolicy.EVERY_COMMAND) {
                commands.force();
                flushes++;
            } else if (options.fsync() == FsyncPolicy.GROUP) {
                unsynced = true;                // flushed by sync(), before any acknowledgement (ADR-0020)
            }
        } catch (IOException e) {
            throw stop("command journal write failed", e);
        }
        lastCommandSequence = sequence;
        lastTimestampMicros = timestamp;
        if (options.fsync() != FsyncPolicy.GROUP && sealer != null && sealer.due(timestamp)) {
            seal();                             // GROUP seals after its flush, in sync()
        }

        EventBatch batch = new EventBatch(sequence, timestamp, engine.process(command, memberId), memberId);
        if (options.fsync() == FsyncPolicy.GROUP) {
            // Written after the flush: on the device the events never get ahead of their commands (ADR-0020 §3).
            unflushedBatches.add(Codec.encodeBatch(batch));
        } else {
            record(Codec.encodeBatch(batch));
        }
        return batch;
    }

    /** Appends one event batch, and a snapshot when one is due; a failure stops this instance (OE-006). */
    private void record(byte[] encodedBatch) {
        if (failed) {
            return;
        }
        try {
            events.append(encodedBatch);
            if (options.snapshotInterval() > 0 && ++commandsSinceSnapshot >= options.snapshotInterval()) {
                snapshots.write(lastCommandSequence, engine.snapshot());
                commandsSinceSnapshot = 0;
                seal();                             // RS-013: a seal at every snapshot
            }
        } catch (IOException e) {
            // The command is journaled and applied: acknowledge it, but accept nothing more.
            failed = true;
            failure = e;
        }
    }

    /**
     * Flushes the commands journaled since the last flush to the storage device, if there are any. With
     * {@link FsyncPolicy#GROUP}, the caller calls it once after processing the commands waiting together and
     * acknowledges none of them before it returns (RS-001, ADR-0020); with the other policies there is nothing to
     * flush. Under {@code GROUP} it then writes the commands' event batches (and a snapshot when one is due), so the
     * event journal is never ahead of the command journal on the device; a failure there stops this instance but
     * leaves the flushed commands acknowledged. It works even after a failed event write has stopped this
     * instance: those commands are journaled and owed their flush.
     *
     * @throws UncheckedIOException  if the flush fails; whether the unflushed commands are on the device is then
     *                               unknown, and the instance stops
     * @throws IllegalStateException if the instance is closed
     */
    public void sync() {
        if (closed) {
            throw new IllegalStateException("journaled engine is closed");
        }
        if (!unsynced) {
            return;
        }
        try {
            commands.force();
        } catch (IOException e) {
            unflushedBatches.clear();               // their commands' outcome is unknown: record nothing of them
            throw stop("command journal flush failed", e);
        }
        flushes++;
        unsynced = false;
        for (byte[] batch : unflushedBatches) {
            record(batch);                          // a failure here stops the instance; the commands stay flushed
        }
        unflushedBatches.clear();
        if (sealer != null && sealer.due(lastTimestampMicros)) {
            seal();
        }
    }

    /**
     * Seals the command journal as written so far (RS-013), after flushing it if the policy has not: a seal only
     * covers bytes on the device. A failure leaves the commands journaled and acknowledged and stops this instance,
     * as a failed event write does: the journal would go on unsealed.
     */
    private void seal() {
        if (sealer == null || failed || !sealer.unsealed()) {
            return;
        }
        try {
            if (options.fsync() == FsyncPolicy.OS || unsynced) {
                commands.force();
            }
            sealer.seal(lastCommandSequence, lastTimestampMicros);
        } catch (IOException e) {
            failed = true;
            failure = e;
        }
    }

    /** Whether commands must be flushed with {@link #sync()} before they are acknowledged. */
    public boolean groupCommit() {
        return options.fsync() == FsyncPolicy.GROUP;
    }

    /** Device flushes of the command journal so far, for tests and metrics; safe to read from any thread. */
    public long flushes() {
        return flushes;
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
        seal();
    }

    /**
     * Complete current state of the engine, as a snapshot would record it, as of
     * {@link #lastCommandSequence()}. Like every method here, call it on the engine's own thread.
     */
    public EngineSnapshot state() {
        return engine.snapshot();
    }

    /** The I/O failure that stopped this instance, or {@code null}: what the operator must fix (OE-006). */
    public IOException failure() {
        return failure;
    }

    /** Whether an I/O failure has stopped this instance; it then refuses every command. */
    public boolean isStopped() {
        return failed;
    }

    /** Read-only view of the resting orders. */
    public OrderBook book() {
        return engine.book();
    }

    /** The instrument's current trading phase (TP-001). */
    public TradingPhase phase() {
        return engine.phase();
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
        // closed in reverse: the seal file, the journals, and the directory lock last (RS-011)
        try (lock; commands; events; sealer) {
            if (!failed) {
                commands.force();
                events.force();
                if (sealer != null && sealer.unsealed()) {
                    sealer.seal(lastCommandSequence, lastTimestampMicros);      // RS-013: sealed at close
                }
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
        failure = cause;
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
        return openWriter(file, validLength, kind, instrument, null);
    }

    private static FrameWriter openWriter(Path file, long validLength, Codec.Kind kind, Instrument instrument,
            FrameWriter.Listener listener) throws IOException {
        FrameWriter writer = new FrameWriter(file, validLength);
        writer.listener(listener);
        if (validLength == 0) {
            writer.append(Codec.encodeHeader(new Codec.Header(kind, instrument)));
            writer.force();
        }
        return writer;
    }
}
