package io.github.ahmedberrada.lob.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;

/**
 * Checks REST credentials only for a name not locked out from the caller's address, and counts the outcome
 * (AC-009). It wraps the provider that checks the secret, so it sees exactly the name Spring Security
 * authenticates, however the {@code Authorization} header was written.
 */
final class ThrottledAuthentication implements AuthenticationProvider {

    /** A name locked out from the caller's address: answered {@code 429} by {@link #ENTRY_POINT}. */
    static final class LockedOutException extends AuthenticationException {

        private final transient Duration retryAfter;

        LockedOutException(Duration retryAfter) {
            super("too many failed authentications");
            this.retryAfter = retryAfter;
        }

        Duration retryAfter() {
            return retryAfter;
        }
    }

    /** HTTP Basic's challenge, except for a locked-out name: {@code 429} with {@code Retry-After} in whole seconds. */
    static final AuthenticationEntryPoint ENTRY_POINT = new AuthenticationEntryPoint() {
        private final BasicAuthenticationEntryPoint basic = basic();

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
                throws IOException {
            if (!(e instanceof LockedOutException locked)) {
                basic.commence(request, response, e);
                return;
            }
            long seconds = Math.max(1, locked.retryAfter().plusNanos(999_999_999).toSeconds());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too many failed authentications\","
                    + "\"status\":429,\"detail\":\"Retry after the delay\",\"reason\":\"LOCKED_OUT\"}");
        }
    };

    private final AuthenticationProvider secrets;
    private final LoginThrottle logins;

    ThrottledAuthentication(AuthenticationProvider secrets, LoginThrottle logins) {
        this.secrets = secrets;
        this.logins = logins;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String name = authentication.getName();
        String address = authentication.getDetails() instanceof WebAuthenticationDetails web
                ? web.getRemoteAddress() : null;
        Optional<Duration> wait = logins.begin(LoginThrottle.Channel.REST, name, address);
        if (wait.isPresent()) {
            throw new LockedOutException(wait.get());
        }
        boolean succeeded = false;
        try {
            Authentication result = secrets.authenticate(authentication);
            succeeded = result != null;
            return result;
        } finally {
            logins.end(LoginThrottle.Channel.REST, name, address, succeeded);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return secrets.supports(authentication);
    }

    private static BasicAuthenticationEntryPoint basic() {
        BasicAuthenticationEntryPoint entryPoint = new BasicAuthenticationEntryPoint();
        entryPoint.setRealmName("Realm");
        return entryPoint;
    }
}
