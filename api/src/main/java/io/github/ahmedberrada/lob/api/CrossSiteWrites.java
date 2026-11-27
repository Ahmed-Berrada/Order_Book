package io.github.ahmedberrada.lob.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The requests the CSRF filter protects (AC-008): writes that a browser says come from another site.
 *
 * <ul>
 *   <li>Reads ({@code GET}, {@code HEAD}, {@code OPTIONS}, {@code TRACE}) are never protected: they change
 *       nothing, and the same-origin policy keeps another site from reading the answer.</li>
 *   <li>A browser sends {@code Sec-Fetch-Site} (Fetch Metadata). Only {@code same-origin} and {@code none} (the
 *       user's own action, such as a typed address) pass; {@code same-site} and {@code cross-site} are protected,
 *       and so is any value this class does not know.</li>
 *   <li>Most browsers too old for Fetch Metadata send {@code Origin} on a cross-origin write; a write that
 *       carries {@code Origin} without {@code Sec-Fetch-Site} is protected.</li>
 *   <li>A write whose body is of a type an HTML form can send without asking the server first
 *       ({@code application/x-www-form-urlencoded}, {@code multipart/form-data}, {@code text/plain}) is
 *       protected whatever its headers: the API reads only JSON, and some old browsers (Internet Explorer,
 *       Firefox before 70) send a cross-site form with neither header. A cross-site request with a JSON body
 *       needs a CORS preflight, which the venue never grants.</li>
 *   <li>A machine client (FIX gateway, curl, a member's system) sends neither header and a JSON body, and is not
 *       protected: it holds its credentials itself, so another site cannot borrow them.</li>
 * </ul>
 *
 * <p>A protected request is refused because it carries no token, and none can be obtained:
 * {@link #NO_TOKENS} never stores one, so refusing a request creates no session either.
 */
final class CrossSiteWrites {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> TRUSTED_SITES = Set.of("same-origin", "none");
    private static final Set<String> FORM_TYPES =
            Set.of("application/x-www-form-urlencoded", "multipart/form-data", "text/plain");

    static final RequestMatcher MATCHER = CrossSiteWrites::isCrossSiteWrite;

    /**
     * Tokens that are never stored or handed out: a protected request can never present a valid one. Unlike the
     * default session-backed repository, it creates no session, so the chain stays stateless.
     */
    static final CsrfTokenRepository NO_TOKENS = new CsrfTokenRepository() {
        @Override
        public CsrfToken generateToken(HttpServletRequest request) {
            return new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", UUID.randomUUID().toString());
        }

        @Override
        public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        }

        @Override
        public CsrfToken loadToken(HttpServletRequest request) {
            return null;
        }
    };

    private CrossSiteWrites() {
    }

    static boolean isCrossSiteWrite(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return false;
        }
        if (isFormType(request.getContentType())) {
            return true;
        }
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null) {
            return !TRUSTED_SITES.contains(site);
        }
        return request.getHeader("Origin") != null;
    }

    private static boolean isFormType(String contentType) {
        if (contentType == null) {
            return false;
        }
        int parameters = contentType.indexOf(';');
        String type = (parameters < 0 ? contentType : contentType.substring(0, parameters)).strip();
        return FORM_TYPES.contains(type.toLowerCase(Locale.ROOT));
    }
}
