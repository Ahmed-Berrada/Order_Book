package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.api.LoginThrottle.Channel;
import io.github.ahmedberrada.lob.core.Rulebook;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@Rulebook("AC-009")
class ThrottledAuthenticationTest {

    private final AtomicLong nanos = new AtomicLong();
    private final LoginThrottle throttle = new LoginThrottle(1, Duration.ofSeconds(60), Duration.ZERO, nanos::get);

    private static AuthenticationProvider secrets(boolean accept) {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication authentication) {
                if (!accept) {
                    throw new BadCredentialsException("bad");
                }
                return UsernamePasswordAuthenticationToken.authenticated(authentication.getName(), null, java.util.List.of());
            }

            @Override
            public boolean supports(Class<?> authentication) {
                return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
            }
        };
    }

    @Test
    void withoutAnAddressTheNameIsCountedUnderAnUnknownOne() {
        ThrottledAuthentication wrong = new ThrottledAuthentication(secrets(false), throttle);
        Authentication guess = UsernamePasswordAuthenticationToken.unauthenticated("alice", "guess");
        assertThatThrownBy(() -> wrong.authenticate(guess)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> wrong.authenticate(guess)).isInstanceOf(ThrottledAuthentication.LockedOutException.class);
        assertThat(wrong.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
        assertThat(wrong.supports(String.class)).isFalse();
    }

    @Test
    void aBusyNameIsAnsweredWithItsOwnReason() throws Exception {
        assertThat(throttle.begin(Channel.REST, "alice", null)).isEmpty();      // one being checked
        ThrottledAuthentication right = new ThrottledAuthentication(secrets(true), throttle);
        ThrottledAuthentication.LockedOutException busy = (ThrottledAuthentication.LockedOutException)
                org.assertj.core.api.Assertions.catchThrowable(() ->
                        right.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", "pass")));
        assertThat(busy.refusal().busy()).isTrue();

        MockHttpServletResponse response = new MockHttpServletResponse();
        ThrottledAuthentication.ENTRY_POINT.commence(new MockHttpServletRequest(), response, busy);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsString()).contains("AUTHENTICATION_BUSY");

        throttle.end(Channel.REST, "alice", null, true);
        assertThat(right.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", "pass"))
                .isAuthenticated()).isTrue();
    }
}
