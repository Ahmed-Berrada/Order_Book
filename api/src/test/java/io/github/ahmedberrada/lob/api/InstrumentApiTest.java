package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class InstrumentApiTest {

    @DynamicPropertySource
    static void venue(DynamicPropertyRegistry registry) {
        TemporaryVenue.register(registry);
    }

    @Autowired
    MockMvcTester mvc;

    private void limit(String side, String price, long quantity) {
        assertThat(mvc.post().uri("/api/v1/instruments/AAPL/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"side\":\"%s\",\"type\":\"LIMIT\",\"price\":\"%s\",\"quantity\":%d}"
                        .formatted(side, price, quantity))).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void listsTheConfiguredInstruments() {
        assertThat(mvc.get().uri("/api/v1/instruments")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                [{"symbol":"AAPL","tickSize":"0.01","maxOrderQuantity":1000000,"maxPrice":"100000.00","halted":false},
                 {"symbol":"MSFT","tickSize":"0.01","maxOrderQuantity":1000000,"maxPrice":"100000.00","halted":false}]""");
    }

    @Test
    void bookShowsTheBestLevelsWithDecimalPrices() {
        limit("BUY", "185.20", 5);
        limit("BUY", "185.20", 7);
        limit("BUY", "185.10", 1);
        limit("SELL", "185.30", 4);
        limit("SELL", "185.50", 2);

        assertThat(mvc.get().uri("/api/v1/instruments/AAPL/book")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                {"symbol":"AAPL","commandSequence":5,
                 "bids":[{"price":"185.20","quantity":12,"orders":2},{"price":"185.10","quantity":1,"orders":1}],
                 "asks":[{"price":"185.30","quantity":4,"orders":1},{"price":"185.50","quantity":2,"orders":1}]}""");
        assertThat(mvc.get().uri("/api/v1/instruments/AAPL/book?depth=1")).bodyJson()
                .extractingPath("$.bids.length()").isEqualTo(1);
        assertThat(mvc.get().uri("/api/v1/instruments/MSFT/book")).bodyJson()
                .isLenientlyEqualTo("{\"commandSequence\":0,\"bids\":[],\"asks\":[]}");
    }

    @Test
    @Rulebook("OE-001")
    void unknownInstrumentAndOutOfRangeDepth() {
        assertThat(mvc.get().uri("/api/v1/instruments/TSLA/book")).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .isLenientlyEqualTo("{\"reason\":\"UNKNOWN_INSTRUMENT\"}");
        assertThat(mvc.get().uri("/api/v1/instruments/AAPL/book?depth=0")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/v1/instruments/AAPL/book?depth=101")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/v1/instruments/AAPL/book?depth=100")).hasStatusOk();
    }
}
