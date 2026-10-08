package io.github.ahmedberrada.lob.journal;

import java.time.Instant;

/**
 * Source of ingress timestamps, in microseconds since the Unix epoch (UTC). Injected so that tests
 * control time; the engine itself never reads a clock (rulebook RS-006).
 */
@FunctionalInterface
public interface TimeSource {

    long nowMicros();

    /** The operating system's UTC clock, at microsecond granularity. */
    static TimeSource system() {
        return () -> {
            Instant now = Instant.now();
            return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000L), now.getNano() / 1_000);
        };
    }
}
