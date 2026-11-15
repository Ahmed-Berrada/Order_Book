package io.github.ahmedberrada.lob.marketdata.binary;

import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.marketdata.BookLevel;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryCodec.Packet;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.Directory;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.Level;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.SnapshotEnd;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.Trade;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.UpdateEnd;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * A reference consumer of the binary feed, following the recovery procedure of the specification:
 * snapshot, then the stream; retransmission on a gap in the stream sequence; a new snapshot if a
 * retransmission is refused or an instrument's feed sequence jumps. Single-threaded: call
 * {@link #poll} in a loop.
 */
public final class BinaryFeedClient implements AutoCloseable {

    /** One instrument's book as rebuilt from the feed. */
    private static final class Book {
        String symbol;
        final NavigableMap<Long, BookLevel> bids = new TreeMap<>(Comparator.reverseOrder());
        final NavigableMap<Long, BookLevel> asks = new TreeMap<>();
        final List<Level> pendingLevels = new ArrayList<>();
        final List<Trade> trades = new ArrayList<>();
        long feedSequence = -1;                                     // -1: no snapshot yet
    }

    static final int RECEIVE_BUFFER_BYTES = 4 << 20;

    private final DatagramSocket socket;
    private final InetSocketAddress service;
    private final Map<Integer, Book> books = new HashMap<>();
    private final Map<String, Integer> locates = new HashMap<>();
    private final byte[] buffer = new byte[65_536];
    private Predicate<Packet> drop = packet -> false;
    private String session;
    private long expected;                                          // next stream sequence, 0 before the first packet
    private int retransmissions;
    private int snapshots;
    private long trades;

    /** Listens for the stream on {@code streamAddress} and uses {@code service} for recovery. */
    public BinaryFeedClient(InetSocketAddress streamAddress, InetSocketAddress service) throws IOException {
        this.socket = new DatagramSocket(streamAddress);
        this.socket.setReceiveBufferSize(RECEIVE_BUFFER_BYTES);     // bursts outrun a default buffer
        this.service = service;
    }

    /** Takes the first snapshot. Packets that arrive meanwhile wait in the socket buffer. */
    public BinaryFeedClient start() throws IOException {
        snapshot();
        return this;
    }

    /** For tests: packets the predicate accepts are thrown away, as a lossy network would. */
    public void dropPackets(Predicate<Packet> drop) {
        this.drop = drop;
    }

    /** Receives and applies one packet; returns false if none arrived within the timeout. */
    public boolean poll(int timeoutMillis) throws IOException {
        socket.setSoTimeout(timeoutMillis);
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(datagram);
        } catch (SocketTimeoutException e) {
            return false;
        }
        Packet packet = BinaryCodec.decodePacket(ByteBuffer.wrap(buffer, 0, datagram.getLength()));
        if (!drop.test(packet)) {
            onPacket(packet);
        }
        return true;
    }

    private void onPacket(Packet packet) throws IOException {
        if (session == null) {
            session = packet.session();
        } else if (!session.equals(packet.session())) {
            session = packet.session();                             // the venue restarted: start over
            expected = 0;
            snapshot();
        }
        if (expected == 0) {
            expected = packet.sequence();
        }
        if (packet.sequence() > expected) {
            recover(expected, packet.sequence() - 1);
        }
        for (int i = 0; i < packet.messages().size(); i++) {
            long sequence = packet.sequence() + i;
            if (sequence >= expected) {
                apply(packet.messages().get(i));
                expected = sequence + 1;
            }
        }
    }

    /** Fetches messages {@code from} to {@code to} again; a refusal means a new snapshot (BF-004). */
    private void recover(long from, long to) throws IOException {
        while (from <= to) {
            int count = (int) Math.min(BinaryFeed.MAX_RETRANSMIT, to - from + 1);
            ByteBuffer request = ByteBuffer.allocate(11).put((byte) 'X').putLong(from).putShort((short) count);
            Packet packet = request(request.array());
            retransmissions++;
            if (packet.isRefused()) {
                snapshot();
                expected = to + 1;
                return;
            }
            packet.messages().forEach(this::apply);
            from += count;
        }
        expected = to + 1;
    }

    private void snapshot() throws IOException {
        Packet packet = request(new byte[] {'S'});
        snapshots++;
        for (BinaryMessage message : packet.messages()) {
            Book book = books.computeIfAbsent(message.locate(), l -> new Book());
            switch (message) {
                case Directory d -> {
                    book.symbol = d.symbol();
                    book.bids.clear();
                    book.asks.clear();
                    locates.put(d.symbol(), d.locate());
                }
                case Level l -> side(book, l.side()).put(l.priceTicks(), new BookLevel(l.priceTicks(), l.quantity(), l.orders()));
                case SnapshotEnd k -> book.feedSequence = k.feedSequence();
                default -> throw new IOException("unexpected message in a snapshot: " + message);
            }
        }
    }

    private void apply(BinaryMessage message) {
        Book book = books.computeIfAbsent(message.locate(), l -> new Book());
        switch (message) {
            case Directory d -> {
                book.symbol = d.symbol();
                locates.put(d.symbol(), d.locate());
            }
            case Level l -> book.pendingLevels.add(l);
            case Trade t -> book.trades.add(t);
            case UpdateEnd u -> endOfUpdate(book, u);
            case SnapshotEnd k -> {
                // only in snapshots
            }
        }
    }

    /** Applies the update atomically (BF-003), unless the snapshot already had it or a gap shows (MD-005). */
    private void endOfUpdate(Book book, UpdateEnd end) {
        try {
            if (book.feedSequence < 0 || end.feedSequence() <= book.feedSequence) {
                return;
            }
            if (end.feedSequence() != book.feedSequence + 1) {
                snapshot();                                         // the instrument's updates were lost
                return;
            }
            for (Level level : book.pendingLevels) {
                NavigableMap<Long, BookLevel> side = side(book, level.side());
                if (level.quantity() == 0) {
                    side.remove(level.priceTicks());
                } else {
                    side.put(level.priceTicks(), new BookLevel(level.priceTicks(), level.quantity(), level.orders()));
                }
            }
            trades += book.trades.size();
            book.feedSequence = end.feedSequence();
        } catch (IOException e) {
            book.feedSequence = -1;                                 // retried on the next snapshot
        } finally {
            book.pendingLevels.clear();
            book.trades.clear();
        }
    }

    private Packet request(byte[] payload) throws IOException {
        try (Socket connection = new Socket(service.getAddress(), service.getPort());
                DataOutputStream out = new DataOutputStream(connection.getOutputStream());
                DataInputStream in = new DataInputStream(connection.getInputStream())) {
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
            byte[] response = new byte[in.readInt()];
            in.readFully(response);
            return BinaryCodec.decodePacket(ByteBuffer.wrap(response));
        }
    }

    private static NavigableMap<Long, BookLevel> side(Book book, Side side) {
        return side == Side.BUY ? book.bids : book.asks;
    }

    public List<BookLevel> bids(String symbol) {
        return book(symbol).map(b -> List.copyOf(b.bids.values())).orElse(List.of());
    }

    public List<BookLevel> asks(String symbol) {
        return book(symbol).map(b -> List.copyOf(b.asks.values())).orElse(List.of());
    }

    /** The instrument's last applied feed sequence, or -1. */
    public long feedSequence(String symbol) {
        return book(symbol).map(b -> b.feedSequence).orElse(-1L);
    }

    public int retransmissions() {
        return retransmissions;
    }

    public int snapshots() {
        return snapshots;
    }

    public long tradesSeen() {
        return trades;
    }

    private Optional<Book> book(String symbol) {
        return Optional.ofNullable(locates.get(symbol)).map(books::get);
    }

    @Override
    public void close() {
        socket.close();
    }
}
