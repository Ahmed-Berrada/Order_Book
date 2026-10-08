package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.journal.EventBatch;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Converts the journal's microsecond timestamps (RS-006) to instants for JSON. */
final class Timestamps {

    private Timestamps() {
    }

    static Instant of(EventBatch batch) {
        return Instant.EPOCH.plus(batch.timestampMicros(), ChronoUnit.MICROS);
    }
}
