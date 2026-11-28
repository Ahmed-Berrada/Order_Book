package io.github.ahmedberrada.lob.journal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The standby (RS-014): keeps, for each instrument a primary replicates, a byte-for-byte copy of its command journal
 * and seal file under {@code root/<symbol>}, appends only what follows what it holds, and confirms once it is on the
 * device. It refuses a primary whose term is lower than its own (RS-016) and any client that cannot prove the shared
 * secret. It never interprets the commands: promoted, the directory is opened as an ordinary venue's, and recovery
 * rebuilds the rest (RS-002, RS-007).
 *
 * <p>Usage: {@code lob-replica <root> <bind-address> <port> <secret-file>}.
 */
public final class ReplicaServer implements Closeable {

    private final Path root;
    private final byte[] secret;
    private final ServerSocket server;
    private final Thread acceptor;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Map<String, DirectoryLock> locks = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Duration idle;

    private ReplicaServer(Path root, byte[] secret, ServerSocket server, Duration idle) {
        this.root = root;
        this.secret = secret.clone();
        this.server = server;
        this.idle = idle;
        this.acceptor = Thread.ofPlatform().name("lob-replica-accept").daemon(true).start(this::accept);
    }

    /** Starts listening; {@code port} 0 picks a free one ({@link #port()}). */
    public static ReplicaServer start(Path root, String bindAddress, int port, byte[] secret) throws IOException {
        return start(root, bindAddress, port, secret, Duration.ofMinutes(5));
    }

    static ReplicaServer start(Path root, String bindAddress, int port, byte[] secret, Duration idle) throws IOException {
        if (secret.length < 32) {
            throw new IllegalArgumentException("the replication secret must be at least 32 bytes");
        }
        Files.createDirectories(root);
        ServerSocket server = new ServerSocket(port, 50, InetAddress.getByName(bindAddress));
        return new ReplicaServer(root, secret, server, idle);
    }

    public int port() {
        return server.getLocalPort();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                connections.add(socket);
                Thread.ofPlatform().name("lob-replica-" + socket.getPort()).daemon(true).start(() -> serve(socket));
            } catch (IOException e) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private void serve(Socket socket) {
        String symbol = null;
        try (socket) {
            socket.setSoTimeout((int) idle.toMillis());
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1 << 16));
            Replication.expect(in, Replication.HELLO);
            String claimed = in.readUTF();
            long term = in.readLong();
            byte[] challenge = new byte[32];
            random.nextBytes(challenge);
            out.writeByte(Replication.CHALLENGE);
            out.write(challenge);
            out.flush();
            Replication.expect(in, Replication.AUTH);
            byte[] mac = in.readNBytes(32);
            if (!MessageDigest.isEqual(mac, Replication.mac(secret, challenge, claimed, term))) {
                Replication.refuse(out, "authentication failed");
                return;
            }
            if (!claimed.matches("[A-Z0-9._-]{1,32}")) {
                Replication.refuse(out, "not a symbol: " + claimed);
                return;
            }
            symbol = claimed;
            Path directory = root.resolve(symbol);
            Files.createDirectories(directory);
            DirectoryLock lock;
            try {
                lock = DirectoryLock.acquire(directory);
            } catch (IOException e) {
                Replication.refuse(out, symbol + " is being replicated by another primary, or opened");
                return;
            }
            locks.put(symbol, lock);
            try {
                long standbyTerm = Replication.term(directory);
                if (term < standbyTerm) {
                    Replication.refuse(out, "fenced: the standby is at term " + standbyTerm + ", the primary at "
                            + term + " (RS-016)");
                    return;
                }
                if (term > standbyTerm) {
                    Replication.writeTerm(directory, term);
                }
                out.writeByte(Replication.WELCOME);
                out.writeLong(term);
                ReplicaFiles files = new ReplicaFiles(directory);
                for (int f = 0; f < Replication.FILES.length; f++) {
                    out.writeLong(files.length(f));
                    out.write(Replication.sha256(directory.resolve(Replication.FILES[f]), files.length(f)));
                }
                out.flush();
                try (files) {
                    receive(in, out, files);
                }
            } finally {
                locks.remove(symbol);
                lock.close();
            }
        } catch (SocketException e) {
            // the primary went away, or the standby is closing
        } catch (IOException e) {
            // a broken or refused connection: the primary sees it and halts or reconnects
        } finally {
            connections.remove(socket);
        }
    }

    private static void receive(DataInputStream in, DataOutputStream out, ReplicaFiles files) throws IOException {
        while (true) {
            byte type = in.readByte();
            if (type == Replication.DATA) {
                int file = in.readUnsignedByte();
                long offset = in.readLong();
                int length = in.readInt();
                if (file >= Replication.FILES.length || length < 0 || length > Replication.MAX_DATA_BYTES) {
                    Replication.refuse(out, "bad data message");
                    return;
                }
                byte[] bytes = in.readNBytes(length);
                if (bytes.length != length) {
                    return;                                    // cut mid-message: what arrived is not appended
                }
                if (offset != files.length(file)) {
                    Replication.refuse(out, "data at " + offset + " for " + Replication.FILES[file] + ", held "
                            + files.length(file));
                    return;
                }
                files.append(file, ByteBuffer.wrap(bytes));
            } else if (type == Replication.SYNC) {
                in.readLong();
                files.force();
                out.writeByte(Replication.ACK);
                for (int f = 0; f < Replication.FILES.length; f++) {
                    out.writeLong(files.length(f));
                }
                out.flush();
            } else {
                Replication.refuse(out, "unexpected message " + type);
                return;
            }
        }
    }

    /** Stops listening and drops every connection; what was confirmed stays on the device. */
    @Override
    public void close() throws IOException {
        server.close();
        for (Socket socket : connections) {
            socket.close();
        }
        try {
            acceptor.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
        if (args.length != 4 || !args[2].matches("\\d{1,5}")) {
            err.println("usage: lob-replica <root> <bind-address> <port> <secret-file>");
            return ReplayTool.USAGE;
        }
        byte[] secret = Files.readAllBytes(Path.of(args[3]));
        try (ReplicaServer server = start(Path.of(args[0]), args[1], Integer.parseInt(args[2]), secret)) {
            out.println("standby for " + Path.of(args[0]).toAbsolutePath() + " on " + args[1] + ":" + server.port());
            server.acceptor.join();
        }
        return ReplayTool.OK;
    }

    /** The files of one instrument on the standby, appended to only. */
    private static final class ReplicaFiles implements Closeable {

        private final java.nio.channels.FileChannel[] channels = new java.nio.channels.FileChannel[Replication.FILES.length];
        private final long[] lengths = new long[Replication.FILES.length];

        ReplicaFiles(Path directory) throws IOException {
            for (int f = 0; f < Replication.FILES.length; f++) {
                channels[f] = java.nio.channels.FileChannel.open(directory.resolve(Replication.FILES[f]),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                lengths[f] = channels[f].size();
                channels[f].position(lengths[f]);
            }
        }

        long length(int file) {
            return lengths[file];
        }

        void append(int file, ByteBuffer bytes) throws IOException {
            while (bytes.hasRemaining()) {
                lengths[file] += channels[file].write(bytes);
            }
        }

        void force() throws IOException {
            for (java.nio.channels.FileChannel channel : channels) {
                channel.force(false);
            }
        }

        @Override
        public void close() throws IOException {
            for (java.nio.channels.FileChannel channel : channels) {
                channel.close();
            }
        }
    }
}
