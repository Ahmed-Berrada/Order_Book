package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.Rulebook;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

@Rulebook("AC-012")
class VerifiedCredentialsTest {

    private final AtomicLong nanos = new AtomicLong();
    private final AtomicInteger checks = new AtomicInteger();
    private final AuthenticationProvider bcrypt = new AuthenticationProvider() {
        @Override
        public Authentication authenticate(Authentication authentication) {
            checks.incrementAndGet();
            if (!"secret".equals(authentication.getCredentials())) {
                throw new BadCredentialsException("bad");
            }
            return UsernamePasswordAuthenticationToken.authenticated(authentication.getName(), null,
                    AuthorityUtils.createAuthorityList("ROLE_MEMBER"));
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return true;
        }
    };
    private final LoginThrottle throttle = new LoginThrottle(3, Duration.ofSeconds(60), Duration.ZERO, nanos::get);
    private final VerifiedCredentials cache = new VerifiedCredentials(Duration.ofMinutes(10), nanos::get);
    private final ThrottledAuthentication authentication = new ThrottledAuthentication(bcrypt, throttle, cache);

    private static Authentication basic(String name, String password) {
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.unauthenticated(name, password);
        token.setDetails("request " + System.nanoTime());
        return token;
    }

    @Test
    void bcryptChecksCredentialsOnceThenTheyAreRemembered() {
        Authentication first = basic("alice", "secret");
        assertThat(authentication.authenticate(first).isAuthenticated()).isTrue();
        Authentication second = basic("Alice", "secret");                  // looked up as InMemoryUserDetails does
        Authentication remembered = authentication.authenticate(second);
        assertThat(checks).as("bcrypt once").hasValue(1);
        assertThat(remembered.isAuthenticated()).isTrue();
        assertThat(remembered.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_MEMBER");
        assertThat(remembered.getDetails()).as("this request's details").isEqualTo(second.getDetails());
        assertThat(remembered.getCredentials()).isNull();
    }

    @Test
    void aWrongPasswordIsAlwaysCheckedAndCountedAndNeverRemembered() {
        authentication.authenticate(basic("alice", "secret"));
        assertThatThrownBy(() -> authentication.authenticate(basic("alice", "guess")))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(checks).hasValue(2);
        assertThatThrownBy(() -> authentication.authenticate(basic("alice", "guess")))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> authentication.authenticate(basic("alice", "guess")))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> authentication.authenticate(basic("alice", "secret")))
                .as("a locked-out name is refused before the cache").isInstanceOf(ThrottledAuthentication.LockedOutException.class);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void aRememberedCredentialExpires() {
        authentication.authenticate(basic("alice", "secret"));
        nanos.addAndGet(Duration.ofMinutes(10).toNanos());
        authentication.authenticate(basic("alice", "secret"));
        assertThat(checks).as("checked again after the time to live").hasValue(2);
    }

    @Test
    void aZeroTimeToLiveChecksEveryRequest() {
        ThrottledAuthentication uncached = new ThrottledAuthentication(bcrypt, throttle);
        uncached.authenticate(basic("alice", "secret"));
        uncached.authenticate(basic("alice", "secret"));
        assertThat(checks).hasValue(2);
        VerifiedCredentials off = new VerifiedCredentials(Duration.ZERO, nanos::get);
        off.remember("alice", "secret", new VerifiedCredentials.Verified("alice", List.of()));
        assertThat(off.lookup("alice", "secret")).isNull();
        assertThat(cache.lookup("alice", null)).isNull();
    }

    @Test
    void anotherPasswordForTheSameNameIsNotAHit() {
        cache.remember("alice", "secret", new VerifiedCredentials.Verified("alice", List.of()));
        assertThat(cache.lookup("alice", "secret")).isNotNull();
        assertThat(cache.lookup("alice", "secreT")).isNull();
        assertThat(cache.lookup("bob", "secret")).isNull();
    }
}
