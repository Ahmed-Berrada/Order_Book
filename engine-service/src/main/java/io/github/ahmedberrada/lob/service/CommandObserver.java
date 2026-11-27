package io.github.ahmedberrada.lob.service;

import io.github.ahmedberrada.lob.journal.EventBatch;

/**
 * Told, on the instrument's thread, how long each command took from the engine's point of view: journal
 * write, matching and the event write (ADR-0018). For metrics. It must be fast and must not throw; a failure
 * is logged and ignored.
 */
@FunctionalInterface
public interface CommandObserver {

    /** Observes nothing. */
    CommandObserver NONE = (symbol, batch, nanos) -> { };

    /**
     * @param nanos wall-clock time of the engine call, in nanoseconds
     */
    void processed(String symbol, EventBatch batch, long nanos);
}
