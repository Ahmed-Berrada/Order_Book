package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.OrderRejected;
import io.github.ahmedberrada.lob.core.ReplaceRejected;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.core.VolatilityInterruption;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.service.CommandObserver;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Records what the instruments' threads do, per symbol (docs/operations/observability.md): how long each
 * command takes, and how many trades, rejections and volatility interruptions there are.
 */
final class MicrometerCommandObserver implements CommandObserver {

    private record Meters(Timer duration, Counter trades, Counter rejections, Counter interruptions, Timer flush,
            DistributionSummary groupSize) {
    }

    private final MeterRegistry registry;
    private final Map<String, Meters> meters = new ConcurrentHashMap<>();

    MicrometerCommandObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void throttled(String symbol, long memberId, RequestRefusedException.Reason reason) {
        Counter.builder("lob.throttled").tag("symbol", symbol).tag("member", Long.toString(memberId))
                .tag("reason", reason.name())
                .description("Members' commands refused by a throttle (OE-008, OE-009)").register(registry).increment();
    }

    @Override
    public void flushed(String symbol, int commands, long nanos) {
        Meters m = meters.computeIfAbsent(symbol, this::create);
        m.flush().record(nanos, TimeUnit.NANOSECONDS);
        m.groupSize().record(commands);
    }

    @Override
    public void processed(String symbol, EventBatch batch, long nanos) {
        Meters m = meters.computeIfAbsent(symbol, this::create);
        m.duration().record(nanos, TimeUnit.NANOSECONDS);
        for (Event event : batch.events()) {
            if (event instanceof TradeExecuted) {
                m.trades().increment();
            } else if (event instanceof OrderRejected || event instanceof ReplaceRejected) {
                m.rejections().increment();
            } else if (event instanceof VolatilityInterruption) {
                m.interruptions().increment();
            }
        }
    }

    private Meters create(String symbol) {
        return new Meters(
                Timer.builder("lob.command.duration").tag("symbol", symbol)
                        .description("Time the engine takes for one command: journal write and matching, and the device"
                                + " flush with fsync EVERY_COMMAND (with GROUP the flush is lob.journal.flush.duration)")
                        .publishPercentileHistogram().minimumExpectedValue(Duration.ofNanos(500))
                        .maximumExpectedValue(Duration.ofMillis(500)).register(registry),
                Counter.builder("lob.trades").tag("symbol", symbol).description("Trades executed").register(registry),
                Counter.builder("lob.rejections").tag("symbol", symbol)
                        .description("Orders and replaces the engine rejected").register(registry),
                Counter.builder("lob.volatility.interruptions").tag("symbol", symbol)
                        .description("Trading interruptions caused by the volatility band").register(registry),
                Timer.builder("lob.journal.flush.duration").tag("symbol", symbol)
                        .description("Time of one device flush of a group of commands (fsync GROUP, ADR-0020)")
                        .publishPercentileHistogram().minimumExpectedValue(Duration.ofNanos(1_000))
                        .maximumExpectedValue(Duration.ofSeconds(1)).register(registry),
                DistributionSummary.builder("lob.journal.group.size").tag("symbol", symbol)
                        .description("Commands covered by one device flush (fsync GROUP, ADR-0020)").register(registry));
    }
}
