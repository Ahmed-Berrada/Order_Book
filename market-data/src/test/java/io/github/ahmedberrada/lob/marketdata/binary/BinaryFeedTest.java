package io.github.ahmedberrada.lob.marketdata.binary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.OrderBook;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import io.github.ahmedberrada.lob.journal.JournalOptions;
import io.github.ahmedberrada.lob.marketdata.BookLevel;
import io.github.ahmedberrada.lob.marketdata.MarketDataPublisher;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryCodec.Packet;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.Directory;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.UpdateEnd;
import io.github.ahmedberrada.lob.service.BookSnapshot;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The binary feed over real UDP and TCP sockets on the loopback interface. */
class BinaryFeedTest {

    private static final List<InstrumentConfig> INSTRUMENTS = List.of(
            new InstrumentConfig("AAPL", new BigDecimal("0.01"), 1_000, new BigDecimal("1000.00")),
            new InstrumentConfig("MSFT", new BigDecimal("0.05"), 1_000, new BigDecimal("1000.00")));
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @TempDir
    Path directory;

    private final AtomicLong clock = new AtomicLong();
    private MatchingService service;
    private BinaryFeed feed;
    private BinaryFeedClient client;

    private void start(int retention) throws IOException {
        service = MatchingService.start(directory, INSTRUMENTS, new JournalOptions(FsyncPolicy.OS, 0),
                clock::incrementAndGet, 10_000);
        MarketDataPublisher publisher = MarketDataPublisher.start(service);
        int streamPort;
        try (DatagramSocket probe = new DatagramSocket(0, LOOPBACK)) {
            streamPort = probe.getLocalPort();
        }
        InetSocketAddress stream = new InetSocketAddress(LOOPBACK, streamPort);
        feed = BinaryFeed.start(publisher, INSTRUMENTS, new BinaryFeedConfig(stream, new InetSocketAddress(LOOPBACK, 0),
                "LOB-TEST", retention, Duration.ofMillis(200), 100_000));
        client = new BinaryFeedClient(stream, new InetSocketAddress(LOOPBACK, feed.servicePort()));
    }

    @AfterEach
    void stop() throws IOException {
        if (client != null) {
            client.close();
            feed.close();
            service.close();
        }
    }

    private final AtomicBoolean dropAll = new AtomicBoolean();
    private final AtomicBoolean heartbeatSeen = new AtomicBoolean();

    /** Loses every packet while {@link #dropAll} is set, and notes heartbeats. */
    private void lossyNetwork() {
        client.dropPackets(packet -> {
            if (packet.isHeartbeat()) {
                heartbeatSeen.set(true);
            }
            return dropAll.get();
        });
    }

