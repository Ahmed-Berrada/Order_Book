package io.github.ahmedberrada.lob.journal;

import static io.github.ahmedberrada.lob.journal.CommandStream.INSTRUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.core.TickBand;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A primary replicates its command journal to a standby before acknowledging, and a promoted standby fences it. */
class ReplicationTest {

    private static final byte[] SECRET = "a replication secret of 32 bytes or more".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path primaryRoot;

    @TempDir
    Path standbyRoot;

    private final ManualClock clock = new ManualClock(1_759_910_400_000_000L);
    private ReplicaServer standby;

    @AfterEach
    void stop() throws IOException {
        if (standby != null) {
            standby.close();
        }
    }

    private ReplicaServer startStandby() throws IOException {
        standby = ReplicaServer.start(standbyRoot, "127.0.0.1", 0, SECRET);
        return standby;
    }

    private JournalOptions options(FsyncPolicy policy, int port) {
        return options(policy, port, SECRET);
    }

    private JournalOptions options(FsyncPolicy policy, int port, byte[] secret) {
        return new JournalOptions(policy, 0, null, new ReplicationTarget("127.0.0.1", port, secret, Duration.ofSeconds(2)));
    }

    private Path primary() {
        return primaryRoot.resolve(INSTRUMENT.symbol());
    }

    private Path replica() {
        return standbyRoot.resolve(INSTRUMENT.symbol());
    }

    private JournaledEngine open(Path directory, JournalOptions options) throws IOException {
        return JournaledEngine.open(directory, INSTRUMENT, options, clock);
    }

    private static byte[] bytes(Path file) throws IOException {
        return Files.exists(file) ? Files.readAllBytes(file) : new byte[0];
    }

    @Test
    @Rulebook("RS-014")
    void everyAcknowledgedCommandIsOnTheStandbyAndAPromotedStandbyHasTheSameBook() throws IOException {
        int port = startStandby().port();
        try (JournaledEngine engine = open(primary(), options(FsyncPolicy.GROUP, port))) {
            for (int i = 0; i < 30; i++) {
                engine.process(new LimitOrder(i % 2 == 0 ? Side.BUY : Side.SELL, 100 + i % 3, 1 + i % 4));
                if (i % 5 == 4) {
                    engine.sync();                                       // a group of five, acknowledged together
                    assertThat(bytes(replica().resolve(JournaledEngine.COMMANDS_FILE)))
                            .as("on the standby before the acknowledgements")
                            .isEqualTo(bytes(primary().resolve(JournaledEngine.COMMANDS_FILE)));
                }
            }
        }
        standby.close();
        standby = null;

        JournaledEngine original = open(primary(), new JournalOptions(FsyncPolicy.OS, 0));
        JournaledEngine promoted = open(replica(), new JournalOptions(FsyncPolicy.OS, 0));
        try (original; promoted) {
            assertThat(promoted.recovery().lastCommandSequence()).isEqualTo(30);
            assertThat(promoted.state()).as("the standby, opened, is the same venue").isEqualTo(original.state());
        }
    }

    @Test
    @Rulebook({"RS-014", "RS-015"})
    void withoutItsStandbyThePrimaryAcknowledgesNothingAndStops() throws IOException {
        int port = startStandby().port();
        JournaledEngine engine = open(primary(), options(FsyncPolicy.EVERY_COMMAND, port));
        try (engine) {
            engine.process(new LimitOrder(Side.BUY, 100, 1));
            standby.close();
            standby = null;
            assertThatThrownBy(() -> engine.process(new LimitOrder(Side.BUY, 100, 1)))
                    .isInstanceOf(UncheckedIOException.class).hasMessageContaining("replication to the standby failed");
            assertThat(engine.isStopped()).isTrue();
        }
        try (JournaledEngine copy = open(replica(), new JournalOptions(FsyncPolicy.OS, 0))) {
            assertThat(copy.recovery().lastCommandSequence()).as("the acknowledged command").isEqualTo(1);
        }
    }

    @Test
    @Rulebook("RS-014")
    void aStandbyThatStartsLateOrIsCutMidMessageCatchesUp() throws IOException {
        try (JournaledEngine alone = open(primary(), new JournalOptions(FsyncPolicy.OS, 0))) {
            for (int i = 0; i < 10; i++) {
                alone.process(new LimitOrder(Side.BUY, 100, 1));                // before there was a standby
            }
        }
        int port = startStandby().port();
        try (FaultyProxy proxy = new FaultyProxy(port)) {
            JournaledEngine engine = open(primary(), options(FsyncPolicy.GROUP, proxy.port()));
            engine.process(new LimitOrder(Side.BUY, 100, 1));
            engine.sync();
            assertThat(bytes(replica().resolve(JournaledEngine.COMMANDS_FILE)))
                    .isEqualTo(bytes(primary().resolve(JournaledEngine.COMMANDS_FILE)));

            proxy.cutAfter(20);                                                  // a partition in mid-message
            engine.process(new LimitOrder(Side.BUY, 100, 2));
            assertThatThrownBy(engine::sync).isInstanceOf(UncheckedIOException.class);
            engine.close();
            byte[] partial = bytes(replica().resolve(JournaledEngine.COMMANDS_FILE));
            byte[] full = bytes(primary().resolve(JournaledEngine.COMMANDS_FILE));
            assertThat(partial.length).isLessThan(full.length);
            assertThat(Arrays.copyOf(full, partial.length)).as("the standby holds a prefix").isEqualTo(partial);

            proxy.heal();
            try (JournaledEngine restarted = open(primary(), options(FsyncPolicy.GROUP, proxy.port()))) {
                restarted.process(new LimitOrder(Side.BUY, 100, 3));
                restarted.sync();
            }
            assertThat(bytes(replica().resolve(JournaledEngine.COMMANDS_FILE)))
                    .isEqualTo(bytes(primary().resolve(JournaledEngine.COMMANDS_FILE)));
        }
    }

