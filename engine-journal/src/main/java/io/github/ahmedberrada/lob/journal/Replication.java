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

/**
 * The replication protocol between a primary and its standby (RS-014 to RS-016, ADR-0025), and the term file.
 *
 * <p>Per instrument, over one TCP connection, both sides prove the shared secret ({@link ReplicationLink}); then the
 * standby sends {@code WELCOME} with its term and the length and SHA-256 of each file it holds, or {@code REFUSED}.
 * The primary sends {@code DATA(file, offset, bytes)} for what the standby lacks and {@code SYNC(sequence)}, which the
 * standby answers with {@code ACK(lengths)} once the bytes are on its device. Every message after the handshake is
 * authenticated; only a refusal is not, and it ends the connection.
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

    /** The term of an instrument directory, or of the standby's root; 1 when it has none (no promotion yet). */
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
        forceDirectory(directory);                  // the rename too: a promotion must survive a power loss
    }

    /** Makes a directory's entries durable; a file system that cannot open directories is left as it is. */
    static void forceDirectory(Path directory) {
        try (var channel = java.nio.channels.FileChannel.open(directory, java.nio.file.StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            // not every platform opens directories; the files themselves are forced either way
        }
    }

    /** A primary's term for an instrument: its own, or its data root's when that is higher (a later instrument). */
    static long primaryTerm(Path directory) throws IOException {
        return Math.max(term(directory), term(directory.toAbsolutePath().getParent()));
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
