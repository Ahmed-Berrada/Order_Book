package io.github.ahmedberrada.lob.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/** What Spring Security reports about REST authentications: logged (AC-006) and counted for lockouts (AC-009). */
class LoginAudit {

    private static final Logger LOG = LoggerFactory.getLogger(LoginAudit.class);

    private final LoginThrottle logins;

    LoginAudit(LoginThrottle logins) {
        this.logins = logins;
    }

    /** A failed authentication is logged with the user name and address, never a secret, and counted. */
    @EventListener
    void onFailure(AbstractAuthenticationFailureEvent event) {
        String name = event.getAuthentication().getName();
        String address = event.getAuthentication().getDetails() instanceof WebAuthenticationDetails web
                ? web.getRemoteAddress() : null;
        LOG.warn("authentication failed for user '{}' from {}", name, address == null ? "unknown address" : address);
        if (address != null) {
            logins.failed(name, address);
        }
    }

    /** A success clears the failures counted for the name from that address. */
    @EventListener
    void onSuccess(AuthenticationSuccessEvent event) {
        if (event.getAuthentication().getDetails() instanceof WebAuthenticationDetails web) {
            logins.succeeded(event.getAuthentication().getName(), web.getRemoteAddress());
        }
    }
}
