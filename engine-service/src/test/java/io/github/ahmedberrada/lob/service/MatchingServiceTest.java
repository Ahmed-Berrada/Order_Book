package io.github.ahmedberrada.lob.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import io.github.ahmedberrada.lob.core.OrderBook;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import io.github.ahmedberrada.lob.journal.JournalCorruptedException;
import io.github.ahmedberrada.lob.journal.JournalOptions;
import io.github.ahmedberrada.lob.journal.TimeSource;
import io.github.ahmedberrada.lob.service.RequestRefusedException.Reason;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MatchingServiceTest {

    private static final InstrumentConfig AAPL =
            new InstrumentConfig("AAPL", new BigDecimal("0.01"), 1_000, new BigDecimal("1000.00"));
    private static final InstrumentConfig MSFT =
            new InstrumentConfig("MSFT", new BigDecimal("0.01"), 1_000, new BigDecimal("1000.00"));
    private static final JournalOptions OPTIONS = new JournalOptions(FsyncPolicy.OS, 500);

    @TempDir
    Path directory;

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final TimeSource time = clock::incrementAndGet;

    private MatchingService start() throws IOException {
        return MatchingService.start(directory, List.of(AAPL, MSFT), OPTIONS, time, 10_000);
    }

    private static void assertRefused(CompletableFuture<?> future, Reason reason) {
        assertThatThrownBy(future::join).isInstanceOf(CompletionException.class)
                .cause().isInstanceOfSatisfying(RequestRefusedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    /** A command together with the acknowledgement the service returned for it. */
    private record Acknowledged(String symbol, Command command, EventBatch batch) {
    }

    @Test
    @Rulebook("OE-003")
    void concurrentClientsGetTheResultsOfSequentialProcessingPerInstrument() throws Exception {
        List<Acknowledged> acknowledged = new CopyOnWriteArrayList<>();
        try (MatchingService service = start()) {
            List<Thread> clients = new ArrayList<>();
            for (int c = 0; c < 8; c++) {
                long seed = c;
                clients.add(Thread.ofVirtual().start(() -> {
                    SplittableRandom random = new SplittableRandom(seed);
                    for (int i = 0; i < 400; i++) {
                        String symbol = random.nextBoolean() ? "AAPL" : "MSFT";
                        Command command = randomCommand(random);
                        acknowledged.add(new Acknowledged(symbol, command, service.submit(symbol, command).join()));
                    }
                }));
            }
            for (Thread client : clients) {
                client.join();
            }
        }

        assertThat(acknowledged).hasSize(3_200);
        for (InstrumentConfig instrument : List.of(AAPL, MSFT)) {
            List<Acknowledged> inOrder = acknowledged.stream().filter(a -> a.symbol().equals(instrument.symbol()))
                    .sorted(Comparator.comparingLong(a -> a.batch().commandSequence())).toList();
            MatchingEngine sequential = new MatchingEngine(instrument.instrument());
            long lastTimestamp = 0;
            for (int i = 0; i < inOrder.size(); i++) {
                Acknowledged a = inOrder.get(i);
                assertThat(a.batch().commandSequence()).isEqualTo(i + 1);
                assertThat(a.batch().timestampMicros()).isGreaterThanOrEqualTo(lastTimestamp);
                assertThat(a.batch().events()).isEqualTo(sequential.process(a.command()));
                lastTimestamp = a.batch().timestampMicros();
            }
        }
    }

    @Test
    @Rulebook("OE-001")
    void unknownInstrumentsAreRefusedAndNotJournaled() throws IOException {
        try (MatchingService service = start()) {
            assertRefused(service.submit("TSLA", new MarketOrder(Side.BUY, 1)), Reason.UNKNOWN_INSTRUMENT);
            assertRefused(service.book("TSLA", 5), Reason.UNKNOWN_INSTRUMENT);
            assertRefused(service.restingQuantity("TSLA", 1), Reason.UNKNOWN_INSTRUMENT);
            assertThat(service.instrument("TSLA")).isEmpty();
            assertThat(service.isHalted("TSLA")).isFalse();
        }
        assertThat(directory.resolve("TSLA")).doesNotExist();
    }

    @Test
    @Rulebook("OE-004")
    void restartRecoversEveryInstrument() throws IOException {
        try (MatchingService service = start()) {
            service.submit("AAPL", new LimitOrder(Side.BUY, 18_500, 10)).join();
            service.submit("AAPL", new LimitOrder(Side.SELL, 18_600, 4)).join();
            EventBatch batch = service.submit("MSFT", new LimitOrder(Side.SELL, 41_000, 7)).join();
            assertThat(batch.commandSequence()).isEqualTo(1);
            assertThat(service.isHalted("AAPL")).isFalse();
        }

        try (MatchingService service = start()) {
            assertThat(service.recoveries().get("AAPL").lastCommandSequence()).isEqualTo(2);
            assertThat(service.recoveries().get("MSFT").lastCommandSequence()).isEqualTo(1);
            BookSnapshot book = service.book("AAPL", 10).join();
            assertThat(book.bids()).containsExactly(new OrderBook.Level(18_500, 10, 1));
            assertThat(book.asks()).containsExactly(new OrderBook.Level(18_600, 4, 1));
            assertThat(service.restingQuantity("MSFT", 1).join()).isEqualTo(7);
            assertThat(service.submit("AAPL", new CancelOrder(1)).join().commandSequence()).isEqualTo(3);
        }
    }

    @Test
    void listsItsInstrumentsInConfigurationOrder() throws IOException {
        try (MatchingService service = start()) {
            assertThat(service.instruments()).containsExactly(AAPL, MSFT);
            assertThat(service.instrument("MSFT")).contains(MSFT);
            assertThat(service.recoveries()).containsOnlyKeys("AAPL", "MSFT");
        }
    }

    @Test
    void failedStartStopsTheInstrumentsAlreadyStarted() throws IOException {
        Path corrupt = Files.createDirectories(directory.resolve("MSFT"));
        Files.write(corrupt.resolve("commands.journal"), new byte[] {0, 0, 0, 4, 1, 2, 3, 4, 9, 9, 9, 9, 0, 0, 0, 1});

        assertThatThrownBy(this::start).isInstanceOf(JournalCorruptedException.class);
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> t.getName().equals("lob-AAPL"));
    }

    @Test
    void validatesItsArguments() throws IOException {
        assertThatIllegalArgumentException().isThrownBy(() ->
                MatchingService.start(directory, List.of(AAPL, AAPL), OPTIONS, time, 10)).withMessageContaining("duplicate");
        assertThatIllegalArgumentException().isThrownBy(() ->
                MatchingService.start(directory, List.of(AAPL), OPTIONS, time, 0));
        assertThatNullPointerException().isThrownBy(() -> MatchingService.start(null, List.of(AAPL), OPTIONS, time, 1));
        try (MatchingService service = start()) {
            assertThatNullPointerException().isThrownBy(() -> service.submit("AAPL", null));
            assertThatIllegalArgumentException().isThrownBy(() -> service.book("AAPL", -1));
        }
    }

    @Test
    void closeReportsEveryFailureAndCanBeRetried() throws IOException {
        MatchingService service = start();
        Thread.currentThread().interrupt();          // makes each worker's close() fail

        assertThatThrownBy(service::close).isInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
        assertThat(Thread.interrupted()).isTrue();
        service.close();
    }

    @Test
    void closingTwiceIsHarmless() throws IOException {
        MatchingService service = start();
        service.close();
        service.close();
        assertRefused(service.submit("AAPL", new MarketOrder(Side.BUY, 1)), Reason.STOPPED);
    }

    private static Command randomCommand(SplittableRandom random) {
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        return switch (random.nextInt(8)) {
            case 0 -> new MarketOrder(side, random.nextLong(1, 20));
            case 1, 2 -> new CancelOrder(random.nextLong(1, 300));
            default -> new LimitOrder(side, random.nextLong(9_990, 10_011), random.nextLong(1, 20));
        };
    }
}
