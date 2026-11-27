package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metrics and health of the venue (docs/operations/observability.md): the state of each instrument's queue
 * and thread, and a readiness signal that goes down when an instrument has halted (OE-006).
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

    @Bean
    MeterBinder instrumentMeters(MatchingService service) {
        return registry -> {
            for (InstrumentConfig instrument : service.instruments()) {
                String symbol = instrument.symbol();
                Gauge.builder("lob.queue.depth", service, s -> s.queueDepth(symbol)).tag("symbol", symbol)
                        .description("Commands waiting for the instrument's thread").register(registry);
                Gauge.builder("lob.queue.capacity", service, s -> s.queueCapacity(symbol)).tag("symbol", symbol)
                        .description("Commands the queue holds before refusing with OVERLOADED").register(registry);
                Gauge.builder("lob.instrument.halted", service, s -> s.isHalted(symbol) ? 1 : 0).tag("symbol", symbol)
                        .description("1 if the instrument stopped after a journal failure").register(registry);
            }
        };
    }

    /** DOWN while any instrument is halted: the venue cannot do what it is for (OE-006). */
    @Bean
    HealthIndicator lobHealthIndicator(MatchingService service) {
        return () -> {
            List<String> halted = service.instruments().stream().map(InstrumentConfig::symbol)
                    .filter(service::isHalted).toList();
            return halted.isEmpty()
                    ? Health.up().withDetail("instruments", service.instruments().size()).build()
                    : Health.down().withDetail("halted", halted).build();
        };
    }
}
