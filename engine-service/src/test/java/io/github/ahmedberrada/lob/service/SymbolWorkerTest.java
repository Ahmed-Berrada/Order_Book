package io.github.ahmedberrada.lob.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import io.github.ahmedberrada.lob.core.OrderBook;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import io.github.ahmedberrada.lob.journal.JournalOptions;
import io.github.ahmedberrada.lob.journal.JournaledEngine;
import io.github.ahmedberrada.lob.service.RequestRefusedException.Reason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymbolWorkerTest {

    private static final Instrument INSTRUMENT = new Instrument("WRK", 1_000, 10_000);
    private static final JournalOptions NO_SNAPSHOTS = new JournalOptions(FsyncPolicy.OS, 0);

    @TempDir
    Path directory;

    private final BlockingClock clock = new BlockingClock();
    private SymbolWorker worker;

    private SymbolWorker start(JournalOptions options, int capacity) throws IOException {
        worker = new SymbolWorker(JournaledEngine.open(directory, INSTRUMENT, options, clock), capacity);
        return worker;
    }

    @AfterEach
    void stop() throws IOException {
        clock.open();
        if (worker != null) {
            worker.close();
        }
    }

    private static LimitOrder buy(long price, long quantity) {
        return new LimitOrder(Side.BUY, price, quantity);
    }

    private static void assertRefused(CompletableFuture<?> future, Reason reason) {
        assertThatThrownBy(future::join).isInstanceOf(CompletionException.class)
                .cause().isInstanceOfSatisfying(RequestRefusedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    @Rulebook({"OE-003", "OE-004"})
    void processesCommandsOneAtATimeInQueueOrder() throws IOException {
        start(NO_SNAPSHOTS, 1_000);
        List<LimitOrder> commands = new ArrayList<>();
        List<CompletableFuture<EventBatch>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            LimitOrder order = new LimitOrder(i % 2 == 0 ? Side.BUY : Side.SELL, 95 + i % 11, 1 + i % 7);
            commands.add(order);
            futures.add(worker.submit(order));
        }

        MatchingEngine sequential = new MatchingEngine(INSTRUMENT);
        for (int i = 0; i < commands.size(); i++) {
            EventBatch batch = futures.get(i).join();
            assertThat(batch.commandSequence()).isEqualTo(i + 1);
            assertThat(batch.events()).isEqualTo(sequential.process(commands.get(i)));
        }
    }

    @Test
    void booksAreCopiedOnTheInstrumentThread() throws IOException {
        start(NO_SNAPSHOTS, 10);
        worker.submit(buy(100, 5));
        worker.submit(buy(99, 3));
        worker.submit(buy(98, 1));
        worker.submit(new LimitOrder(Side.SELL, 101, 2));

        BookSnapshot book = worker.book(2).join();

        assertThat(book.lastCommandSequence()).isEqualTo(4);
        assertThat(book.bids()).containsExactly(new OrderBook.Level(100, 5, 1), new OrderBook.Level(99, 3, 1));
        assertThat(book.asks()).containsExactly(new OrderBook.Level(101, 2, 1));
        assertThat(worker.restingQuantity(1).join()).isEqualTo(5);
        assertThat(worker.restingQuantity(42).join()).isZero();
    }

    @Test
    @Rulebook("OE-005")
    void fullQueueRefusesAtOnceWithoutJournaling() throws Exception {
        start(NO_SNAPSHOTS, 2);
        clock.close();
        CompletableFuture<EventBatch> inProgress = worker.submit(buy(100, 1));
        clock.awaitBlocked();
        CompletableFuture<EventBatch> queued1 = worker.submit(buy(100, 1));
        CompletableFuture<EventBatch> queued2 = worker.submit(buy(100, 1));

        CompletableFuture<EventBatch> refused = worker.submit(buy(100, 1));

        assertRefused(refused, Reason.OVERLOADED);
        clock.open();
        assertThat(Stream.of(inProgress, queued1, queued2).map(f -> f.join().commandSequence()))
                .containsExactly(1L, 2L, 3L);
        assertThat(worker.book(1).join().lastCommandSequence()).isEqualTo(3);
    }

    @Test
    @Rulebook("OE-006")
    void journalFailureHaltsCommandsButNotReads() throws Exception {
        start(new JournalOptions(FsyncPolicy.OS, 1), 10);
        clock.close();
        CompletableFuture<EventBatch> first = worker.submit(buy(100, 5));
        clock.awaitBlocked();
        CompletableFuture<EventBatch> queued = worker.submit(buy(100, 1));
        deleteDirectory();               // the snapshot after the first command will fail
        clock.open();

        assertThat(first.join().commandSequence()).isEqualTo(1);   // journaled: acknowledged
        assertRefused(queued, Reason.HALTED);
        assertThat(worker.isHalted()).isTrue();
        assertRefused(worker.submit(buy(100, 1)), Reason.HALTED);
        assertThat(worker.book(1).join().bids()).containsExactly(new OrderBook.Level(100, 5, 1));
    }

    @Test
    @Rulebook("OE-007")
    void shutdownProcessesWhatIsQueuedThenRefuses() throws Exception {
        start(NO_SNAPSHOTS, 10);
        clock.close();
        List<CompletableFuture<EventBatch>> accepted = new ArrayList<>();
        accepted.add(worker.submit(buy(100, 1)));
        clock.awaitBlocked();
        accepted.add(worker.submit(buy(100, 2)));
        accepted.add(worker.submit(buy(100, 3)));

        Thread closer = Thread.ofPlatform().start(() -> {
            try {
                worker.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        while (worker.isAccepting()) {
            Thread.onSpinWait();                        // until close() has started
        }
        CompletableFuture<EventBatch> late = worker.submit(buy(100, 4));
        clock.open();
        closer.join();

        assertRefused(late, Reason.STOPPED);
        assertThat(accepted).allSatisfy(f -> assertThat(f.join().events()).hasSize(2));
        try (JournaledEngine reopened = JournaledEngine.open(directory, INSTRUMENT, NO_SNAPSHOTS, clock)) {
            assertThat(reopened.lastCommandSequence()).isEqualTo(3);
        }
        worker = null;
    }

    private void deleteDirectory() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(directory);
    }
}
