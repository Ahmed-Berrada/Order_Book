package io.github.ahmedberrada.lob.journal;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32C;

/**
 * Reads the frames of a journal file, in order: {@code [int length][int crc32c][payload]}.
 *
 * <p>Tells a torn tail (RS-003) from corruption (RS-004). The end of valid data is reached, and
 * {@link #next()} returns {@code null}, when the remaining bytes are too short for a frame, when a
 * frame announces more bytes than remain, when the rest of the file is zeros, or when the
 * <em>last</em> frame fails its checksum. A bad frame followed by more data is corruption.
 */
final class FrameReader implements Closeable {

    static final int HEADER_BYTES = 8;
    static final int MAX_PAYLOAD_BYTES = 1 << 20;

    private final DataInputStream in;
    private final long size;
    private long position;
    private boolean ended;

    FrameReader(Path file) throws IOException {
        this.size = Files.size(file);
        this.in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16));
    }

    /** Next payload, or {@code null} at the end of valid data. */
    byte[] next() throws IOException {
        if (ended) {
            return null;
        }
        long remaining = size - position;
        if (remaining < HEADER_BYTES) {
            return end();
        }
        int length = in.readInt();
        int checksum = in.readInt();
        if (length > remaining - HEADER_BYTES) {
            return end();
        }
        if (length <= 0 || length > MAX_PAYLOAD_BYTES) {
            if (length == 0 && checksum == 0 && restIsZero(remaining - HEADER_BYTES)) {
                return end();
            }
            throw new JournalCorruptedException("invalid frame length " + length + " at offset " + position);
        }
        byte[] payload = in.readNBytes(length);
        if (checksum(payload) != checksum) {
            if (position + HEADER_BYTES + length == size) {
                return end();
            }
            throw new JournalCorruptedException("checksum mismatch at offset " + position);
        }
        position += HEADER_BYTES + length;
        return payload;
    }

    /** End of the valid data read so far: after the last good frame. */
    long validLength() {
        return position;
    }

    /** Bytes after the valid data: a torn tail to discard. Meaningful once {@link #next()} returned null. */
    long tornBytes() {
        return size - position;
    }

    static int checksum(byte[] payload) {
        CRC32C crc = new CRC32C();
        crc.update(payload);
        return (int) crc.getValue();
    }

    private byte[] end() {
        ended = true;
        return null;
    }

    private boolean restIsZero(long count) throws IOException {
        for (long i = 0; i < count; i++) {
            if (in.read() != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
