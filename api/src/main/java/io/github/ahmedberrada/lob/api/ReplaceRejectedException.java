package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.RejectReason;

/** The engine refused a replace (OT-005); the order is unchanged. The replace was journaled. */
final class ReplaceRejectedException extends RuntimeException {

    private final long orderId;
    private final RejectReason reason;

    ReplaceRejectedException(long orderId, RejectReason reason) {
        super("Replace of order " + orderId + " rejected: " + reason + "; the order is unchanged");
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
