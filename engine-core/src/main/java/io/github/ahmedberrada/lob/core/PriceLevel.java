package io.github.ahmedberrada.lob.core;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;

/** All resting orders of one side at one price, in arrival order (CT-002). */
final class PriceLevel {

    private final long priceTicks;
    private final ArrayDeque<Order> orders = new ArrayDeque<>();
    private long totalQuantity;

    PriceLevel(long priceTicks) {
        this.priceTicks = priceTicks;
    }

    long priceTicks() {
        return priceTicks;
    }

    long totalQuantity() {
        return totalQuantity;
    }

    int orderCount() {
        return orders.size();
    }

    boolean isEmpty() {
        return orders.isEmpty();
    }

    /** The queue, oldest order first. Read-only. */
    Collection<Order> orders() {
        return Collections.unmodifiableCollection(orders);
    }

    /** The order with time priority at this price. */
    Order first() {
        return orders.peekFirst();
    }

    /** Joins the back of the queue (CT-005). */
    void add(Order order) {
        orders.addLast(order);
        totalQuantity += order.remainingQuantity;
    }

    /** Fills the first order; it leaves the queue once fully filled and keeps its place otherwise. */
    void fillFirst(long quantity) {
        Order first = orders.peekFirst();
        first.remainingQuantity -= quantity;
        totalQuantity -= quantity;
        if (first.remainingQuantity == 0) {
            orders.pollFirst();
        }
    }

    /** Removes an order from anywhere in the queue. O(n) in v1, by decision (ADR-0001 §5). */
    void remove(Order order) {
        orders.remove(order);
        totalQuantity -= order.remainingQuantity;
    }
}
