package io.github.ahmedberrada.lob.journal;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The standby (RS-014): keeps, for each instrument a primary replicates, a byte-for-byte copy of its command journal
 * and seal file under {@code root/<symbol>}, appends only what follows what it holds, and confirms once it is on the
 * device. It never interprets the commands: promoted, the directory is opened as an ordinary venue's, and recovery
 * rebuilds the rest (RS-002, RS-007).
 *
 * <p>It proves the shared secret to the primary and requires the primary to prove it ({@link ReplicationLink}). It
 * refuses a primary whose term is lower than the standby's (RS-016): the higher of the instrument's term and the
 * root's, which every promotion and every primary seen raises, so a promoted standby fences the old primary for
 * every instrument, including those it never held. A new authenticated connection for an instrument, at the same
 * term or higher, replaces the one it holds: a primary that restarted is not locked out by its own dead connection.
 *
 * <p>Usage: {@code lob-replica <root> <bind-address> <port> <secret-file>}.
 */
public final class ReplicaServer implements Closeable {

    /** How long a connection may take to authenticate. */
    static final Duration HANDSHAKE = Duration.ofSeconds(10);

    /** Connections served at once; more are closed on accept. */
    static final int MAX_CONNECTIONS = 256;

    private static final String SYMBOL = "[A-Z0-9][A-Z0-9._-]{0,31}";

    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "lob-replica-deadline");
        thread.setDaemon(true);
        return thread;
    });

    static {
        DEADLINES.setRemoveOnCancelPolicy(true);
    }

    private final Path root;
    private final byte[] secret;
    private final ServerSocket server;
    private final DirectoryLock rootLock;
    private final Thread acceptor;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Map<String, Holder> holders = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(MAX_CONNECTIONS);
    private final Object terms = new Object();
    private boolean closed;

    /** The connection replicating an instrument, and its primary's term. */
    private record Holder(Socket socket, long term) {
    }

    private ReplicaServer(Path root, byte[] secret, ServerSocket server, DirectoryLock rootLock) {
        this.root = root;
        this.secret = secret.clone();
        this.server = server;
        this.rootLock = rootLock;
        this.acceptor = Thread.ofPlatform().name("lob-replica-accept").daemon(true).start(this::accept);
    }

    /** Starts listening; {@code port} 0 picks a free one ({@link #port()}). */
    public static ReplicaServer start(Path root, String bindAddress, int port, byte[] secret) throws IOException {
        if (secret.length < 32) {
            throw new IllegalArgumentException("the replication secret must be at least 32 bytes");
        }
        Files.createDirectories(root);
        DirectoryLock rootLock = DirectoryLock.acquire(root);
        try {
            ServerSocket server = new ServerSocket(port, 50, InetAddress.getByName(bindAddress));
            return new ReplicaServer(root, secret, server, rootLock);
        } catch (IOException | RuntimeException e) {
            rootLock.close();
            throw e;
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                if (!slots.tryAcquire()) {
                    socket.close();
                    continue;
                }
                connections.add(socket);
                Thread.ofPlatform().name("lob-replica-" + socket.getPort()).daemon(true).start(() -> {
                    try {
                        serve(socket);
                    } finally {
                        connections.remove(socket);
                        slots.release();
                    }
                });
            } catch (IOException e) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private void serve(Socket socket) {
        try {
            serve(socket, ReplicationLink.asStandby(socket));
        } catch (IOException | RuntimeException e) {
            // a broken, refused or replaced connection: the primary sees it and halts or reconnects
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // already broken
            }
        }
    }

    private void serve(Socket socket, ReplicationLink link) throws IOException {
        socket.setKeepAlive(true);
        // An absolute deadline, not a read timeout: a client dribbling bytes cannot hold a connection slot.
        ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
            try {
                socket.close();
            } catch (IOException e) {
                // already closed
            }
        }, HANDSHAKE.toMillis(), TimeUnit.MILLISECONDS);
        ReplicationLink.Hello hello;
        boolean authenticated;
        try {
            hello = link.readHello();
            authenticated = link.authenticate(secret, hello);
        } finally {
            deadline.cancel(false);
        }
        if (!authenticated) {
            link.refuse("authentication failed");
            return;
        }
        String symbol = hello.symbol();
        if (!symbol.matches(SYMBOL) || symbol.equalsIgnoreCase(Replication.TERM_FILE)
                || symbol.equalsIgnoreCase(DirectoryLock.FILE)) {
            link.refuse("not a symbol: " + symbol);
            return;
        }
        // No read timeout once authenticated: a quiet instrument sends nothing for hours. A dead primary's
        // connection is found by TCP keepalive, or replaced by the primary's next one.
        Path directory = root.resolve(symbol);
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory);
            Replication.forceDirectory(root);       // the instrument's directory survives a power loss
        }
        DirectoryLock lock = takeOver(symbol, socket, hello.term(), directory, link);
        if (lock == null) {
            return;
        }
        try {
            if (!raiseTerms(directory, hello.term(), link)) {
                return;
            }
            try (ReplicaFiles files = new ReplicaFiles(directory)) {
                link.send(Replication.WELCOME, ReplicationLink.body(out -> {
                    out.writeLong(hello.term());
                    for (int f = 0; f < Replication.FILES.length; f++) {
                        out.writeLong(files.length(f));
                        out.write(Replication.sha256(directory.resolve(Replication.FILES[f]), files.length(f)));
                    }
                }));
                link.flush();
                receive(link, files);
            }
        } finally {
            holders.remove(symbol, new Holder(socket, hello.term()));
            lock.close();
        }
    }

    /**
     * Makes this connection the one replicating the instrument: a holder at the same term or lower is closed, and its
     * lock waited for; one at a higher term is kept, and this connection refused.
     */
    private DirectoryLock takeOver(String symbol, Socket socket, long term, Path directory, ReplicationLink link)
            throws IOException {
        Holder previous;
        synchronized (holders) {
            previous = holders.get(symbol);
            if (previous != null && previous.term() > term) {
                link.refuse("fenced: " + symbol + " is replicated at term " + previous.term() + ", the primary is at "
                        + term + " (RS-016)");
                return null;
            }
            holders.put(symbol, new Holder(socket, term));
        }
        if (previous != null) {
            previous.socket().close();
        }
        for (int attempt = 0; ; attempt++) {
            try {
                return DirectoryLock.acquire(directory);
            } catch (IOException e) {
                if (attempt == 100 || holders.get(symbol) == null || holders.get(symbol).socket() != socket) {
                    holders.remove(symbol, new Holder(socket, term));
                    link.refuse(symbol + " is being replicated by another primary, or opened");
                    return null;
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
    }

    /** Refuses a term below the standby's (RS-016); raises the instrument's and the root's to a higher one. */
    private boolean raiseTerms(Path directory, long term, ReplicationLink link) throws IOException {
        synchronized (terms) {
            long standbyTerm = Math.max(Replication.term(root), Replication.term(directory));
            if (term < standbyTerm) {
                link.refuse("fenced: the standby is at term " + standbyTerm + ", the primary at " + term + " (RS-016)");
                return false;
            }
            if (term > Replication.term(directory)) {
                Replication.writeTerm(directory, term);
            }
            if (term > Replication.term(root)) {
                Replication.writeTerm(root, term);
            }
            return true;
        }
    }

    private static void receive(ReplicationLink link, ReplicaFiles files) throws IOException {
        while (true) {
            ReplicationLink.Message message = link.receive();
            DataInputStream in = message.reader();
            if (message.type() == Replication.DATA) {
                int file = in.readUnsignedByte();
                long offset = in.readLong();
                byte[] bytes = in.readAllBytes();
                if (file >= Replication.FILES.length || bytes.length > Replication.MAX_DATA_BYTES) {
                    link.refuse("bad data message");
                    return;
                }
                if (offset != files.length(file)) {
                    link.refuse("data at " + offset + " for " + Replication.FILES[file] + ", held "
                            + files.length(file));
                    return;
                }
                files.append(file, ByteBuffer.wrap(bytes));
            } else if (message.type() == Replication.SYNC) {
                files.force();
                link.send(Replication.ACK, ReplicationLink.body(out -> {
                    for (int f = 0; f < Replication.FILES.length; f++) {
                        out.writeLong(files.length(f));
                    }
                }));
                link.flush();
            } else {
                link.refuse("unexpected message " + message.type());
                return;
            }
        }
    }

    /** Stops listening and drops every connection; what was confirmed stays on the device. */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            server.close();
            for (Socket socket : connections) {
                socket.close();
            }
            try {
                acceptor.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (int attempt = 0; attempt < 100 && !connections.isEmpty(); attempt++) {
                try {
                    Thread.sleep(50);                            // the connections release their locks
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            rootLock.close();
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

        private final FileChannel[] channels = new FileChannel[Replication.FILES.length];
        private final long[] lengths = new long[Replication.FILES.length];

        private final boolean[] dirty = new boolean[Replication.FILES.length];

        ReplicaFiles(Path directory) throws IOException {
            try {
                boolean created = false;
                for (int f = 0; f < Replication.FILES.length; f++) {
                    Path file = directory.resolve(Replication.FILES[f]);
                    created |= !Files.exists(file);
                    channels[f] = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    lengths[f] = channels[f].size();
                    channels[f].position(lengths[f]);
                }
                if (created) {
                    Replication.forceDirectory(directory);  // before any ACK: a confirmed file cannot vanish
                }
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            }
        }

        long length(int file) {
            return lengths[file];
        }

        void append(int file, ByteBuffer bytes) throws IOException {
            if (file > 0 && dirty[0]) {
                force(0);                           // the commands before the seals that cover them: never a seal
            }                                       // on the device past the journal, whatever a crash keeps
            while (bytes.hasRemaining()) {
                lengths[file] += channels[file].write(bytes);
            }
            dirty[file] = true;
        }

        void force() throws IOException {
            for (int f = 0; f < channels.length; f++) {
                force(f);
            }
        }

        private void force(int file) throws IOException {
            if (dirty[file]) {
                channels[file].force(false);
                dirty[file] = false;
            }
        }

        @Override
        public void close() throws IOException {
            for (FileChannel channel : channels) {
                if (channel != null) {
                    channel.close();
                }
            }
        }
    }
}
