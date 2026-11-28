package io.github.ahmedberrada.lob.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.context.event.EventListener;

/**
 * Authentication and authorisation of the REST API (docs/rulebook/access-control.md, ADR-0017): HTTP Basic
 * over a stateless chain, users from the configuration, secrets as salted hashes. A venue that enables
 * security but has no usable users does not start (AC-003).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfiguration.class);
    /** An algorithm prefix, as the delegating password encoder reads it: {@code {bcrypt}...}. */
    private static final Pattern PREFIXED_HASH = Pattern.compile("^\\{[a-z0-9]+}.+");
    /** The authority that names the member of a user (AC-005). */
    static final String MEMBER_AUTHORITY = "MEMBER:";

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(LobProperties properties) {
        LobProperties.SecurityProperties security = properties.security();
        if (!security.enabled()) {
            return new InMemoryUserDetailsManager();
        }
        if (security.users().isEmpty()) {
            throw new IllegalStateException(
                    "lob.security.enabled is true but no user is configured (lob.security.users); "
                            + "configure users, or disable security explicitly with lob.security.enabled=false");
        }
        List<UserDetails> users = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (LobProperties.UserProperties user : security.users()) {
            if (!names.add(user.name())) {
                throw new IllegalStateException("user configured twice: " + user.name());
            }
            if (!PREFIXED_HASH.matcher(user.password()).matches()) {
                throw new IllegalStateException("the password of " + user.name()
                        + " is not a hash with an algorithm prefix such as {bcrypt}; plain text is refused");
            }
            users.add(org.springframework.security.core.userdetails.User.withUsername(user.name())
                    .password(user.password()).authorities(authorities(user)).build());
        }
        return new InMemoryUserDetailsManager(users);
    }

    /** A higher role includes the lower ones; a member user also carries the member it acts for. */
    private static List<org.springframework.security.core.GrantedAuthority> authorities(
            LobProperties.UserProperties user) {
        Set<String> names = new LinkedHashSet<>();
        for (LobProperties.Role role : user.roles()) {
            switch (role) {
                case ADMIN -> {
                    names.add("ROLE_ADMIN");
                    names.add("ROLE_MEMBER");
                    names.add("ROLE_READ");
                }
                case MEMBER -> {
                    names.add("ROLE_MEMBER");
                    names.add("ROLE_READ");
                }
                case READ -> names.add("ROLE_READ");
            }
        }
        if (names.contains("ROLE_MEMBER") && !names.contains("ROLE_ADMIN")) {
            if (user.memberId() <= 0) {
                throw new IllegalStateException("the member user " + user.name() + " needs a member ID (member-id)");
            }
            names.add(MEMBER_AUTHORITY + user.memberId());
        }
        return AuthorityUtils.createAuthorityList(names.toArray(String[]::new));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, LobProperties properties) throws Exception {
        // A browser that has answered a Basic challenge resends the credentials on its own, so a page on
        // another site could make it act for the user. The CSRF filter therefore applies to every write a
        // browser marks as cross-site, and to form bodies; such a request cannot carry a token (none is ever
        // stored or handed out, so no session is created either) and is refused. Machine clients send no
        // Fetch Metadata and a JSON body, and are not affected (AC-008, ADR-0017 §2).
        http.csrf(c -> c.requireCsrfProtectionMatcher(CrossSiteWrites.MATCHER)
                        .csrfTokenRepository(CrossSiteWrites.NO_TOKENS))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        if (!properties.security().enabled()) {
            LOG.warn("lob.security.enabled is false: every caller who reaches this port is trusted as the operator");
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }
        return http.httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/instruments/*/phase").hasRole("ADMIN")
                        .requestMatchers("/api/v1/members/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/instruments/*/orders").hasRole("MEMBER")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/instruments/*/orders/*").hasRole("MEMBER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/instruments/*/orders/*").hasRole("MEMBER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/instruments/*/orders").hasRole("MEMBER")
                        .requestMatchers(HttpMethod.GET, "/**").hasRole("READ")
                        .anyRequest().denyAll())
                .build();
    }

    /** AC-006: a failed authentication is logged with the user name and address, never a secret. */
    @EventListener
    void onFailure(AbstractAuthenticationFailureEvent event) {
        Object details = event.getAuthentication().getDetails();
        LOG.warn("authentication failed for user '{}' from {}", event.getAuthentication().getName(),
                details instanceof WebAuthenticationDetails web ? web.getRemoteAddress() : "unknown address");
    }
}
