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
import io.github.ahmedberrada.lob.core.OrderAccepted;
import io.github.ahmedberrada.lob.core.OrderCancelled;
import io.github.ahmedberrada.lob.core.OrderRejected;
import io.github.ahmedberrada.lob.core.OrderRested;
import io.github.ahmedberrada.lob.core.RejectReason;
import io.github.ahmedberrada.lob.core.RestingOrder;
import io.github.ahmedberrada.lob.core.Side;
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
    static final short FORMAT_VERSION = 1;

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
                }
                case MarketOrder order -> {
                    out.writeByte(2);
                    out.writeByte(sideCode(order.side()));
                    out.writeLong(order.quantity());
                }
                case CancelOrder cancel -> {
                    out.writeByte(3);
                    out.writeLong(cancel.orderId());
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
                case 1 -> new LimitOrder(readSide(in), in.readLong(), in.readLong());
                case 2 -> new MarketOrder(readSide(in), in.readLong());
                case 3 -> new CancelOrder(in.readLong());
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
        }
    }

    private static Event readEvent(DataInputStream in) throws IOException {
        byte type = in.readByte();
        return switch (type) {
            case 1 -> new OrderAccepted(in.readLong(), in.readLong());
            case 2 -> new OrderRejected(in.readLong(), in.readLong(), readRejectReason(in));
            case 3 -> new TradeExecuted(in.readLong(), in.readLong(), in.readLong(), readSide(in),
                    in.readLong(), in.readLong(), in.readLong(), in.readLong());
            case 4 -> new OrderRested(in.readLong(), in.readLong(), readSide(in), in.readLong(), in.readLong());
            case 5 -> new OrderCancelled(in.readLong(), in.readLong(), in.readLong(), readCancelReason(in));
            case 6 -> new CancelRejected(in.readLong(), in.readLong(), readRejectReason(in));
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
            }
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
                orders.add(new RestingOrder(in.readLong(), readSide(in), in.readLong(), in.readLong()));
            }
            return new StoredSnapshot(lastCommandSequence,
                    new EngineSnapshot(header.instrument(), nextOrderId, nextSequence, orders));
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

    private static int rejectCode(RejectReason reason) {
        return switch (reason) {
            case INVALID_QUANTITY -> 1;
            case QUANTITY_ABOVE_MAXIMUM -> 2;
            case INVALID_PRICE -> 3;
            case PRICE_ABOVE_MAXIMUM -> 4;
            case NO_LIQUIDITY -> 5;
            case UNKNOWN_ORDER -> 6;
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
            default -> throw new JournalCorruptedException("unknown reject reason " + code);
        };
    }

    private static int cancelCode(CancelReason reason) {
        return switch (reason) {
            case CLIENT_REQUEST -> 1;
            case UNFILLED_MARKET_REMAINDER -> 2;
        };
    }

    private static CancelReason readCancelReason(DataInputStream in) throws IOException {
        byte code = in.readByte();
        return switch (code) {
            case 1 -> CancelReason.CLIENT_REQUEST;
            case 2 -> CancelReason.UNFILLED_MARKET_REMAINDER;
            default -> throw new JournalCorruptedException("unknown cancel reason " + code);
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
