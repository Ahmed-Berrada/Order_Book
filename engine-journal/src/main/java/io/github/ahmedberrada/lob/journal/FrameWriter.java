package io.github.ahmedberrada.lob.journal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Appends frames to a journal file, after discarding anything beyond the valid data. */
final class FrameWriter implements Closeable {

    private final FileChannel channel;

    /**
     * Opens a journal for appending at {@code validLength}. Bytes beyond it are a torn tail and are
     * cut off (RS-003); nothing before it is ever rewritten (RS-008).
     */
    FrameWriter(Path file, long validLength) throws IOException {
        this.channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        if (channel.size() > validLength) {
            channel.truncate(validLength);
            channel.force(true);
        }
        channel.position(validLength);
    }

    void append(byte[] payload) throws IOException {
        ByteBuffer frame = ByteBuffer.allocate(FrameReader.HEADER_BYTES + payload.length);
        frame.putInt(payload.length).putInt(FrameReader.checksum(payload)).put(payload).flip();
        while (frame.hasRemaining()) {
            channel.write(frame);
        }
    }

    /** Flushes appended data to the storage device. */
    void force() throws IOException {
        channel.force(false);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
