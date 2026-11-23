package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.marketdata.MarketDataPublisher;
import io.github.ahmedberrada.lob.marketdata.QueuedSubscriber;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /api/v1/market-data/{symbol}}: one instrument's feed per connection (ADR-0007 §4). Each
 * connection gets a {@link QueuedSubscriber}, so writing to a slow client never blocks the instrument's
 * thread; a client that falls behind is closed with 1008 "slow consumer" (MD-008).
 */
final class MarketDataWebSocketHandler extends TextWebSocketHandler implements HandshakeInterceptor {

    static final String PATH = "/api/v1/market-data/";
    static final CloseStatus SLOW_CONSUMER = CloseStatus.POLICY_VIOLATION.withReason("slow consumer");
    private static final String SUBSCRIBER = "subscriber";
    private static final String SUBSCRIPTION = "subscription";

    private final MatchingService service;
    private final MarketDataPublisher publisher;
    private final JsonMapper json;
    private final int queueCapacity;

    MarketDataWebSocketHandler(MatchingService service, MarketDataPublisher publisher, JsonMapper json,
            int queueCapacity) {
        this.service = service;
        this.publisher = publisher;
        this.json = json;
        this.queueCapacity = queueCapacity;
    }

    /** Refuses the handshake with 404 for a symbol that is not listed (OE-001). */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Map<String, Object> attributes) {
        if (instrument(request.getURI().getPath()).isEmpty()) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Exception exception) {
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        InstrumentConfig instrument = instrument(session.getUri().getPath()).orElseThrow();
        QueuedSubscriber subscriber = new QueuedSubscriber(queueCapacity,
                message -> session.sendMessage(new TextMessage(json.writeValueAsString(MarketDataJson.of(message, instrument)))),
                why -> close(session, why == QueuedSubscriber.Disconnect.SLOW_CONSUMER ? SLOW_CONSUMER : CloseStatus.NORMAL));
        session.getAttributes().put(SUBSCRIBER, subscriber);
        session.getAttributes().put(SUBSCRIPTION, publisher.subscribe(instrument.symbol(), subscriber));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (session.getAttributes().get(SUBSCRIPTION) instanceof MarketDataPublisher.Subscription subscription) {
            subscription.cancel();
        }
        if (session.getAttributes().get(SUBSCRIBER) instanceof QueuedSubscriber subscriber) {
            subscriber.close();
        }
    }

    private Optional<InstrumentConfig> instrument(String path) {
        return path.startsWith(PATH) ? service.instrument(path.substring(PATH.length())) : Optional.empty();
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            // already gone
        }
    }
}
