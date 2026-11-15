package io.github.ahmedberrada.lob.marketdata.binary;

import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.marketdata.BookLevel;
import io.github.ahmedberrada.lob.marketdata.MarketDataMessage;
import io.github.ahmedberrada.lob.marketdata.MarketDataMessage.Snapshot;
import io.github.ahmedberrada.lob.marketdata.MarketDataMessage.Update;
import io.github.ahmedberrada.lob.marketdata.MarketDataPublisher;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.Directory;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.Level;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.SnapshotEnd;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.Trade;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.UpdateEnd;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The binary feed (ADR-0008): subscribes to every instrument, packs their updates into sequenced
 * packets on one stream, keeps the messages for retransmission, and serves retransmissions and
 * snapshots over TCP.
 *
 * <p>The instruments only ever offer updates to a bounded queue (MD-008). If an instrument's updates
 * overflow it, that instrument is resubscribed: consumers see a jump in its feed sequence and take a
 * snapshot, so a loss is always visible (MD-005).
 */
public final class BinaryFeed implements AutoCloseable {

    /** Packets are kept under this size unless a single update is larger (it is never split, BF-003). */
    static final int MAX_PACKET_BYTES = 1_400;
    static final int MAX_RETRANSMIT = 1_000;

    private record Item(int locate, Update update) {
    }

    private final MarketDataPublisher publisher;
    private final List<InstrumentConfig> instruments;
    private final BinaryFeedConfig config;
    private final DatagramChannel channel;
    private final ServerSocket server;
    private final MessageRing ring;
    private final BlockingQueue<Item> queue;
    private final AtomicBoolean[] overflowed;
    private final MarketDataPublisher.Subscription[] subscriptions;
    private final Thread sender;
    private final Thread acceptor;
    private volatile boolean running = true;
    private long nextSequence = 1;                                  // sender thread only

