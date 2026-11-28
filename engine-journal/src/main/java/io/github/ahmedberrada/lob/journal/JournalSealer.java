package io.github.ahmedberrada.lob.journal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Seals the command journal as it is written (RS-013): a running SHA-256 of every byte of the file, and in
 * {@code commands.seal}, at most every interval and only over bytes already on the device, a record signed with the
 * venue's Ed25519 key: the length of the journal it covers, the last command sequence, the time, and the SHA-256 of
 * the journal up to that length. Rewriting or removing any byte the latest seal covers breaks the seal.
 */
final class JournalSealer implements FrameWriter.Listener, java.io.Closeable {

    static final String FILE = "commands.seal";
    private static final byte[] MAGIC = "LOBSEAL1".getBytes(StandardCharsets.US_ASCII);
    private static final int RECORD_BYTES = 8 + 8 + 8 + 32 + 64;

    /** One seal, as read from the seal file. */
    record Seal(long length, long lastSequence, long timestampMicros, byte[] sha256, byte[] signature) {

        static Seal decode(byte[] payload) throws JournalCorruptedException {
            if (payload.length != RECORD_BYTES) {
                throw new JournalCorruptedException("seal record of " + payload.length + " bytes");
            }
            ByteBuffer in = ByteBuffer.wrap(payload);
            long length = in.getLong();
            long sequence = in.getLong();
            long timestamp = in.getLong();
            byte[] hash = new byte[32];
            byte[] signature = new byte[64];
            in.get(hash).get(signature);
            return new Seal(length, sequence, timestamp, hash, signature);
        }

        byte[] encode() {
            return ByteBuffer.allocate(RECORD_BYTES).putLong(length).putLong(lastSequence).putLong(timestampMicros)
                    .put(sha256).put(signature).array();
        }
    }

    /** What the seals of a journal establish. */
    record Verified(List<Seal> seals, long journalLength) {

        /** Bytes of the journal no seal covers yet. */
        long unsealedBytes() {
            return journalLength - (seals.isEmpty() ? 0 : seals.getLast().length());
        }
    }

    private final JournalSealing sealing;
    private final byte[] symbol;
    private final MessageDigest digest;
    private final FrameWriter seals;
    private long length;
    private long sealedLength;
    private long lastSealMicros;
    private final long intervalMicros;
    private long framesSinceSeal;

    private JournalSealer(JournalSealing sealing, byte[] symbol, MessageDigest digest, FrameWriter seals, long length,
            long sealedLength, long lastSealMicros) {
        this.sealing = sealing;
        this.symbol = symbol;
        this.digest = digest;
        this.seals = seals;
        this.length = length;
        this.sealedLength = sealedLength;
        this.lastSealMicros = lastSealMicros;
        this.intervalMicros = sealing.interval().toNanos() / 1_000;
    }

    /**
     * Checks every seal against the journal's first {@code validLength} bytes, then keeps sealing from there.
     *
     * @throws JournalCorruptedException if a seal does not match the journal or its signature, or covers more than
     *                                   the journal holds: the journal was changed after it was sealed
     */
    static JournalSealer open(Path journal, long validLength, Path sealFile, JournalSealing sealing, String symbol)
            throws IOException {
        byte[] name = symbol.getBytes(StandardCharsets.UTF_8);
        List<Seal> found = new ArrayList<>();
        long sealValidLength = readSeals(sealFile, name, found);
        if (found.isEmpty() && holdsCommands(journal, validLength) && !sealing.sealExistingJournal()) {
            // A journal with commands and no seals is either one never sealed, which the operator must say, or one
            // whose seal file was deleted to hide a change: never seal over it silently.
            throw new JournalCorruptedException(journal + " holds commands but no seal: its seals were removed, or it "
                    + "was never sealed (to begin sealing it, set seal-existing-journal once)");
        }
        MessageDigest digest = verify(journal, validLength, name, found, sealing.verifyingKey());
        boolean created = !Files.exists(sealFile);
        FrameWriter writer = new FrameWriter(sealFile, sealValidLength);
        try {
            if (sealValidLength == 0) {
                writer.append(header(name));
                writer.force();
            }
            if (created) {
                forceDirectory(sealFile.toAbsolutePath().getParent());
            }
        } catch (IOException e) {
            writer.close();
            throw e;
        }
        return new JournalSealer(sealing, name, digest, writer, validLength,
                found.isEmpty() ? 0 : found.getLast().length(),
                found.isEmpty() ? Long.MIN_VALUE / 2 : found.getLast().timestampMicros());
    }

    /** Whether a journal holds more than its header. */
    private static boolean holdsCommands(Path journal, long validLength) throws IOException {
        if (validLength == 0) {
            return false;
        }
        try (FrameReader reader = new FrameReader(journal)) {
            reader.next();                                     // the header
            return reader.validLength() < validLength;
        }
    }

