package io.github.ahmedberrada.lob.core;

import java.util.List;
import java.util.Objects;

/**
 * Complete state of a {@link MatchingEngine}, from which an identical engine can be rebuilt
 * (ADR-0003). Resting orders are listed in priority order: bids best price first, then asks best price
 * first, and arrival order within a price. Restoring them in that order recreates every queue exactly.
 *
 * @param nextOrderId  ID the next new order will receive
 * @param nextSequence sequence number the next event will carry
 * @param lastTradePriceTicks price of the most recent trade, or 0 before the first one (ST-002)
 * @param parkedStops  stop orders waiting for their trigger, in order of order ID (ST-008)
 * @param phase        trading phase (AU-006)
 * @param members      members with limits, a suspension or a position, in order of member ID (MB-004)
 * @param referencePriceTicks the volatility reference price, or 0 before the first trade (VI-001, VI-006)
 */
public record EngineSnapshot(
        Instrument instrument, long nextOrderId, long nextSequence, List<RestingOrder> restingOrders,
        long lastTradePriceTicks, List<ParkedStop> parkedStops,
        TradingPhase phase, long referencePriceTicks,
        List<MemberState> members) {

    public EngineSnapshot {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(phase, "phase");
        restingOrders = List.copyOf(restingOrders);
        parkedStops = List.copyOf(parkedStops);
        members = List.copyOf(members);
    }

    /** A continuous-trading state. */
    public EngineSnapshot(Instrument instrument, long nextOrderId, long nextSequence,
            List<RestingOrder> restingOrders, long lastTradePriceTicks, List<ParkedStop> parkedStops) {
        this(instrument, nextOrderId, nextSequence, restingOrders, lastTradePriceTicks, parkedStops,
                TradingPhase.CONTINUOUS, 0);
    }

    /** A state with no reference price yet. */
    public EngineSnapshot(Instrument instrument, long nextOrderId, long nextSequence,
            List<RestingOrder> restingOrders, long lastTradePriceTicks, List<ParkedStop> parkedStops,
            TradingPhase phase) {
        this(instrument, nextOrderId, nextSequence, restingOrders, lastTradePriceTicks, parkedStops, phase, 0);
    }

    /** A state without members. */
    public EngineSnapshot(Instrument instrument, long nextOrderId, long nextSequence,
            List<RestingOrder> restingOrders, long lastTradePriceTicks, List<ParkedStop> parkedStops,
            TradingPhase phase, long referencePriceTicks) {
        this(instrument, nextOrderId, nextSequence, restingOrders, lastTradePriceTicks, parkedStops, phase,
                referencePriceTicks, List.of());
    }

    /** A state with no trade yet and no parked stop. */
    public EngineSnapshot(Instrument instrument, long nextOrderId, long nextSequence, List<RestingOrder> restingOrders) {
        this(instrument, nextOrderId, nextSequence, restingOrders, 0, List.of(), TradingPhase.CONTINUOUS, 0);
    }
}
