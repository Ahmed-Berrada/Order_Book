package io.github.ahmedberrada.lob.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/** Logs what Spring Security reports about failed REST authentications (AC-006). Lockouts count elsewhere (AC-009). */
class LoginAudit {

    private static final Logger LOG = LoggerFactory.getLogger(LoginAudit.class);

    /** A failed authentication is logged with the user name and address, never a secret. */
    @EventListener
    void onFailure(AbstractAuthenticationFailureEvent event) {
        String name = event.getAuthentication().getName();
        String address = event.getAuthentication().getDetails() instanceof WebAuthenticationDetails web
                ? web.getRemoteAddress() : null;
        LOG.warn("authentication failed for user '{}' from {}{}", name, address == null ? "unknown address" : address,
                event.getException() instanceof ThrottledAuthentication.LockedOutException ? " (locked out)" : "");
    }
}
