package io.github.ahmedberrada.lob.marketdata;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A subscriber with a bounded queue, drained by its own virtual thread into a {@link Sink} that may
 * block, such as a network connection (ADR-0007 §3). If the queue is full, or the sink fails, the
 * subscriber disconnects instead of slowing the instrument's thread (MD-008).
 */
public final class QueuedSubscriber implements Subscriber, AutoCloseable {

    /** Where messages go; may block. */
    @FunctionalInterface
    public interface Sink {
        void send(MarketDataMessage message) throws Exception;
    }

    /** Why the subscriber stopped. */
    public enum Disconnect {
        /** It could not keep up and its queue filled. */
        SLOW_CONSUMER,
        /** The sink failed, for example because the connection was closed. */
        SINK_FAILED,
        /** {@link #close()} was called. */
        CLOSED
    }

    private final BlockingQueue<MarketDataMessage> queue;
    private final Sink sink;
    private final Consumer<Disconnect> onDisconnect;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile Disconnect reason = Disconnect.CLOSED;
    private final Thread drainer;

    /**
     * @param onDisconnect called once, on the subscriber's own thread, when it stops
     */
    public QueuedSubscriber(int capacity, Sink sink, Consumer<Disconnect> onDisconnect) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.sink = Objects.requireNonNull(sink, "sink");
        this.onDisconnect = Objects.requireNonNull(onDisconnect, "onDisconnect");
        this.drainer = Thread.ofVirtual().name("market-data-subscriber").start(this::drain);
    }

    /** Never blocks: queues the message, or disconnects if the queue is full. */
    @Override
    public boolean offer(MarketDataMessage message) {
        if (!open.get()) {
            return false;
        }
        if (!queue.offer(message)) {
            stop(Disconnect.SLOW_CONSUMER);
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        stop(Disconnect.CLOSED);
    }

    private void stop(Disconnect why) {
        if (open.compareAndSet(true, false)) {
            reason = why;
            drainer.interrupt();
        }
    }

    private void drain() {
        try {
            while (open.get()) {
                sink.send(queue.take());
            }
        } catch (InterruptedException e) {
            // stopped
        } catch (Exception e) {
            if (open.compareAndSet(true, false)) {
                reason = Disconnect.SINK_FAILED;
            }
        }
        onDisconnect.accept(reason);
    }
}
