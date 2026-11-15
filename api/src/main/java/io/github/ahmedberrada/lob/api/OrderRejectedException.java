package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.RejectReason;

/** The engine rejected a command (CT-007 to CT-009). It was journaled and consumed {@code orderId}. */
final class OrderRejectedException extends RuntimeException {

    private final long orderId;
    private final RejectReason reason;

    OrderRejectedException(long orderId, RejectReason reason) {
        super("Order " + orderId + " rejected: " + reason);
        this.orderId = orderId;
        this.reason = reason;
    }

    long orderId() {
        return orderId;
    }

    RejectReason reason() {
        return reason;
    }
}
