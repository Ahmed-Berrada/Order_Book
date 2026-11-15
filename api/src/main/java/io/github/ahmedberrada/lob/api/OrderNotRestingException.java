package io.github.ahmedberrada.lob.api;

/** A cancel or replace targeted an order that is not resting (CT-008, OT-005). It was journaled. */
final class OrderNotRestingException extends RuntimeException {

    private final long orderId;

    OrderNotRestingException(long orderId) {
        super("Order " + orderId + " is not resting");
        this.orderId = orderId;
    }

    long orderId() {
        return orderId;
    }
}
