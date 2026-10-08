package io.github.ahmedberrada.lob.core;

/** A new order failed validation and never reached the book (CT-007, CT-009). */
public record OrderRejected(long sequence, long orderId, RejectReason reason) implements Event {
}
