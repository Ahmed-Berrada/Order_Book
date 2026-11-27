package io.github.ahmedberrada.lob.journal;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.CancelReason;
import io.github.ahmedberrada.lob.core.CancelRejected;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.EngineSnapshot;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.AuctionPriceDetermined;
import io.github.ahmedberrada.lob.core.OrderAccepted;
import io.github.ahmedberrada.lob.core.OrderCancelled;
import io.github.ahmedberrada.lob.core.OrderParked;
import io.github.ahmedberrada.lob.core.PhaseChangeRejected;
import io.github.ahmedberrada.lob.core.PhaseChanged;
import io.github.ahmedberrada.lob.core.OrderRejected;
import io.github.ahmedberrada.lob.core.OrderReplaced;
import io.github.ahmedberrada.lob.core.OrderReplenished;
import io.github.ahmedberrada.lob.core.OrderRested;
import io.github.ahmedberrada.lob.core.ParkedStop;
import io.github.ahmedberrada.lob.core.RejectReason;
import io.github.ahmedberrada.lob.core.ReplaceOrder;
import io.github.ahmedberrada.lob.core.ReplaceRejected;
import io.github.ahmedberrada.lob.core.RestingOrder;
import io.github.ahmedberrada.lob.core.SelfTradeInstruction;
import io.github.ahmedberrada.lob.core.SetPhase;
import io.github.ahmedberrada.lob.core.SelfTradeMode;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.core.StopOrder;
import io.github.ahmedberrada.lob.core.StopTriggered;
import io.github.ahmedberrada.lob.core.TimeInForce;
import io.github.ahmedberrada.lob.core.TradingPhase;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary encoding of journal payloads, specified in {@code docs/interfaces/journal-format.md}.
 *
 * <p>Big-endian, as written by {@link DataOutputStream}. Enumerations use explicit codes, never
 * {@code ordinal()}, so that reordering a Java enum cannot silently change the meaning of old journals.
 */
final class Codec {

    static final int MAGIC = 0x4C4F424A;            // "LOBJ"
    static final short FORMAT_VERSION = 6;

    enum Kind {
        COMMANDS(1), EVENTS(2), SNAPSHOT(3);

        final byte code;

        Kind(int code) {
            this.code = (byte) code;
        }
    }

    record Header(Kind kind, Instrument instrument) {
    }

    record StoredSnapshot(long lastCommandSequence, EngineSnapshot state) {
    }

    private Codec() {
    }

    // ---- headers ----------------------------------------------------------------------------------

    static byte[] encodeHeader(Header header) {
        return encode(out -> writeHeader(out, header));
    }

    static Header decodeHeader(byte[] payload) throws JournalCorruptedException {
        return decode(payload, Codec::readHeader);
    }

    private static void writeHeader(DataOutputStream out, Header header) throws IOException {
        out.writeInt(MAGIC);
        out.writeShort(FORMAT_VERSION);
        out.writeByte(header.kind().code);
        out.writeUTF(header.instrument().symbol());
        out.writeLong(header.instrument().maxOrderQuantity());
        out.writeLong(header.instrument().maxPriceTicks());
    }

