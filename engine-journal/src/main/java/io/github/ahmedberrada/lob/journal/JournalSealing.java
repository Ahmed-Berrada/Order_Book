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
 * @param interval     the longest time between seals while commands arrive, by the commands' timestamps, from 10 ms to
 *                     1 h; also sealed at every snapshot, at close, and after {@link #MAX_UNSEALED_COMMANDS}
 * @param sealExistingJournal begin sealing a journal that already holds commands but has no seals. Only for the first
 *                     start with sealing: otherwise a missing seal file is someone deleting it, and stops the instrument
 */
public record JournalSealing(PrivateKey signingKey, PublicKey verifyingKey, Duration interval,
        boolean sealExistingJournal) {

    /** Commands after which a seal is due whatever the time: a clock that stalls cannot stop the seals. */
    public static final int MAX_UNSEALED_COMMANDS = 10_000;

    /** Seals the journal of a new instrument, refusing an existing journal without seals. */
    public JournalSealing(PrivateKey signingKey, PublicKey verifyingKey, Duration interval) {
        this(signingKey, verifyingKey, interval, false);
    }

    public JournalSealing {
        Objects.requireNonNull(signingKey, "signingKey");
        Objects.requireNonNull(verifyingKey, "verifyingKey");
        Objects.requireNonNull(interval, "interval");
        if (!"Ed25519".equals(signingKey.getAlgorithm()) && !"EdDSA".equals(signingKey.getAlgorithm())) {
            throw new IllegalArgumentException("journal seals are Ed25519: " + signingKey.getAlgorithm());
        }
        if (interval.compareTo(Duration.ofMillis(10)) < 0 || interval.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("the seal interval must be from 10 ms to 1 h: " + interval);
        }
    }

    @Override
    public String toString() {                         // never the private key
        return "JournalSealing[interval=" + interval + ", sealExistingJournal=" + sealExistingJournal + "]";
    }
}
