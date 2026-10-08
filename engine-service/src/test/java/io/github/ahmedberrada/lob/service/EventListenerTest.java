package io.github.ahmedberrada.lob.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import io.github.ahmedberrada.lob.journal.JournalOptions;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventListenerTest {

    private static final InstrumentConfig AAPL =
            new InstrumentConfig("AAPL", new BigDecimal("0.01"), 1_000, new BigDecimal("1000.00"));
    private static final InstrumentConfig MSFT =
            new InstrumentConfig("MSFT", new BigDecimal("0.01"), 1_000, new BigDecimal("1000.00"));

    @TempDir
    Path directory;

    private final AtomicLong clock = new AtomicLong();

    /** One notification, with the thread it arrived on. */
    private record Seen(String symbol, long commandSequence, Object context, String thread) {
    }

    private MatchingService start() throws IOException {
        return MatchingService.start(directory, List.of(AAPL, MSFT), new JournalOptions(FsyncPolicy.OS, 0),
                clock::incrementAndGet, 100);
    }

    @Test
    void seesEveryCommandInOrderOnTheInstrumentThreadWithItsContext() throws IOException {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        try (MatchingService service = start()) {
            service.addListener((symbol, batch, context) ->
                    seen.add(new Seen(symbol, batch.commandSequence(), context, Thread.currentThread().getName())));

            CompletableFuture<EventBatch> last = null;
            for (int i = 0; i < 20; i++) {
                service.submit("AAPL", new LimitOrder(Side.BUY, 100 + i, 1), "client-" + i);
                last = service.submit("MSFT", new MarketOrder(Side.SELL, 1));     // rejected: still an event batch
            }
            last.join();
            service.submit("AAPL", new MarketOrder(Side.SELL, 1), "client-20").join();
        }

        List<Seen> aapl = seen.stream().filter(s -> s.symbol().equals("AAPL")).toList();
        assertThat(aapl).extracting(Seen::commandSequence).containsExactlyElementsOf(
                LongStream.rangeClosed(1, 21).boxed().toList());
        assertThat(aapl).extracting(Seen::context).startsWith("client-0", "client-1").endsWith("client-20");
        assertThat(aapl).extracting(Seen::thread).containsOnly("lob-AAPL");
        assertThat(seen.stream().filter(s -> s.symbol().equals("MSFT"))).hasSize(20)
                .allSatisfy(s -> assertThat(s.context()).isNull());
    }

    @Test
    void failingListenerNeitherBlocksTheCommandNorTheOtherListeners() throws IOException {
        List<Long> seen = new CopyOnWriteArrayList<>();
        try (MatchingService service = start()) {
            service.addListener((symbol, batch, context) -> {
                throw new IllegalStateException("broken listener");
            });
            service.addListener((symbol, batch, context) -> seen.add(batch.commandSequence()));

            EventBatch batch = service.submit("AAPL", new LimitOrder(Side.BUY, 100, 1)).join();

            assertThat(batch.commandSequence()).isEqualTo(1);
            assertThat(seen).containsExactly(1L);
            assertThatNullPointerException().isThrownBy(() -> service.addListener(null));
        }
    }

    @Test
    void refusedCommandsProduceNoEvents() throws IOException {
        List<String> seen = new CopyOnWriteArrayList<>();
        try (MatchingService service = start()) {
            service.addListener((symbol, batch, context) -> seen.add(symbol));
            assertThat(service.submit("TSLA", new MarketOrder(Side.BUY, 1), "x")).isCompletedExceptionally();
        }
        assertThat(seen).isEmpty();
    }
}
