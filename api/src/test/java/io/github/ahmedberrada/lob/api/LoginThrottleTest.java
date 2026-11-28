package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.ahmedberrada.lob.api.LoginThrottle.Channel;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.fix.LogonAuthenticator;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

@Rulebook("AC-009")
class LoginThrottleTest {

    private final AtomicLong nanos = new AtomicLong(-5_000_000_000L);       // System.nanoTime may be negative
    private final LoginThrottle throttle = new LoginThrottle(3, Duration.ofSeconds(60), Duration.ZERO, nanos::get);

    private Optional<Duration> attempt(String name, String address, boolean succeeds) {
        Optional<LoginThrottle.Refusal> refusal = throttle.begin(Channel.REST, name, address);
        if (refusal.isEmpty()) {
            throttle.end(Channel.REST, name, address, succeeds);
        }
        return refusal.map(LoginThrottle.Refusal::retryAfter);
    }

    private void fail(String name, String address, int times) {
        for (int i = 0; i < times; i++) {
            assertThat(attempt(name, address, false)).isEmpty();
        }
    }

    @Test
    void aNameIsLockedOutFromAnAddressAfterTheFailuresInARow() {
        fail("alice", "10.0.0.1", 3);
        assertThat(attempt("alice", "10.0.0.1", true)).contains(Duration.ofSeconds(60));
        assertThat(attempt("alice", "10.0.0.2", true)).as("another address").isEmpty();
        assertThat(attempt("bob", "10.0.0.1", true)).as("another name").isEmpty();

        nanos.addAndGet(59_000_000_000L);
        assertThat(attempt("alice", "10.0.0.1", true)).contains(Duration.ofSeconds(1));
        nanos.addAndGet(1_000_000_000L);
        fail("alice", "10.0.0.1", 2);                                           // counted again from zero
        assertThat(attempt("alice", "10.0.0.1", false)).isEmpty();
        assertThat(attempt("alice", "10.0.0.1", true)).isPresent();
    }

    @Test
    void aSuccessClearsTheCount() {
        fail("alice", "10.0.0.1", 2);
        assertThat(attempt("alice", "10.0.0.1", true)).isEmpty();
        fail("alice", "10.0.0.1", 2);
        assertThat(attempt("alice", "10.0.0.1", true)).isEmpty();
        assertThat(throttle.tracked()).isZero();
    }

    @Test
    void attemptsInParallelCountUntilTheyAreKnownToHaveSucceeded() {
        fail("alice", "10.0.0.1", 1);
        assertThat(throttle.begin(Channel.REST, "alice", "10.0.0.1")).isEmpty();
        assertThat(throttle.begin(Channel.REST, "alice", "10.0.0.1")).isEmpty();
        assertThat(throttle.begin(Channel.REST, "alice", "10.0.0.1")).as("one failed, two in flight")
                .contains(new LoginThrottle.Refusal(Duration.ofSeconds(1), true));
        throttle.end(Channel.REST, "alice", "10.0.0.1", true);                 // one succeeded: count cleared
        assertThat(throttle.begin(Channel.REST, "alice", "10.0.0.1")).isEmpty();
        throttle.end(Channel.REST, "alice", "10.0.0.1", false);
        throttle.end(Channel.REST, "alice", "10.0.0.1", false);
        assertThat(attempt("alice", "10.0.0.1", false)).isEmpty();
        assertThat(attempt("alice", "10.0.0.1", true)).contains(Duration.ofSeconds(60));
    }

    @Test
    void restNamesIgnoreCaseAsTheirLookupDoesAndFixCompIdsDoNot() {
        fail("alice", "10.0.0.1", 1);
        fail("Alice", "10.0.0.1", 1);
        fail("ALICE", "10.0.0.1", 1);
        assertThat(attempt("aLiCe", "10.0.0.1", true)).isPresent();

        for (String compId : new String[] {"MEMBER1", "member1", "Member1"}) {
            assertThat(throttle.begin(Channel.FIX, compId, "10.0.0.1")).isEmpty();
            throttle.end(Channel.FIX, compId, "10.0.0.1", false);
        }
        assertThat(throttle.begin(Channel.FIX, "MEMBER1", "10.0.0.1")).isEmpty();
    }

    @Test
    void restAndFixNamesAreCountedApart() {
        fail("MEMBER1", "10.0.0.1", 3);
        assertThat(throttle.begin(Channel.FIX, "MEMBER1", "10.0.0.1")).isEmpty();
    }

    @Test
    void anAddressCountsForItsHost() {
        assertThat(LoginThrottle.host("/10.0.0.1:50001")).isEqualTo("10.0.0.1");
        assertThat(LoginThrottle.host("host.example/10.0.0.1:50002")).isEqualTo("10.0.0.1");
        assertThat(LoginThrottle.host("10.0.0.1")).isEqualTo("10.0.0.1");
        assertThat(LoginThrottle.host("::ffff:10.0.0.1")).as("IPv4 mapped into IPv6").isEqualTo("10.0.0.1");
        assertThat(LoginThrottle.host("/[2001:db8:1:2:aaaa::1]:5000")).isEqualTo("20010db800010002::/64");
        assertThat(LoginThrottle.host("2001:db8:1:2:bbbb::7")).as("the same /64").isEqualTo("20010db800010002::/64");
        assertThat(LoginThrottle.host("0:0:0:0:0:0:0:1")).isEqualTo("0000000000000000::/64");
        assertThat(LoginThrottle.host("fe80::1%eth0")).as("not a plain literal: kept").isEqualTo("fe80::1%eth0");
        assertThat(LoginThrottle.host(null)).isEqualTo("unknown");
    }

