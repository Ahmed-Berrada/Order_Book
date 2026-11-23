package io.github.ahmedberrada.lob.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import io.github.ahmedberrada.lob.core.OrderBook;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import io.github.ahmedberrada.lob.journal.JournalOptions;
import io.github.ahmedberrada.lob.marketdata.MarketDataMessage.Snapshot;
import io.github.ahmedberrada.lob.marketdata.MarketDataMessage.Update;
import io.github.ahmedberrada.lob.service.BookSnapshot;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.InstrumentState;
import io.github.ahmedberrada.lob.service.MatchingService;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarketDataPublisherTest {

    private static final InstrumentConfig AAPL =
            new InstrumentConfig("AAPL", new BigDecimal("0.01"), 1_000, new BigDecimal("1000.00"));

    @TempDir
    Path directory;

    private final AtomicLong clock = new AtomicLong();

    private MatchingService startService() throws IOException {
        return MatchingService.start(directory, List.of(AAPL), new JournalOptions(FsyncPolicy.OS, 0),
                clock::incrementAndGet, 10_000);
    }

    private static List<BookLevel> levels(List<OrderBook.Level> levels) {
        return levels.stream().map(l -> new BookLevel(l.priceTicks(), l.quantity(), l.orderCount())).toList();
    }

    /** Records every message; rebuilds the book from them as a subscriber would. */
    private static final class Recorder implements Subscriber {
        final List<MarketDataMessage> messages = new CopyOnWriteArrayList<>();

        @Override
        public boolean offer(MarketDataMessage message) {
            messages.add(message);
            return true;
        }

        ReplicaBook rebuild() {
            ReplicaBook book = new ReplicaBook((Snapshot) messages.getFirst());
            messages.subList(1, messages.size()).forEach(m -> book.apply((Update) m));
            return book;
        }
    }

    @Test
    @Rulebook({"MD-004", "MD-005", "MD-006"})
    void subscribersJoiningDuringTradingRebuildTheBookExactly() throws Exception {
        try (MatchingService service = startService()) {
            MarketDataPublisher publisher = MarketDataPublisher.start(service);
            List<Recorder> subscribers = new CopyOnWriteArrayList<>();
            List<Thread> traders = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                long seed = t;
                traders.add(Thread.ofVirtual().start(() -> {
                    SplittableRandom random = new SplittableRandom(seed);
                    for (int i = 0; i < 500; i++) {
                        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                        var command = random.nextInt(5) == 0 ? new CancelOrder(random.nextLong(1, 2_000))
                                : random.nextInt(6) == 0 ? new MarketOrder(side, random.nextLong(1, 10))
                                : new LimitOrder(side, random.nextLong(9_990, 10_010), random.nextLong(1, 10));
                        service.submit("AAPL", command).join();
                    }
                }));
            }
            for (int s = 0; s < 20; s++) {                      // subscribe while orders flow
                Recorder recorder = new Recorder();
                publisher.subscribe("AAPL", recorder);
                subscribers.add(recorder);
                Thread.sleep(2);
            }
            for (Thread trader : traders) {
                trader.join();
            }

            BookSnapshot book = service.book("AAPL", Integer.MAX_VALUE).join();   // queued after every command
            for (Recorder recorder : subscribers) {
                ReplicaBook replica = recorder.rebuild();                      // throws on any feed gap
                assertThat(replica.bids()).isEqualTo(levels(book.bids()));
                assertThat(replica.asks()).isEqualTo(levels(book.asks()));
            }
            assertThat(publisher.subscriberCount("AAPL")).isEqualTo(20);
        }
    }

    @Test
    @Rulebook("MD-007")
    void afterARestartTheFeedStartsFromTheRecoveredBook() throws Exception {
        try (MatchingService service = startService()) {
            service.submit("AAPL", new LimitOrder(Side.BUY, 9_990, 5)).join();
            service.submit("AAPL", new LimitOrder(Side.SELL, 10_010, 7)).join();
        }
        try (MatchingService service = startService()) {
            Recorder recorder = new Recorder();
            MarketDataPublisher.start(service).subscribe("AAPL", recorder);

            Snapshot snapshot = (Snapshot) recorder.messages.getFirst();
            assertThat(snapshot.commandSequence()).isEqualTo(2);
            assertThat(snapshot.bids()).containsExactly(new BookLevel(9_990, 5, 1));
            assertThat(snapshot.asks()).containsExactly(new BookLevel(10_010, 7, 1));
        }
    }

    @Test
    @Rulebook("MD-008")
    void subscribersThatRefuseOrThrowAreDroppedAndCancellationStopsDelivery() throws Exception {
        try (MatchingService service = startService()) {
            MarketDataPublisher publisher = MarketDataPublisher.start(service);
            Recorder kept = new Recorder();
            Recorder cancelled = new Recorder();
            publisher.subscribe("AAPL", kept);
            MarketDataPublisher.Subscription subscription = publisher.subscribe("AAPL", cancelled);
            publisher.subscribe("AAPL", message -> message instanceof Snapshot);         // refuses updates
            publisher.subscribe("AAPL", message -> {
                throw new IllegalStateException("broken");
            });
            assertThat(publisher.subscriberCount("AAPL")).isEqualTo(3);                  // the thrower never joined

            subscription.cancel();
            service.submit("AAPL", new LimitOrder(Side.BUY, 9_990, 5)).join();

            assertThat(publisher.subscriberCount("AAPL")).isEqualTo(1);
            assertThat(kept.messages).hasSize(2);
            assertThat(cancelled.messages).hasSize(1);
        }
    }

    @Test
    @Rulebook("MD-008")
    void aStuckSubscriberIsDisconnectedWithoutSlowingOrderEntry() throws Exception {
        try (MatchingService service = startService()) {
            MarketDataPublisher publisher = MarketDataPublisher.start(service);
            CountDownLatch never = new CountDownLatch(1);
            AtomicReference<QueuedSubscriber.Disconnect> reason = new AtomicReference<>();
            CountDownLatch disconnected = new CountDownLatch(1);
            QueuedSubscriber stuck = new QueuedSubscriber(2, message -> never.await(), why -> {
                reason.set(why);
                disconnected.countDown();
            });
            publisher.subscribe("AAPL", stuck);

            long start = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                service.submit("AAPL", new LimitOrder(Side.BUY, 9_990, 1)).join();
            }
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2_000);
            assertThat(disconnected.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(reason.get()).isEqualTo(QueuedSubscriber.Disconnect.SLOW_CONSUMER);
            assertThat(publisher.subscriberCount("AAPL")).isZero();
        }
    }

    @Test
    void queuedSubscriberDeliversInOrderAndReportsHowItStopped() throws Exception {
        List<MarketDataMessage> sent = new CopyOnWriteArrayList<>();
        AtomicReference<QueuedSubscriber.Disconnect> reason = new AtomicReference<>();
        CountDownLatch stopped = new CountDownLatch(1);
        QueuedSubscriber subscriber = new QueuedSubscriber(10, sent::add, why -> {
            reason.set(why);
            stopped.countDown();
        });
        Snapshot first = new Snapshot("AAPL", 0, 0, List.of(), List.of());
        Snapshot second = new Snapshot("AAPL", 1, 1, List.of(), List.of());
        assertThat(subscriber.offer(first)).isTrue();
        assertThat(subscriber.offer(second)).isTrue();
        while (sent.size() < 2) {
            Thread.onSpinWait();
        }
        subscriber.close();
        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sent).containsExactly(first, second);
        assertThat(reason.get()).isEqualTo(QueuedSubscriber.Disconnect.CLOSED);
        assertThat(subscriber.offer(first)).isFalse();

        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<QueuedSubscriber.Disconnect> failure = new AtomicReference<>();
        QueuedSubscriber broken = new QueuedSubscriber(10, message -> {
            throw new IOException("connection closed");
        }, why -> {
            failure.set(why);
            failed.countDown();
        });
        broken.offer(first);
        assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isEqualTo(QueuedSubscriber.Disconnect.SINK_FAILED);
    }

    @Test
    @Rulebook({"MD-004", "MD-007"})
    void eventsArrivingWhileStartingAreAppliedOnceOnTopOfTheState() {
        MarketDataPublisher publisher = new MarketDataPublisher(List.of(AAPL));
        MatchingEngine engine = new MatchingEngine(AAPL.instrument());
        EventBatch first = new EventBatch(1, 1, engine.process(new LimitOrder(Side.BUY, 9_990, 5)));
        InstrumentState state = new InstrumentState(1, engine.snapshot());           // the state read includes command 1
        EventBatch second = new EventBatch(2, 2, engine.process(new LimitOrder(Side.BUY, 9_980, 3)));

        publisher.onEvents("AAPL", first, null);          // buffered: arrived before the image existed
        publisher.onEvents("AAPL", second, null);
        publisher.initialize("AAPL", state);

        Recorder recorder = new Recorder();
        publisher.subscribe("AAPL", recorder);
        Snapshot snapshot = (Snapshot) recorder.messages.getFirst();
        assertThat(snapshot.commandSequence()).isEqualTo(2);
        assertThat(snapshot.bids()).containsExactly(new BookLevel(9_990, 5, 1), new BookLevel(9_980, 3, 1));
    }

    @Test
    @Rulebook("MD-008")
    void overflowIsRefusedAtOnce() throws Exception {
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        QueuedSubscriber subscriber = new QueuedSubscriber(1, message -> {
            sending.countDown();
            never.await();
        }, why -> { });
        Snapshot message = new Snapshot("AAPL", 0, 0, List.of(), List.of());

        assertThat(subscriber.offer(message)).isTrue();             // taken by the sender, which blocks
        assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(subscriber.offer(message)).isTrue();             // fills the queue of one
        assertThat(subscriber.offer(message)).isFalse();            // overflow
        assertThat(subscriber.offer(message)).isFalse();            // and stays disconnected
    }

    @Test
    @Rulebook("OE-001")
    void unknownInstrumentsAndInvalidArgumentsAreRefused() throws Exception {
        try (MatchingService service = startService()) {
            MarketDataPublisher publisher = MarketDataPublisher.start(service);
            assertThatThrownBy(() -> publisher.subscribe("TSLA", m -> true)).isInstanceOf(RequestRefusedException.class);
            assertThatNullPointerException().isThrownBy(() -> publisher.subscribe("AAPL", null));
            assertThatNullPointerException().isThrownBy(() -> MarketDataPublisher.start(null));
            assertThatThrownBy(() -> new QueuedSubscriber(0, m -> { }, why -> { }))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
