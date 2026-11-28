package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.fix.LogonAuthenticator;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

@Rulebook("AC-009")
class LoginThrottleTest {

    private final AtomicLong nanos = new AtomicLong(-5_000_000_000L);       // System.nanoTime may be negative
    private final LoginThrottle throttle = new LoginThrottle(3, Duration.ofSeconds(60), nanos::get);

    @Test
    void aNameIsLockedOutFromAnAddressAfterTheFailuresInARow() {
        throttle.failed("alice", "10.0.0.1");
        throttle.failed("alice", "10.0.0.1");
        assertThat(throttle.lockedOut("alice", "10.0.0.1")).isEmpty();
        throttle.failed("alice", "10.0.0.1");
        assertThat(throttle.lockedOut("alice", "10.0.0.1")).contains(Duration.ofSeconds(60));
        assertThat(throttle.lockedOut("alice", "10.0.0.2")).as("another address").isEmpty();
        assertThat(throttle.lockedOut("bob", "10.0.0.1")).as("another name").isEmpty();

        nanos.addAndGet(59_000_000_000L);
        assertThat(throttle.lockedOut("alice", "10.0.0.1")).contains(Duration.ofSeconds(1));
        nanos.addAndGet(1_000_000_000L);
        assertThat(throttle.lockedOut("alice", "10.0.0.1")).isEmpty();

        throttle.failed("alice", "10.0.0.1");                                // counted again from zero
        assertThat(throttle.lockedOut("alice", "10.0.0.1")).isEmpty();
        throttle.failed("alice", "10.0.0.1");
        throttle.failed("alice", "10.0.0.1");
        assertThat(throttle.lockedOut("alice", "10.0.0.1")).isPresent();
    }

    @Test
    void aSuccessClearsTheCount() {
        throttle.failed("alice", "10.0.0.1");
        throttle.failed("alice", "10.0.0.1");
        throttle.succeeded("alice", "10.0.0.1");
        throttle.failed("alice", "10.0.0.1");
        throttle.failed("alice", "10.0.0.1");
        assertThat(throttle.lockedOut("alice", "10.0.0.1")).isEmpty();
    }

    @Test
    void thePortOfAnAddressDoesNotMatter() {
        throttle.failed("FIX MEMBER1", "/10.0.0.1:50001");
        throttle.failed("FIX MEMBER1", "host.example/10.0.0.1:50002");
        throttle.failed("FIX MEMBER1", "10.0.0.1");
        assertThat(throttle.lockedOut("FIX MEMBER1", "/10.0.0.1:50003")).isPresent();
        assertThat(LoginThrottle.host("/[0:0:0:0:0:0:0:1]:5000")).isEqualTo("0:0:0:0:0:0:0:1");
        assertThat(LoginThrottle.host("0:0:0:0:0:0:0:1")).isEqualTo("0:0:0:0:0:0:0:1");
        assertThat(LoginThrottle.host("[::1")).isEqualTo("[::1");
        assertThat(LoginThrottle.host(null)).isEqualTo("unknown");
    }

    @Test
    void zeroFailuresSwitchesLockoutsOff() {
        for (int i = 0; i < 100; i++) {
            LoginThrottle.NONE.failed("alice", "10.0.0.1");
        }
        assertThat(LoginThrottle.NONE.lockedOut("alice", "10.0.0.1")).isEmpty();
    }

    @Test
    void theCountsAreBoundedButLockoutsAreKept() {
        for (int i = 0; i < 3; i++) {
            throttle.failed("alice", "10.0.0.1");
        }
        for (int i = 0; i < LoginThrottle.MAX_TRACKED + 10; i++) {
            throttle.failed("guess" + i, "10.9.9.9");
        }
        throttle.failed("guess1", "10.9.9.9");
        throttle.failed("guess1", "10.9.9.9");
        assertThat(throttle.lockedOut("alice", "10.0.0.1")).as("a lockout survives a flood").isPresent();
        assertThat(throttle.lockedOut("guess1", "10.9.9.9")).as("counts below the limit were forgotten").isEmpty();
    }

    @Test
    void aLockedOutFixSessionIsRefusedWithoutCheckingItsPassword() {
        AtomicLong checks = new AtomicLong();
        LogonAuthenticator passwords = (compId, password) -> {
            checks.incrementAndGet();
            return "secret".equals(password);
        };
        LogonAuthenticator logons = FixConfiguration.throttled(passwords, throttle);
        for (int i = 0; i < 3; i++) {
            assertThat(logons.authenticate("MEMBER1", "guess", "/10.0.0.1:4000")).isFalse();
        }
        assertThat(logons.authenticate("MEMBER1", "secret", "/10.0.0.1:4001")).isFalse();
        assertThat(checks).hasValue(3);
        assertThat(logons.authenticate("MEMBER1", "secret", "/10.0.0.2:4000")).as("from elsewhere").isTrue();
        assertThat(throttle.lockedOut("MEMBER1", "10.0.0.1")).as("FIX names are apart from REST users").isEmpty();

        nanos.addAndGet(60_000_000_000L);
        assertThat(logons.authenticate("MEMBER1", "secret", "/10.0.0.1:4002")).isTrue();
        assertThat(logons.authenticate("MEMBER1", "guess")).isFalse();             // no address known
        assertThat(throttle.lockedOut("FIX MEMBER1", "10.0.0.1")).isEmpty();
    }
}
