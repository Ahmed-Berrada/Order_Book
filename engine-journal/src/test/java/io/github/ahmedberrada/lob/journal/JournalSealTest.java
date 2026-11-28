package io.github.ahmedberrada.lob.journal;

import static io.github.ahmedberrada.lob.journal.CommandStream.INSTRUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command journal is sealed as it is written, and any change to what a seal covers is found (RS-013). */
@Rulebook("RS-013")
class JournalSealTest {

    @TempDir
    Path directory;

    private final KeyPair keys = generate();
    private final ManualClock clock = new ManualClock(1_759_910_400_000_000L);

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private JournalOptions options(FsyncPolicy policy, Duration interval) {
        return new JournalOptions(policy, 0, new JournalSealing(keys.getPrivate(), keys.getPublic(), interval));
    }

    private JournalOptions adopting(Duration interval) {
        return new JournalOptions(FsyncPolicy.OS, 0, new JournalSealing(keys.getPrivate(), keys.getPublic(), interval,
                true));
    }

    private long now = 1_759_910_400_000_000L;

    /** Moves the clock a second on: a seal is due again with a 10 ms interval. */
    private void later() {
        now += 1_000_000;
        clock.set(now);
    }

    private JournaledEngine open(JournalOptions options) throws IOException {
        return JournaledEngine.open(directory, INSTRUMENT, options, clock);
    }

    private void write(JournalOptions options, int orders) throws IOException {
        try (JournaledEngine engine = open(options)) {
            for (int i = 0; i < orders; i++) {
                engine.process(new LimitOrder(Side.BUY, 100 + i % 5, 1 + i % 3));
                engine.sync();
            }
        }
    }

    private List<JournalSealer.Seal> seals() throws IOException {
        return JournalSealer.verify(directory.resolve(JournaledEngine.COMMANDS_FILE),
                directory.resolve(JournalSealer.FILE), INSTRUMENT.symbol(), keys.getPublic()).seals();
    }

