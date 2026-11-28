package io.github.ahmedberrada.lob.service;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.MassCancel;
import io.github.ahmedberrada.lob.core.ReplaceOrder;
import io.github.ahmedberrada.lob.core.StopOrder;
import io.github.ahmedberrada.lob.core.TradeExecuted;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.journal.TimeSource;
import io.github.ahmedberrada.lob.service.RequestRefusedException.Reason;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The message-rate and order-to-trade throttles (OE-008, OE-009, ADR-0021). Thread-safe: {@link #check} runs on
 * the submitting threads, {@link #onEvents} on the instruments' threads.
 */
final class Throttles implements EventListener {

    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long MICROS_PER_DAY = 86_400L * MICROS_PER_SECOND;

    private final IngressLimits limits;
    private final TimeSource clock;
    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<Key, Ratio> ratios = new ConcurrentHashMap<>();

    Throttles(IngressLimits limits, TimeSource clock) {
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * Refuses a member's command that is over a limit, or counts it.
     *
     * @return the refusal, or {@code null} if the command may be queued
     */
    RequestRefusedException check(String symbol, Command command, long memberId) {
        if (memberId == 0) {
            return null;
        }
        boolean entersOrder = entersOrder(command);
        if (!entersOrder && !(isCancel(command))) {
            return null;
        }
        long now = clock.nowMicros();
        Ratio ratio = limits.maxOrderToTradeRatio() == 0 ? null
                : ratios.computeIfAbsent(new Key(symbol, memberId), k -> new Ratio());
        if (entersOrder) {
            if (ratio != null && ratio.over(now, limits)) {
                return new RequestRefusedException(Reason.ORDER_TO_TRADE_LIMIT, symbol,
                        Duration.ofNanos((MICROS_PER_DAY - Math.floorMod(now, MICROS_PER_DAY)) * 1_000));
            }
            if (limits.messagesPerSecond() > 0) {
                long waitMicros = buckets.computeIfAbsent(memberId, m -> new Bucket(limits.messagesPerSecond(), now))
                        .take(now);
                if (waitMicros > 0) {
                    return new RequestRefusedException(Reason.THROTTLED, symbol, Duration.ofNanos(waitMicros * 1_000));
                }
            }
        }
        if (ratio != null) {
            ratio.message(now);
        }
        return null;
    }

    /**
     * Gives back what {@link #check} counted for a command that the instrument then refused before queueing it
     * ({@code OVERLOADED}, {@code HALTED}, {@code STOPPED}): it never reached the journal, so it is not a message.
     */
    void release(String symbol, Command command, long memberId) {
        if (memberId == 0 || !(entersOrder(command) || isCancel(command))) {
            return;
        }
        if (entersOrder(command) && limits.messagesPerSecond() > 0) {
            Bucket bucket = buckets.get(memberId);
            if (bucket != null) {
                bucket.refund();
            }
        }
        Ratio ratio = ratios.get(new Key(symbol, memberId));
        if (ratio != null) {
            ratio.unmessage();
        }
    }

    /** A cancel, of one order or all (RK-006): counted as a message, never refused. */
    private static boolean isCancel(Command command) {
        return command instanceof CancelOrder || command instanceof MassCancel;
    }

    private static boolean entersOrder(Command command) {
        return command instanceof LimitOrder || command instanceof MarketOrder || command instanceof StopOrder
                || command instanceof ReplaceOrder;
    }

    @Override
    public void onEvents(String symbol, EventBatch batch, Object context) {
        if (limits.maxOrderToTradeRatio() == 0) {
            return;
        }
        for (Event event : batch.events()) {
            if (event instanceof TradeExecuted trade) {
                trade(symbol, trade.takerMemberId(), batch.timestampMicros());
                if (trade.makerMemberId() != trade.takerMemberId()) {
                    trade(symbol, trade.makerMemberId(), batch.timestampMicros());
                }
            }
        }
    }

    private void trade(String symbol, long memberId, long micros) {
        if (memberId != 0) {
            ratios.computeIfAbsent(new Key(symbol, memberId), k -> new Ratio()).trade(micros);
        }
    }

    private record Key(String symbol, long memberId) {
    }

    /** One member's token bucket, in millionths of a message so that it refills without rounding. */
    private static final class Bucket {

        private final long capacity;
        private final long perSecond;
        private long tokens;
        private long lastMicros;

        Bucket(int perSecond, long now) {
            this.perSecond = perSecond;
            this.capacity = perSecond * MICROS_PER_SECOND;
            this.tokens = capacity;
            this.lastMicros = now;
        }

        /** Takes one message, or returns how many microseconds until one is available. */
        synchronized long take(long now) {
            // A clock that steps back refills nothing, and the bucket refills from the new time on: keeping the
            // later time would stop refilling until the clock caught up.
            long elapsed = Math.max(0, now - lastMicros);
            lastMicros = now;
            tokens = elapsed >= MICROS_PER_SECOND ? capacity : Math.min(capacity, tokens + elapsed * perSecond);
            if (tokens >= MICROS_PER_SECOND) {
                tokens -= MICROS_PER_SECOND;
                return 0;
            }
            return Math.ceilDiv(MICROS_PER_SECOND - tokens, perSecond);
        }

        synchronized void refund() {
            tokens = Math.min(capacity, tokens + MICROS_PER_SECOND);
        }
    }

    /** One member's messages and trades in one instrument, for the current UTC day. */
    private static final class Ratio {

        private long day = Long.MIN_VALUE;
        private long messages;
        private long trades;

        synchronized boolean over(long now, IngressLimits limits) {
            roll(now);
            return messages >= limits.orderToTradeMinimum()
                    && messages + 1 > (long) limits.maxOrderToTradeRatio() * Math.max(1, trades);
        }

        synchronized void message(long now) {
            roll(now);
            messages++;
        }

        synchronized void unmessage() {
            if (messages > 0) {
                messages--;
            }
        }

        synchronized void trade(long now) {
            roll(now);
            if (Math.floorDiv(now, MICROS_PER_DAY) == day) {        // not a trade of a day already over
                trades++;
            }
        }

        private void roll(long now) {
            long today = Math.floorDiv(now, MICROS_PER_DAY);
            if (today > day) {
                day = today;
                messages = 0;
                trades = 0;
            }
        }
    }
}
