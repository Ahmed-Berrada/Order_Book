package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import io.github.ahmedberrada.lob.journal.JournalOptions;
import io.github.ahmedberrada.lob.marketdata.MarketDataPublisher;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/** Connection-level behaviour that a real socket cannot produce on demand: slow and broken clients. */
class MarketDataWebSocketHandlerTest {

    private static final InstrumentConfig AAPL =
            new InstrumentConfig("AAPL", new BigDecimal("0.01"), 1_000, new BigDecimal("1000.00"));

    @TempDir
    Path directory;

    private MatchingService service;
    private MarketDataWebSocketHandler handler;
    private final CountDownLatch never = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        AtomicLong clock = new AtomicLong();
        service = MatchingService.start(directory, List.of(AAPL), new JournalOptions(FsyncPolicy.OS, 0),
                clock::incrementAndGet, 100);
        handler = new MarketDataWebSocketHandler(service, MarketDataPublisher.start(service),
                JsonMapper.builder().build(), 1);
    }

    @AfterEach
    void stop() throws IOException {
        never.countDown();
        service.close();
    }

    private WebSocketSession session(String path) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        when(session.getUri()).thenReturn(URI.create("ws://localhost" + path));
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    private void trade(int orders) {
        for (int i = 0; i < orders; i++) {
            service.submit("AAPL", new LimitOrder(Side.BUY, 9_990, 1)).join();
        }
    }

    @Test
    @Rulebook("MD-008")
    void aClientThatStopsReadingIsClosedAsASlowConsumer() throws Exception {
        WebSocketSession session = session("/api/v1/market-data/AAPL");
        doAnswer(invocation -> {
            never.await();                       // the network write never completes
            return null;
        }).when(session).sendMessage(any());
        handler.afterConnectionEstablished(session);

        trade(5);

        verify(session, timeout(5_000)).close(MarketDataWebSocketHandler.SLOW_CONSUMER);
        assertThat(MarketDataWebSocketHandler.SLOW_CONSUMER.getCode()).isEqualTo(1008);
    }

    @Test
    void aBrokenConnectionIsClosedNormallyAndUnsubscribed() throws Exception {
        WebSocketSession session = session("/api/v1/market-data/AAPL");
        doThrow(new IOException("reset")).when(session).sendMessage(any());
        doThrow(new IOException("already closed")).when(session).close(any());
        handler.afterConnectionEstablished(session);

        verify(session, timeout(5_000)).close(CloseStatus.NORMAL);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        handler.afterConnectionClosed(session("/api/v1/market-data/AAPL"), CloseStatus.NORMAL);   // never established
    }

    @Test
    void handshakeAcceptsOnlyListedSymbolsUnderTheFeedPath() {
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        assertThat(handler.beforeHandshake(request("/api/v1/market-data/AAPL"), response, handler, new HashMap<>())).isTrue();
        assertThat(handler.beforeHandshake(request("/api/v1/market-data/TSLA"), response, handler, new HashMap<>())).isFalse();
        assertThat(handler.beforeHandshake(request("/elsewhere/AAPL"), response, handler, new HashMap<>())).isFalse();
        verify(response, times(2)).setStatusCode(HttpStatus.NOT_FOUND);
        handler.afterHandshake(request("/api/v1/market-data/AAPL"), response, handler, null);
    }

    private static ServerHttpRequest request(String path) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("ws://localhost" + path));
        return request;
    }
}