    @Test
    @Rulebook("RS-015")
    void aStandbyTooSlowToConfirmStopsThePrimary() throws IOException {
        int port = startStandby().port();
        try (FaultyProxy proxy = new FaultyProxy(port)) {
            JournalOptions slow = new JournalOptions(FsyncPolicy.GROUP, 0, null,
                    new ReplicationTarget("127.0.0.1", proxy.port(), SECRET, Duration.ofMillis(300)));
            try (JournaledEngine engine = open(primary(), slow)) {
                engine.process(new LimitOrder(Side.BUY, 100, 1));
                engine.sync();
                proxy.delay(1_000);
                engine.process(new LimitOrder(Side.BUY, 100, 1));
                assertThatThrownBy(engine::sync).isInstanceOf(UncheckedIOException.class);
                assertThat(engine.isStopped()).isTrue();
            }
        }
    }

    @Test
    @Rulebook("RS-016")
    void aPromotedStandbyFencesTheOldPrimary() throws IOException {
        int port = startStandby().port();
        try (JournaledEngine engine = open(primary(), options(FsyncPolicy.GROUP, port))) {
            engine.process(new LimitOrder(Side.BUY, 100, 1));
            engine.sync();
        }
        standby.close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ReplicaTool.run(new String[] {standbyRoot.toString()}, new PrintStream(out, true, StandardCharsets.UTF_8),
                System.err)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains(INSTRUMENT.symbol() + ": term 2");
        assertThat(Replication.term(replica())).isEqualTo(2);

        port = startStandby().port();                                            // say the old primary reaches it
        int promoted = port;
        assertThatThrownBy(() -> open(primary(), options(FsyncPolicy.GROUP, promoted)))
                .as("the old primary does not even open").isInstanceOf(IOException.class)
                .hasRootCauseMessage("the standby refused: fenced: the standby is at term 2, the primary at 1 (RS-016)");
    }

    @Test
    @Rulebook("RS-016")
    void afterAPromotionAnInstrumentAddedLaterTakesTheVenuesTermAndANewStandbyIsSeededWithIt() throws IOException {
        int port = startStandby().port();
        try (JournaledEngine engine = open(primary(), options(FsyncPolicy.GROUP, port))) {
            engine.process(new LimitOrder(Side.BUY, 100, 1));
            engine.sync();
        }
        standby.close();
        standby = null;
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream());
        assertThat(ReplicaTool.run(new String[] {standbyRoot.toString()}, quiet, quiet)).isZero();   // promoted: term 2

