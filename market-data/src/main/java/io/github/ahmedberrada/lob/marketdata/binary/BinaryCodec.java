package io.github.ahmedberrada.lob.marketdata.binary;

import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.Directory;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.Level;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.SnapshotEnd;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.Trade;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.UpdateEnd;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Encodes and decodes messages and packets (docs/interfaces/market-data-binary.md). Big-endian. */
public final class BinaryCodec {

    static final int HEADER_BYTES = 20;
    static final int SESSION_BYTES = 10;
    static final int SYMBOL_BYTES = 8;
    /** Message count of a refused retransmission. */
    static final int REFUSED = 0xFFFF;

    /** A decoded packet: its messages carry sequence numbers {@code sequence}, {@code sequence + 1}… */
    public record Packet(String session, long sequence, int count, List<BinaryMessage> messages) {

        public Packet {
            messages = List.copyOf(messages);
        }

        public boolean isHeartbeat() {
            return count == 0;
        }

        public boolean isRefused() {
            return count == REFUSED;
        }
    }

    private BinaryCodec() {
    }

    public static byte[] encode(BinaryMessage message) {
        ByteBuffer buffer = ByteBuffer.allocate(40);
        switch (message) {
            case Directory m -> {
                put(buffer, 'R', m.locate());
                buffer.put(padded(m.symbol(), SYMBOL_BYTES)).putLong(m.tickMantissa()).put(m.tickExponent());
            }
            case Level m -> {
                put(buffer, 'L', m.locate());
                buffer.put(side(m.side())).putLong(m.priceTicks()).putLong(m.quantity()).putInt(m.orders());
            }
            case Trade m -> {
                put(buffer, 'T', m.locate());
                buffer.put(side(m.aggressor())).putLong(m.priceTicks()).putLong(m.quantity());
            }
            case UpdateEnd m -> {
                put(buffer, 'U', m.locate());
                buffer.putLong(m.feedSequence()).putLong(m.commandSequence()).putLong(m.timestampMicros());
            }
            case SnapshotEnd m -> {
                put(buffer, 'K', m.locate());
                buffer.putLong(m.feedSequence()).putLong(m.commandSequence());
            }
        }
        byte[] bytes = new byte[buffer.position()];
        buffer.flip().get(bytes);
        return bytes;
    }

    /** A packet from already-encoded messages, as sent on the stream or in a TCP response. */
    public static byte[] packet(String session, long sequence, int count, List<byte[]> messages) {
        int size = HEADER_BYTES + messages.stream().mapToInt(m -> 2 + m.length).sum();
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.put(padded(session, SESSION_BYTES)).putLong(sequence).putShort((short) count);
        for (byte[] message : messages) {
            buffer.putShort((short) message.length).put(message);
        }
        return buffer.array();
    }

    /**
     * @throws IllegalArgumentException if the bytes are not a complete, well-formed packet
     */
    public static Packet decodePacket(ByteBuffer buffer) {
        try {
            byte[] session = new byte[SESSION_BYTES];
            buffer.get(session);
            long sequence = buffer.getLong();
            int count = Short.toUnsignedInt(buffer.getShort());
            List<BinaryMessage> messages = new ArrayList<>();
            int blocks = count == REFUSED ? 0 : count;
            for (int i = 0; i < blocks; i++) {
                int length = Short.toUnsignedInt(buffer.getShort());
                ByteBuffer message = buffer.slice(buffer.position(), length);
                buffer.position(buffer.position() + length);
                messages.add(decode(message));
                if (message.hasRemaining()) {
                    throw new IllegalArgumentException("message longer than its type");
                }
            }
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException(buffer.remaining() + " trailing bytes");
            }
            return new Packet(new String(session, StandardCharsets.US_ASCII).strip(), sequence, count, messages);
        } catch (BufferUnderflowException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("truncated packet", e);
        }
    }

    static BinaryMessage decode(ByteBuffer buffer) {
        char type = (char) buffer.get();
        int locate = Short.toUnsignedInt(buffer.getShort());
        return switch (type) {
            case 'R' -> {
                byte[] symbol = new byte[SYMBOL_BYTES];
                buffer.get(symbol);
                yield new Directory(locate, new String(symbol, StandardCharsets.US_ASCII).strip(), buffer.getLong(),
                        buffer.get());
            }
            case 'L' -> new Level(locate, side(buffer.get()), buffer.getLong(), buffer.getLong(), buffer.getInt());
            case 'T' -> new Trade(locate, side(buffer.get()), buffer.getLong(), buffer.getLong());
            case 'U' -> new UpdateEnd(locate, buffer.getLong(), buffer.getLong(), buffer.getLong());
            case 'K' -> new SnapshotEnd(locate, buffer.getLong(), buffer.getLong());
            default -> throw new IllegalArgumentException("unknown message type " + type);
        };
    }

    private static void put(ByteBuffer buffer, char type, int locate) {
        buffer.put((byte) type).putShort((short) locate);
    }

    private static byte side(Side side) {
        return (byte) (side == Side.BUY ? 'B' : 'S');
    }

    private static Side side(byte code) {
        return switch (code) {
            case 'B' -> Side.BUY;
            case 'S' -> Side.SELL;
            default -> throw new IllegalArgumentException("unknown side " + (char) code);
        };
    }

    private static byte[] padded(String text, int length) {
        byte[] ascii = text.getBytes(StandardCharsets.US_ASCII);
        if (ascii.length > length) {
            throw new IllegalArgumentException("longer than " + length + " bytes: " + text);
        }
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) ' ');
        System.arraycopy(ascii, 0, bytes, 0, ascii.length);
        return bytes;
    }
}