    /** Reads until a heartbeat arrives: the feed sends one only once it has sent everything. */
    private void drain() throws IOException {
        heartbeatSeen.set(false);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!heartbeatSeen.get() && System.nanoTime() < deadline) {
            client.poll(100);
        }
        assertThat(heartbeatSeen).as("stream went idle").isTrue();
    }

    private void trade(int commands) {
        SplittableRandom random = new SplittableRandom(7);
        for (int i = 0; i < commands; i++) {
            String symbol = random.nextBoolean() ? "AAPL" : "MSFT";
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            Command command = switch (random.nextInt(6)) {
                case 0 -> new CancelOrder(random.nextLong(1, commands));
                case 1 -> new MarketOrder(side, random.nextLong(1, 10));
                default -> new LimitOrder(side, random.nextLong(9_990, 10_010), random.nextLong(1, 10));
            };
            service.submit(symbol, command).join();
        }
    }

    /** Polls until the client has applied the feed sequence the publisher reached for every instrument. */
    private void catchUp() throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            client.poll(50);
            boolean done = true;
            for (InstrumentConfig instrument : INSTRUMENTS) {
                BookSnapshot book = service.book(instrument.symbol(), Integer.MAX_VALUE).join();
                done &= client.bids(instrument.symbol()).equals(levels(book.bids()))
                        && client.asks(instrument.symbol()).equals(levels(book.asks()));
            }
            if (done) {
                return;
            }
        }
        throw new AssertionError("client never caught up");
    }

    private static List<BookLevel> levels(List<OrderBook.Level> levels) {
        return levels.stream().map(l -> new BookLevel(l.priceTicks(), l.quantity(), l.orderCount())).toList();
    }

    @Test
    @Rulebook({"BF-001", "BF-002", "BF-004", "BF-005"})
    void aLossyConsumerRebuildsEveryBookByRetransmission() throws Exception {
        start(1_000_000);
        client.start();
        AtomicInteger packets = new AtomicInteger();
        client.dropPackets(packet -> !packet.isHeartbeat() && packets.incrementAndGet() % 5 == 0);   // lose one in five

        Thread traders = Thread.ofVirtual().start(() -> trade(3_000));
        while (traders.isAlive()) {
            client.poll(10);
        }
        catchUp();

        assertThat(client.retransmissions()).isPositive();
        assertThat(client.tradesSeen()).isPositive();
        assertThat(client.bids("AAPL")).isNotEmpty();
    }

    @Test
    @Rulebook({"BF-004", "BF-005"})
    void aRefusedRetransmissionFallsBackToASnapshot() throws Exception {
        start(10);                                          // keeps only the last 10 messages
        client.start();
        lossyNetwork();
        trade(5);
        drain();                                            // the client is following the stream
        dropAll.set(true);                                  // then loses everything, heartbeats included
        trade(300);
        drain();
        dropAll.set(false);
        trade(10);
        catchUp();                                          // the gap is far older than the window

        assertThat(client.retransmissions()).isPositive();
        assertThat(client.snapshots()).isGreaterThan(1);
    }

    @Test
    @Rulebook({"BF-002", "BF-003"})
    void updatesAreNeverSplitAndIdleStreamsHeartbeat() throws Exception {
        start(1_000_000);
        List<Packet> seen = new CopyOnWriteArrayList<>();
        client.dropPackets(packet -> {
            seen.add(packet);
            return false;
        });
        client.start();
        trade(500);
        catchUp();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (seen.stream().noneMatch(Packet::isHeartbeat) && System.nanoTime() < deadline) {
            client.poll(100);
        }

        long previousEnd = 0;
        long lastSequence = 0;
        for (Packet packet : seen) {
            // UDP may lose packets on the way, but the stream never reuses or reorders a sequence number
            assertThat(packet.sequence()).as("increasing sequence").isGreaterThanOrEqualTo(previousEnd);
            if (!packet.isHeartbeat()) {
                previousEnd = packet.sequence() + packet.count();
                lastSequence = previousEnd - 1;
            }
            List<BinaryMessage> messages = packet.messages();
            if (!messages.isEmpty() && !(messages.getFirst() instanceof Directory)) {
                assertThat(messages.getLast()).as("a packet ends with an end of update").isInstanceOf(UpdateEnd.class);
            }
            assertThat(BinaryCodec.packet(packet.session(), packet.sequence(), packet.count(),
                    messages.stream().map(BinaryCodec::encode).toList()).length)
                    .as("packet size").isLessThanOrEqualTo(BinaryFeed.MAX_PACKET_BYTES);
        }
        // at the source, every sequence number exists: the whole stream can be fetched again (BF-002, BF-004)
        for (long from = 1; from <= lastSequence; from += BinaryFeed.MAX_RETRANSMIT) {
            int count = (int) Math.min(BinaryFeed.MAX_RETRANSMIT, lastSequence - from + 1);
            Packet again = retransmit(from, count);
            assertThat(again.sequence()).isEqualTo(from);
            assertThat(again.messages()).hasSize(count);
        }
        assertThat(retransmit(lastSequence + 1_000, 1).isRefused()).isTrue();
        assertThat(seen.getFirst().messages()).containsExactly(
                new Directory(1, "AAPL", 1, (byte) -2), new Directory(2, "MSFT", 5, (byte) -2));
        assertThat(seen).anyMatch(Packet::isHeartbeat);
    }

    private Packet retransmit(long from, int count) throws IOException {
        try (Socket connection = new Socket(LOOPBACK, feed.servicePort());
                DataOutputStream out = new DataOutputStream(connection.getOutputStream());
                DataInputStream in = new DataInputStream(connection.getInputStream())) {
            out.writeInt(11);
            out.write(ByteBuffer.allocate(11).put((byte) 'X').putLong(from).putShort((short) count).array());
            byte[] response = new byte[in.readInt()];
            in.readFully(response);
            return BinaryCodec.decodePacket(ByteBuffer.wrap(response));
        }
    }

    @Test
    void theServiceDropsMalformedRequestsAndKeepsServing() throws Exception {
        start(1_000_000);
        for (int length : new int[] {Integer.MAX_VALUE, -1, 0}) {
            try (Socket connection = new Socket(LOOPBACK, feed.servicePort());
                    DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
                out.writeInt(length);                          // a hostile length prefix
                out.flush();
                assertThat(connection.getInputStream().read()).as("connection closed").isEqualTo(-1);
            }
        }
        try (Socket connection = new Socket(LOOPBACK, feed.servicePort());
                DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
            out.writeInt(1);
            out.write('?');                                    // unknown request type
            out.flush();
            assertThat(connection.getInputStream().read()).isEqualTo(-1);
        }
        assertThat(retransmit(1, 1).messages()).hasSize(1);    // still serving
    }

    @Test
    void configurationIsValidated() {
        InetSocketAddress any = new InetSocketAddress(LOOPBACK, 0);
        assertThatIllegalArgumentException().isThrownBy(() -> new BinaryFeedConfig(any, any, "", 1, Duration.ofSeconds(1), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new BinaryFeedConfig(any, any, "ELEVEN_CHAR", 1, Duration.ofSeconds(1), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new BinaryFeedConfig(any, any, "S", 0, Duration.ofSeconds(1), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new BinaryFeedConfig(any, any, "S", 1, Duration.ZERO, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new BinaryFeedConfig(any, any, "S", 1, Duration.ofSeconds(1), 0));
        MessageRing ring = new MessageRing(3);
        assertThat(ring.get(1, 1)).isNull();
        for (long s = 1; s <= 5; s++) {
            ring.put(s, new byte[] {(byte) s});
        }
        assertThat(ring.get(3, 3)).extracting(b -> b[0]).containsExactly((byte) 3, (byte) 4, (byte) 5);
        assertThat(ring.get(2, 1)).isNull();
        assertThat(ring.get(5, 2)).isNull();
        assertThat(ring.get(3, 0)).isNull();
    }
}
