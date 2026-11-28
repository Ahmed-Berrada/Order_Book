package io.github.ahmedberrada.lob.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.OrderAccepted;
import io.github.ahmedberrada.lob.core.ReplaceOrder;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.core.StopOrder;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import io.github.ahmedberrada.lob.journal.JournalOptions;
import io.github.ahmedberrada.lob.service.RequestRefusedException.Reason;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThrottlesTest {

    private static final InstrumentConfig AAPL =
            new InstrumentConfig("AAPL", new BigDecimal("0.01"), 1_000, new BigDecimal("1000.00"));
    private static final InstrumentConfig MSFT =
            new InstrumentConfig("MSFT", new BigDecimal("0.01"), 1_000, new BigDecimal("1000.00"));
    private static final long TEN_AM = Instant.parse("2026-11-28T10:00:00Z").toEpochMilli() * 1_000;
    private static final long MIDNIGHT = Instant.parse("2026-11-29T00:00:00Z").toEpochMilli() * 1_000;

    @TempDir
    Path directory;

    private final AtomicLong micros = new AtomicLong(TEN_AM);
    private final List<String> throttled = new CopyOnWriteArrayList<>();
    private final CommandObserver observer = new CommandObserver() {
        @Override
        public void processed(String symbol, EventBatch batch, long nanos) {
        }

        @Override
        public void throttled(String symbol, long memberId, Reason reason) {
            throttled.add(symbol + "/" + memberId + "/" + reason);
        }
    };

    private MatchingService start(IngressLimits limits) throws IOException {
        return MatchingService.start(directory, List.of(AAPL, MSFT), new JournalOptions(FsyncPolicy.OS, 0),
                micros::get, 100, observer, limits);
    }

    private static CompletableFuture<EventBatch> buy(MatchingService service, String symbol, long member, long price) {
        return service.submit(symbol, new LimitOrder(Side.BUY, price, 1), member, null);
    }

    private static long orderId(CompletableFuture<EventBatch> future) {
        return future.join().events().stream().filter(OrderAccepted.class::isInstance).map(OrderAccepted.class::cast)
                .findFirst().orElseThrow().orderId();
    }

    private static RequestRefusedException refusal(CompletableFuture<?> future) {
        assertThat(future).isCompletedExceptionally();
        try {
            future.join();
        } catch (CompletionException e) {
            return (RequestRefusedException) e.getCause();
        }
        throw new AssertionError("not refused");
    }

    @Test
    @Rulebook("OE-008")
    void aMemberOverItsRateIsRefusedUntilTheBucketRefills() throws IOException {
        try (MatchingService service = start(new IngressLimits(3, 0, 0))) {
            long first = orderId(buy(service, "AAPL", 7, 100));
            orderId(service.submit("MSFT", new LimitOrder(Side.BUY, 100, 1), 7, null));      // all instruments count
            service.submit("AAPL", new ReplaceOrder(first, 101, 1), 7, null).join();             // a replace counts
            long sequence = service.book("AAPL", 0).join().lastCommandSequence();

            RequestRefusedException refused = refusal(buy(service, "AAPL", 7, 100));
            assertThat(refused.reason()).isEqualTo(Reason.THROTTLED);
            assertThat(refused.retryAfter()).contains(Duration.ofNanos(333_334_000));
            assertThat(refusal(service.submit("AAPL", new MarketOrder(Side.SELL, 1), 7, null)).reason())
                    .isEqualTo(Reason.THROTTLED);
            assertThat(refusal(service.submit("AAPL", new StopOrder(Side.SELL, 50, 0, 1), 7, null)).reason())
                    .isEqualTo(Reason.THROTTLED);
            assertThat(service.book("AAPL", 0).join().lastCommandSequence())
                    .as("a refused command is not journaled").isEqualTo(sequence);
            assertThat(throttled).containsExactly("AAPL/7/THROTTLED", "AAPL/7/THROTTLED", "AAPL/7/THROTTLED");

            service.submit("AAPL", new CancelOrder(first), 7, null).join();                     // never refused
            buy(service, "AAPL", 0, 100).join();                                                  // the operator is exempt
            buy(service, "AAPL", 8, 100).join();                                                  // per member

            micros.addAndGet(333_333);
            assertThat(refusal(buy(service, "AAPL", 7, 100)).retryAfter()).contains(Duration.ofNanos(1_000));
            micros.addAndGet(1);
            buy(service, "AAPL", 7, 100).join();
            assertThat(refusal(buy(service, "AAPL", 7, 100)).reason()).isEqualTo(Reason.THROTTLED);

            micros.addAndGet(5_000_000);                                                          // a full bucket, no more
            for (int i = 0; i < 3; i++) {
                buy(service, "AAPL", 7, 100).join();
            }
            assertThat(refusal(buy(service, "AAPL", 7, 100)).reason()).isEqualTo(Reason.THROTTLED);
        }
    }

    @Test
    @Rulebook("OE-008")
    void aClockThatStepsBackRefillsNothing() throws IOException {
        try (MatchingService service = start(new IngressLimits(1, 0, 0))) {
            buy(service, "AAPL", 7, 100).join();
            micros.addAndGet(-3_600_000_000L);                         // an hour back
            assertThat(refusal(buy(service, "AAPL", 7, 100)).reason()).isEqualTo(Reason.THROTTLED);
            micros.addAndGet(999_999);                                  // refills from the new time on
            assertThat(refusal(buy(service, "AAPL", 7, 100)).retryAfter()).contains(Duration.ofNanos(1_000));
            micros.incrementAndGet();
            buy(service, "AAPL", 7, 100).join();
        }
    }

    @Test
    @Rulebook("OE-009")
    void aMemberOverItsOrderToTradeRatioMayOnlyCancelUntilTheDayEnds() throws IOException {
        try (MatchingService service = start(new IngressLimits(0, 2, 4))) {
            long[] orders = new long[4];
            for (int i = 0; i < 4; i++) {
                orders[i] = orderId(buy(service, "AAPL", 7, 100));                // 4 messages, no trade: the minimum
            }
            RequestRefusedException refused = refusal(buy(service, "AAPL", 7, 100));
            assertThat(refused.reason()).isEqualTo(Reason.ORDER_TO_TRADE_LIMIT);
            assertThat(refused.retryAfter()).contains(Duration.ofNanos((MIDNIGHT - TEN_AM) * 1_000));
            assertThat(refusal(service.submit("AAPL", new ReplaceOrder(orders[0], 101, 1), 7, null)).reason())
                    .isEqualTo(Reason.ORDER_TO_TRADE_LIMIT);
            assertThat(throttled).containsExactly("AAPL/7/ORDER_TO_TRADE_LIMIT", "AAPL/7/ORDER_TO_TRADE_LIMIT");
            service.submit("AAPL", new CancelOrder(orders[3]), 7, null).join();   // 5 messages
            buy(service, "MSFT", 7, 100).join();                                   // per instrument
            buy(service, "AAPL", 8, 100).join();                                   // per member

            // Member 8 sells into member 7's bids: trades count for the maker too.
            service.submit("AAPL", new MarketOrder(Side.SELL, 2), 8, null).join();   // 7: 2 trades, 5 messages
            assertThat(refusal(buy(service, "AAPL", 7, 100)).reason())
                    .as("6 messages would be above 2 × 2 trades").isEqualTo(Reason.ORDER_TO_TRADE_LIMIT);
            service.submit("AAPL", new MarketOrder(Side.SELL, 1), 8, null).join();   // 7: 3 trades
            buy(service, "AAPL", 7, 100).join();                                       // 6 messages ≤ 2 × 3
            assertThat(refusal(buy(service, "AAPL", 7, 100)).reason()).isEqualTo(Reason.ORDER_TO_TRADE_LIMIT);

            micros.set(MIDNIGHT);                                                      // a new UTC day
            buy(service, "AAPL", 7, 100).join();
        }
    }

    @Test
    @Rulebook("OE-009")
    void theRatioWaitsForTheMinimumAndTheOperatorIsExempt() throws IOException {
        try (MatchingService service = start(new IngressLimits(0, 1, 3))) {
            for (int i = 0; i < 3; i++) {
                buy(service, "AAPL", 7, 100).join();
            }
            assertThat(refusal(buy(service, "AAPL", 7, 100)).reason()).isEqualTo(Reason.ORDER_TO_TRADE_LIMIT);
            for (int i = 0; i < 10; i++) {
                buy(service, "AAPL", 0, 100).join();
            }
        }
    }

    @Test
    void withoutLimitsNothingIsThrottled() throws IOException {
        try (MatchingService service = start(IngressLimits.NONE)) {
            for (int i = 0; i < 2_000; i++) {
                buy(service, "AAPL", 7, 100).join();
            }
        }
        assertThat(throttled).isEmpty();
    }

    @Test
    @Rulebook({"OE-008", "OE-009"})
    void aCommandTheInstrumentRefusesGivesBackWhatItCounted() {
        Throttles throttles = new Throttles(new IngressLimits(1, 1, 1), micros::get);
        Command order = new LimitOrder(Side.BUY, 100, 1);
        assertThat(throttles.check("AAPL", order, 7)).isNull();
        throttles.release("AAPL", order, 7);                     // say, OVERLOADED: neither the token nor the message
        assertThat(throttles.check("AAPL", order, 7)).isNull();
        assertThat(throttles.check("AAPL", order, 7).reason()).isEqualTo(Reason.ORDER_TO_TRADE_LIMIT);

        Throttles rate = new Throttles(new IngressLimits(1, 0, 0), micros::get);
        assertThat(rate.check("AAPL", order, 7)).isNull();
        rate.release("AAPL", order, 7);
        rate.release("AAPL", order, 7);                          // never more than the bucket holds
        assertThat(rate.check("AAPL", order, 7)).isNull();
        assertThat(rate.check("AAPL", order, 7).reason()).isEqualTo(Reason.THROTTLED);
        rate.release("AAPL", new CancelOrder(1), 7);             // a cancel took no token
        rate.release("AAPL", order, 0);                          // nor did the operator
        assertThat(rate.check("AAPL", order, 7).reason()).isEqualTo(Reason.THROTTLED);
    }

    @Test
    void aCommandRefusedAfterTheVenueStopsIsNotThrottled() throws IOException {
        MatchingService service = start(new IngressLimits(1, 0, 0));
        service.close();
        assertThat(refusal(buy(service, "AAPL", 7, 100)).reason()).isEqualTo(Reason.STOPPED);
        assertThat(refusal(buy(service, "AAPL", 7, 100)).reason()).as("the first gave its token back")
                .isEqualTo(Reason.STOPPED);
        assertThat(throttled).isEmpty();
    }

    @Test
    void limitsMustNotBeNegative() {
        assertThatIllegalArgumentException().isThrownBy(() -> new IngressLimits(-1, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new IngressLimits(0, -1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new IngressLimits(0, 0, -1));
    }

    @Test
    void anUnknownInstrumentIsRefusedBeforeTheThrottles() throws IOException {
        try (MatchingService service = start(new IngressLimits(1, 0, 0))) {
            Command order = new LimitOrder(Side.BUY, 100, 1);
            assertThat(refusal(service.submit("IBM", order, 7, null)).reason()).isEqualTo(Reason.UNKNOWN_INSTRUMENT);
            service.submit("AAPL", order, 7, null).join();
        }
    }
}
