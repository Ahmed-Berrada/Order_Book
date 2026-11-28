package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Each REST command is a span, a child of its request's, and a sample of lob.request (ADR-0023). */
@SpringBootTest(properties = {"management.tracing.sampling.probability=1.0",
        "management.opentelemetry.tracing.export.schedule-delay=50ms"})
@AutoConfigureMockMvc
class TracingTest {

    @TestConfiguration
    static class Exporter {
        @Bean
        InMemorySpanExporter spans() {
            return InMemorySpanExporter.create();
        }
    }

    @DynamicPropertySource
    static void venue(DynamicPropertyRegistry registry) {
        TemporaryVenue.register(registry);
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    InMemorySpanExporter exporter;

    @Autowired
    MeterRegistry meters;

    @Autowired
    org.springframework.context.ApplicationContext context;

    @Test
    void withoutAnEndpointNothingIsExportedOverOtlp() {
        assertThat(context.getBeansOfType(io.opentelemetry.sdk.trace.export.SpanExporter.class))
                .as("only this test's exporter").containsOnlyKeys("spans");
    }

    private static String attribute(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    private SpanData span(String name) {
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> exporter.getFinishedSpanItems().stream().anyMatch(s -> s.getName().equals(name)));
        return exporter.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void anOrderIsASpanUnderItsRequestWithItsJournalSequence() {
        exporter.reset();
        assertThat(mvc.post().uri("/api/v1/instruments/AAPL/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"10.00\",\"quantity\":1}"))
                .hasStatus(HttpStatus.CREATED);

        SpanData command = span("lob LimitOrder");
        assertThat(attribute(command, "symbol")).isEqualTo("AAPL");
        assertThat(attribute(command, "lob.outcome")).isEqualTo("accepted");
        assertThat(attribute(command, "lob.member")).isEqualTo("0");
        assertThat(Long.parseLong(attribute(command, "lob.command.sequence"))).isPositive();

        // Its ancestors, up to the HTTP request's span (with Spring Security's in between), are in the same trace.
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> exporter.getFinishedSpanItems().stream()
                .anyMatch(s -> s.getName().toLowerCase(java.util.Locale.ROOT).startsWith("http post")));
        List<SpanData> all = exporter.getFinishedSpanItems();
        List<String> ancestors = new java.util.ArrayList<>();
        SpanData current = command;
        while (current != null) {
            String parent = current.getParentSpanId();
            current = all.stream().filter(s -> s.getSpanId().equals(parent)).findFirst().orElse(null);
            if (current != null) {
                assertThat(current.getTraceId()).isEqualTo(command.getTraceId());
                ancestors.add(current.getName().toLowerCase(java.util.Locale.ROOT));
            }
        }
        assertThat(ancestors).anyMatch(name -> name.startsWith("http post"));

        var timer = meters.get(TracedCommands.NAME).tag("symbol", "AAPL").tag("lob.command", "LimitOrder")
                .tag("lob.outcome", "accepted").timer();
        assertThat(timer.count()).isPositive();
        assertThat(timer.takeSnapshot().histogramCounts()).as("a histogram, for quantiles").isNotEmpty();
    }

    @Test
    void aRejectionAndARefusalAreTheirOutcomes() {
        exporter.reset();
        assertThat(mvc.post().uri("/api/v1/instruments/AAPL/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);                   // no liquidity: journaled, rejected
        assertThat(attribute(span("lob MarketOrder"), "lob.outcome")).isEqualTo("rejected");

        assertThat(mvc.delete().uri("/api/v1/instruments/AAPL/orders/999")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(attribute(span("lob CancelOrder"), "lob.outcome")).isEqualTo("rejected");

        exporter.reset();
        assertThat(mvc.delete().uri("/api/v1/instruments/NOPE/orders/1")).hasStatus(HttpStatus.NOT_FOUND);
        SpanData refused = span("lob CancelOrder");
        assertThat(attribute(refused, "symbol")).as("an unlisted symbol is no tag value").isEqualTo("unknown");
        assertThat(attribute(refused, "lob.outcome")).isEqualTo("refused");
        assertThat(attribute(refused, "lob.refusal")).isEqualTo("UNKNOWN_INSTRUMENT");
    }

    @Test
    void aCallersTraceparentIsContinued() {
        exporter.reset();
        String trace = "4bf92f3577b34da6a3ce929d0e0e4736";
        assertThat(mvc.post().uri("/api/v1/instruments/AAPL/orders").contentType(MediaType.APPLICATION_JSON)
                .header("traceparent", "00-" + trace + "-00f067aa0ba902b7-01")
                .content("{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"1.00\",\"quantity\":1}"))
                .hasStatus(HttpStatus.CREATED);
        assertThat(span("lob LimitOrder").getTraceId()).isEqualTo(trace);
    }
}
