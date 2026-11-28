package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.SelfTradeMode;
import io.github.ahmedberrada.lob.fix.FixGatewayConfig;
import io.github.ahmedberrada.lob.fix.LogonAuthenticator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** A venue with security on that cannot be secured does not start; secrets are hashes; failures are logged. */
@ExtendWith(OutputCaptureExtension.class)
class SecurityConfigurationTest {

    private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(VenueConfiguration.class, SecurityConfiguration.class);

    private static String[] properties(String... more) {
        String[] base = {
            "lob.data-directory=target/test-venue-security",
            "lob.instruments[0].symbol=AAPL",
            "lob.instruments[0].tick-size=0.01",
            "lob.instruments[0].max-order-quantity=100",
            "lob.instruments[0].max-price=1000.00",
        };
        return java.util.stream.Stream.concat(java.util.Arrays.stream(base), java.util.Arrays.stream(more))
                .toArray(String[]::new);
    }

    @Test
    @Rulebook("AC-003")
    void securityOnWithoutUsersStopsTheVenue() {
        runner.withPropertyValues(properties()).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "lob.security.enabled is true but no user is configured (lob.security.users); "
                            + "configure users, or disable security explicitly with lob.security.enabled=false");
        });
    }

    @Test
    @Rulebook("AC-002")
    void aSecretThatIsNotAPrefixedHashStopsTheVenue() {
        runner.withPropertyValues(properties("lob.security.users[0].name=admin",
                "lob.security.users[0].password=plain-text", "lob.security.users[0].roles[0]=ADMIN"))
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseMessage(
                        "the password of admin is not a hash with an algorithm prefix such as {bcrypt}; "
                                + "plain text is refused"));
    }

    @Test
    @Rulebook("AC-003")
    void aMemberUserWithoutAMemberAndADuplicateUserStopTheVenue() {
        String hash = ENCODER.encode("x");
        runner.withPropertyValues(properties("lob.security.users[0].name=alice",
                "lob.security.users[0].password=" + hash, "lob.security.users[0].roles[0]=MEMBER"))
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseMessage(
                        "the member user alice needs a member ID (member-id)"));
        runner.withPropertyValues(properties("lob.security.users[0].name=admin",
                "lob.security.users[0].password=" + hash, "lob.security.users[0].roles[0]=ADMIN",
                "lob.security.users[1].name=admin", "lob.security.users[1].password=" + hash,
                "lob.security.users[1].roles[0]=READ"))
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseMessage(
                        "user configured twice: admin"));
    }

    @Test
    @Rulebook("AC-003")
    void switchingSecurityOffIsExplicitAndWarned(CapturedOutput output) {
        new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(VenueConfiguration.class, SecurityConfiguration.class)
                .withPropertyValues(properties("lob.security.enabled=false")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(output).contains("lob.security.enabled is false");
        });
    }

    @Test
    @Rulebook("AC-006")
    void aFailedAuthenticationIsLoggedWithoutTheSecret(CapturedOutput output) {
        SecurityConfiguration configuration = new SecurityConfiguration();
        configuration.onFailure(new org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("mallory",
                        "hunter2"), new org.springframework.security.authentication.BadCredentialsException("bad")));

        assertThat(output).contains("authentication failed for user 'mallory' from unknown address")
                .doesNotContain("hunter2");
    }

    @Test
    @Rulebook({"AC-002", "AC-007"})
    void fixSessionsNeedAHashedPasswordAndTheRightOneToLogOn() {
        Path store = Path.of("target", "fix-store-test");
        FixGatewayConfig config = new FixGatewayConfig("127.0.0.1", 9999, "LOB", List.of("MEMBER1"), List.of("RISK"),
                store, SelfTradeMode.CANCEL_NEWEST);
        String memberHash = ENCODER.encode("member-pass");
        String riskHash = ENCODER.encode("risk-pass");
        LobProperties secured = fixProperties(true, Map.of("MEMBER1", memberHash, "RISK", riskHash));

        LogonAuthenticator authenticator = FixConfiguration.authenticator(secured, config);

        assertThat(authenticator.authenticate("MEMBER1", "member-pass")).isTrue();
        assertThat(authenticator.authenticate("MEMBER1", "risk-pass")).isFalse();
        assertThat(authenticator.authenticate("MEMBER1", null)).isFalse();
        assertThat(authenticator.authenticate("RISK", "risk-pass")).isTrue();
        assertThat(authenticator.authenticate("UNKNOWN", "member-pass")).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> FixConfiguration.authenticator(
                fixProperties(true, Map.of("MEMBER1", memberHash)), config)).withMessageContaining("RISK");
        assertThatIllegalStateException().isThrownBy(() -> FixConfiguration.authenticator(
                fixProperties(true, Map.of("MEMBER1", "plain", "RISK", riskHash)), config))
                .withMessageContaining("MEMBER1");
        assertThat(FixConfiguration.authenticator(fixProperties(false, Map.of()), config))
                .isSameAs(LogonAuthenticator.ALLOW_ALL);
    }

    private static LobProperties fixProperties(boolean secure, Map<String, String> passwords) {
        return new LobProperties(Path.of("x"), 10, io.github.ahmedberrada.lob.journal.FsyncPolicy.OS, 0, List.of(),
                new LobProperties.FixProperties(true, "127.0.0.1", 9999, "LOB", List.of("MEMBER1"), List.of("RISK"),
                        null, SelfTradeMode.CANCEL_NEWEST, passwords),
                null, null, List.of(), new LobProperties.SecurityProperties(secure, List.of()), null);
    }

    @Test
    @Rulebook("AC-002")
    void thePasswordToolPrintsAPrefixedHash() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        assertThat(HashPassword.run(new String[] {"pw"}, new PrintStream(out), new PrintStream(err))).isZero();
        assertThat(out.toString().trim()).startsWith("{bcrypt}");
        assertThat(ENCODER.matches("pw", out.toString().trim())).isTrue();
        assertThat(HashPassword.run(new String[] {}, new PrintStream(out), new PrintStream(err))).isEqualTo(2);
        assertThat(err.toString()).contains("usage");
    }
}
