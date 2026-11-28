package io.github.ahmedberrada.lob.service;

import io.github.ahmedberrada.lob.journal.EventBatch;

/**
 * Told, on the instrument's thread, how long each command took from the engine's point of view (journal write
 * and matching; with {@code fsync: EVERY_COMMAND} the device flush too), and, with {@code GROUP}, how long each
 * group's flush took (ADR-0018, ADR-0020). Called only once the command is on the device, so a metric never
 * counts a command whose outcome is unknown. For metrics. It must be fast and must not throw; a failure is
 * logged and ignored.
 */
@FunctionalInterface
public interface CommandObserver {

    /** Observes nothing. */
    CommandObserver NONE = (symbol, batch, nanos) -> { };

    /**
     * @param nanos wall-clock time of the engine call, in nanoseconds
     */
    void processed(String symbol, EventBatch batch, long nanos);

    /**
     * One device flush covered {@code commands} commands (group commit, ADR-0020).
     *
     * @param nanos wall-clock time of the flush, in nanoseconds
     */
    default void flushed(String symbol, int commands, long nanos) {
    }
}