    private BinaryFeed(MarketDataPublisher publisher, List<InstrumentConfig> instruments, BinaryFeedConfig config)
            throws IOException {
        this.publisher = publisher;
        this.instruments = List.copyOf(instruments);
        this.config = config;
        this.ring = new MessageRing(config.retention());
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity());
        this.overflowed = new AtomicBoolean[instruments.size() + 1];
        this.subscriptions = new MarketDataPublisher.Subscription[instruments.size() + 1];
        this.channel = DatagramChannel.open();
        this.server = new ServerSocket();
        server.bind(config.serviceAddress());
        for (int locate = 1; locate <= instruments.size(); locate++) {
            overflowed[locate] = new AtomicBoolean();
            subscribe(locate);
        }
        this.sender = Thread.ofPlatform().name("binary-feed-sender").start(this::send);
        this.acceptor = Thread.ofVirtual().name("binary-feed-service").start(this::accept);
    }

    /** Starts the stream and the TCP service; instruments get locate codes 1, 2… in the given order. */
    public static BinaryFeed start(MarketDataPublisher publisher, List<InstrumentConfig> instruments,
            BinaryFeedConfig config) throws IOException {
        return new BinaryFeed(publisher, instruments, config);
    }

    /** The port the TCP retransmission and snapshot service listens on. */
    public int servicePort() {
        return server.getLocalPort();
    }

    private void subscribe(int locate) {
        String symbol = instruments.get(locate - 1).symbol();
        subscriptions[locate] = publisher.subscribe(symbol, message -> {
            if (message instanceof Update update && !queue.offer(new Item(locate, update))) {
                overflowed[locate].set(true);                        // resubscribed by the sender
                return false;
            }
            return true;                                             // snapshots come from the TCP service
        });
    }

    // ---- stream -----------------------------------------------------------------------------------

    private void send() {
        List<byte[]> packet = new ArrayList<>();
        List<byte[]> directory = new ArrayList<>();
        for (int locate = 1; locate <= instruments.size(); locate++) {
            directory.add(BinaryCodec.encode(directory(locate)));
        }
        long first = append(packet, directory);
        flush(packet, first);
        while (running) {
            try {
                Item item = queue.poll(config.heartbeat().toMillis(), TimeUnit.MILLISECONDS);
                resubscribeOverflowed();
                if (item == null) {
                    transmit(BinaryCodec.packet(config.session(), nextSequence, 0, List.of()));       // heartbeat
                    continue;
                }
                int size = BinaryCodec.HEADER_BYTES;
                first = nextSequence;
                while (item != null) {
                    List<byte[]> messages = encode(item);
                    int bytes = messages.stream().mapToInt(m -> 2 + m.length).sum();
                    if (!packet.isEmpty() && size + bytes > MAX_PACKET_BYTES) {
                        flush(packet, first);
                        size = BinaryCodec.HEADER_BYTES;
                        first = nextSequence;
                    }
                    append(packet, messages);
                    size += bytes;
                    item = queue.poll();
                }
                flush(packet, first);
            } catch (InterruptedException e) {
                running = false;
            }
        }
    }

    /** Assigns stream sequence numbers and keeps the messages for retransmission; returns the first. */
    private long append(List<byte[]> packet, List<byte[]> messages) {
        long first = nextSequence;
        for (byte[] message : messages) {
            ring.put(nextSequence++, message);
            packet.add(message);
        }
        return first;
    }

    private void flush(List<byte[]> packet, long first) {
        if (!packet.isEmpty()) {
            transmit(BinaryCodec.packet(config.session(), first, packet.size(), packet));
            packet.clear();
        }
    }

    private void transmit(byte[] packet) {
        try {
            channel.send(ByteBuffer.wrap(packet), config.streamTarget());
        } catch (IOException e) {
            // UDP is lossy by design: consumers recover by retransmission (BF-004)
        }
    }

    private void resubscribeOverflowed() {
        for (int locate = 1; locate <= instruments.size(); locate++) {
            if (overflowed[locate].getAndSet(false)) {
                subscriptions[locate].cancel();
                subscribe(locate);
            }
        }
    }

    private static List<byte[]> encode(Item item) {
        Update update = item.update();
        List<byte[]> messages = new ArrayList<>();
        update.bids().forEach(l -> messages.add(BinaryCodec.encode(level(item.locate(), Side.BUY, l))));
        update.asks().forEach(l -> messages.add(BinaryCodec.encode(level(item.locate(), Side.SELL, l))));
        update.trades().forEach(t -> messages.add(BinaryCodec.encode(
                new Trade(item.locate(), t.aggressor(), t.priceTicks(), t.quantity()))));
        messages.add(BinaryCodec.encode(new UpdateEnd(item.locate(), update.feedSequence(), update.commandSequence(),
                update.timestampMicros())));
        return messages;
    }

    private static Level level(int locate, Side side, BookLevel level) {
        return new Level(locate, side, level.priceTicks(), level.quantity(), level.orders());
    }

    private Directory directory(int locate) {
        InstrumentConfig instrument = instruments.get(locate - 1);
        return new Directory(locate, instrument.symbol(), instrument.tickSize().unscaledValue().longValueExact(),
                (byte) -instrument.tickSize().scale());
    }

    // ---- TCP service ------------------------------------------------------------------------------

    private void accept() {
        while (running) {
            try {
                Socket socket = server.accept();
                Thread.ofVirtual().name("binary-feed-client").start(() -> serve(socket));
            } catch (IOException e) {
                return;                                              // closed
            }
        }
    }

    private void serve(Socket socket) {
        try (socket; DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
            while (true) {
                byte[] request = new byte[in.readInt()];
                in.readFully(request);
                ByteBuffer buffer = ByteBuffer.wrap(request);
                byte[] response = switch ((char) buffer.get()) {
                    case 'X' -> retransmit(buffer.getLong(), Short.toUnsignedInt(buffer.getShort()));
                    case 'S' -> snapshot();
                    default -> throw new IOException("unknown request");
                };
                out.writeInt(response.length);
                out.write(response);
                out.flush();
            }
        } catch (IOException | RuntimeException e) {
            // client disconnected or sent garbage: drop the connection
        }
    }

    private byte[] retransmit(long from, int count) {
        List<byte[]> messages = count > MAX_RETRANSMIT ? null : ring.get(from, count);
        if (messages == null) {
            return BinaryCodec.packet(config.session(), from, BinaryCodec.REFUSED, List.of());
        }
        return BinaryCodec.packet(config.session(), from, messages.size(), messages);
    }

    /** Every instrument's directory entry, levels and feed sequence (BF-005). */
    private byte[] snapshot() {
        List<byte[]> messages = new ArrayList<>();
        for (int locate = 1; locate <= instruments.size(); locate++) {
            AtomicReference<MarketDataMessage> captured = new AtomicReference<>();
            publisher.subscribe(instruments.get(locate - 1).symbol(), message -> {
                captured.set(message);
                return false;                                        // the snapshot only
            });
            Snapshot snapshot = (Snapshot) captured.get();
            messages.add(BinaryCodec.encode(directory(locate)));
            for (BookLevel level : snapshot.bids()) {
                messages.add(BinaryCodec.encode(level(locate, Side.BUY, level)));
            }
            for (BookLevel level : snapshot.asks()) {
                messages.add(BinaryCodec.encode(level(locate, Side.SELL, level)));
            }
            messages.add(BinaryCodec.encode(new SnapshotEnd(locate, snapshot.feedSequence(), snapshot.commandSequence())));
        }
        return BinaryCodec.packet(config.session(), 0, messages.size(), messages);
    }

    @Override
    public void close() throws IOException {
        running = false;
        for (int locate = 1; locate < subscriptions.length; locate++) {
            subscriptions[locate].cancel();
        }
        sender.interrupt();
        server.close();
        channel.close();
        try {
            sender.join();
            acceptor.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
