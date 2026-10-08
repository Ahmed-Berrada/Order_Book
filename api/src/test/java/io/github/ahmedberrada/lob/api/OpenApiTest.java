package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The published contract lists every endpoint, so it cannot drift from the controllers unnoticed. */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiTest {

    @DynamicPropertySource
    static void venue(DynamicPropertyRegistry registry) {
        TemporaryVenue.register(registry);
    }

    @Autowired
    MockMvcTester mvc;

    @Test
    void servesTheOpenApiDocument() {
        var document = assertThat(mvc.get().uri("/v3/api-docs")).hasStatusOk().bodyJson();

        document.extractingPath("$.info.title").isEqualTo("LOB Engine API");
        document.extractingPath("$.paths").asMap().containsOnlyKeys(
                "/api/v1/instruments",
                "/api/v1/instruments/{symbol}/book",
                "/api/v1/instruments/{symbol}/orders",
                "/api/v1/instruments/{symbol}/orders/{orderId}");
        document.extractingPath("$.paths['/api/v1/instruments/{symbol}/orders/{orderId}']").asMap()
                .containsOnlyKeys("get", "delete");
        document.extractingPath("$.components.schemas.OrderRequest.required").asArray()
                .containsExactlyInAnyOrder("side", "type", "quantity");
    }

    @Test
    void servesTheSwaggerUi() {
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk();
    }
}
