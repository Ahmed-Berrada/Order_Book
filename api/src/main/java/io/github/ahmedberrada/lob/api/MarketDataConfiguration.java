package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.marketdata.MarketDataPublisher;
import io.github.ahmedberrada.lob.service.MatchingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.json.JsonMapper;

/** Publishes the order books over WebSocket (ADR-0007). */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class MarketDataConfiguration implements WebSocketConfigurer {

    private final MarketDataWebSocketHandler handler;

    MarketDataConfiguration(MatchingService service, MarketDataPublisher publisher, JsonMapper json,
            LobProperties properties) {
        this.handler = new MarketDataWebSocketHandler(service, publisher, json, properties.marketData().queueCapacity());
    }

    @Bean
    static MarketDataPublisher marketDataPublisher(MatchingService service) {
        return MarketDataPublisher.start(service);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, MarketDataWebSocketHandler.PATH + "*").addInterceptors(handler);
    }
}
