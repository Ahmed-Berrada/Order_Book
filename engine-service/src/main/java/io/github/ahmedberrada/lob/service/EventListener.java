package io.github.ahmedberrada.lob.service;

import io.github.ahmedberrada.lob.journal.EventBatch;

/**
 * Receives the events of every command processed by any instrument (ADR-0004 §7): order gateways use
 * it to report fills to the owners of resting orders, and market data will use it to publish the book.
 *
 * <p>Called on the instrument's writer thread, right after the command is journaled and processed and
 * before its future completes, so listeners see each instrument's batches in sequence order. It must
 * be quick and must not block: the instrument waits for it.
 */
@FunctionalInterface
public interface EventListener {

    /**
     * @param context what the submitter attached to the command, or {@code null}
     */
    void onEvents(String symbol, EventBatch batch, Object context);
}
