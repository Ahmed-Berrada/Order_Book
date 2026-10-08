package io.github.ahmedberrada.lob.journal;

import io.github.ahmedberrada.lob.core.Instrument;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a command or event journal record by record, checking what the frames cannot: the file kind,
 * consecutive sequence numbers from 1, and timestamps that never decrease (RS-004, RS-006).
 */
final class JournalReader<T> implements Closeable {

    @FunctionalInterface
    private interface Decoder<T> {
        T decode(byte[] payload) throws JournalCorruptedException;
    }

    @FunctionalInterface
    private interface Field<T> {
        long of(T record);
    }

    private final FrameReader frames;
    private final Instrument instrument;
    private final Decoder<T> decoder;
    private final Field<T> sequenceOf;
    private final Field<T> timestampOf;
    private long lastSequence;
    private long lastTimestampMicros;

    static JournalReader<JournalEntry> commands(Path file) throws IOException {
        return new JournalReader<>(file, Codec.Kind.COMMANDS, Codec::decodeEntry,
                JournalEntry::sequence, JournalEntry::timestampMicros);
    }

    static JournalReader<EventBatch> events(Path file) throws IOException {
        return new JournalReader<>(file, Codec.Kind.EVENTS, Codec::decodeBatch,
                EventBatch::commandSequence, EventBatch::timestampMicros);
    }

    private JournalReader(Path file, Codec.Kind kind, Decoder<T> decoder, Field<T> sequenceOf, Field<T> timestampOf)
            throws IOException {
        this.decoder = decoder;
        this.sequenceOf = sequenceOf;
        this.timestampOf = timestampOf;
        if (!Files.exists(file)) {
            this.frames = null;
            this.instrument = null;
            return;
        }
        this.frames = new FrameReader(file);
        byte[] header = frames.next();
        if (header == null) {
            this.instrument = null;
            return;
        }
        Codec.Header decoded = Codec.decodeHeader(header);
        if (decoded.kind() != kind) {
            throw new JournalCorruptedException(file + " is a " + decoded.kind() + " file, expected " + kind);
        }
        this.instrument = decoded.instrument();
    }

    /** Instrument named in the header, or {@code null} if the journal is missing or has no complete header. */
    Instrument instrument() {
        return instrument;
    }

    /** Next record, or {@code null} at the end of valid data. */
    T next() throws IOException {
        if (instrument == null) {
            return null;
        }
        byte[] payload = frames.next();
        if (payload == null) {
            return null;
        }
        T record = decoder.decode(payload);
        long sequence = sequenceOf.of(record);
        if (sequence != lastSequence + 1) {
            throw new JournalCorruptedException("sequence " + sequence + " follows " + lastSequence);
        }
        long timestamp = timestampOf.of(record);
        if (timestamp < lastTimestampMicros) {
            throw new JournalCorruptedException("timestamp decreases at sequence " + sequence);
        }
        lastSequence = sequence;
        lastTimestampMicros = timestamp;
        return record;
    }

    long lastSequence() {
        return lastSequence;
    }

    long lastTimestampMicros() {
        return lastTimestampMicros;
    }

    /** End of the valid data: where appending resumes. 0 if the file is missing or has no header. */
    long validLength() {
        return instrument == null ? 0 : frames.validLength();
    }

    /** Bytes after the valid data, discarded when appending resumes (RS-003). */
    long tornBytes() throws IOException {
        if (frames == null) {
            return 0;
        }
        return instrument == null ? frames.validLength() + frames.tornBytes() : frames.tornBytes();
    }

    @Override
    public void close() throws IOException {
        if (frames != null) {
            frames.close();
        }
    }
}
