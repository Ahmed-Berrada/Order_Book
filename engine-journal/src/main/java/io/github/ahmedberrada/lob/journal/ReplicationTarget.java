package io.github.ahmedberrada.lob.journal;

import java.time.Duration;
import java.util.Objects;

/**
 * Where a primary replicates its command journals (RS-014): the standby's address, the secret both share to
 * authenticate the link, and how long the primary waits for the standby to confirm before it halts (RS-015).
 */
public record ReplicationTarget(String host, int port, byte[] secret, Duration timeout) {

    public ReplicationTarget {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(timeout, "timeout");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (secret.length < 32) {
            throw new IllegalArgumentException("the replication secret must be at least 32 bytes");
        }
        if (!timeout.isPositive() || timeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("timeout must be positive and at most an hour: " + timeout);
        }
        secret = secret.clone();
    }

    @Override
    public byte[] secret() {
        return secret.clone();
    }

    @Override
    public String toString() {                         // never the secret
        return "ReplicationTarget[" + host + ":" + port + ", timeout=" + timeout + "]";
    }
}
