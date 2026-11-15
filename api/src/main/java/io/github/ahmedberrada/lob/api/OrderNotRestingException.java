package io.github.ahmedberrada.lob.api;

/** A cancel targeted an order that is not resting (CT-008). The cancel was journaled. */
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
