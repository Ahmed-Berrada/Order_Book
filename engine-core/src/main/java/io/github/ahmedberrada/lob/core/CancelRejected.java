package io.github.ahmedberrada.lob.core;

/** A cancel targeted an order that is not resting; nothing changed (CT-008). */
public record CancelRejected(long sequence, long orderId, RejectReason reason) implements Event {
}
