package io.github.ahmedberrada.lob.core;

/** Request to remove a resting order from the book (rulebook CT-008). */
public record CancelOrder(long orderId) implements Command {
}