    @Test
    void zeroFailuresSwitchesLockoutsOffAndALockoutMustBePositive() {
        for (int i = 0; i < 100; i++) {
            assertThat(LoginThrottle.NONE.begin(Channel.REST, "alice", "10.0.0.1")).isEmpty();
            LoginThrottle.NONE.end(Channel.REST, "alice", "10.0.0.1", false);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new LoginThrottle(5, Duration.ZERO, Duration.ZERO, nanos::get));
        assertThatIllegalArgumentException().isThrownBy(() -> new LoginThrottle(5, Duration.ofSeconds(-1), Duration.ZERO, nanos::get));
    }

    @Test
    void aFloodOfNewNamesForgetsPartialCountsButNeverALockoutOrAnAttemptInProgress() {
        fail("alice", "10.0.0.1", 3);                                           // locked, and the eldest entry
        assertThat(throttle.begin(Channel.REST, "bob", "10.0.0.1")).isEmpty();  // in progress
        fail("carol", "10.0.0.1", 1);                                           // a partial count
        for (int i = 0; i < LoginThrottle.MAX_TRACKED; i++) {
            fail("guess" + i, "10.9.9.9", 1);
        }
        assertThat(throttle.tracked()).isEqualTo(LoginThrottle.MAX_TRACKED);
        assertThat(attempt("alice", "10.0.0.1", true)).as("a lockout survives a flood").isPresent();
        throttle.end(Channel.REST, "bob", "10.0.0.1", false);
        fail("bob", "10.0.0.1", 2);
        assertThat(attempt("bob", "10.0.0.1", true)).as("the attempt in progress was counted").isPresent();
        fail("carol", "10.0.0.1", 2);
        assertThat(attempt("carol", "10.0.0.1", true)).as("carol's partial count was forgotten").isEmpty();
    }

    @Test
    void anAttemptBeyondThoseBeingCheckedWaitsForOneToFinish() throws Exception {
        LoginThrottle waiting = new LoginThrottle(2, Duration.ofSeconds(60), Duration.ofSeconds(5), nanos::get);
        assertThat(waiting.begin(Channel.REST, "alice", "10.0.0.1")).isEmpty();
        assertThat(waiting.begin(Channel.REST, "alice", "10.0.0.1")).isEmpty();
        var third = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> waiting.begin(Channel.REST, "alice", "10.0.0.1"));
        Thread.sleep(100);
        assertThat(third).as("waits while two are being checked").isNotDone();
        waiting.end(Channel.REST, "alice", "10.0.0.1", true);                    // the right secret
        assertThat(third.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEmpty();

        LoginThrottle impatient = new LoginThrottle(1, Duration.ofSeconds(60), Duration.ofMillis(50), nanos::get);
        assertThat(impatient.begin(Channel.REST, "alice", "10.0.0.1")).isEmpty();
        assertThat(impatient.begin(Channel.REST, "alice", "10.0.0.1"))
                .contains(new LoginThrottle.Refusal(Duration.ofSeconds(1), true));
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

        nanos.addAndGet(60_000_000_000L);
        assertThat(logons.authenticate("MEMBER1", "secret", "/10.0.0.1:4002")).isTrue();
        assertThat(logons.authenticate("MEMBER1", "guess")).isFalse();             // no address known
    }

    @Test
    void endingAnAttemptWhoseEntryWasForgottenCountsAFailureAfresh() {
        LoginThrottle one = new LoginThrottle(1, Duration.ofSeconds(60), Duration.ZERO, nanos::get);
        one.end(Channel.REST, "alice", "10.0.0.1", true);                        // nothing to clear
        assertThat(one.tracked()).isZero();
        one.end(Channel.REST, "alice", "10.0.0.1", false);
        assertThat(one.begin(Channel.REST, "alice", "10.0.0.1")).isPresent();
    }

    @Test
    void aTableFullOfLockoutsRefusesNewNames() {
        for (int i = 0; i < 2 * LoginThrottle.MAX_TRACKED; i++) {
            fail("locked" + i, "10.9.9.9", 3);
        }
        assertThat(throttle.tracked()).isEqualTo(2 * LoginThrottle.MAX_TRACKED);
        assertThat(throttle.begin(Channel.REST, "newcomer", "10.0.0.1"))
                .contains(new LoginThrottle.Refusal(Duration.ofSeconds(1), true));
        nanos.addAndGet(60_000_000_000L);                                        // the lockouts are over
        assertThat(attempt("newcomer", "10.0.0.1", true)).isEmpty();
    }

    @Test
    void anInterruptedWaitIsRefused() throws Exception {
        LoginThrottle waiting = new LoginThrottle(1, Duration.ofSeconds(60), Duration.ofSeconds(30), nanos::get);
        assertThat(waiting.begin(Channel.REST, "alice", "10.0.0.1")).isEmpty();
        java.util.concurrent.atomic.AtomicReference<Optional<LoginThrottle.Refusal>> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> result.set(waiting.begin(Channel.REST, "alice", "10.0.0.1")));
        Thread.sleep(100);
        thread.interrupt();
        thread.join(5_000);
        assertThat(result.get()).contains(new LoginThrottle.Refusal(Duration.ofSeconds(1), true));
    }
}
