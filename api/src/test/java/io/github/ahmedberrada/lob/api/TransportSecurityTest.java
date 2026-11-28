package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/** A port open beyond loopback without TLS stops a secured venue (AC-010). */
@Rulebook("AC-010")
@ExtendWith(OutputCaptureExtension.class)
class TransportSecurityTest {

    private static final String HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("x");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(VenueConfiguration.class, SecurityConfiguration.class)
            .withPropertyValues("lob.data-directory=target/test-venue-transport",
                    "lob.instruments[0].symbol=AAPL", "lob.instruments[0].tick-size=0.01",
                    "lob.instruments[0].max-order-quantity=100", "lob.instruments[0].max-price=1000.00",
                    "lob.security.users[0].name=admin", "lob.security.users[0].password=" + HASH,
                    "lob.security.users[0].roles[0]=ADMIN");

    @Test
    void restOnEveryInterfaceInClearStopsTheVenue() {
        runner.withPropertyValues("server.address=0.0.0.0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("REST on 0.0.0.0 (server.ssl)");
        });
        runner.run(context -> assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("REST on every interface"));
    }

    @Test
    void loopbackTlsOrAnExplicitTerminationInFrontStart(CapturedOutput output) {
        runner.withPropertyValues("server.address=127.0.0.1").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("server.address=localhost").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("server.address=0.0.0.0", "server.ssl.bundle=venue")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("server.address=0.0.0.0", "server.ssl.bundle=venue", "server.ssl.enabled=false")
                .run(context -> assertThat(context).as("TLS switched off is clear").hasFailed());
        runner.withPropertyValues("server.address=0.0.0.0", "lob.security.tls-terminated-in-front=true")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).contains("lob.security.tls-terminated-in-front is true");
    }

    @Test
    void securityOffIsNotChecked() {
        runner.withPropertyValues("server.address=0.0.0.0", "lob.security.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void fixBeyondLoopbackNeedsTls() {
        LobProperties.FixProperties open = new LobProperties.FixProperties(true, "0.0.0.0", 9878, "LOB",
                java.util.List.of("M1"), java.util.List.of(), null, null, java.util.Map.of(), null);
        org.springframework.mock.env.MockEnvironment environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("server.address", "127.0.0.1");
        LobProperties.SecurityProperties security = new LobProperties.SecurityProperties(true, java.util.List.of(), 5,
                java.time.Duration.ofMinutes(1), false);
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> TransportSecurity.check(properties(open, security), environment))
                .withMessageContaining("FIX on 0.0.0.0 (lob.fix.tls)");
        LobProperties.FixProperties tls = new LobProperties.FixProperties(true, "0.0.0.0", 9878, "LOB",
                java.util.List.of("M1"), java.util.List.of(), null, null, java.util.Map.of(),
                new LobProperties.FixTlsProperties(true, null, null, "PKCS12", null, null, "PKCS12", false));
        TransportSecurity.check(properties(tls, security), environment);
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> FixConfiguration.tls(tls.tls())).withMessageContaining("key-store");
        assertThat(FixConfiguration.tls(null)).isNull();
        assertThat(tls.tls().toString()).doesNotContain("Password");
    }

    private static LobProperties properties(LobProperties.FixProperties fix, LobProperties.SecurityProperties security) {
        return new LobProperties(java.nio.file.Path.of("x"), 10, io.github.ahmedberrada.lob.journal.FsyncPolicy.OS, 0,
                java.util.List.of(), fix, null, null, java.util.List.of(), security, null);
    }
}
