package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import io.github.ahmedberrada.lob.service.MatchingService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class LobEngineApplicationTest {

    @DynamicPropertySource
    static void venue(DynamicPropertyRegistry registry) {
        TemporaryVenue.register(registry);
    }

    @Autowired
    LobProperties properties;

    @Autowired
    MatchingService service;

    @Test
    void startsTheConfiguredVenue() {
        assertThat(properties.fsync()).isEqualTo(FsyncPolicy.OS);
        assertThat(properties.queueCapacity()).isEqualTo(10_000);
        assertThat(service.instruments()).extracting(i -> i.symbol()).containsExactly("AAPL", "MSFT");
        assertThat(service.instrument("AAPL").orElseThrow().tickSize()).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(service.recoveries()).containsOnlyKeys("AAPL", "MSFT");
    }
}
