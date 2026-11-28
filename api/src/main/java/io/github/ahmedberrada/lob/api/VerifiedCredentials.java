package io.github.ahmedberrada.lob.api;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Credentials that bcrypt has already accepted, so that HTTP Basic, which sends them with every request, does not
 * pay bcrypt's cost on every request (AC-012, ADR-0017 §6). A password is kept only as an HMAC-SHA256 under a
 * random key that never leaves this process, and compared in constant time; an entry expires after its time to
 * live. Only successful authentications are kept, so the cache holds at most one entry per configured user.
 */
final class VerifiedCredentials {

    /**
     * A user and what it may do, as bcrypt last confirmed it: names and authorities only, never a password or a
     * shared user object, so nothing a request is handed carries the bcrypt hash or is changed by another request.
     */
    record Verified(String username, List<GrantedAuthority> authorities) {

        Verified {
            authorities = List.copyOf(authorities);
        }

        /** A fresh principal for one request, with no password. */
        UserDetails principal() {
            return User.withUsername(username).password("").authorities(authorities).build();
        }
    }

    private record Entry(byte[] mac, Verified verified, long expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final SecretKeySpec key;
    private final long ttlNanos;
    private final LongSupplier nanos;

    VerifiedCredentials(Duration ttl, LongSupplier nanos) {
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("lob.security.credential-cache must not be negative: " + ttl);
        }
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        this.key = new SecretKeySpec(secret, "HmacSHA256");
        this.ttlNanos = ttl.toNanos();
        this.nanos = nanos;
    }

    /** What bcrypt confirmed for these exact credentials within the time to live, or null. */
    Verified lookup(String name, String password) {
        if (ttlNanos <= 0 || password == null) {
            return null;
        }
        Entry entry = entries.get(key(name));
        if (entry == null || entry.expiresAt() - nanos.getAsLong() <= 0) {
            return null;
        }
        return MessageDigest.isEqual(entry.mac(), mac(name, password)) ? entry.verified() : null;
    }

    /** Records credentials bcrypt has just accepted. */
    void remember(String name, String password, Verified verified) {
        if (ttlNanos > 0 && password != null) {
            entries.put(key(name), new Entry(mac(name, password), verified, nanos.getAsLong() + ttlNanos));
        }
    }

    /** Users remembered now (for tests). */
    int size() {
        return entries.size();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);                    // as InMemoryUserDetailsManager looks users up
    }

    private byte[] mac(String name, String password) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(key(name).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(password.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required of every JVM", e);
        }
    }
}
