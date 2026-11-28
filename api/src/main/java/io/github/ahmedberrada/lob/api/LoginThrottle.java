package io.github.ahmedberrada.lob.api;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locks a name out from a source address after repeated failed authentications (AC-009, ADR-0017 §6), for REST
 * users and FIX CompIDs alike. Thread-safe. The counts are in memory and bounded.
 */
final class LoginThrottle {

    /** Lockouts off. */
    static final LoginThrottle NONE = new LoginThrottle(0, Duration.ZERO, System::nanoTime);

    private static final Logger LOG = LoggerFactory.getLogger(LoginThrottle.class);
    /** Above this many tracked names and addresses, those not locked out are forgotten. */
    static final int MAX_TRACKED = 10_000;

    private final int maxFailures;
    private final long lockoutNanos;
    private final LongSupplier nanos;
    private final Map<Key, Failures> failures = new ConcurrentHashMap<>();

    LoginThrottle(int maxFailures, Duration lockout, LongSupplier nanos) {
        this.maxFailures = maxFailures;
        this.lockoutNanos = lockout.toNanos();
        this.nanos = nanos;
    }

    /** How long {@code name} is still locked out from {@code address}, if it is. */
    Optional<Duration> lockedOut(String name, String address) {
        if (maxFailures == 0) {
            return Optional.empty();
        }
        Failures f = failures.get(new Key(name, host(address)));
        if (f == null) {
            return Optional.empty();
        }
        long left = f.remaining(nanos.getAsLong(), maxFailures);
        return left > 0 ? Optional.of(Duration.ofNanos(left)) : Optional.empty();
    }

    void failed(String name, String address) {
        if (maxFailures == 0) {
            return;
        }
        if (failures.size() >= MAX_TRACKED) {
            long now = nanos.getAsLong();
            failures.values().removeIf(f -> f.remaining(now, maxFailures) == 0);
        }
        Key key = new Key(name, host(address));
        if (failures.computeIfAbsent(key, k -> new Failures()).fail(nanos.getAsLong(), lockoutNanos, maxFailures)) {
            LOG.warn("'{}' locked out from {} for {} after {} failed authentications", name, key.address(),
                    Duration.ofNanos(lockoutNanos), maxFailures);
        }
    }

    void succeeded(String name, String address) {
        failures.remove(new Key(name, host(address)));
    }

    /**
     * The host of an address, without a port: {@code /10.0.0.1:5000} and {@code host/10.0.0.1:5000} (a socket
     * address as text) are {@code 10.0.0.1}; {@code /[::1]:5000} is {@code ::1}; a bare IPv6 address, as a servlet
     * gives it, is kept whole.
     */
    static String host(String address) {
        if (address == null) {
            return "unknown";
        }
        String a = address.substring(address.lastIndexOf('/') + 1);
        if (a.startsWith("[")) {                               // [::1]:5000
            int end = a.indexOf(']');
            return end < 0 ? a : a.substring(1, end);
        }
        int colon = a.lastIndexOf(':');
        return colon >= 0 && a.indexOf(':') == colon ? a.substring(0, colon) : a;   // one colon: host:port
    }

    private record Key(String name, String address) {
    }

    private static final class Failures {

        private int count;
        private long lockedUntil;

        /** Counts a failure; returns whether it started a lockout. */
        synchronized boolean fail(long now, long lockoutNanos, int maxFailures) {
            if (count >= maxFailures && lockedUntil - now <= 0) {
                count = 0;                                      // the lockout is over: count again from zero
            }
            count++;
            if (count == maxFailures) {
                lockedUntil = now + lockoutNanos;
                return true;
            }
            return false;
        }

        /** Nanoseconds the lockout still lasts, or 0. */
        synchronized long remaining(long now, int maxFailures) {
            return count >= maxFailures ? Math.max(0, lockedUntil - now) : 0;
        }
    }
}
