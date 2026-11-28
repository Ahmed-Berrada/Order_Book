package io.github.ahmedberrada.lob.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import java.time.Duration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;

/**
 * Checks REST credentials only for a name not locked out from the caller's address, and counts the outcome
 * (AC-009). It wraps the provider that checks the secret, so it sees exactly the name Spring Security
 * authenticates, however the {@code Authorization} header was written.
 */
final class ThrottledAuthentication implements AuthenticationProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ThrottledAuthentication.class);

    /** A name locked out from the caller's address: answered {@code 429} by {@link #ENTRY_POINT}. */
    static final class LockedOutException extends AuthenticationException {

        private final transient LoginThrottle.Refusal refusal;

        LockedOutException(LoginThrottle.Refusal refusal) {
            super(refusal.busy() ? "too many authentications at once" : "too many failed authentications");
            this.refusal = refusal;
        }

        LoginThrottle.Refusal refusal() {
            return refusal;
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
            LoginThrottle.Refusal refusal = locked.refusal();
            long seconds = Math.max(1, refusal.retryAfter().plusNanos(999_999_999).toSeconds());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(refusal.busy()
                    ? "{\"type\":\"about:blank\",\"title\":\"Too many authentications at once\",\"status\":429,"
                            + "\"detail\":\"Retry after the delay\",\"reason\":\"AUTHENTICATION_BUSY\"}"
                    : "{\"type\":\"about:blank\",\"title\":\"Too many failed authentications\",\"status\":429,"
                            + "\"detail\":\"Retry after the delay\",\"reason\":\"LOCKED_OUT\"}");
        }
    };

    private final AuthenticationProvider secrets;
    private final LoginThrottle logins;
    private final VerifiedCredentials verified;

    ThrottledAuthentication(AuthenticationProvider secrets, LoginThrottle logins) {
        this(secrets, logins, new VerifiedCredentials(Duration.ZERO, System::nanoTime));
    }

    ThrottledAuthentication(AuthenticationProvider secrets, LoginThrottle logins, VerifiedCredentials verified) {
        this.secrets = secrets;
        this.logins = logins;
        this.verified = verified;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String name = authentication.getName();
        String address = authentication.getDetails() instanceof WebAuthenticationDetails web
                ? web.getRemoteAddress() : null;
        Optional<LoginThrottle.Refusal> refusal = logins.begin(LoginThrottle.Channel.REST, name, address);
        if (refusal.isPresent()) {
            // Spring publishes no failure event for an exception it does not know, so it is logged here (AC-006).
            LOG.warn("authentication refused for user '{}' from {}: {}", name, address == null ? "unknown address" : address,
                    refusal.get().busy() ? "too many at once" : "locked out");
            throw new LockedOutException(refusal.get());
        }
        String password = authentication.getCredentials() instanceof String p ? p : null;
        // AC-012: bcrypt accepted these very credentials. While failures are counted for the name from this address,
        // bcrypt checks again, and its success clears them (AC-009): a hit, cheap and frequent, must not reset a
        // guesser's count behind an address it shares with the member faster than bcrypt would.
        VerifiedCredentials.Verified known = logins.hasFailures(LoginThrottle.Channel.REST, name, address)
                ? null : verified.lookup(name, password);
        if (known != null) {
            logins.release(LoginThrottle.Channel.REST, name, address);
            UsernamePasswordAuthenticationToken result =
                    UsernamePasswordAuthenticationToken.authenticated(known.principal(), null, known.authorities());
            result.setDetails(authentication.getDetails());
            return result;
        }
        boolean succeeded = false;
        try {
            Authentication result = secrets.authenticate(authentication);
            succeeded = result != null;
            if (succeeded) {
                verified.remember(name, password, new VerifiedCredentials.Verified(
                        result.getPrincipal() instanceof UserDetails user ? user.getUsername() : result.getName(),
                        java.util.List.copyOf(result.getAuthorities())));
            }
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
