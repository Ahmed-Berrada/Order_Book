package io.github.ahmedberrada.lob.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.CancelReason;
import io.github.ahmedberrada.lob.core.CancelRejected;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import io.github.ahmedberrada.lob.core.OrderCancelled;
import io.github.ahmedberrada.lob.core.OrderRejected;
import io.github.ahmedberrada.lob.core.RejectReason;
import io.github.ahmedberrada.lob.core.Side;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class CodecTest {

    @Property(tries = 200)
    void everyCommandEventAndSnapshotSurvivesARoundTrip(@ForAll long seed) throws IOException {
        List<Command> commands = CommandStream.generate(seed, 200);
        MatchingEngine engine = new MatchingEngine(CommandStream.INSTRUMENT);
        for (int i = 0; i < commands.size(); i++) {
            JournalEntry entry = new JournalEntry(i + 1, 1_000L + i, commands.get(i));
            assertThat(Codec.decodeEntry(Codec.encodeEntry(entry))).isEqualTo(entry);

            EventBatch batch = new EventBatch(i + 1, 1_000L + i, engine.process(commands.get(i)));
            assertThat(Codec.decodeBatch(Codec.encodeBatch(batch))).isEqualTo(batch);
        }
        Codec.StoredSnapshot snapshot = new Codec.StoredSnapshot(200, engine.snapshot());
        assertThat(Codec.decodeSnapshot(Codec.encodeSnapshot(snapshot))).isEqualTo(snapshot);
    }

    @Test
    void everyReasonHasACode() throws IOException {
        List<Event> events = new ArrayList<>();
        for (RejectReason reason : RejectReason.values()) {
            events.add(new OrderRejected(1, 1, reason));
            events.add(new CancelRejected(1, 1, reason));
        }
        for (CancelReason reason : CancelReason.values()) {
            events.add(new OrderCancelled(1, 1, 1, reason));
        }
        EventBatch batch = new EventBatch(1, 0, events);

        assertThat(Codec.decodeBatch(Codec.encodeBatch(batch))).isEqualTo(batch);
    }

    @Test
    void emptyBatchesAndEmptyBooksSurviveARoundTrip() throws IOException {
        EventBatch empty = new EventBatch(1, 1, List.of());
        assertThat(Codec.decodeBatch(Codec.encodeBatch(empty))).isEqualTo(empty);

        Codec.StoredSnapshot snapshot = new Codec.StoredSnapshot(0, new MatchingEngine(CommandStream.INSTRUMENT).snapshot());
        assertThat(Codec.decodeSnapshot(Codec.encodeSnapshot(snapshot))).isEqualTo(snapshot);
    }

    @Test
    void headersRoundTripAndAreChecked() throws IOException {
        for (Codec.Kind kind : Codec.Kind.values()) {
            Codec.Header header = new Codec.Header(kind, CommandStream.INSTRUMENT);
            assertThat(Codec.decodeHeader(Codec.encodeHeader(header))).isEqualTo(header);
        }
        byte[] valid = Codec.encodeHeader(new Codec.Header(Codec.Kind.COMMANDS, CommandStream.INSTRUMENT));

        assertCorrupt(withByte(valid, 0, 0), "magic");
        assertCorrupt(withByte(valid, 5, 9), "version");
        assertCorrupt(withByte(valid, 6, 9), "kind");
        assertCorrupt(header(out -> {
            out.writeUTF("X");
            out.writeLong(0);
            out.writeLong(1);
        }), "instrument");
    }

    @Test
    void invalidRecordsAreCorruption() throws IOException {
        byte[] entry = Codec.encodeEntry(new JournalEntry(1, 1, new CancelOrder(1)));
        assertThatThrownBy(() -> Codec.decodeEntry(withByte(entry, 16, 9)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("command type");
        assertThatThrownBy(() -> Codec.decodeEntry(Arrays.copyOf(entry, entry.length - 1)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("shorter");
        assertThatThrownBy(() -> Codec.decodeEntry(Arrays.copyOf(entry, entry.length + 1)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("trailing");

        byte[] limit = Codec.encodeEntry(new JournalEntry(1, 1,
                new LimitOrder(Side.BUY, 1, 1)));
        assertThatThrownBy(() -> Codec.decodeEntry(withByte(limit, 17, 7)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("side");

        byte[] batch = Codec.encodeBatch(new EventBatch(1, 1, List.of(
                new OrderRejected(1, 1, RejectReason.NO_LIQUIDITY))));
        int eventStart = 8 + 8 + 4;
        assertThatThrownBy(() -> Codec.decodeBatch(withByte(batch, eventStart, 9)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("event type");
        assertThatThrownBy(() -> Codec.decodeBatch(withByte(batch, eventStart + 17, 9)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("reject reason");
        assertThatThrownBy(() -> Codec.decodeBatch(withByte(batch, 16, 0xFF)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("negative");

        byte[] cancelled = Codec.encodeBatch(new EventBatch(1, 1, List.of(
                new OrderCancelled(1, 1, 1, CancelReason.CLIENT_REQUEST))));
        assertThatThrownBy(() -> Codec.decodeBatch(withByte(cancelled, eventStart + 25, 9)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("cancel reason");
    }

    @Test
    void invalidSnapshotsAreCorruption() {
        MatchingEngine engine = new MatchingEngine(CommandStream.INSTRUMENT);
        byte[] snapshot = Codec.encodeSnapshot(new Codec.StoredSnapshot(0, engine.snapshot()));
        byte[] notSnapshot = Codec.encodeHeader(new Codec.Header(Codec.Kind.EVENTS, CommandStream.INSTRUMENT));

        assertThatThrownBy(() -> Codec.decodeSnapshot(notSnapshot))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("not a snapshot");
        byte[] negativeCount = withByte(snapshot, snapshot.length - 4, 0xFF);
        assertThatThrownBy(() -> Codec.decodeSnapshot(negativeCount))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("negative");
    }

    @FunctionalInterface
    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    /** A header with a valid magic, version and kind, followed by {@code body}. */
    private static byte[] header(Body body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(Codec.MAGIC);
            out.writeShort(Codec.FORMAT_VERSION);
            out.writeByte(1);
            body.write(out);
        }
        return bytes.toByteArray();
    }

    private static byte[] withByte(byte[] payload, int offset, int value) {
        byte[] copy = payload.clone();
        copy[offset] = (byte) value;
        return copy;
    }

    private static void assertCorrupt(byte[] header, String message) {
        assertThatThrownBy(() -> Codec.decodeHeader(header))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining(message);
    }
}
