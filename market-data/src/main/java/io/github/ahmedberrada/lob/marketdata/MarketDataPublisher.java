package io.github.ahmedberrada.lob.marketdata;

import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.service.EventListener;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.InstrumentState;
import io.github.ahmedberrada.lob.service.MatchingService;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes every instrument's level-2 book (ADR-0007): listens to the service's events, keeps one
 * {@link BookImage} per instrument, and fans its updates out to subscribers.
 *
 * <p>Each instrument has a lock, held while an update is applied and delivered and while a subscriber
 * is added with its snapshot, so a new subscriber's snapshot and first update are contiguous (MD-004).
 */
public final class MarketDataPublisher implements EventListener {

    /** Ends a subscription. */
    @FunctionalInterface
    public interface Subscription {
        void cancel();
    }

    private static final class Channel {
        private final String symbol;
        private BookImage image;
        private final List<EventBatch> pending = new ArrayList<>();
        private final List<Subscriber> subscribers = new ArrayList<>();

        Channel(String symbol) {
            this.symbol = symbol;
        }
    }

    private final Map<String, Channel> channels = new HashMap<>();

    MarketDataPublisher(List<InstrumentConfig> instruments) {
        instruments.forEach(i -> channels.put(i.symbol(), new Channel(i.symbol())));
    }

    /**
     * Starts publishing every instrument of the service. The listener is registered before each
     * instrument's state is read, and events the state already includes are skipped, so nothing is
     * lost or applied twice (ADR-0007 §1). Returns once every book image is ready.
     */
    public static MarketDataPublisher start(MatchingService service) {
        Objects.requireNonNull(service, "service");
        MarketDataPublisher publisher = new MarketDataPublisher(service.instruments());
        service.addListener(publisher);
        for (InstrumentConfig instrument : service.instruments()) {
            publisher.initialize(instrument.symbol(), service.state(instrument.symbol()).join());
        }
        return publisher;
    }

    /**
     * Installs an instrument's starting state. Events that arrived since the listener was registered
     * were buffered: those the state already includes are skipped, later ones applied. The later ones
     * exist because commands keep flowing between reading the state and taking the lock here.
     */
    void initialize(String symbol, InstrumentState state) {
        Channel channel = channels.get(symbol);
        synchronized (channel) {
            channel.image = new BookImage(symbol, state);
            channel.pending.forEach(channel.image::apply);           // nobody is subscribed yet
            channel.pending.clear();
        }
    }

    /**
     * Subscribes to an instrument: the subscriber is offered the current snapshot, then every update.
     *
     * @throws RequestRefusedException {@code UNKNOWN_INSTRUMENT} if the symbol is not listed
     */
    public Subscription subscribe(String symbol, Subscriber subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        Channel channel = channels.get(symbol);
        if (channel == null) {
            throw new RequestRefusedException(RequestRefusedException.Reason.UNKNOWN_INSTRUMENT, symbol);
        }
        synchronized (channel) {
            if (deliver(subscriber, channel.image.snapshot())) {
                channel.subscribers.add(subscriber);
            }
        }
        return () -> {
            synchronized (channel) {
                channel.subscribers.remove(subscriber);
            }
        };
    }

    /** Called on the instrument's writer thread for every command (ADR-0004 §7). */
    @Override
    public void onEvents(String symbol, EventBatch batch, Object context) {
        Channel channel = channels.get(symbol);
        synchronized (channel) {
            if (channel.image == null) {
                channel.pending.add(batch);                         // still starting: applied once the state is read
                return;
            }
            channel.image.apply(batch).ifPresent(update ->
                    channel.subscribers.removeIf(subscriber -> !deliver(subscriber, update)));
        }
    }

    /** Number of subscribers of an instrument, for monitoring. */
    public int subscriberCount(String symbol) {
        Channel channel = channels.get(symbol);
        synchronized (channel) {
            return channel.subscribers.size();
        }
    }

    /** A subscriber that refuses a message, or throws, is dropped (MD-008). */
    private static boolean deliver(Subscriber subscriber, MarketDataMessage message) {
        try {
            return subscriber.offer(message);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
