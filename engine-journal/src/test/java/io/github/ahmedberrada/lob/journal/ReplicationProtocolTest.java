package io.github.ahmedberrada.lob.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.Rulebook;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
    }

    /** A connection through the handshake, as a primary of this term. */
    private final class Session implements AutoCloseable {
        final Socket socket = new Socket("127.0.0.1", standby.port());
        final DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        final DataOutputStream out = new DataOutputStream(socket.getOutputStream());

        Session() throws IOException {
        }

        /** Sends HELLO and AUTH; returns the standby's first answer type after them. */
        byte hello(String symbol, long term, byte[] secret) throws IOException {
            out.writeByte(Replication.HELLO);
            out.writeUTF(symbol);
            out.writeLong(term);
            out.flush();
            assertThat(in.readByte()).isEqualTo(Replication.CHALLENGE);
            byte[] challenge = in.readNBytes(32);
            out.writeByte(Replication.AUTH);
            out.write(Replication.mac(secret, challenge, symbol, term));
            out.flush();
            return in.readByte();
        }

        void welcome() throws IOException {
            in.readLong();
            for (int f = 0; f < Replication.FILES.length; f++) {
                in.readLong();
                in.readNBytes(32);
            }
        }

        String refusal() throws IOException {
            return in.readUTF();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void aWrongSecretABadSymbolOrALowerTermIsRefused() throws IOException {
        try (Session s = new Session()) {
            assertThat(s.hello("AAPL", 1, "not the secret, though 32 bytes long....".getBytes(StandardCharsets.UTF_8)))
                    .isEqualTo(Replication.REFUSED);
            assertThat(s.refusal()).isEqualTo("authentication failed");
        }
        try (Session s = new Session()) {
            assertThat(s.hello("../etc", 1, SECRET)).isEqualTo(Replication.REFUSED);
            assertThat(s.refusal()).startsWith("not a symbol");
        }
        Files.createDirectories(root.resolve("AAPL"));
        Replication.writeTerm(root.resolve("AAPL"), 3);
        try (Session s = new Session()) {
            assertThat(s.hello("AAPL", 2, SECRET)).isEqualTo(Replication.REFUSED);
            assertThat(s.refusal()).contains("fenced");
        }
        try (Session s = new Session()) {
            assertThat(s.hello("AAPL", 4, SECRET)).isEqualTo(Replication.WELCOME);
        }
        assertThat(Replication.term(root.resolve("AAPL"))).as("a higher term is taken up").isEqualTo(4);
    }

    @Test
    void dataOutOfPlaceAnUnknownMessageOrASecondPrimaryIsRefused() throws Exception {
        try (Session s = new Session()) {
            assertThat(s.hello("AAPL", 1, SECRET)).isEqualTo(Replication.WELCOME);
            s.welcome();
            try (Session second = new Session()) {
                assertThat(second.hello("AAPL", 1, SECRET)).isEqualTo(Replication.REFUSED);
                assertThat(second.refusal()).contains("another primary");
            }
            s.out.writeByte(Replication.DATA);
            s.out.writeByte(0);
            s.out.writeLong(10);                                                  // the standby holds 0 bytes
            s.out.writeInt(3);
            s.out.write(new byte[3]);
            s.out.flush();
            assertThat(s.in.readByte()).isEqualTo(Replication.REFUSED);
            assertThat(s.refusal()).startsWith("data at 10");
        }
        try (Session s = new Session()) {
            assertThat(s.hello("AAPL", 1, SECRET)).isEqualTo(Replication.WELCOME);
            s.welcome();
            s.out.writeByte(42);
            s.out.flush();
            assertThat(s.in.readByte()).isEqualTo(Replication.REFUSED);
            assertThat(s.refusal()).isEqualTo("unexpected message 42");
        }
        try (Session s = new Session()) {
            assertThat(s.hello("AAPL", 1, SECRET)).isEqualTo(Replication.WELCOME);
            s.welcome();
            s.out.writeByte(Replication.DATA);
            s.out.writeByte(9);                                                   // no such file
            s.out.writeLong(0);
            s.out.writeInt(1);
            s.out.flush();
            assertThat(s.in.readByte()).isEqualTo(Replication.REFUSED);
            assertThat(s.refusal()).isEqualTo("bad data message");
        }
    }

    @Test
    void theTermFileIsCheckedAndTheProtocolOrderEnforced() throws IOException {
        Path directory = Files.createDirectories(root.resolve("MSFT"));
        assertThat(Replication.term(directory)).isEqualTo(1);
        Files.writeString(directory.resolve(Replication.TERM_FILE), "zero");
        assertThatThrownBy(() -> Replication.term(directory)).isInstanceOf(JournalCorruptedException.class);
        Files.writeString(directory.resolve(Replication.TERM_FILE), "0");
        assertThatThrownBy(() -> Replication.term(directory)).isInstanceOf(JournalCorruptedException.class);
        try (Session s = new Session()) {
            s.out.writeByte(Replication.SYNC);                                    // not a HELLO
            s.out.flush();
            assertThat(s.in.read()).as("the standby hangs up").isEqualTo(-1);
        }
        assertThatThrownBy(() -> ReplicaServer.start(root, "127.0.0.1", 0, new byte[8]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Replication.size(root.resolve("none"))).isZero();
        assertThatThrownBy(() -> Replication.sha256(root.resolve("none"), 5)).isInstanceOf(IOException.class);
    }
}
