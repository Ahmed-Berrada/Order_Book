package io.github.ahmedberrada.lob.core;

/** A new order passed validation and received its engine-assigned ID (CT-010). */
public record OrderAccepted(long sequence, long orderId) implements Event {
}
