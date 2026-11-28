package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.fix.FixGateway;
import io.github.ahmedberrada.lob.fix.FixGatewayConfig;
import io.github.ahmedberrada.lob.fix.LogonAuthenticator;
import io.github.ahmedberrada.lob.service.MatchingService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    FixGateway fixGateway(MatchingService service, LobProperties properties, LoginThrottle logins) throws ConfigError {
        LobProperties.FixProperties fix = properties.fix();
        Path store = fix.storeDirectory() != null ? fix.storeDirectory() : properties.dataDirectory().resolve("fix");
        FixGatewayConfig config = new FixGatewayConfig(fix.bindAddress(), fix.port(), fix.senderCompId(),
                fix.members(), fix.dropCopySessions(), store, fix.selfTradeMode(), properties.fixMemberIds());
        return FixGateway.start(service, config, throttled(authenticator(properties, config), logins));
    }

    /** Refuses a CompID locked out from the Logon's address without checking its password, and counts failures (AC-009). */
    static LogonAuthenticator throttled(LogonAuthenticator authenticator, LoginThrottle logins) {
        return new LogonAuthenticator() {
            @Override
            public boolean authenticate(String compId, String password) {
                return authenticate(compId, password, null);
            }

            @Override
            public boolean authenticate(String compId, String password, String remoteAddress) {
                if (logins.begin(LoginThrottle.Channel.FIX, compId, remoteAddress).isPresent()) {
                    return false;
                }
                boolean accepted = false;
                try {
                    accepted = authenticator.authenticate(compId, password);
                    return accepted;
                } finally {
                    logins.end(LoginThrottle.Channel.FIX, compId, remoteAddress, accepted);
                }
            }
        };
    }

    /**
     * Sessions log on with the password whose hash is configured for their CompID (AC-007). With security
     * enabled, a session without one stops the venue from starting (AC-003).
     */
    static LogonAuthenticator authenticator(LobProperties properties, FixGatewayConfig config) {
        if (!properties.security().enabled()) {
            return LogonAuthenticator.ALLOW_ALL;
        }
        Map<String, String> hashes = properties.fix().passwords();
        List<String> sessions = new ArrayList<>(config.memberCompIds());
        sessions.addAll(config.dropCopyCompIds());
        for (String compId : sessions) {
            String hash = hashes.get(compId);
            if (hash == null || !hash.matches("^\\{[a-z0-9]+}.+")) {
                throw new IllegalStateException("the FIX session " + compId + " has no password hash with an "
                        + "algorithm prefix in lob.fix.passwords (or disable security with lob.security.enabled=false)");
            }
        }
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return (compId, password) -> {
            String hash = hashes.get(compId);
            return hash != null && password != null && encoder.matches(password, hash);
        };
    }
}
