package io.github.ahmedberrada.lob.journal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A TCP proxy between a primary and its standby that can fail the way networks do: cut the connection after a number
 * of bytes from the primary (a partition mid-message), or delay everything (a slow standby).
 */
final class FaultyProxy implements AutoCloseable {

    private final ServerSocket server;
    private final int target;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile long cutAfterBytes = Long.MAX_VALUE;
    private volatile long delayMillis;
    private final AtomicLong forwarded = new AtomicLong();

    FaultyProxy(int targetPort) throws IOException {
        this.target = targetPort;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon(true).start(this::accept);
    }

    int port() {
        return server.getLocalPort();
    }

    /** Cuts every connection once this many more bytes from the primary have been forwarded. */
    void cutAfter(long bytes) {
        forwarded.set(0);
        cutAfterBytes = bytes;
    }

    void heal() {
        cutAfterBytes = Long.MAX_VALUE;
        delayMillis = 0;
    }

    void delay(long millis) {
        delayMillis = millis;
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Socket upstream = new Socket(InetAddress.getLoopbackAddress(), target);
                sockets.add(client);
                sockets.add(upstream);
                Thread.ofPlatform().daemon(true).start(() -> pump(client, upstream, true));
                Thread.ofPlatform().daemon(true).start(() -> pump(upstream, client, false));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void pump(Socket from, Socket to, boolean fromPrimary) {
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                int allowed = read;
                if (fromPrimary) {
                    long left = cutAfterBytes - forwarded.get();
                    allowed = (int) Math.max(0, Math.min(read, left));
                    forwarded.addAndGet(allowed);
                }
                out.write(buffer, 0, allowed);
                out.flush();
                if (allowed < read) {
                    from.close();
                    to.close();
                    return;
                }
            }
        } catch (IOException | InterruptedException e) {
            // a connection closed: the other direction closes too
        } finally {
            try {
                from.close();
                to.close();
            } catch (IOException e) {
                // already closed
            }
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket socket : sockets) {
            socket.close();
        }
    }
}
