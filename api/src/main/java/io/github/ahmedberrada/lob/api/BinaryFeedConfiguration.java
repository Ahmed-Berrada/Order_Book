package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.marketdata.MarketDataPublisher;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryFeed;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryFeedConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Starts the binary market data feed next to the WebSocket feed (ADR-0008). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("lob.binary-feed.enabled")
class BinaryFeedConfiguration {

    /** One session per venue start, so consumers never mix sequence numbers of two runs. */
    private static final DateTimeFormatter SESSION = DateTimeFormatter.ofPattern("yyMMddHHmm");

    @Bean(destroyMethod = "close")
    BinaryFeed binaryFeed(MatchingService service, MarketDataPublisher publisher, LobProperties properties)
            throws IOException {
        LobProperties.BinaryFeedProperties feed = properties.binaryFeed();
        return BinaryFeed.start(publisher, service.instruments(), new BinaryFeedConfig(
                new InetSocketAddress(feed.streamHost(), feed.streamPort()),
                new InetSocketAddress(feed.serviceAddress(), feed.servicePort()),
                SESSION.format(ZonedDateTime.now(ZoneOffset.UTC)), feed.retention(), feed.heartbeat(),
                feed.queueCapacity()));
    }
}
