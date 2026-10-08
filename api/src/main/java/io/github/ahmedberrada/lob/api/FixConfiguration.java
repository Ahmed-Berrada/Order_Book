package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.fix.FixGateway;
import io.github.ahmedberrada.lob.fix.FixGatewayConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import quickfix.ConfigError;

/**
 * Hosts the FIX gateway next to the REST API, on the same matching service (ADR-0006 §1). It depends
 * on the service, so Spring stops it first on shutdown: members are logged out before the journals
 * close.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("lob.fix.enabled")
class FixConfiguration {

    @Bean(destroyMethod = "close")
    FixGateway fixGateway(MatchingService service, LobProperties properties) throws ConfigError {
        LobProperties.FixProperties fix = properties.fix();
        Path store = fix.storeDirectory() != null ? fix.storeDirectory() : properties.dataDirectory().resolve("fix");
        return FixGateway.start(service, new FixGatewayConfig(fix.port(), fix.senderCompId(), fix.members(), store));
    }
}
