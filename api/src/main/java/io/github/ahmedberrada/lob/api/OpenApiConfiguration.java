package io.github.ahmedberrada.lob.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Describes the API in the OpenAPI document generated from the controllers (ADR-0005 §6). */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI lobOpenApi() {
        return new OpenAPI().info(new Info()
                .title("LOB Engine API")
                .version("v1")
                .description("Order entry and market data for the limit order book matching engine. "
                        + "Prices are decimal strings on the instrument's tick grid; errors are RFC 9457 "
                        + "problem details with a machine-readable reason. See docs/interfaces/rest-api.md.")
                .license(new License().name("MIT")));
    }
}
