package io.github.ahmedberrada.lob.api;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/** Which member a REST request acts for (AC-005). */
@Component
class MemberResolver {

    /**
     * @param authentication the caller, or {@code null} when security is off
     * @param header         the {@code X-Member-Id} header, or {@code null}
     * @throws AccessDeniedException if a member user names another member
     */
    long resolve(Authentication authentication, Long header) {
        if (authentication == null || authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            return header == null ? 0 : header;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (authority.getAuthority().startsWith(SecurityConfiguration.MEMBER_AUTHORITY)) {
                long member = Long.parseLong(
                        authority.getAuthority().substring(SecurityConfiguration.MEMBER_AUTHORITY.length()));
                if (header != null && header != member) {
                    throw new AccessDeniedException("a member user acts for its own member only");
                }
                return member;
            }
        }
        throw new AccessDeniedException("the user acts for no member");
    }
}
