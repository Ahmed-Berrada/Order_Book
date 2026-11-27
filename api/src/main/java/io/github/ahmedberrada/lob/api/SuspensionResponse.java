package io.github.ahmedberrada.lob.api;

/** Outcome of the kill switch: how many open orders were cancelled across all instruments (RK-004). */
public record SuspensionResponse(long memberId, boolean suspended, long cancelledOrders) {
}
