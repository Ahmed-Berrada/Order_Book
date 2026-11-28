package io.github.ahmedberrada.lob.journal;

import java.time.Duration;
import java.util.Objects;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * Seals the command journal (RS-013): the venue's Ed25519 key pair, and how often it seals at most.
 *
 * @param signingKey   signs each seal; never leaves the venue
 * @param verifyingKey checks the seals at start-up; the auditor's copy is what {@link JournalVerify} uses
 * @param interval     the longest time between seals while commands arrive; also sealed at every snapshot and at close
 */
public record JournalSealing(PrivateKey signingKey, PublicKey verifyingKey, Duration interval) {

    public JournalSealing {
        Objects.requireNonNull(signingKey, "signingKey");
        Objects.requireNonNull(verifyingKey, "verifyingKey");
        Objects.requireNonNull(interval, "interval");
        if (!"Ed25519".equals(signingKey.getAlgorithm()) && !"EdDSA".equals(signingKey.getAlgorithm())) {
            throw new IllegalArgumentException("journal seals are Ed25519: " + signingKey.getAlgorithm());
        }
        if (interval.isNegative()) {
            throw new IllegalArgumentException("interval must not be negative: " + interval);
        }
    }

    @Override
    public String toString() {                         // never the private key
        return "JournalSealing[interval=" + interval + "]";
    }
}