    private int verifyTool(Path publicKey, ByteArrayOutputStream out, ByteArrayOutputStream err) {
        return JournalVerify.run(new String[] {directory.toString(), publicKey.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private Path publicKeyFile() throws IOException {
        Path file = directory.resolveSibling("seal-" + System.nanoTime() + ".pub");
        Files.writeString(file, SealKeys.pem("PUBLIC KEY", keys.getPublic().getEncoded()));
        return file;
    }

    @Test
    void aJournalIsSealedAtCloseAndItsSealsHoldWhenReopened() throws IOException {
        write(options(FsyncPolicy.OS, Duration.ofHours(1)), 20);
        List<JournalSealer.Seal> seals = seals();
        assertThat(seals).extracting(JournalSealer.Seal::lastSequence)
                .as("a new journal is sealed at its first command, then within the hour, and at close").containsExactly(1L, 20L);
        assertThat(seals.getLast().length()).isEqualTo(Files.size(directory.resolve(JournaledEngine.COMMANDS_FILE)));

        write(options(FsyncPolicy.OS, Duration.ofHours(1)), 5);                // reopened: seals checked, then more
        assertThat(seals()).hasSize(3).last().extracting(JournalSealer.Seal::lastSequence).isEqualTo(25L);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(verifyTool(publicKeyFile(), out, new ByteArrayOutputStream())).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("3 seals hold; sealed up to command 25")
                .contains("0 bytes after the last seal");
    }

    @Test
    void sealsFollowTheIntervalAndOnlyEverCoverFlushedCommands() throws IOException {
        try (JournaledEngine engine = open(options(FsyncPolicy.GROUP, Duration.ofMillis(10)))) {
            engine.process(new LimitOrder(Side.BUY, 100, 1));
            assertThat(seals()).as("not before the group's flush").isEmpty();
            engine.sync();
            assertThat(seals()).hasSize(1);
            later();
            engine.process(new LimitOrder(Side.BUY, 100, 1));
            engine.process(new LimitOrder(Side.BUY, 100, 1));
            engine.sync();
            assertThat(seals()).hasSize(2).last().extracting(JournalSealer.Seal::lastSequence).isEqualTo(3L);
            engine.sync();                                                       // nothing new: no seal
            assertThat(seals()).hasSize(2);
        }
        try (JournaledEngine engine = open(options(FsyncPolicy.EVERY_COMMAND, Duration.ofHours(1)))) {
            engine.process(new LimitOrder(Side.BUY, 100, 1));
            assertThat(seals()).as("the interval, by the commands' time, has not elapsed").hasSize(2);
            engine.snapshot();
            assertThat(seals()).as("sealed at a snapshot").hasSize(3);
        }
    }

    @Test
    void aRewrittenCommandIsFoundEvenWithAValidChecksum() throws IOException {
        write(options(FsyncPolicy.OS, Duration.ofHours(1)), 10);
        rewriteFrame(directory.resolve(JournaledEngine.COMMANDS_FILE), 5);     // a careful attacker fixes the CRC

        assertThatThrownBy(() -> open(options(FsyncPolicy.OS, Duration.ofHours(1))))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("changed after they were sealed");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(verifyTool(publicKeyFile(), new ByteArrayOutputStream(), err)).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("TAMPERED");
        try (JournaledEngine unsealed = JournaledEngine.open(directory, INSTRUMENT, new JournalOptions(FsyncPolicy.OS, 0),
                clock)) {
            assertThat(unsealed.recovery().lastCommandSequence()).as("without the seal it would go unnoticed")
                    .isEqualTo(10);
        }
    }

    @Test
    void aCutJournalIsFound() throws IOException {
        write(options(FsyncPolicy.OS, Duration.ofHours(1)), 10);
        Path journal = directory.resolve(JournaledEngine.COMMANDS_FILE);
        long cut = frameOffsets(journal).get(8);
        try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.WRITE)) {
            channel.truncate(cut);
        }
        // a careful attacker removes the events too, which would otherwise be ahead of the commands left
        Files.delete(directory.resolve(JournaledEngine.EVENTS_FILE));
        assertThatThrownBy(() -> open(options(FsyncPolicy.OS, Duration.ofHours(1))))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("sealed data is missing");
    }

    @Test
    void anotherKeyOrAnotherInstrumentsSealsDoNotHold() throws IOException {
        write(options(FsyncPolicy.OS, Duration.ofHours(1)), 3);
        KeyPair other = generate();
        assertThatThrownBy(() -> JournalSealer.verify(directory.resolve(JournaledEngine.COMMANDS_FILE),
                directory.resolve(JournalSealer.FILE), INSTRUMENT.symbol(), other.getPublic()))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("not signed by the key");
        assertThatThrownBy(() -> JournalSealer.verify(directory.resolve(JournaledEngine.COMMANDS_FILE),
                directory.resolve(JournalSealer.FILE), "OTHER", keys.getPublic()))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("not the seal file of this instrument");
    }

    @Test
    void sealingAnExistingJournalCoversItFromTheStart() throws IOException {
        try (JournaledEngine engine = JournaledEngine.open(directory, INSTRUMENT, new JournalOptions(FsyncPolicy.OS, 0),
                clock)) {
            engine.process(new LimitOrder(Side.BUY, 100, 1));
        }
        assertThatThrownBy(() -> open(options(FsyncPolicy.OS, Duration.ofHours(1))))
                .as("unsealed commands: never sealed over without being told").isInstanceOf(JournalCorruptedException.class)
                .hasMessageContaining("holds commands but no seal");
        write(adopting(Duration.ofHours(1)), 1);
        assertThat(seals()).hasSize(1).first().extracting(JournalSealer.Seal::lastSequence).isEqualTo(2L);
        write(options(FsyncPolicy.OS, Duration.ofHours(1)), 1);                // from then on, sealed as any
    }

    @Test
    void aJournalWithoutSealsIsReportedAsUnsealed() throws IOException {
        try (JournaledEngine engine = JournaledEngine.open(directory, INSTRUMENT, new JournalOptions(FsyncPolicy.OS, 0),
                clock)) {
            engine.process(new LimitOrder(Side.BUY, 100, 1));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(verifyTool(publicKeyFile(), out, new ByteArrayOutputStream())).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("no seal");
        assertThat(JournalVerify.run(new String[] {"only-one"}, System.out, new PrintStream(new ByteArrayOutputStream())))
                .isEqualTo(2);
    }

    @Test
    void keysAreEd25519AndReadFromPem() throws IOException {
        Path privateKey = directory.resolveSibling("seal-" + System.nanoTime() + ".pem");
        Files.writeString(privateKey, SealKeys.pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
        assertThat(SealKeys.privateKey(privateKey).getEncoded()).isEqualTo(keys.getPrivate().getEncoded());
        assertThat(SealKeys.publicKey(publicKeyFile()).getEncoded()).isEqualTo(keys.getPublic().getEncoded());
        assertThatThrownBy(() -> SealKeys.publicKey(privateKey)).isInstanceOf(IOException.class);
        for (Duration bad : new Duration[] {Duration.ofSeconds(-1), Duration.ZERO, Duration.ofMillis(9), Duration.ofHours(2)}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new JournalSealing(keys.getPrivate(), keys.getPublic(), bad));
        }
        assertThat(new JournalSealing(keys.getPrivate(), keys.getPublic(), Duration.ofSeconds(1)).toString())
                .doesNotContain("Private");
        Instrument unused = INSTRUMENT;
        assertThat(unused.symbol()).isNotBlank();
    }

    @Test
    void aDeletedSealFileIsFoundNotSealedOver() throws IOException {
        write(options(FsyncPolicy.OS, Duration.ofHours(1)), 5);
        rewriteFrame(directory.resolve(JournaledEngine.COMMANDS_FILE), 3);
        Files.delete(directory.resolve(JournalSealer.FILE));                   // to hide the change
        assertThatThrownBy(() -> open(options(FsyncPolicy.OS, Duration.ofHours(1))))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("holds commands but no seal");
    }

    @Test
    void aDamagedLastSealIsFoundButAPartlyWrittenOneIsATornTail() throws IOException {
        write(options(FsyncPolicy.OS, Duration.ofHours(1)), 5);
        Path seals = directory.resolve(JournalSealer.FILE);
        byte[] intact = Files.readAllBytes(seals);
        byte[] damaged = intact.clone();
        damaged[damaged.length - 1] ^= 0x01;                                    // the last seal's signature
        Files.write(seals, damaged);
        assertThatThrownBy(() -> open(options(FsyncPolicy.OS, Duration.ofHours(1))))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("a seal was damaged or removed");

        Files.write(seals, java.util.Arrays.copyOf(intact, intact.length - 50)); // a crash during the last seal
        try (JournaledEngine engine = open(options(FsyncPolicy.OS, Duration.ofHours(1)))) {
            assertThat(engine.recovery().lastCommandSequence()).isEqualTo(5);
        }
        assertThat(seals()).last().extracting(JournalSealer.Seal::lastSequence).as("sealed again at close").isEqualTo(5L);
    }

    @Test
    void aStalledClockCannotStopTheSeals() throws IOException {
        clock.set(now);
        try (JournaledEngine engine = open(options(FsyncPolicy.OS, Duration.ofHours(1)))) {
            for (int i = 0; i < JournalSealing.MAX_UNSEALED_COMMANDS + 5; i++) {
                engine.process(new LimitOrder(Side.BUY, 100, 1));
            }
            assertThat(seals()).as("after the first command, then after the most commands a seal may wait for")
                    .extracting(JournalSealer.Seal::lastSequence)
                    .containsExactly(1L, (long) JournalSealing.MAX_UNSEALED_COMMANDS + 1);
        }
    }

    @Test
    void theToolChecksAgainstASequenceRecordedElsewhereAndCanRequireNoUnsealedTail() throws IOException {
        write(options(FsyncPolicy.OS, Duration.ofHours(1)), 5);
        Path key = publicKeyFile();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream());
        assertThat(JournalVerify.run(new String[] {directory.toString(), key.toString(), "--expect", "5", "--strict"},
                quiet, quiet)).isZero();
        assertThat(JournalVerify.run(new String[] {directory.toString(), key.toString(), "--expect", "6"}, quiet,
                new PrintStream(err, true, StandardCharsets.UTF_8))).as("rolled back: older than recorded").isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("OLDER");
        try (JournaledEngine engine = JournaledEngine.open(directory, INSTRUMENT, new JournalOptions(FsyncPolicy.OS, 0),
                clock)) {
            engine.process(new LimitOrder(Side.BUY, 100, 1));                  // appended without sealing
        }
        assertThat(JournalVerify.run(new String[] {directory.toString(), key.toString()}, quiet, quiet)).isZero();
        assertThat(JournalVerify.run(new String[] {directory.toString(), key.toString(), "--strict"}, quiet, quiet))
                .isEqualTo(1);
        assertThat(JournalVerify.run(new String[] {directory.toString(), key.toString(), "--expect"}, quiet, quiet))
                .isEqualTo(2);
    }

    /** Offsets of the frames of a journal file. */
    private static List<Long> frameOffsets(Path file) throws IOException {
        List<Long> offsets = new ArrayList<>();
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(file));
        while (bytes.remaining() >= FrameReader.HEADER_BYTES) {
            offsets.add((long) bytes.position());
            int length = bytes.getInt();
            bytes.getInt();
            bytes.position(bytes.position() + length);
        }
        return offsets;
    }

    /** Changes a byte of a frame's payload and writes the frame's checksum to match. */
    private static void rewriteFrame(Path file, int frame) throws IOException {
        byte[] all = Files.readAllBytes(file);
        int offset = Math.toIntExact(frameOffsets(file).get(frame));
        int length = ByteBuffer.wrap(all, offset, 4).getInt();
        byte[] payload = java.util.Arrays.copyOfRange(all, offset + 8, offset + 8 + length);
        payload[33] ^= 0x01;                       // the low byte of a limit order's price: still a valid command
        ByteBuffer.wrap(all, offset + 4, 4).putInt(FrameReader.checksum(payload));
        System.arraycopy(payload, 0, all, offset + 8, length);
        Files.write(file, all);
    }
}
