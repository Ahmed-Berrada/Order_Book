package io.github.ahmedberrada.lob.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.Rulebook;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The standby refuses what does not follow the protocol (RS-014, RS-016): spoken to directly, as an attacker would. */
@Rulebook({"RS-014", "RS-016"})
class ReplicationProtocolTest {

    private static final byte[] SECRET = "a replication secret of 32 bytes or more".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private ReplicaServer standby;

    @BeforeEach
    void start() throws IOException {
        standby = ReplicaServer.start(root, "127.0.0.1", 0, SECRET);
    }

    @AfterEach
    void stop() throws IOException {
        standby.close();
        standby.close();                                                         // closing twice is harmless
    }

    /** An authenticated connection, as a primary of this term, past the standby's welcome. */
    private ReplicationLink connect(String symbol, long term) throws IOException {
        ReplicationLink link = ReplicationLink.asPrimary(new Socket("127.0.0.1", standby.port()), SECRET, symbol, term);
        assertThat(link.receive().type()).isEqualTo(Replication.WELCOME);
        return link;
    }

    private String refusal(String symbol, long term) {
        try (ReplicationLink link = ReplicationLink.asPrimary(new Socket("127.0.0.1", standby.port()), SECRET, symbol,
                term)) {
            link.receive();
            throw new AssertionError("welcomed");
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private static String refusalOf(ReplicationLink link, byte type, ReplicationLink.IoWriter body) {
        try (link) {
            link.send(type, ReplicationLink.body(body));
            link.flush();
            link.receive();
            throw new AssertionError("answered");
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    @Test
    void aPrimaryWithoutTheSecretIsRefusedAndAStandbyWithoutItIsNotTrusted() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", standby.port())) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            out.writeByte(Replication.HELLO);
            out.writeUTF("AAPL");
            out.writeLong(1);
            out.write(new byte[32]);
            assertThat(in.readByte()).isEqualTo(Replication.CHALLENGE);
            in.readNBytes(64);
            out.writeByte(Replication.AUTH);
            out.write(new byte[32]);                                             // a guess
            assertThat(in.readByte()).isEqualTo(Replication.REFUSED);
            assertThat(in.readUTF()).isEqualTo("authentication failed");
        }
        try (ServerSocket impostor = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofPlatform().daemon(true).start(() -> {
                try (Socket socket = impostor.accept()) {
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeByte(Replication.CHALLENGE);
                    out.write(new byte[64]);                                     // cannot prove the secret
                    out.flush();
                    socket.getInputStream().read();
                } catch (IOException e) {
                    // the primary hung up
                }
            });
            assertThatThrownBy(() -> ReplicationLink.asPrimary(new Socket("127.0.0.1", impostor.getLocalPort()),
                    SECRET, "AAPL", 1)).hasMessageContaining("could not prove the replication secret");
        }
    }

    @Test
    void aBadSymbolOrALowerTermIsRefusedAndAPromotionFencesEveryInstrument() throws IOException {
        for (String bad : new String[] {"../etc", "..", ".", "aapl", "", "-X", "REPLICATION.TERM",
            "JOURNAL.LOCK"}) {
            assertThat(refusal(bad, 1)).startsWith("the standby refused: not a symbol");
        }
        Files.createDirectories(root.resolve("AAPL"));
        Replication.writeTerm(root.resolve("AAPL"), 3);
        assertThat(refusal("AAPL", 2)).contains("fenced: the standby is at term 3, the primary at 2");
        connect("AAPL", 4).close();
        assertThat(Replication.term(root.resolve("AAPL"))).as("a higher term is taken up").isEqualTo(4);
        assertThat(Replication.term(root)).as("by the whole standby").isEqualTo(4);
        assertThat(refusal("MSFT", 3)).as("an instrument it never held").contains("fenced");
    }

    @Test
    void aMessageChangedOnTheWayIsRejected() throws Exception {
        ReplicationLink link = connect("AAPL", 1);
        // the link's socket, written to directly: a DATA message whose MAC does not match
        DataOutputStream out = new DataOutputStream(link.socket().getOutputStream());
        out.writeByte(Replication.DATA);
        out.writeInt(12);
        out.write(new byte[12]);
        out.write(new byte[32]);
        out.flush();
        assertThatThrownBy(link::receive).as("the standby hangs up").isInstanceOf(java.io.EOFException.class);
        link.close();
        assertThat(Replication.size(root.resolve("AAPL").resolve(JournaledEngine.COMMANDS_FILE))).isZero();
    }

    @Test
    void dataOutOfPlaceOrAnUnknownMessageIsRefused() throws Exception {
        assertThat(refusalOf(connect("AAPL", 1), Replication.DATA, out -> {
            out.writeByte(0);
            out.writeLong(10);                                                   // the standby holds 0 bytes
            out.write(new byte[3]);
        })).isEqualTo("the standby refused: data at 10 for commands.journal, held 0");
        assertThat(refusalOf(connect("AAPL", 1), (byte) 42, out -> { }))
                .isEqualTo("the standby refused: unexpected message 42");
        assertThat(refusalOf(connect("AAPL", 1), Replication.DATA, out -> {
            out.writeByte(9);                                                    // no such file
            out.writeLong(0);
        })).isEqualTo("the standby refused: bad data message");
    }

    @Test
    void aNewConnectionReplacesAStaleOneUnlessItsTermIsLower() throws Exception {
        ReplicationLink stale = connect("AAPL", 1);
        ReplicationLink restarted = connect("AAPL", 1);                          // the same primary, restarted
        assertThatThrownBy(stale::receive).as("the stale connection is closed").isInstanceOf(IOException.class);
        stale.close();
        ReplicationLink promoted = connect("AAPL", 2);
        assertThatThrownBy(restarted::receive).isInstanceOf(IOException.class);
        restarted.close();
        assertThat(refusal("AAPL", 1)).contains("fenced");
        promoted.close();
    }

    @Test
    void theTermFileIsCheckedAndTheProtocolOrderEnforced() throws IOException {
        Path directory = Files.createDirectories(root.resolve("MSFT"));
        assertThat(Replication.term(directory)).isEqualTo(1);
        Files.writeString(directory.resolve(Replication.TERM_FILE), "zero");
        assertThatThrownBy(() -> Replication.term(directory)).isInstanceOf(JournalCorruptedException.class);
        Files.writeString(directory.resolve(Replication.TERM_FILE), "0");
        assertThatThrownBy(() -> Replication.term(directory)).isInstanceOf(JournalCorruptedException.class);
        try (Socket socket = new Socket("127.0.0.1", standby.port())) {
            socket.getOutputStream().write(Replication.SYNC);                   // not a HELLO
            assertThat(socket.getInputStream().read()).as("the standby hangs up").isEqualTo(-1);
        }
        assertThatThrownBy(() -> ReplicaServer.start(root.resolve("other"), "127.0.0.1", 0, new byte[8]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReplicaServer.start(root, "127.0.0.1", 0, SECRET))
                .as("one standby per root").isInstanceOf(IOException.class);
        assertThat(Replication.size(root.resolve("none"))).isZero();
        assertThatThrownBy(() -> Replication.sha256(root.resolve("none"), 5)).isInstanceOf(IOException.class);
    }
}
