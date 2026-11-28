package io.github.ahmedberrada.lob.journal;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The primary's side of one instrument's replication (RS-014, RS-015). Used by the instrument's thread only.
 *
 * <p>It keeps no copy of what it sends: at each {@link #replicate}, it reads back from its own files the bytes the
 * standby does not hold yet, sends them, and waits until the standby has them on its device. Catching up after a
 * connection is the same thing, done at {@link #connect} with the longer catch-up deadline, before any command.
 * Every exchange has a deadline: a watchdog closes the connection when it passes, so neither a standby that stops
 * reading nor one that stops answering can hold the instrument's thread.
 */
final class ReplicationClient implements AutoCloseable {

    /** How long connecting, comparing the files and catching up may take: whole journals are hashed and sent. */
    static final Duration CATCH_UP = Duration.ofMinutes(10);

    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "lob-replication-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    static {
        WATCHDOG.setRemoveOnCancelPolicy(true);         // one cancelled deadline per command must not pile up
    }

    private final Path directory;
    private final String symbol;
    private final ReplicationTarget target;
    private final long term;
    private volatile ReplicationLink link;
    private IOException failure;                        // once replication has failed, it is never retried
    private final long[] standbyLengths = new long[Replication.FILES.length];
    private final long[] limits = {Long.MAX_VALUE, Long.MAX_VALUE};

    ReplicationClient(Path directory, String symbol, ReplicationTarget target) throws IOException {
        this.directory = directory;
        this.symbol = symbol;
        this.target = target;
        this.term = Replication.primaryTerm(directory);
    }

    /** Connects, checks the standby holds a prefix of this primary's files, and brings it up to date. */
    void connect(long lastSequence) throws IOException {
        if (link == null) {
            within(CATCH_UP, () -> {
                open();
                push(lastSequence);
            });
        }
    }

    /**
     * Brings the standby up to the primary's files and returns once it has them on its device.
     *
     * @throws IOException if the standby cannot be reached, refuses (another term, another history), or does not
     *                     confirm within the timeout; the connection is dropped and the next call reconnects
     */
    void replicate(long lastSequence) throws IOException {
        if (failure != null || link == null) {
            throw new IOException("replication failed earlier; the instance has stopped", failure);
        }
        try {
            within(target.timeout(), () -> push(lastSequence));
        } catch (IOException e) {
            failure = e;
            throw e;
        }
    }

    /** The size of a replicated file, as it is now on this primary. */
    long size(int file) throws IOException {
        return Replication.size(directory.resolve(Replication.FILES[file]));
    }

    /** Sends no more than this of a file: after a failed write, its torn tail stays on this primary only. */
    void limit(int file, long length) {
        limits[file] = Math.min(limits[file], length);
    }

    @FunctionalInterface
    private interface Exchange {
        void run() throws IOException;
    }

    /** Runs an exchange, closing the connection if it outlasts the deadline (a blocked write has no timeout). */
    private void within(Duration deadline, Exchange exchange) throws IOException {
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            expired.set(true);
            close();
        }, deadline.toMillis(), TimeUnit.MILLISECONDS);
        try {
            exchange.run();
        } catch (IOException | RuntimeException e) {
            watchdog.cancel(false);
            close();
            if (expired.get()) {
                throw new IOException("the standby did not confirm within " + deadline, e);
            }
            throw e instanceof IOException io ? io : new IOException(e);
        }
        if (!watchdog.cancel(false) || expired.get()) {     // it ran, or is running: the connection is gone
            close();
            throw new IOException("the standby did not confirm within " + deadline);
        }
    }

    private void push(long lastSequence) throws IOException {
        long[] local = new long[Replication.FILES.length];
        boolean behind = false;
        for (int f = 0; f < Replication.FILES.length; f++) {
            Path file = directory.resolve(Replication.FILES[f]);
            local[f] = Math.min(Replication.size(file), limits[f]);
            if (local[f] > standbyLengths[f]) {
                send(f, file, standbyLengths[f], local[f]);
                behind = true;
            }
        }
        if (!behind) {
            return;
        }
        current().send(Replication.SYNC, ReplicationLink.body(out -> out.writeLong(lastSequence)));
        current().flush();
        ReplicationLink.Message ack = current().receive();
        if (ack.type() != Replication.ACK) {
            throw new IOException("replication protocol: expected an acknowledgement, got " + ack.type());
        }
        DataInputStream in = ack.reader();
        for (int f = 0; f < Replication.FILES.length; f++) {
            long confirmed = in.readLong();
            if (confirmed != local[f]) {
                throw new IOException("the standby confirmed " + confirmed + " bytes of " + Replication.FILES[f]
                        + ", " + local[f] + " were sent");
            }
            standbyLengths[f] = confirmed;
        }
    }

    private void open() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(target.host(), target.port()),
                    (int) Math.min(Integer.MAX_VALUE, target.timeout().toMillis()));
            ReplicationLink opened = ReplicationLink.asPrimary(socket, target.secret(), symbol, term);
            link = opened;
            ReplicationLink.Message welcome = opened.receive();
            if (welcome.type() != Replication.WELCOME) {
                throw new IOException("replication protocol: expected a welcome, got " + welcome.type());
            }
            DataInputStream in = welcome.reader();
            in.readLong();                          // the term it took up: this primary's (a lower one is refused)
            for (int f = 0; f < Replication.FILES.length; f++) {
                long length = in.readLong();
                byte[] hash = in.readNBytes(32);
                Path file = directory.resolve(Replication.FILES[f]);
                long local = Replication.size(file);
                if (length > local) {
                    throw new IOException("the standby holds " + length + " bytes of " + Replication.FILES[f]
                            + ", this primary " + local + ": the standby is ahead (promote it, RS-016)");
                }
                if (!MessageDigest.isEqual(hash, Replication.sha256(file, length))) {
                    throw new IOException("the standby's " + Replication.FILES[f] + " differs from this primary's in"
                            + " its first " + length + " bytes: another history; resynchronise it from empty");
                }
                standbyLengths[f] = length;
            }
        } catch (IOException | RuntimeException e) {
            socket.close();
            link = null;
            throw e;
        }
    }

    private void send(int fileId, Path file, long from, long to) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long offset = from;
            while (offset < to) {
                int chunk = (int) Math.min(Replication.MAX_DATA_BYTES, to - offset);
                ByteBuffer buffer = ByteBuffer.allocate(chunk);
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer, offset + buffer.position()) < 0) {
                        throw new IOException(file + " ended before " + to);
                    }
                }
                long at = offset;
                current().send(Replication.DATA, ReplicationLink.body(out -> {
                    out.writeByte(fileId);
                    out.writeLong(at);
                    out.write(buffer.array());
                }));
                offset += chunk;
            }
        }
    }

    private ReplicationLink current() throws IOException {
        ReplicationLink current = link;
        if (current == null) {
            throw new IOException("the connection to the standby was closed");
        }
        return current;
    }

    @Override
    public synchronized void close() {
        if (link != null) {
            try {
                link.close();
            } catch (IOException e) {
                // closing a broken connection
            }
        }
        link = null;
    }
}
