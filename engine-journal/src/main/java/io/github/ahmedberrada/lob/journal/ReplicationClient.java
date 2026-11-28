package io.github.ahmedberrada.lob.journal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

/**
 * The primary's side of one instrument's replication (RS-014, RS-015). Used by the instrument's thread only.
 *
 * <p>It keeps no copy of what it sends: at each {@link #replicate}, it reads back from its own files the bytes the
 * standby does not hold yet, sends them, and waits until the standby has them on its device. Catching up after a
 * reconnection and replicating live are the same thing.
 */
final class ReplicationClient implements AutoCloseable {

    private final Path directory;
    private final String symbol;
    private final ReplicationTarget target;
    private final long term;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private final long[] standbyLengths = new long[Replication.FILES.length];

    ReplicationClient(Path directory, String symbol, ReplicationTarget target) throws IOException {
        this.directory = directory;
        this.symbol = symbol;
        this.target = target;
        this.term = Replication.term(directory);
    }

    /**
     * Brings the standby up to the primary's files and returns once it has them on its device.
     *
     * @throws IOException if the standby cannot be reached, refuses (another term, another history), or does not
     *                     confirm within the timeout; the connection is dropped and the next call reconnects
     */
    void replicate(long lastSequence) throws IOException {
        try {
            if (socket == null) {
                connect();
            }
            boolean sent = false;
            for (int f = 0; f < Replication.FILES.length; f++) {
                Path file = directory.resolve(Replication.FILES[f]);
                long local = Replication.size(file);
                if (local > standbyLengths[f]) {
                    send(f, file, standbyLengths[f], local);
                    sent = true;
                }
            }
            if (!sent) {
                return;
            }
            out.writeByte(Replication.SYNC);
            out.writeLong(lastSequence);
            out.flush();
            Replication.expect(in, Replication.ACK);
            for (int f = 0; f < Replication.FILES.length; f++) {
                standbyLengths[f] = in.readLong();
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private void connect() throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(target.host(), target.port()), (int) target.timeout().toMillis());
            s.setSoTimeout((int) Math.max(1, target.timeout().toMillis()));
            s.setTcpNoDelay(true);
            DataInputStream input = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16));
            output.writeByte(Replication.HELLO);
            output.writeUTF(symbol);
            output.writeLong(term);
            output.flush();
            Replication.expect(input, Replication.CHALLENGE);
            byte[] challenge = input.readNBytes(32);
            output.writeByte(Replication.AUTH);
            output.write(Replication.mac(target.secret(), challenge, symbol, term));
            output.flush();
            Replication.expect(input, Replication.WELCOME);
            long standbyTerm = input.readLong();
            if (standbyTerm > term) {
                throw new IOException("the standby is at term " + standbyTerm + ", this primary at " + term
                        + ": it was promoted, and this primary is fenced (RS-016)");
            }
            for (int f = 0; f < Replication.FILES.length; f++) {
                long length = input.readLong();
                byte[] hash = input.readNBytes(32);
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
            socket = s;
            in = input;
            out = output;
        } catch (IOException e) {
            s.close();
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
                out.writeByte(Replication.DATA);
                out.writeByte(fileId);
                out.writeLong(offset);
                out.writeInt(chunk);
                out.write(buffer.array());
                offset += chunk;
            }
        }
    }

    @Override
    public void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // closing a broken connection
            }
        }
        socket = null;
        in = null;
        out = null;
    }

    /** Whether a file exists (for tests). */
    static boolean exists(Path file) {
        return Files.exists(file);
    }
}
