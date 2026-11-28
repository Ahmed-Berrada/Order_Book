package io.github.ahmedberrada.lob.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.AuctionPriceDetermined;
import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.CancelReason;
import io.github.ahmedberrada.lob.core.CancelRejected;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.EngineSnapshot;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import io.github.ahmedberrada.lob.core.MemberCommandRejected;
import io.github.ahmedberrada.lob.core.MemberLimits;
import io.github.ahmedberrada.lob.core.MemberLimitsChanged;
import io.github.ahmedberrada.lob.core.MassCancel;
import io.github.ahmedberrada.lob.core.MassCancelled;
import io.github.ahmedberrada.lob.core.MemberResumed;
import io.github.ahmedberrada.lob.core.MemberState;
import io.github.ahmedberrada.lob.core.MemberSuspended;
import io.github.ahmedberrada.lob.core.OrderCancelled;
import io.github.ahmedberrada.lob.core.OrderParked;
import io.github.ahmedberrada.lob.core.OrderRejected;
import io.github.ahmedberrada.lob.core.OrderReplaced;
import io.github.ahmedberrada.lob.core.OrderReplenished;
import io.github.ahmedberrada.lob.core.OrderRested;
import io.github.ahmedberrada.lob.core.ParkedStop;
import io.github.ahmedberrada.lob.core.PhaseChangeRejected;
import io.github.ahmedberrada.lob.core.PhaseChanged;
import io.github.ahmedberrada.lob.core.RejectReason;
import io.github.ahmedberrada.lob.core.ReplaceOrder;
import io.github.ahmedberrada.lob.core.ReplaceRejected;
import io.github.ahmedberrada.lob.core.RestingOrder;
import io.github.ahmedberrada.lob.core.ResumeMember;
import io.github.ahmedberrada.lob.core.SelfTradeInstruction;
import io.github.ahmedberrada.lob.core.SelfTradeMode;
import io.github.ahmedberrada.lob.core.SetMemberLimits;
import io.github.ahmedberrada.lob.core.SetPhase;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.core.StopOrder;
import io.github.ahmedberrada.lob.core.StopTriggered;
import io.github.ahmedberrada.lob.core.SuspendMember;
import io.github.ahmedberrada.lob.core.TickBand;
import io.github.ahmedberrada.lob.core.TimeInForce;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.core.TradingPhase;
import io.github.ahmedberrada.lob.core.VolatilityInterruption;
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
            events.add(new PhaseChangeRejected(1, reason));
        }
        for (CancelReason reason : CancelReason.values()) {
            events.add(new OrderCancelled(1, 1, 1, reason));
        }
        for (RejectReason reason : RejectReason.values()) {
            events.add(new ReplaceRejected(1, 1, reason));
        }
        events.add(new OrderReplaced(1, 1, Side.SELL, 5, 6, true));
        events.add(new OrderReplaced(1, 1, Side.BUY, 5, 6, false));
        events.add(new OrderReplenished(1, 2, Side.SELL, 7, 3));
        events.add(new OrderRested(1, 2, Side.SELL, 7, 9, 3));
        events.add(new OrderParked(1, 3, Side.BUY, 8, 4));
        events.add(new StopTriggered(1, 3));
        for (TradingPhase from : TradingPhase.values()) {
            for (TradingPhase to : TradingPhase.values()) {
                events.add(new PhaseChanged(1, from, to));
            }
        }
        events.add(new AuctionPriceDetermined(1, 101, 40));
        events.add(new VolatilityInterruption(1, 100, 106));
        EventBatch batch = new EventBatch(1, 0, events);

        assertThat(Codec.decodeBatch(Codec.encodeBatch(batch))).isEqualTo(batch);
    }

    @Test
    void everyTimeInForceAndAttributeHasACode() throws IOException {
        for (TimeInForce tif : TimeInForce.values()) {
            for (boolean postOnly : new boolean[] {false, true}) {
                JournalEntry limit = new JournalEntry(1, 1, new LimitOrder(Side.SELL, 3, 4, tif, postOnly, 2));
                assertThat(Codec.decodeEntry(Codec.encodeEntry(limit))).isEqualTo(limit);
            }
            JournalEntry market = new JournalEntry(1, 1, new MarketOrder(Side.BUY, 4, tif));
            assertThat(Codec.decodeEntry(Codec.encodeEntry(market))).isEqualTo(market);
        }
        JournalEntry replace = new JournalEntry(1, 1, new ReplaceOrder(7, 8, 9));
        assertThat(Codec.decodeEntry(Codec.encodeEntry(replace))).isEqualTo(replace);
        for (Command stop : List.<Command>of(new StopOrder(Side.SELL, 5, 4, 3), new StopOrder(Side.BUY, 5, 3))) {
            JournalEntry entry = new JournalEntry(1, 1, stop);
            assertThat(Codec.decodeEntry(Codec.encodeEntry(entry))).isEqualTo(entry);
        }
    }

    @Test
    void membersAndLimitsRoundTripThroughEntriesBatchesAndSnapshots() throws IOException {
        MemberLimits limits = new MemberLimits(10, 20, 30, 40, 50);
        for (Command command : List.<Command>of(new SetMemberLimits(7, limits), new SuspendMember(7),
                new ResumeMember(7), new MassCancel(Side.BUY), new MassCancel(Side.SELL), MassCancel.all())) {
            JournalEntry entry = new JournalEntry(1, 2, command, 9);
            assertThat(Codec.decodeEntry(Codec.encodeEntry(entry))).isEqualTo(entry);
        }
        List<Event> events = new ArrayList<>(List.of(new MemberLimitsChanged(1, 7, limits),
                new MemberSuspended(2, 7, 3), new MemberResumed(3, 7),
                new MassCancelled(6, 7, Side.SELL, 2), new MassCancelled(7, 7, null, 0),
                new OrderCancelled(8, 3, 4, CancelReason.MASS_CANCEL),
                new TradeExecuted(4, 2, 1, Side.BUY, 100, 5, 0, 0, 7, 8)));
        for (RejectReason reason : RejectReason.values()) {
            events.add(new MemberCommandRejected(5, 7, reason));
        }
        EventBatch batch = new EventBatch(1, 2, events, 9);
        assertThat(Codec.decodeBatch(Codec.encodeBatch(batch))).isEqualTo(batch);

        EngineSnapshot state = new EngineSnapshot(CommandStream.INSTRUMENT, 5, 9,
                List.of(new RestingOrder(1, Side.BUY, 100, 5, false, 0, 5, SelfTradeInstruction.NONE, false, 4)), 100,
                List.of(new ParkedStop(2, Side.SELL, 90, 89, 4, SelfTradeInstruction.NONE, 6)),
                TradingPhase.CONTINUOUS, 100,
                List.of(new MemberState(4, limits, true, -12), new MemberState(6, MemberLimits.NONE, false, 12)));
        Codec.StoredSnapshot snapshot = new Codec.StoredSnapshot(4, state);
        assertThat(Codec.decodeSnapshot(Codec.encodeSnapshot(snapshot))).isEqualTo(snapshot);
        assertThat(Codec.decodeSnapshot(Codec.encodeSnapshot(snapshot)).state().members()).hasSize(2);
        assertThatThrownBy(() -> new JournalEntry(1, 2, new SuspendMember(1), -1))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] entry = Codec.encodeEntry(new JournalEntry(1, 2, new SuspendMember(1), 1));
        assertThatThrownBy(() -> Codec.decodeEntry(withByte(entry, 16, 0xFF)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("negative member");
        byte[] snapshotBytes = Codec.encodeSnapshot(new Codec.StoredSnapshot(0, new MatchingEngine(
                CommandStream.INSTRUMENT).snapshot()));
        assertThatThrownBy(() -> Codec.decodeSnapshot(withByte(snapshotBytes, snapshotBytes.length - 4, 0xFF)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("negative member count");
    }

    @Test
    void everyTradingPhaseHasACode() throws IOException {
        for (TradingPhase phase : TradingPhase.values()) {
            JournalEntry entry = new JournalEntry(1, 1, new SetPhase(phase));
            assertThat(Codec.decodeEntry(Codec.encodeEntry(entry))).isEqualTo(entry);
            EngineSnapshot state = new EngineSnapshot(CommandStream.INSTRUMENT, 2, 3,
                    List.of(new RestingOrder(1, Side.BUY, 5, 3, false, 0, 3, SelfTradeInstruction.NONE, true)), 7,
                    List.of(), phase, 42);
            Codec.StoredSnapshot snapshot = new Codec.StoredSnapshot(1, state);
            assertThat(Codec.decodeSnapshot(Codec.encodeSnapshot(snapshot))).isEqualTo(snapshot);
        }
        byte[] entry = Codec.encodeEntry(new JournalEntry(1, 1, new SetPhase(TradingPhase.CALL)));
        for (int invalid : new int[] {0, 4, 99}) {
            assertThatThrownBy(() -> Codec.decodeEntry(withByte(entry, entry.length - 1, invalid)))
                    .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("trading phase");
        }
        byte[] unknownCommand = withByte(entry, 24, 99);
        assertThatThrownBy(() -> Codec.decodeEntry(unknownCommand))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("command type");
    }

    @Test
    void everySelfTradeInstructionHasACode() throws IOException {
        for (SelfTradeMode mode : SelfTradeMode.values()) {
            SelfTradeInstruction instruction = new SelfTradeInstruction(7, mode);
            for (Command command : List.<Command>of(
                    new LimitOrder(Side.BUY, 3, 4, TimeInForce.GTC, false, 2, instruction),
                    new MarketOrder(Side.SELL, 4, TimeInForce.FOK, instruction),
                    new StopOrder(Side.BUY, 5, 4, 3, instruction))) {
                JournalEntry entry = new JournalEntry(1, 1, command);
                assertThat(Codec.decodeEntry(Codec.encodeEntry(entry))).isEqualTo(entry);
            }
        }
        EngineSnapshot state = new EngineSnapshot(CommandStream.INSTRUMENT, 5, 9,
                List.of(new RestingOrder(1, Side.BUY, 100, 5, false, 2, 2,
                        new SelfTradeInstruction(3, SelfTradeMode.CANCEL_BOTH))),
                100, List.of(new ParkedStop(2, Side.SELL, 90, 89, 4,
                        new SelfTradeInstruction(1, SelfTradeMode.CANCEL_OLDEST))));
        Codec.StoredSnapshot snapshot = new Codec.StoredSnapshot(4, state);
        assertThat(Codec.decodeSnapshot(Codec.encodeSnapshot(snapshot))).isEqualTo(snapshot);
    }

    @Test
    void invalidSelfTradeModesAreCorruption() throws IOException {
        // limit: 25 bytes of header (sequence, timestamp, member, type), side 1, price 8, quantity 8, tif 1, post-only 1, display 8, group 8, mode 1
        byte[] limit = Codec.encodeEntry(new JournalEntry(1, 1, new LimitOrder(Side.BUY, 1, 1)));
        assertThat(limit).hasSize(61);
        for (int invalid : new int[] {0, 4, 99}) {
            assertThatThrownBy(() -> Codec.decodeEntry(withByte(limit, 60, invalid)))
                    .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("self-trade mode");
        }
    }

    @Test
    void invalidAttributesAreCorruption() throws IOException {
        // limit: header 25 bytes (sequence, timestamp, member, type), side 1, price 8, quantity 8, tif 1, post-only 1
        byte[] limit = Codec.encodeEntry(new JournalEntry(1, 1, new LimitOrder(Side.BUY, 1, 1)));
        assertThatThrownBy(() -> Codec.decodeEntry(withByte(limit, 42, 0)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("time in force");
        assertThatThrownBy(() -> Codec.decodeEntry(withByte(limit, 42, 5)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("time in force");
        assertThatThrownBy(() -> Codec.decodeEntry(withByte(limit, 43, 2)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("boolean");
        assertThatThrownBy(() -> Codec.decodeEntry(withByte(limit, 43, 0xFF)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("boolean");
    }

    @Test
    void versionEightIsReadAndEarlierVersionsAreRefused() throws IOException {
        byte[] valid = Codec.encodeHeader(new Codec.Header(Codec.Kind.COMMANDS, CommandStream.INSTRUMENT));
        assertThat(Codec.FORMAT_VERSION).isEqualTo((short) 9);
        assertThat(Codec.decodeHeader(withByte(valid, 5, 8))).as("version 9 only adds codes")
                .isEqualTo(new Codec.Header(Codec.Kind.COMMANDS, CommandStream.INSTRUMENT));
        assertCorrupt(withByte(valid, 5, 7), "version 7");
        assertCorrupt(withByte(valid, 5, 6), "version 6");
        assertCorrupt(withByte(valid, 5, 4), "version 4");
        assertCorrupt(withByte(valid, 5, 3), "version 3");
        assertCorrupt(withByte(valid, 5, 1), "version 1");
        assertCorrupt(withByte(valid, 5, 2), "version 2");
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
        assertCorrupt(withByte(valid, 5, 10), "version");
        assertCorrupt(withByte(valid, 6, 9), "kind");
        assertCorrupt(header(out -> {
            out.writeUTF("X");
            out.writeLong(0);
            out.writeLong(1);
            out.writeInt(1);
            out.writeLong(1);
            out.writeLong(1);
            out.writeLong(0);
        }), "instrument");
        assertCorrupt(header(out -> {
            out.writeUTF("X");
            out.writeLong(5);
            out.writeLong(5);
            out.writeInt(-1);
        }), "tick band count");
        assertCorrupt(header(out -> {
            out.writeUTF("X");
            out.writeLong(5);
            out.writeLong(5);
            out.writeInt(1);
            out.writeLong(0);
            out.writeLong(1);
            out.writeLong(0);
        }), "instrument");
        Codec.Header banded = new Codec.Header(Codec.Kind.EVENTS, new Instrument("B", 5, 500,
                List.of(new TickBand(1, 1), new TickBand(100, 5)), 250));
        assertThat(Codec.decodeHeader(Codec.encodeHeader(banded))).isEqualTo(banded);
    }

    @Test
    void invalidRecordsAreCorruption() throws IOException {
        byte[] entry = Codec.encodeEntry(new JournalEntry(1, 1, new CancelOrder(1)));
        assertThatThrownBy(() -> Codec.decodeEntry(withByte(entry, 24, 99)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("command type");
        assertThatThrownBy(() -> Codec.decodeEntry(Arrays.copyOf(entry, entry.length - 1)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("shorter");
        assertThatThrownBy(() -> Codec.decodeEntry(Arrays.copyOf(entry, entry.length + 1)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("trailing");

        byte[] limit = Codec.encodeEntry(new JournalEntry(1, 1,
                new LimitOrder(Side.BUY, 1, 1)));
        assertThatThrownBy(() -> Codec.decodeEntry(withByte(limit, 25, 7)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("side");

        byte[] batch = Codec.encodeBatch(new EventBatch(1, 1, List.of(
                new OrderRejected(1, 1, RejectReason.NO_LIQUIDITY))));
        int eventStart = 8 + 8 + 8 + 4;
        assertThatThrownBy(() -> Codec.decodeBatch(withByte(batch, eventStart, 99)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("event type");
        assertThatThrownBy(() -> Codec.decodeBatch(withByte(batch, eventStart + 17, 99)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("reject reason");
        assertThatThrownBy(() -> Codec.decodeBatch(withByte(batch, 24, 0xFF)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("negative");

        byte[] cancelled = Codec.encodeBatch(new EventBatch(1, 1, List.of(
                new OrderCancelled(1, 1, 1, CancelReason.CLIENT_REQUEST))));
        assertThatThrownBy(() -> Codec.decodeBatch(withByte(cancelled, eventStart + 25, 10)))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("cancel reason");
    }

    @Test
    void invalidSnapshotsAreCorruption() {
        MatchingEngine engine = new MatchingEngine(CommandStream.INSTRUMENT);
        byte[] snapshot = Codec.encodeSnapshot(new Codec.StoredSnapshot(0, engine.snapshot()));
        byte[] notSnapshot = Codec.encodeHeader(new Codec.Header(Codec.Kind.EVENTS, CommandStream.INSTRUMENT));

        assertThatThrownBy(() -> Codec.decodeSnapshot(notSnapshot))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("not a snapshot");
        byte[] negativeStops = withByte(snapshot, snapshot.length - 17, 0xFF);
        assertThatThrownBy(() -> Codec.decodeSnapshot(negativeStops))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("negative stop count");
        // the order count sits before the last trade price (8), the stop count (4), the phase (1) and the reference price (8) and the member count (4)
        byte[] negativeCount = withByte(snapshot, snapshot.length - 29, 0xFF);
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