    /** Makes a new file's directory entry durable; a file system that cannot is left as it is. */
    private static void forceDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(directory,
                java.nio.file.StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            // not every platform opens directories; the journal's own files are forced either way
        }
    }

    /** Checks a journal's seals without a private key, for an auditor (JournalVerify). */
    static Verified verify(Path journal, Path sealFile, String symbol, PublicKey key) throws IOException {
        long validLength;
        try (FrameReader reader = new FrameReader(journal)) {
            while (reader.next() != null) {
                // only the length of the valid frames matters here
            }
            validLength = reader.validLength();
        }
        byte[] name = symbol.getBytes(StandardCharsets.UTF_8);
        List<Seal> found = new ArrayList<>();
        if (Files.exists(sealFile)) {
            readSeals(sealFile, name, found);
        }
        verify(journal, validLength, name, found, key);
        return new Verified(List.copyOf(found), validLength);
    }

    @Override
    public void appended(byte[] frame) {
        digest.update(frame);
        length += frame.length;
        framesSinceSeal++;
    }

    /**
     * Whether a seal is due: bytes unsealed, and the interval elapsed by the journal's own time, the timestamps of its
     * commands (the clock is read only by the time source, RS-006).
     */
    boolean due(long nowMicros) {
        return length > sealedLength && (nowMicros - lastSealMicros >= intervalMicros
                || framesSinceSeal >= JournalSealing.MAX_UNSEALED_COMMANDS);
    }

    /** Whether any byte is unsealed. */
    boolean unsealed() {
        return length > sealedLength;
    }

    /**
     * Seals the journal as written so far. The caller has just flushed it to the device: a seal never covers bytes
     * that a crash could still take away.
     */
    void seal(long lastSequence, long timestampMicros) throws IOException {
        if (!unsealed()) {
            return;
        }
        byte[] hash = cloneDigest().digest();
        byte[] signature;
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(sealing.signingKey());
            signer.update(signed(symbol, length, lastSequence, timestampMicros, hash));
            signature = signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IOException("could not sign the journal seal", e);
        }
        seals.append(new Seal(length, lastSequence, timestampMicros, hash, signature).encode());
        seals.force();
        sealedLength = length;
        lastSealMicros = timestampMicros;
        framesSinceSeal = 0;
    }

    @Override
    public void close() throws IOException {
        seals.close();
    }

    private MessageDigest cloneDigest() throws IOException {
        try {
            return (MessageDigest) digest.clone();
        } catch (CloneNotSupportedException e) {
            throw new IOException("SHA-256 digest cannot be cloned", e);
        }
    }

    private static byte[] header(byte[] symbol) {
        return ByteBuffer.allocate(MAGIC.length + symbol.length).put(MAGIC).put(symbol).array();
    }

    private static byte[] signed(byte[] symbol, long length, long sequence, long timestamp, byte[] hash) {
        return ByteBuffer.allocate(MAGIC.length + symbol.length + 24 + hash.length).put(MAGIC).put(symbol)
                .putLong(length).putLong(sequence).putLong(timestamp).put(hash).array();
    }

    /** Reads the seals; returns the seal file's valid length (0 if it does not exist or is empty). */
    private static long readSeals(Path sealFile, byte[] symbol, List<Seal> into) throws IOException {
        if (!Files.exists(sealFile) || Files.size(sealFile) == 0) {
            return 0;
        }
        try (FrameReader reader = new FrameReader(sealFile)) {
            byte[] first = reader.next();
            if (first == null) {
                return 0;
            }
            if (!Arrays.equals(first, header(symbol))) {
                throw new JournalCorruptedException(sealFile + " is not the seal file of this instrument");
            }
            for (byte[] payload = reader.next(); payload != null; payload = reader.next()) {
                Seal seal = Seal.decode(payload);
                if (!into.isEmpty() && seal.length() <= into.getLast().length()) {
                    throw new JournalCorruptedException("seals out of order in " + sealFile);
                }
                into.add(seal);
            }
            // A crash can cut the last seal short; it cannot leave a whole one that fails its checksum, which is
            // someone removing the latest seal to change what it covered.
            if (reader.tornBytes() >= FrameReader.HEADER_BYTES + RECORD_BYTES) {
                throw new JournalCorruptedException(sealFile + " ends with " + reader.tornBytes()
                        + " bytes that are not a seal: a seal was damaged or removed");
            }
            return reader.validLength();
        }
    }

    /** Hashes the journal's first {@code validLength} bytes, checking each seal on the way. */
    private static MessageDigest verify(Path journal, long validLength, byte[] symbol, List<Seal> seals, PublicKey key)
            throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IOException("SHA-256 is required of every JVM", e);
        }
        if (!seals.isEmpty() && seals.getLast().length() > validLength) {
            throw new JournalCorruptedException("the journal is " + validLength + " bytes but was sealed at "
                    + seals.getLast().length() + ": sealed data is missing");
        }
        byte[] buffer = new byte[1 << 16];
        long position = 0;
        int next = 0;
        try (InputStream in = Files.exists(journal) ? Files.newInputStream(journal) : InputStream.nullInputStream()) {
            while (next < seals.size() || position < validLength) {
                long target = next < seals.size() ? seals.get(next).length() : validLength;
                while (position < target) {
                    int read = in.read(buffer, 0, (int) Math.min(buffer.length, target - position));
                    if (read < 0) {
                        throw new JournalCorruptedException("journal ended at " + position + ", before " + target);
                    }
                    digest.update(buffer, 0, read);
                    position += read;
                }
                if (next < seals.size()) {
                    check(seals.get(next), digest, symbol, key);
                    next++;
                }
            }
        }
        return digest;
    }

    private static void check(Seal seal, MessageDigest digest, byte[] symbol, PublicKey key) throws IOException {
        byte[] hash;
        try {
            hash = ((MessageDigest) digest.clone()).digest();
        } catch (CloneNotSupportedException e) {
            throw new IOException("SHA-256 digest cannot be cloned", e);
        }
        if (!MessageDigest.isEqual(hash, seal.sha256())) {
            throw new JournalCorruptedException("the journal's first " + seal.length() + " bytes changed after they"
                    + " were sealed (at command " + seal.lastSequence() + ")");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(signed(symbol, seal.length(), seal.lastSequence(), seal.timestampMicros(), seal.sha256()));
            if (!verifier.verify(seal.signature())) {
                throw new JournalCorruptedException("the seal at " + seal.length() + " bytes is not signed by the key");
            }
        } catch (GeneralSecurityException e) {
            throw new JournalCorruptedException("the seal at " + seal.length() + " bytes cannot be verified: " + e);
        }
    }
}
