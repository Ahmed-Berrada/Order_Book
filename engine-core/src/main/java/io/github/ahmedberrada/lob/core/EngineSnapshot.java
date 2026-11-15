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
 */
public record EngineSnapshot(
        Instrument instrument, long nextOrderId, long nextSequence, List<RestingOrder> restingOrders) {

    public EngineSnapshot {
        Objects.requireNonNull(instrument, "instrument");
        restingOrders = List.copyOf(restingOrders);
    }
}
