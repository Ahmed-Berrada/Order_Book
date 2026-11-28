package io.github.ahmedberrada.lob.journal;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The replication protocol between a primary and its standby (RS-014 to RS-016, ADR-0025), and the term file.
 *
 * <p>Per instrument, over one TCP connection, the primary sends {@code HELLO(symbol, term)}; the standby answers a
 * random {@code CHALLENGE}; the primary proves the shared secret with {@code AUTH(HMAC-SHA256(secret, challenge ||
 * symbol || term))}; the standby answers {@code WELCOME} with its term and the length and SHA-256 of each file it
 * holds, or {@code REFUSED}. Then the primary sends {@code DATA(file, offset, bytes)} for what the standby lacks and
 * {@code SYNC(sequence)}, which the standby answers with {@code ACK(lengths)} once the bytes are on its device.
 */
final class Replication {

    /** The fencing term of an instrument's journal: the number of the latest promotion (RS-016). */
    static final String TERM_FILE = "replication.term";

    static final byte HELLO = 1;
    static final byte CHALLENGE = 2;
    static final byte AUTH = 3;
    static final byte WELCOME = 4;
    static final byte REFUSED = 5;
    static final byte DATA = 6;
    static final byte SYNC = 7;
    static final byte ACK = 8;

    /** The replicated files, in protocol order: the command journal, then its seals (RS-013). */
    static final String[] FILES = {JournaledEngine.COMMANDS_FILE, JournalSealer.FILE};

    static final int MAX_DATA_BYTES = 1 << 20;

    private Replication() {
    }

    /** An instrument directory's term; 1 when it has none (no promotion yet). */
    static long term(Path directory) throws IOException {
        Path file = directory.resolve(TERM_FILE);
        if (!Files.exists(file)) {
            return 1;
        }
        String text = Files.readString(file, StandardCharsets.US_ASCII).strip();
        if (!text.matches("\\d{1,18}") || Long.parseLong(text) < 1) {
            throw new JournalCorruptedException(file + " holds no term: '" + text + "'");
        }
        return Long.parseLong(text);
    }

    /** Writes a term durably and atomically. */
    static void writeTerm(Path directory, long term) throws IOException {
        Path file = directory.resolve(TERM_FILE);
        Path temporary = directory.resolve(TERM_FILE + ".tmp");
        Files.writeString(temporary, Long.toString(term) + "\n", StandardCharsets.US_ASCII);
        try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static byte[] mac(byte[] secret, byte[] challenge, String symbol, long term) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(challenge);
            mac.update(symbol.getBytes(StandardCharsets.UTF_8));
            mac.update(java.nio.ByteBuffer.allocate(8).putLong(term).array());
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IOException("HmacSHA256 is required of every JVM", e);
        }
    }

    /** SHA-256 of a file's first {@code length} bytes (a missing file is empty). */
    static byte[] sha256(Path file, long length) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IOException("SHA-256 is required of every JVM", e);
        }
        if (length > 0) {
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                long left = length;
                while (left > 0) {
                    int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                    if (read < 0) {
                        throw new IOException(file + " is shorter than " + length + " bytes");
                    }
                    digest.update(buffer, 0, read);
                    left -= read;
                }
            }
        }
        return digest.digest();
    }

    static long size(Path file) throws IOException {
        return Files.exists(file) ? Files.size(file) : 0;
    }

    static void expect(DataInputStream in, byte type) throws IOException {
        byte got = in.readByte();
        if (got == REFUSED) {
            throw new IOException("the standby refused: " + in.readUTF());
        }
        if (got != type) {
            throw new IOException("replication protocol: expected message " + type + ", got " + got);
        }
    }

    static void refuse(DataOutputStream out, String reason) throws IOException {
        out.writeByte(REFUSED);
        out.writeUTF(reason);
        out.flush();
    }
}
