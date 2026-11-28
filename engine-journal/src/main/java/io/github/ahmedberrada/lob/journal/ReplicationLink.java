package io.github.ahmedberrada.lob.journal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * One authenticated replication connection (RS-014, ADR-0025 §5). Both sides prove the shared secret in the handshake,
 * and every later message carries an HMAC-SHA256, under a key of this connection, of its direction, its number in that
 * direction, its type and its body: nothing can be inserted, replayed, reordered or changed on the way, and a peer
 * without the secret can neither confirm nor send anything. It is not encrypted.
 *
 * <p>Handshake: the primary sends {@code HELLO(symbol, term, nonce)}; the standby answers {@code CHALLENGE(nonce,
 * proof)}; the primary checks the standby's proof and sends its own ({@code AUTH}). Proofs and the connection key are
 * HMACs, under the secret, of a label, both nonces, the symbol and the term.
 */
final class ReplicationLink implements AutoCloseable {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAC_BYTES = 32;
    private static final int MAX_BODY_BYTES = Replication.MAX_DATA_BYTES + 64;

    /** A message received and authenticated. */
    record Message(byte type, byte[] body) {

        DataInputStream reader() {
            return new DataInputStream(new java.io.ByteArrayInputStream(body));
        }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final boolean primary;
    private SecretKeySpec key;
    private long sent;
    private long received;

    private ReplicationLink(Socket socket, boolean primary) throws IOException {
        this.socket = socket;
        this.primary = primary;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1 << 16));
    }

    /** The primary's side of the handshake; throws if the standby cannot prove the secret or refuses. */
    static ReplicationLink asPrimary(Socket socket, byte[] secret, String symbol, long term) throws IOException {
        ReplicationLink link = new ReplicationLink(socket, true);
        byte[] primaryNonce = nonce();
        link.out.writeByte(Replication.HELLO);
        link.out.writeUTF(symbol);
        link.out.writeLong(term);
        link.out.write(primaryNonce);
        link.out.flush();
        Replication.expect(link.in, Replication.CHALLENGE);
        byte[] standbyNonce = link.in.readNBytes(32);
        byte[] standbyProof = link.in.readNBytes(MAC_BYTES);
        if (!MessageDigest.isEqual(standbyProof, derive(secret, "standby", primaryNonce, standbyNonce, symbol, term))) {
            throw new IOException("the standby could not prove the replication secret: not the standby");
        }
        link.out.writeByte(Replication.AUTH);
        link.out.write(derive(secret, "primary", primaryNonce, standbyNonce, symbol, term));
        link.out.flush();
        link.key = new SecretKeySpec(derive(secret, "key", primaryNonce, standbyNonce, symbol, term), "HmacSHA256");
        return link;
    }

    /** What a primary claims in its HELLO, before it has proved anything. */
    record Hello(String symbol, long term, byte[] nonce) {
    }

    static ReplicationLink asStandby(Socket socket) throws IOException {
        return new ReplicationLink(socket, false);
    }

    Hello readHello() throws IOException {
        Replication.expect(in, Replication.HELLO);
        return new Hello(in.readUTF(), in.readLong(), in.readNBytes(32));
    }

    /** The standby's side of the handshake; returns false if the primary could not prove the secret. */
    boolean authenticate(byte[] secret, Hello hello) throws IOException {
        byte[] standbyNonce = nonce();
        out.writeByte(Replication.CHALLENGE);
        out.write(standbyNonce);
        out.write(derive(secret, "standby", hello.nonce(), standbyNonce, hello.symbol(), hello.term()));
        out.flush();
        Replication.expect(in, Replication.AUTH);
        byte[] proof = in.readNBytes(MAC_BYTES);
        if (!MessageDigest.isEqual(proof, derive(secret, "primary", hello.nonce(), standbyNonce, hello.symbol(),
                hello.term()))) {
            return false;
        }
        key = new SecretKeySpec(derive(secret, "key", hello.nonce(), standbyNonce, hello.symbol(), hello.term()),
                "HmacSHA256");
        return true;
    }

    /** Sends an authenticated message. */
    void send(byte type, byte[] body) throws IOException {
        out.writeByte(type);
        out.writeInt(body.length);
        out.write(body);
        out.write(mac(primary, sent++, type, body));
    }

    void flush() throws IOException {
        out.flush();
    }

    /** Receives and authenticates the next message; a refusal (unauthenticated) is thrown as such. */
    Message receive() throws IOException {
        byte type = in.readByte();
        if (type == Replication.REFUSED) {
            throw new IOException("the standby refused: " + in.readUTF());
        }
        int length = in.readInt();
        if (length < 0 || length > MAX_BODY_BYTES) {
            throw new IOException("replication message of " + length + " bytes");
        }
        byte[] body = in.readNBytes(length);
        byte[] mac = in.readNBytes(MAC_BYTES);
        if (body.length != length || mac.length != MAC_BYTES) {
            throw new java.io.EOFException("replication connection cut mid-message");
        }
        if (!MessageDigest.isEqual(mac, mac(!primary, received++, type, body))) {
            throw new IOException("replication message not authenticated: changed or forged on the way");
        }
        return new Message(type, body);
    }

    /** Refuses, unauthenticated: it tells the peer why, and nothing else follows. */
    void refuse(String reason) throws IOException {
        Replication.refuse(out, reason);
    }

    static byte[] body(IoWriter writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            writer.write(data);
        }
        return bytes.toByteArray();
    }

    @FunctionalInterface
    interface IoWriter {
        void write(DataOutputStream out) throws IOException;
    }

    private byte[] mac(boolean fromPrimary, long number, byte type, byte[] body) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(fromPrimary ? (byte) 'P' : (byte) 'S');
            mac.update(ByteBuffer.allocate(8).putLong(number).array());
            mac.update(type);
            mac.update(body);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IOException("HmacSHA256 is required of every JVM", e);
        }
    }

    private static byte[] derive(byte[] secret, String label, byte[] primaryNonce, byte[] standbyNonce, String symbol,
            long term) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(label.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) 0);
            mac.update(primaryNonce);
            mac.update(standbyNonce);
            mac.update(symbol.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(ByteBuffer.allocate(8).putLong(term).array());
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IOException("HmacSHA256 is required of every JVM", e);
        }
    }

    private static byte[] nonce() {
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    Socket socket() {
        return socket;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
