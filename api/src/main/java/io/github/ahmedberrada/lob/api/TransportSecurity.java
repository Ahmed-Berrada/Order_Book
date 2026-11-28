package io.github.ahmedberrada.lob.api;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * AC-010: with security enabled, a port that listens beyond loopback must speak TLS, or the venue does not start,
 * unless TLS is declared terminated in front of it. Credentials and orders never cross a network in clear.
 */
final class TransportSecurity {

    private static final Logger LOG = LoggerFactory.getLogger(TransportSecurity.class);

    private TransportSecurity() {
    }

    /** @throws IllegalStateException naming each port that would listen beyond loopback in clear */
    static void check(LobProperties properties, Environment environment) {
        if (!properties.security().enabled()) {
            return;                                            // logged as such by SecurityConfiguration (AC-003)
        }
        List<String> clear = new ArrayList<>();
        String restAddress = environment.getProperty("server.address");
        if (!isLoopback(restAddress) && !restTls(environment)) {
            clear.add("REST on " + (restAddress == null ? "every interface" : restAddress) + " (server.ssl)");
        }
        LobProperties.FixProperties fix = properties.fix();
        if (fix.enabled() && !isLoopback(fix.bindAddress()) && (fix.tls() == null || !fix.tls().enabled())) {
            clear.add("FIX on " + fix.bindAddress() + " (lob.fix.tls)");
        }
        if (clear.isEmpty()) {
            return;
        }
        if (properties.security().tlsTerminatedInFront()) {
            LOG.warn("lob.security.tls-terminated-in-front is true: {} listen in clear, trusting TLS in front of the "
                    + "venue", clear);
            return;
        }
        throw new IllegalStateException("with security enabled, these ports would listen beyond loopback without TLS: "
                + clear + "; configure TLS, or declare lob.security.tls-terminated-in-front=true if it ends in front");
    }

    /** Whether the REST connector is configured for TLS (a bundle, a key store or a certificate). */
    static boolean restTls(Environment environment) {
        if (!environment.getProperty("server.ssl.enabled", Boolean.class, true)) {
            return false;
        }
        return environment.containsProperty("server.ssl.bundle") || environment.containsProperty("server.ssl.key-store")
                || environment.containsProperty("server.ssl.certificate");
    }

    /** Whether an address is loopback; no address at all means every interface. */
    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
