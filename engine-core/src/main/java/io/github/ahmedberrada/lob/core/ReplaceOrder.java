package io.github.ahmedberrada.lob.core;

/**
 * Request to change the price and remaining quantity of a resting order, which keeps its order ID
 * (rulebook OT-005 to OT-008).
 *
 * @param quantity the new remaining quantity; what has already been filled is not affected
 */
public record ReplaceOrder(long orderId, long priceTicks, long quantity) implements Command {
}
