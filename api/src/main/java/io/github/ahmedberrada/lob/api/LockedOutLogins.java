package io.github.ahmedberrada.lob.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses HTTP Basic credentials for a name locked out from the caller's address, before the secret is checked
 * (AC-009): {@code 429} with {@code Retry-After} in whole seconds.
 */
final class LockedOutLogins extends OncePerRequestFilter {

    private final LoginThrottle logins;

    LockedOutLogins(LoginThrottle logins) {
        this.logins = logins;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String name = basicUserName(request.getHeader(HttpHeaders.AUTHORIZATION));
        Optional<Duration> locked = name == null ? Optional.empty() : logins.lockedOut(name, request.getRemoteAddr());
        if (locked.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        long seconds = Math.max(1, locked.get().plusNanos(999_999_999).toSeconds());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too many failed authentications\","
                + "\"status\":429,\"detail\":\"Retry after the delay\",\"reason\":\"LOCKED_OUT\"}");
    }

    /** The user name of HTTP Basic credentials, or {@code null} if the header carries none (Spring then decides). */
    static String basicUserName(String header) {
        if (header == null || header.length() < 6 || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon < 0 ? null : decoded.substring(0, colon);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
