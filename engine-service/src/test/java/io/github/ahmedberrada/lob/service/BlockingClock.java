package io.github.ahmedberrada.lob.service;

import io.github.ahmedberrada.lob.journal.TimeSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test clock that can hold the instrument's thread: while closed, the next command blocks inside
 * {@code nowMicros()}, which is called on the worker thread before journaling. This lets tests fill
 * the queue deterministically.
 */
final class BlockingClock implements TimeSource {

    private volatile CountDownLatch gate = new CountDownLatch(0);
    private final CountDownLatch blocked = new CountDownLatch(1);
    private long now = 1_000_000;

    void close() {
        gate = new CountDownLatch(1);
    }

    void open() {
        gate.countDown();
    }

    /** Waits until the worker thread is blocked in the clock. */
    void awaitBlocked() throws InterruptedException {
        if (!blocked.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("worker never reached the clock");
        }
    }

    @Override
    public long nowMicros() {
        CountDownLatch current = gate;
        if (current.getCount() > 0) {
            blocked.countDown();
            try {
                current.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return now++;
    }
}
