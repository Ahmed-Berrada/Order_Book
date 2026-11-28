package io.github.ahmedberrada.lob.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import io.github.ahmedberrada.lob.journal.JournalOptions;
import io.github.ahmedberrada.lob.journal.JournaledEngine;
import io.github.ahmedberrada.lob.journal.TimeSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Group commit (RS-001, ADR-0020): one flush for the commands waiting together, nothing out before it. */
class GroupCommitTest {

    private static final Instrument INSTRUMENT = new Instrument("GRP", 1_000, 10_000);

    @TempDir
    Path directory;

    /**
     * Holds the worker inside the clock on the calls it is told to. Each hold has its own gate, so a test can arm
     * the next hold while the worker still waits on the current one.
     */
    private static final class GatedClock implements TimeSource {
        private volatile int holdAt = -1;
        private volatile CountDownLatch armed = new CountDownLatch(0);    // the gate of the next hold
        private volatile CountDownLatch waiting = new CountDownLatch(0);  // the gate the worker waits on now
        private volatile CountDownLatch held = new CountDownLatch(1);
        private volatile int calls;
        private long now = 1_000_000;

        /** Holds the worker on the {@code n}th call from now (opening the engine reads the clock too). */
        void holdAtCall(int n) {
            armed = new CountDownLatch(1);
            held = new CountDownLatch(1);
            holdAt = calls + n;
        }

        void awaitHeld() throws InterruptedException {
            assertThat(held.await(10, TimeUnit.SECONDS)).as("worker reached the clock").isTrue();
        }

        /** Lets the worker leave the hold it is in. */
        void release() {
            waiting.countDown();
        }

        void releaseAll() {
            waiting.countDown();
            armed.countDown();
            holdAt = -1;
        }

        @Override
        public long nowMicros() {
            if (++calls == holdAt) {
                CountDownLatch gate = armed;
                waiting = gate;
                held.countDown();
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return now++;
        }
    }

    private final GatedClock clock = new GatedClock();
    private final List<Long> published = new CopyOnWriteArrayList<>();      // command sequences, as listeners saw
    private final List<Long> flushesWhenPublished = new CopyOnWriteArrayList<>();
    private final List<Long> observed = new CopyOnWriteArrayList<>();       // command sequences, as metrics saw
    private final List<Integer> groups = new CopyOnWriteArrayList<>();      // commands per flush
    private JournaledEngine engine;
    private SymbolWorker worker;

    private void start(FsyncPolicy policy) throws IOException {
        engine = JournaledEngine.open(directory, INSTRUMENT, new JournalOptions(policy, 0), clock);
        EventListener listener = (symbol, batch, context) -> {
            published.add(batch.commandSequence());
            flushesWhenPublished.add(engine.flushes());
        };
        CommandObserver observer = new CommandObserver() {
            @Override
            public void processed(String symbol, EventBatch batch, long nanos) {
                observed.add(batch.commandSequence());
            }

            @Override
            public void flushed(String symbol, int commands, long nanos) {
                groups.add(commands);
            }
        };
        worker = new SymbolWorker(engine, 1_000, List.of(listener), observer);
    }

    @AfterEach
    void stop() throws IOException {
        clock.releaseAll();
        if (worker != null) {
            worker.close();
        }
    }

    private static LimitOrder buy(long price) {
        return new LimitOrder(Side.BUY, price, 1);
    }

    @Test
    @Rulebook("RS-001")
    void theCommandsWaitingTogetherShareOneFlushAndNothingIsPublishedBeforeIt() throws Exception {
        start(FsyncPolicy.GROUP);
        clock.holdAtCall(1);
        CompletableFuture<EventBatch> first = worker.submit(buy(100));
        clock.awaitHeld();                                     // the worker is inside the first command
        List<CompletableFuture<EventBatch>> waiting = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            waiting.add(worker.submit(buy(90 + i)));
        }
        CompletableFuture<Long> read = worker.restingQuantity(1);
        clock.release();

        assertThat(first.join().commandSequence()).isEqualTo(1);
        for (int i = 0; i < 9; i++) {
            assertThat(waiting.get(i).join().commandSequence()).isEqualTo(i + 2);
        }
        assertThat(read.join()).isEqualTo(1);
        assertThat(engine.flushes()).as("one flush for the first command, one for the nine behind it").isEqualTo(2);
        assertThat(published).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(flushesWhenPublished).as("each command was flushed before anyone saw it")
                .containsExactly(1L, 2L, 2L, 2L, 2L, 2L, 2L, 2L, 2L, 2L);
        assertThat(groups).as("commands per flush").containsExactly(1, 9);
        assertThat(observed).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    }

