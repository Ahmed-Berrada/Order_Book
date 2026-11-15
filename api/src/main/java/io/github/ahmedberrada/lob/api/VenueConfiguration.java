package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.journal.JournalOptions;
import io.github.ahmedberrada.lob.journal.TimeSource;
import io.github.ahmedberrada.lob.service.MatchingService;
import java.io.IOException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Starts the venue: recovers every instrument from its journal before the web server accepts
 * requests, and closes it on shutdown after the queued commands are processed (OE-007).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LobProperties.class)
class VenueConfiguration {

    @Bean(destroyMethod = "close")
    MatchingService matchingService(LobProperties properties) throws IOException {
        return MatchingService.start(properties.dataDirectory(), properties.instrumentConfigs(),
                new JournalOptions(properties.fsync(), properties.snapshotInterval()),
                TimeSource.system(), properties.queueCapacity());
    }
}