    private static Header readHeader(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new JournalCorruptedException("not a journal file: bad magic number");
        }
        short version = in.readShort();
        if (version != FORMAT_VERSION) {
            throw new JournalCorruptedException("unsupported format version " + version);
        }
        byte code = in.readByte();
        Kind kind = switch (code) {
            case 1 -> Kind.COMMANDS;
            case 2 -> Kind.EVENTS;
            case 3 -> Kind.SNAPSHOT;
            default -> throw new JournalCorruptedException("unknown file kind " + code);
        };
        Instrument instrument;
        try {
            instrument = new Instrument(in.readUTF(), in.readLong(), in.readLong());
        } catch (IllegalArgumentException e) {
            throw new JournalCorruptedException("invalid instrument in header: " + e.getMessage());
        }
        return new Header(kind, instrument);
    }

    // ---- commands ---------------------------------------------------------------------------------

    static byte[] encodeEntry(JournalEntry entry) {
        return encode(out -> {
            out.writeLong(entry.sequence());
            out.writeLong(entry.timestampMicros());
            switch (entry.command()) {
                case LimitOrder order -> {
                    out.writeByte(1);
                    out.writeByte(sideCode(order.side()));
                    out.writeLong(order.priceTicks());
                    out.writeLong(order.quantity());
                    out.writeByte(tifCode(order.timeInForce()));
                    out.writeBoolean(order.postOnly());
                    out.writeLong(order.displayQuantity());
                    writeSelfTrade(out, order.selfTrade());
                }
                case MarketOrder order -> {
                    out.writeByte(2);
                    out.writeByte(sideCode(order.side()));
                    out.writeLong(order.quantity());
                    out.writeByte(tifCode(order.timeInForce()));
                    writeSelfTrade(out, order.selfTrade());
                }
                case CancelOrder cancel -> {
                    out.writeByte(3);
                    out.writeLong(cancel.orderId());
                }
                case ReplaceOrder replace -> {
                    out.writeByte(4);
                    out.writeLong(replace.orderId());
                    out.writeLong(replace.priceTicks());
                    out.writeLong(replace.quantity());
                }
                case StopOrder stop -> {
                    out.writeByte(5);
                    out.writeByte(sideCode(stop.side()));
                    out.writeLong(stop.stopPriceTicks());
                    out.writeLong(stop.limitPriceTicks());
                    out.writeLong(stop.quantity());
                    writeSelfTrade(out, stop.selfTrade());
                }
                case SetPhase setPhase -> {
                    out.writeByte(6);
                    out.writeByte(phaseCode(setPhase.phase()));
                }
            }
        });
    }

    static JournalEntry decodeEntry(byte[] payload) throws JournalCorruptedException {
        return decode(payload, in -> {
            long sequence = in.readLong();
            long timestamp = in.readLong();
            byte type = in.readByte();
            Command command = switch (type) {
                case 1 -> new LimitOrder(readSide(in), in.readLong(), in.readLong(), readTif(in), readBoolean(in),
                        in.readLong(), readSelfTrade(in));
                case 2 -> new MarketOrder(readSide(in), in.readLong(), readTif(in), readSelfTrade(in));
                case 3 -> new CancelOrder(in.readLong());
                case 4 -> new ReplaceOrder(in.readLong(), in.readLong(), in.readLong());
                case 5 -> new StopOrder(readSide(in), in.readLong(), in.readLong(), in.readLong(), readSelfTrade(in));
                case 6 -> new SetPhase(readPhase(in));
                default -> throw new JournalCorruptedException("unknown command type " + type);
            };
            return new JournalEntry(sequence, timestamp, command);
        });
    }

    // ---- events -----------------------------------------------------------------------------------

    static byte[] encodeBatch(EventBatch batch) {
        return encode(out -> {
            out.writeLong(batch.commandSequence());
            out.writeLong(batch.timestampMicros());
            out.writeInt(batch.events().size());
            for (Event event : batch.events()) {
                writeEvent(out, event);
            }
        });
    }

    static EventBatch decodeBatch(byte[] payload) throws JournalCorruptedException {
        return decode(payload, in -> {
            long commandSequence = in.readLong();
            long timestamp = in.readLong();
            int count = in.readInt();
            if (count < 0) {
                throw new JournalCorruptedException("negative event count " + count);
            }
            List<Event> events = new ArrayList<>(Math.min(count, 64));
            for (int i = 0; i < count; i++) {
                events.add(readEvent(in));
            }
            return new EventBatch(commandSequence, timestamp, events);
        });
    }

    private static void writeEvent(DataOutputStream out, Event event) throws IOException {
        switch (event) {
            case OrderAccepted e -> {
                out.writeByte(1);
                out.writeLong(e.sequence());
                out.writeLong(e.orderId());
            }
            case OrderRejected e -> {
                out.writeByte(2);
                out.writeLong(e.sequence());
                out.writeLong(e.orderId());
                out.writeByte(rejectCode(e.reason()));
            }
            case TradeExecuted e -> {
                out.writeByte(3);
                out.writeLong(e.sequence());
                out.writeLong(e.takerOrderId());
                out.writeLong(e.makerOrderId());
                out.writeByte(sideCode(e.aggressorSide()));
                out.writeLong(e.priceTicks());
                out.writeLong(e.quantity());
                out.writeLong(e.takerRemainingQuantity());
                out.writeLong(e.makerRemainingQuantity());
            }
            case OrderRested e -> {
                out.writeByte(4);
                out.writeLong(e.sequence());
                out.writeLong(e.orderId());
                out.writeByte(sideCode(e.side()));
                out.writeLong(e.priceTicks());
                out.writeLong(e.quantity());
                out.writeLong(e.displayedQuantity());
            }
            case OrderCancelled e -> {
                out.writeByte(5);
                out.writeLong(e.sequence());
                out.writeLong(e.orderId());
                out.writeLong(e.cancelledQuantity());
                out.writeByte(cancelCode(e.reason()));
            }
            case CancelRejected e -> {
                out.writeByte(6);
                out.writeLong(e.sequence());
                out.writeLong(e.orderId());
                out.writeByte(rejectCode(e.reason()));
            }
            case OrderReplaced e -> {
                out.writeByte(7);
                out.writeLong(e.sequence());
                out.writeLong(e.orderId());
                out.writeByte(sideCode(e.side()));
                out.writeLong(e.priceTicks());
                out.writeLong(e.quantity());
                out.writeBoolean(e.priorityKept());
            }
            case ReplaceRejected e -> {
                out.writeByte(8);
                out.writeLong(e.sequence());
                out.writeLong(e.orderId());
                out.writeByte(rejectCode(e.reason()));
            }
            case OrderParked e -> {
                out.writeByte(10);
                out.writeLong(e.sequence());
                out.writeLong(e.orderId());
                out.writeByte(sideCode(e.side()));
                out.writeLong(e.stopPriceTicks());
                out.writeLong(e.quantity());
            }
            case PhaseChanged e -> {
                out.writeByte(12);
                out.writeLong(e.sequence());
                out.writeByte(phaseCode(e.from()));
                out.writeByte(phaseCode(e.to()));
            }
            case PhaseChangeRejected e -> {
                out.writeByte(13);
                out.writeLong(e.sequence());
                out.writeByte(rejectCode(e.reason()));
            }
            case AuctionPriceDetermined e -> {
                out.writeByte(14);
                out.writeLong(e.sequence());
                out.writeLong(e.priceTicks());
                out.writeLong(e.volume());
            }
            case StopTriggered e -> {
                out.writeByte(11);
                out.writeLong(e.sequence());
                out.writeLong(e.orderId());
            }
            case OrderReplenished e -> {
                out.writeByte(9);
                out.writeLong(e.sequence());
                out.writeLong(e.orderId());
                out.writeByte(sideCode(e.side()));
                out.writeLong(e.priceTicks());
                out.writeLong(e.displayedQuantity());
            }
        }
    }

    private static Event readEvent(DataInputStream in) throws IOException {
        byte type = in.readByte();
        return switch (type) {
            case 1 -> new OrderAccepted(in.readLong(), in.readLong());
            case 2 -> new OrderRejected(in.readLong(), in.readLong(), readRejectReason(in));
            case 3 -> new TradeExecuted(in.readLong(), in.readLong(), in.readLong(), readSide(in),
                    in.readLong(), in.readLong(), in.readLong(), in.readLong());
            case 4 -> new OrderRested(in.readLong(), in.readLong(), readSide(in), in.readLong(), in.readLong(),
                    in.readLong());
            case 5 -> new OrderCancelled(in.readLong(), in.readLong(), in.readLong(), readCancelReason(in));
            case 6 -> new CancelRejected(in.readLong(), in.readLong(), readRejectReason(in));
            case 7 -> new OrderReplaced(in.readLong(), in.readLong(), readSide(in), in.readLong(), in.readLong(),
                    readBoolean(in));
            case 8 -> new ReplaceRejected(in.readLong(), in.readLong(), readRejectReason(in));
            case 9 -> new OrderReplenished(in.readLong(), in.readLong(), readSide(in), in.readLong(), in.readLong());
            case 10 -> new OrderParked(in.readLong(), in.readLong(), readSide(in), in.readLong(), in.readLong());
            case 11 -> new StopTriggered(in.readLong(), in.readLong());
            case 12 -> new PhaseChanged(in.readLong(), readPhase(in), readPhase(in));
            case 13 -> new PhaseChangeRejected(in.readLong(), readRejectReason(in));
            case 14 -> new AuctionPriceDetermined(in.readLong(), in.readLong(), in.readLong());
            default -> throw new JournalCorruptedException("unknown event type " + type);
        };
    }

    // ---- snapshots --------------------------------------------------------------------------------

    static byte[] encodeSnapshot(StoredSnapshot snapshot) {
        return encode(out -> {
            EngineSnapshot state = snapshot.state();
            writeHeader(out, new Header(Kind.SNAPSHOT, state.instrument()));
            out.writeLong(snapshot.lastCommandSequence());
            out.writeLong(state.nextOrderId());
            out.writeLong(state.nextSequence());
            out.writeInt(state.restingOrders().size());
            for (RestingOrder order : state.restingOrders()) {
                out.writeLong(order.orderId());
                out.writeByte(sideCode(order.side()));
                out.writeLong(order.priceTicks());
                out.writeLong(order.remainingQuantity());
                out.writeBoolean(order.postOnly());
                out.writeLong(order.displayQuantity());
                out.writeLong(order.displayedQuantity());
                writeSelfTrade(out, order.selfTrade());
            }
            out.writeLong(state.lastTradePriceTicks());
            out.writeInt(state.parkedStops().size());
            for (ParkedStop stop : state.parkedStops()) {
                out.writeLong(stop.orderId());
                out.writeByte(sideCode(stop.side()));
                out.writeLong(stop.stopPriceTicks());
                out.writeLong(stop.limitPriceTicks());
                out.writeLong(stop.quantity());
                writeSelfTrade(out, stop.selfTrade());
            }
            out.writeByte(phaseCode(state.phase()));
        });
    }

    static StoredSnapshot decodeSnapshot(byte[] payload) throws JournalCorruptedException {
        return decode(payload, in -> {
            Header header = readHeader(in);
            if (header.kind() != Kind.SNAPSHOT) {
                throw new JournalCorruptedException("not a snapshot: " + header.kind());
            }
            long lastCommandSequence = in.readLong();
            long nextOrderId = in.readLong();
            long nextSequence = in.readLong();
            int count = in.readInt();
            if (count < 0) {
                throw new JournalCorruptedException("negative order count " + count);
            }
            List<RestingOrder> orders = new ArrayList<>(Math.min(count, 1 << 16));
            for (int i = 0; i < count; i++) {
                orders.add(new RestingOrder(in.readLong(), readSide(in), in.readLong(), in.readLong(), readBoolean(in),
                        in.readLong(), in.readLong(), readSelfTrade(in)));
            }
            long lastTradePrice = in.readLong();
            int stopCount = in.readInt();
            if (stopCount < 0) {
                throw new JournalCorruptedException("negative stop count " + stopCount);
            }
            List<ParkedStop> stops = new ArrayList<>(Math.min(stopCount, 1 << 16));
            for (int i = 0; i < stopCount; i++) {
                stops.add(new ParkedStop(in.readLong(), readSide(in), in.readLong(), in.readLong(), in.readLong(),
                        readSelfTrade(in)));
            }
            TradingPhase phase = readPhase(in);
            return new StoredSnapshot(lastCommandSequence, new EngineSnapshot(header.instrument(), nextOrderId,
                    nextSequence, orders, lastTradePrice, stops, phase));
        });
    }

    // ---- enumeration codes ------------------------------------------------------------------------

    private static int sideCode(Side side) {
        return switch (side) {
            case BUY -> 1;
            case SELL -> 2;
        };
    }

    private static Side readSide(DataInputStream in) throws IOException {
        byte code = in.readByte();
        return switch (code) {
            case 1 -> Side.BUY;
            case 2 -> Side.SELL;
            default -> throw new JournalCorruptedException("unknown side " + code);
        };
    }

    private static int phaseCode(TradingPhase phase) {
        return switch (phase) {
            case CONTINUOUS -> 1;
            case CALL -> 2;
            case CLOSED -> 3;
        };
    }

    private static TradingPhase readPhase(DataInputStream in) throws IOException {
        byte code = in.readByte();
        return switch (code) {
            case 1 -> TradingPhase.CONTINUOUS;
            case 2 -> TradingPhase.CALL;
            case 3 -> TradingPhase.CLOSED;
            default -> throw new JournalCorruptedException("unknown trading phase " + code);
        };
    }

    private static int rejectCode(RejectReason reason) {
        return switch (reason) {
            case INVALID_QUANTITY -> 1;
            case QUANTITY_ABOVE_MAXIMUM -> 2;
            case INVALID_PRICE -> 3;
            case PRICE_ABOVE_MAXIMUM -> 4;
            case NO_LIQUIDITY -> 5;
            case UNKNOWN_ORDER -> 6;
            case INVALID_TIME_IN_FORCE -> 7;
            case POST_ONLY_WOULD_TRADE -> 8;
            case INVALID_DISPLAY_QUANTITY -> 9;
            case STOP_PRICE_ALREADY_REACHED -> 10;
            case INVALID_SELF_TRADE_GROUP -> 11;
            case NOT_ALLOWED_IN_PHASE -> 12;
            case INVALID_PHASE_TRANSITION -> 13;
        };
    }

    private static RejectReason readRejectReason(DataInputStream in) throws IOException {
        byte code = in.readByte();
        return switch (code) {
            case 1 -> RejectReason.INVALID_QUANTITY;
            case 2 -> RejectReason.QUANTITY_ABOVE_MAXIMUM;
            case 3 -> RejectReason.INVALID_PRICE;
            case 4 -> RejectReason.PRICE_ABOVE_MAXIMUM;
            case 5 -> RejectReason.NO_LIQUIDITY;
            case 6 -> RejectReason.UNKNOWN_ORDER;
            case 7 -> RejectReason.INVALID_TIME_IN_FORCE;
            case 8 -> RejectReason.POST_ONLY_WOULD_TRADE;
            case 9 -> RejectReason.INVALID_DISPLAY_QUANTITY;
            case 10 -> RejectReason.STOP_PRICE_ALREADY_REACHED;
            case 11 -> RejectReason.INVALID_SELF_TRADE_GROUP;
            case 12 -> RejectReason.NOT_ALLOWED_IN_PHASE;
            case 13 -> RejectReason.INVALID_PHASE_TRANSITION;
            default -> throw new JournalCorruptedException("unknown reject reason " + code);
        };
    }

    private static int cancelCode(CancelReason reason) {
        return switch (reason) {
            case CLIENT_REQUEST -> 1;
            case UNFILLED_MARKET_REMAINDER -> 2;
            case IMMEDIATE_OR_CANCEL -> 3;
            case FILL_OR_KILL -> 4;
            case SELF_TRADE_PREVENTION -> 5;
        };
    }

    private static CancelReason readCancelReason(DataInputStream in) throws IOException {
        byte code = in.readByte();
        return switch (code) {
            case 1 -> CancelReason.CLIENT_REQUEST;
            case 2 -> CancelReason.UNFILLED_MARKET_REMAINDER;
            case 3 -> CancelReason.IMMEDIATE_OR_CANCEL;
            case 4 -> CancelReason.FILL_OR_KILL;
            case 5 -> CancelReason.SELF_TRADE_PREVENTION;
            default -> throw new JournalCorruptedException("unknown cancel reason " + code);
        };
    }

    private static int tifCode(TimeInForce timeInForce) {
        return switch (timeInForce) {
            case GTC -> 1;
            case IOC -> 2;
            case FOK -> 3;
        };
    }

    private static TimeInForce readTif(DataInputStream in) throws IOException {
        byte code = in.readByte();
        return switch (code) {
            case 1 -> TimeInForce.GTC;
            case 2 -> TimeInForce.IOC;
            case 3 -> TimeInForce.FOK;
            default -> throw new JournalCorruptedException("unknown time in force " + code);
        };
    }

    private static void writeSelfTrade(DataOutputStream out, SelfTradeInstruction instruction) throws IOException {
        out.writeLong(instruction.group());
        out.writeByte(selfTradeModeCode(instruction.mode()));
    }

    private static SelfTradeInstruction readSelfTrade(DataInputStream in) throws IOException {
        long group = in.readLong();
        byte code = in.readByte();
        SelfTradeMode mode = switch (code) {
            case 1 -> SelfTradeMode.CANCEL_NEWEST;
            case 2 -> SelfTradeMode.CANCEL_OLDEST;
            case 3 -> SelfTradeMode.CANCEL_BOTH;
            default -> throw new JournalCorruptedException("unknown self-trade mode " + code);
        };
        return new SelfTradeInstruction(group, mode);
    }

    private static int selfTradeModeCode(SelfTradeMode mode) {
        return switch (mode) {
            case CANCEL_NEWEST -> 1;
            case CANCEL_OLDEST -> 2;
            case CANCEL_BOTH -> 3;
        };
    }

    /** A boolean is one byte, 0 or 1; anything else is corruption. */
    private static boolean readBoolean(DataInputStream in) throws IOException {
        byte code = in.readByte();
        return switch (code) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new JournalCorruptedException("invalid boolean " + code);
        };
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(DataInputStream in) throws IOException;
    }

    private static byte[] encode(Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException("in-memory encoding cannot fail", e);
        }
        return bytes.toByteArray();
    }

    /** Decodes a whole payload: a short payload, an invalid value or trailing bytes are corruption. */
    private static <T> T decode(byte[] payload, Reader<T> reader) throws JournalCorruptedException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            T value = reader.read(in);
            if (in.available() > 0) {
                throw new JournalCorruptedException(in.available() + " unexpected trailing bytes");
            }
            return value;
        } catch (JournalCorruptedException e) {
            throw e;
        } catch (EOFException e) {
            throw new JournalCorruptedException("record shorter than its type requires");
        } catch (IOException e) {
            throw new JournalCorruptedException("unreadable record: " + e.getMessage());
        }
    }
}
