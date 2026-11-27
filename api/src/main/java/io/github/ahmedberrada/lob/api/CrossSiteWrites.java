package io.github.ahmedberrada.lob.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
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
 *   <li>A browser too old for Fetch Metadata still sends {@code Origin} on a cross-origin write; a write that
 *       carries {@code Origin} without {@code Sec-Fetch-Site} is protected.</li>
 *   <li>A machine client (FIX gateway, curl, a member's system) sends neither header and is not protected:
 *       it holds its credentials itself, so another site cannot borrow them.</li>
 * </ul>
 */
final class CrossSiteWrites {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> TRUSTED_SITES = Set.of("same-origin", "none");

    static final RequestMatcher MATCHER = CrossSiteWrites::isCrossSiteWrite;

    private CrossSiteWrites() {
    }

    static boolean isCrossSiteWrite(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return false;
        }
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null) {
            return !TRUSTED_SITES.contains(site);
        }
        return request.getHeader("Origin") != null;
    }
}
