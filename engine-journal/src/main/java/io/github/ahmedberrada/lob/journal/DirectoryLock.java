package io.github.ahmedberrada.lob.journal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * An exclusive lock on an instrument's journal directory, held for as long as its engine is open
 * (RS-011): two writers on one journal would interleave frames and corrupt it. The operating system
 * releases the lock if the process dies, so a crash never leaves a stale lock behind.
 */
final class DirectoryLock implements Closeable {

    static final String FILE = "journal.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private DirectoryLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** @throws IOException if another process, or another engine of this one, holds the directory */
    static DirectoryLock acquire(Path directory) throws IOException {
        FileChannel channel = FileChannel.open(directory.resolve(FILE), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return new DirectoryLock(channel, lock);
            }
        } catch (OverlappingFileLockException e) {
            // held by this very JVM: the same refusal
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        channel.close();
        throw new IOException("the journal in " + directory + " is in use by another engine; only one writer may open it");
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
