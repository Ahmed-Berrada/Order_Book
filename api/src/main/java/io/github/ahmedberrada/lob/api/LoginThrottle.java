package io.github.ahmedberrada.lob.api;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locks a name out from a source address after repeated failed authentications (AC-009, ADR-0017 §6), for REST
 * users and FIX CompIDs alike. Thread-safe.
 *
 * <p>An attempt reserves its place before the secret is checked ({@link #begin}) and reports how it went
 * ({@link #end}), so attempts made in parallel count as failures until they are known not to be: a caller
 * cannot get more guesses by sending them at once. The counts are in memory, for at most {@link #MAX_TRACKED}
 * names and addresses, the least recently used forgotten first.
 */
final class LoginThrottle {

    /** Where a name comes from: REST users and FIX CompIDs are counted apart. */
    enum Channel {
        REST, FIX
    }

    /**
     * Why an attempt may not go on.
     *
     * @param retryAfter how long to wait before trying again
     * @param busy       {@code true} if the name is not locked out, but enough of its attempts are already being
     *                   checked that they could reach the limit
     */
    record Refusal(Duration retryAfter, boolean busy) {
    }

    /** Lockouts off. */
    static final LoginThrottle NONE = new LoginThrottle(0, Duration.ZERO, Duration.ZERO, System::nanoTime);

    static final int MAX_TRACKED = 10_000;

    private static final Logger LOG = LoggerFactory.getLogger(LoginThrottle.class);
    /** How long a refused busy attempt is told to wait. */
    private static final Duration BUSY = Duration.ofSeconds(1);
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

    private final int maxFailures;
    private final long lockoutNanos;
    private final LongSupplier nanos;
    private final long busyWaitNanos;
    /** In access order: the least recently used first. */
    private final Map<Key, Failures> failures = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * @param busyWait how long an attempt waits for one of the same name and address to finish, when those being
     *                 checked could reach the limit, before it is refused
     * @param nanos    the clock of lockouts
     */
    LoginThrottle(int maxFailures, Duration lockout, Duration busyWait, LongSupplier nanos) {
        if (maxFailures > 0 && !lockout.isPositive()) {
            throw new IllegalArgumentException("a lockout must be positive: " + lockout);
        }
        this.maxFailures = maxFailures;
        this.lockoutNanos = lockout.toNanos();
        this.busyWaitNanos = busyWait.toNanos();
        this.nanos = nanos;
    }

    /**
     * Starts an attempt for {@code name} from {@code address}, unless the name is locked out from there.
     *
     * <p>At most {@code maxFailures} minus the failures so far are checked at once for a name and address; another
     * attempt waits for one of them to finish, for up to {@code busyWait}. A client with the right secret is
     * therefore slowed, not refused, when it sends many requests at once.
     *
     * @return why the attempt may not go on, or empty if it may; then {@link #end} must follow
     */
    synchronized Optional<Refusal> begin(Channel channel, String name, String address) {
        if (maxFailures == 0) {
            return Optional.empty();
        }
        Key key = key(channel, name, address);
        long waitUntil = System.nanoTime() + busyWaitNanos;
        while (true) {
            if (!failures.containsKey(key) && failures.size() >= MAX_TRACKED && !forgetOne()
                    && failures.size() >= 2 * MAX_TRACKED) {
                return Optional.of(new Refusal(BUSY, true));     // full of lockouts and attempts in progress
            }
            Failures f = failures.computeIfAbsent(key, k -> new Failures());
            if (f.count >= maxFailures) {
                long left = f.lockedUntil - nanos.getAsLong();
                if (left > 0) {
                    return Optional.of(new Refusal(Duration.ofNanos(left), false));
                }
                f.count = 0;                               // the lockout is over: count again from zero
            }
            if (f.count + f.inFlight < maxFailures) {
                f.inFlight++;
                return Optional.empty();
            }
            long waitNanos = waitUntil - System.nanoTime();
            if (waitNanos <= 0) {
                return Optional.of(new Refusal(BUSY, true));
            }
            try {
                wait(Math.max(1, waitNanos / 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.of(new Refusal(BUSY, true));
            }
        }
    }

    /**
     * Forgets the least recently used entry that is neither locked out nor being checked, so that a flood of new
     * names cannot reset a lockout. Partial counts can be forgotten: undoing them takes as many new names as there
     * are entries, a guess on another name each.
     */
    private boolean forgetOne() {
        long now = nanos.getAsLong();
        var entries = failures.values().iterator();
        while (entries.hasNext()) {
            Failures f = entries.next();
            boolean locked = f.count >= maxFailures && f.lockedUntil - now > 0;
            if (!locked && f.inFlight == 0) {
                entries.remove();
                return true;
            }
        }
        return false;
    }

    /** Ends an attempt that {@link #begin} let go on. */
    synchronized void end(Channel channel, String name, String address, boolean succeeded) {
        if (maxFailures == 0) {
            return;
        }
        notifyAll();                                       // an attempt waiting for this one may go on
        Key key = key(channel, name, address);
        Failures f = failures.get(key);
        if (f == null) {                                   // forgotten meanwhile: count the failure afresh
            if (succeeded) {
                return;
            }
            f = new Failures();
            failures.put(key, f);
        } else {
            f.inFlight = Math.max(0, f.inFlight - 1);
        }
        if (succeeded) {
            if (f.inFlight == 0) {
                failures.remove(key);
            } else {
                f.count = 0;
            }
            return;
        }
        f.count++;
        if (f.count == maxFailures) {
            f.lockedUntil = nanos.getAsLong() + lockoutNanos;
            LOG.warn("{} '{}' locked out from {} for {} after {} failed authentications", channel, name,
                    key.address(), Duration.ofNanos(lockoutNanos), maxFailures);
        }
    }

    /** Whether failures are counted for a name from an address, which a success would have to clear (AC-009). */
    synchronized boolean hasFailures(Channel channel, String name, String address) {
        Failures f = maxFailures == 0 ? null : failures.get(key(channel, name, address));
        return f != null && f.count > 0;
    }

    /**
     * Ends an attempt that {@link #begin} let go on without a verdict of bcrypt (AC-012: remembered credentials): it
     * frees its place, and neither counts a failure nor clears those counted.
     */
    synchronized void release(Channel channel, String name, String address) {
        if (maxFailures == 0) {
            return;
        }
        notifyAll();
        Failures f = failures.get(key(channel, name, address));
        if (f != null) {
            f.inFlight = Math.max(0, f.inFlight - 1);
        }
    }

    /** Names and addresses tracked now (for tests). */
    synchronized int tracked() {
        return failures.size();
    }

    private static Key key(Channel channel, String name, String address) {
        // REST user names are looked up without regard to case (InMemoryUserDetailsManager), so they are counted
        // that way; CompIDs are exact.
        return new Key(channel, channel == Channel.REST ? name.toLowerCase(Locale.ROOT) : name, host(address));
    }

    /**
     * The host an address counts for, without a port. {@code /10.0.0.1:5000} and {@code host/10.0.0.1:5000} (a
     * socket address as text) are {@code 10.0.0.1}; an IPv4 address mapped into IPv6 is the IPv4 address; any
     * other IPv6 address counts for its /64 network, which one host can usually choose addresses from.
     */
    static String host(String address) {
        if (address == null) {
            return "unknown";
        }
        String a = address.substring(address.lastIndexOf('/') + 1);
        if (a.startsWith("[")) {                               // [::1]:5000
            int end = a.indexOf(']');
            a = end < 0 ? a : a.substring(1, end);
        } else if (a.indexOf(':') >= 0 && a.indexOf(':') == a.lastIndexOf(':')) {
            a = a.substring(0, a.indexOf(':'));                 // host:port
        }
        if (a.indexOf(':') < 0) {
            return a;
        }
        if (!IPV6_LITERAL.matcher(a).matches()) {
            return a;
        }
        try {
            InetAddress ip = InetAddress.getByName(a);          // an IPv6 literal: parsed, never looked up
            if (ip instanceof Inet6Address) {
                return HexFormat.of().formatHex(Arrays.copyOf(ip.getAddress(), 8)) + "::/64";
            }
            return ip.getHostAddress();                         // ::ffff:10.0.0.1 is 10.0.0.1
        } catch (UnknownHostException e) {
            return a;
        }
    }

    private record Key(Channel channel, String name, String address) {
    }

    private static final class Failures {
        int count;
        int inFlight;
        long lockedUntil;
    }
}
