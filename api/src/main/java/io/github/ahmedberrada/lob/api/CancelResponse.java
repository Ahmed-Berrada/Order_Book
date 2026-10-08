package io.github.ahmedberrada.lob.api;

import java.time.Instant;

/** Outcome of a successful cancel. */
public record CancelResponse(long orderId, long cancelledQuantity, long commandSequence, Instant timestamp) {
}
