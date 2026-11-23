package io.github.ahmedberrada.lob.core;

/** A replace was refused; the order is unchanged (OT-005). */
public record ReplaceRejected(long sequence, long orderId, RejectReason reason) implements Event {
}