        Path nextStandby = primaryRoot.resolve("next-standby");                  // the new primary's new standby
        assertThat(ReplicaTool.run(new String[] {"--term", "2", nextStandby.toString()}, quiet, quiet)).isZero();
        assertThat(ReplicaTool.run(new String[] {"--term", "0", nextStandby.toString()}, quiet, quiet)).isEqualTo(2);
        try (ReplicaServer next = ReplicaServer.start(nextStandby, "127.0.0.1", 0, SECRET)) {
            JournalOptions toNext = options(FsyncPolicy.GROUP, next.port());
            assertThatThrownBy(() -> open(primary(), toNext)).as("the deposed primary, at term 1, reaches it first")
                    .hasRootCauseMessage("the standby refused: fenced: the standby is at term 2, the primary at 1 (RS-016)");
            Instrument listed = new Instrument("NEW", 100, 1_000, List.of(new TickBand(1, 1)), 300);
            try (JournaledEngine added = JournaledEngine.open(standbyRoot.resolve("NEW"), listed, toNext, clock)) {
                added.process(new LimitOrder(Side.BUY, 100, 1));
                added.sync();                           // a new instrument of the promoted venue: at its term, 2
            }
            assertThat(Replication.term(nextStandby.resolve("NEW"))).isEqualTo(2);
        }
    }

    @Test
    @Rulebook("RS-015")
    void aFailedReplicationIsNeverRetriedByTheStoppedInstance() throws IOException {
        int port = startStandby().port();
        try (JournaledEngine engine = open(primary(), options(FsyncPolicy.GROUP, port))) {
            engine.process(new LimitOrder(Side.BUY, 100, 1));
            standby.close();
            assertThatThrownBy(engine::sync).isInstanceOf(ReplicationException.class);
            standby = startStandby();                                            // back, but too late
            assertThatThrownBy(engine::sync).isInstanceOf(ReplicationException.class)
                    .cause().hasMessage("replication failed earlier; the instance has stopped");
        }
    }

    @Test
    @Rulebook("RS-014")
    void aStandbyWithAnotherHistoryOrAWrongSecretIsRefused() throws IOException {
        Files.createDirectories(replica());
        Files.write(replica().resolve(JournaledEngine.COMMANDS_FILE), new byte[] {1, 2, 3, 4});
        int port = startStandby().port();
        try (JournaledEngine alone = open(primary(), new JournalOptions(FsyncPolicy.OS, 0))) {
            alone.process(new LimitOrder(Side.BUY, 100, 1));
        }
        assertThatThrownBy(() -> open(primary(), options(FsyncPolicy.GROUP, port)))
                .hasMessageContaining("could not be brought up to date")
                .hasRootCauseMessage("the standby's commands.journal differs from this "
                        + "primary's in its first 4 bytes: another history; resynchronise it from empty");
        Files.delete(replica().resolve(JournaledEngine.COMMANDS_FILE));
        byte[] wrong = "another secret, also of 32 bytes or more".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> open(primary(), options(FsyncPolicy.GROUP, port, wrong)))
                .hasRootCauseMessage("the standby could not prove the replication secret: not the standby");
        try (JournaledEngine engine = open(primary(), options(FsyncPolicy.GROUP, port))) {
            assertThat(engine.recovery().lastCommandSequence()).as("the lock was released each time").isEqualTo(1);
        }
        standby.close();
        Files.write(replica().resolve(JournaledEngine.COMMANDS_FILE), new byte[] {9}, StandardOpenOption.APPEND);
        startStandby();
        int restarted = standby.port();
        assertThatThrownBy(() -> open(primary(), options(FsyncPolicy.GROUP, restarted)))
                .hasRootCauseMessage("the standby holds " + (bytes(primary().resolve(JournaledEngine.COMMANDS_FILE))
                        .length + 1) + " bytes of commands.journal, this primary " + bytes(primary()
                        .resolve(JournaledEngine.COMMANDS_FILE)).length + ": the standby is ahead (promote it, RS-016)");
    }

    @Test
    @Rulebook({"RS-013", "RS-014"})
    void aSealedJournalIsReplicatedWithItsSealsAndTheirKeyHoldsOnThePromotedStandby() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        JournalSealing sealing = new JournalSealing(keys.getPrivate(), keys.getPublic(), Duration.ofHours(1));
        int port = startStandby().port();
        try (JournaledEngine engine = open(primary(), new JournalOptions(FsyncPolicy.GROUP, 0, sealing,
                new ReplicationTarget("127.0.0.1", port, SECRET, Duration.ofSeconds(2))))) {
            for (int i = 0; i < 5; i++) {
                engine.process(new LimitOrder(Side.BUY, 100, 1));
                engine.sync();
            }
        }
        standby.close();
        standby = null;
        assertThat(bytes(replica().resolve(JournalSealer.FILE))).isEqualTo(bytes(primary().resolve(JournalSealer.FILE)));
        try (JournaledEngine promoted = open(replica(), new JournalOptions(FsyncPolicy.OS, 0, sealing))) {
            assertThat(promoted.recovery().lastCommandSequence()).isEqualTo(5);
        }
    }

    @Test
    void theToolsCheckTheirArguments() throws Exception {
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream());
        assertThat(ReplicaTool.run(new String[] {}, quiet, quiet)).isEqualTo(2);
        assertThat(ReplicaTool.run(new String[] {"a", "b", "c"}, quiet, quiet)).isEqualTo(2);
        assertThat(ReplicaTool.run(new String[] {"--term", "x", "r"}, quiet, quiet)).isEqualTo(2);
        assertThat(ReplicaTool.run(new String[] {standbyRoot.toString()}, quiet, quiet)).as("nothing to promote").isEqualTo(1);
        assertThat(ReplicaServer.run(new String[] {"x"}, quiet, quiet)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReplicationTarget("h", 1, new byte[8], Duration.ofSeconds(1)));
        assertThat(new ReplicationTarget("h", 1, SECRET, Duration.ofSeconds(1)).toString()).doesNotContain("secret of");
        for (Duration timeout : new Duration[] {Duration.ofHours(2), Duration.ZERO}) {
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReplicationTarget("h", 1, SECRET, timeout));
        }
        for (int port : new int[] {0, 65_536}) {
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReplicationTarget("h", port, SECRET, Duration.ofSeconds(1)));
        }
        assertThat(ReplicaServer.run(new String[] {"r", "127.0.0.1", "port", "secret"}, quiet, quiet)).isEqualTo(2);
    }
}