    @Test
    @Rulebook({"RS-001", "OE-006"})
    void aFailedFlushAcknowledgesNoCommandOfTheGroupAndHalts() throws Exception {
        start(FsyncPolicy.GROUP);
        clock.holdAtCall(1);
        CompletableFuture<EventBatch> first = worker.submit(buy(100));
        clock.awaitHeld();
        CompletableFuture<EventBatch> second = worker.submit(buy(99));     // calls 2, 3, 4: one group
        CompletableFuture<EventBatch> third = worker.submit(buy(98));
        CompletableFuture<EventBatch> fourth = worker.submit(buy(97));
        CompletableFuture<Long> read = worker.restingQuantity(1);
        clock.holdAtCall(3);                   // the fourth command: the third call from now
        clock.release();
        clock.awaitHeld();                     // second and third are written, not flushed; fourth is being written
        workerThread().interrupt();            // closes the journal's channel: the write fails, and so does the flush
        clock.release();

        assertThat(first.join().commandSequence()).as("its own group was flushed").isEqualTo(1);
        for (CompletableFuture<EventBatch> unknown : List.of(second, third, fourth)) {
            assertThatThrownBy(unknown::join).isInstanceOf(CompletionException.class)
                    .cause().isInstanceOf(UncheckedIOException.class);
        }
        assertThat(worker.isHalted()).isTrue();
        assertThat(published).as("nothing of the failed group was published").containsExactly(1L);
        assertThat(observed).as("nor counted by the metrics").containsExactly(1L);
        // The engine's memory now holds commands whose outcome is unknown: nothing is read from it until a restart.
        assertRefusedAsHalted(read);
        assertRefusedAsHalted(worker.restingQuantity(1));
        assertRefusedAsHalted(worker.book(5));
        assertRefusedAsHalted(worker.submit(buy(90)));
    }

    private static void assertRefusedAsHalted(CompletableFuture<?> future) {
        assertThatThrownBy(future::join).isInstanceOf(CompletionException.class).cause()
                .isInstanceOfSatisfying(RequestRefusedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RequestRefusedException.Reason.HALTED));
    }

    @Test
    void anErrorInsideAGroupFailsEveryTaskOfItInsteadOfLeavingThemWaiting() throws Exception {
        engine = JournaledEngine.open(directory, INSTRUMENT, new JournalOptions(FsyncPolicy.GROUP, 0), clock);
        EventListener dies = (symbol, batch, context) -> {
            if (batch.commandSequence() == 3) {
                throw new StackOverflowError("simulated");
            }
        };
        worker = new SymbolWorker(engine, 1_000, List.of(dies));
        clock.holdAtCall(1);
        CompletableFuture<EventBatch> first = worker.submit(buy(100));
        clock.awaitHeld();
        List<CompletableFuture<EventBatch>> group = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            group.add(worker.submit(buy(90 + i)));                   // commands 2 to 5, one group
        }
        clock.release();

        assertThat(first.join().commandSequence()).isEqualTo(1);
        assertThat(group.get(0).join().commandSequence()).as("reported before the error: it is flushed").isEqualTo(2);
        for (CompletableFuture<EventBatch> task : group.subList(1, 4)) {
            assertThat(task).as("not left waiting").failsWithin(java.time.Duration.ofSeconds(10));
        }
        assertThat(worker.isHalted()).isTrue();
    }

    @Test
    @Rulebook("RS-001")
    void everyCommandIsFlushedOnItsOwnWithTheOtherPolicy() throws Exception {
        start(FsyncPolicy.EVERY_COMMAND);
        clock.holdAtCall(1);
        CompletableFuture<EventBatch> first = worker.submit(buy(100));
        clock.awaitHeld();
        List<CompletableFuture<EventBatch>> waiting = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            waiting.add(worker.submit(buy(90 + i)));
        }
        clock.release();
        first.join();
        waiting.forEach(CompletableFuture::join);
        assertThat(engine.flushes()).isEqualTo(6);
        assertThat(flushesWhenPublished).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    private static Thread workerThread() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("lob-" + INSTRUMENT.symbol())).findFirst().orElseThrow();
    }
}
