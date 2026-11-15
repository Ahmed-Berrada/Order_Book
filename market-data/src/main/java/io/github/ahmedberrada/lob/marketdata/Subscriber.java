package io.github.ahmedberrada.lob.marketdata;

/**
 * Receives one instrument's messages: a snapshot, then every update, in order (MD-004).
 *
 * <p>Called on the instrument's writer thread, under the publisher's lock: it must return at once and
 * never block (MD-008). {@link QueuedSubscriber} does the blocking part on its own thread.
 */
@FunctionalInterface
public interface Subscriber {

    /** @return {@code false} to be unsubscribed, for example because the subscriber is falling behind */
    boolean offer(MarketDataMessage message);
}
