package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.OrderRejected;
import io.github.ahmedberrada.lob.core.ReplaceRejected;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.core.VolatilityInterruption;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.service.CommandObserver;
import io.micrometer.core.instrument.Counter;
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

    private record Meters(Timer duration, Counter trades, Counter rejections, Counter interruptions) {
    }

    private final MeterRegistry registry;
    private final Map<String, Meters> meters = new ConcurrentHashMap<>();

    MicrometerCommandObserver(MeterRegistry registry) {
        this.registry = registry;
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
                        .description("Time the engine takes for one command, journal included")
                        .publishPercentileHistogram().minimumExpectedValue(Duration.ofNanos(500))
                        .maximumExpectedValue(Duration.ofMillis(500)).register(registry),
                Counter.builder("lob.trades").tag("symbol", symbol).description("Trades executed").register(registry),
                Counter.builder("lob.rejections").tag("symbol", symbol)
                        .description("Orders and replaces the engine rejected").register(registry),
                Counter.builder("lob.volatility.interruptions").tag("symbol", symbol)
                        .description("Trading interruptions caused by the volatility band").register(registry));
    }
}
