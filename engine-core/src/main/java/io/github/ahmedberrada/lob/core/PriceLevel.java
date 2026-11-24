package io.github.ahmedberrada.lob.core;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;

/** All resting orders of one side at one price, in arrival order (CT-002). */
final class PriceLevel {

    private final long priceTicks;
    private final ArrayDeque<Order> orders = new ArrayDeque<>();
    private long totalQuantity;
    private long displayedQuantity;

    PriceLevel(long priceTicks) {
        this.priceTicks = priceTicks;
    }

    long priceTicks() {
        return priceTicks;
    }

    /** All executable quantity, hidden included. */
    long totalQuantity() {
        return totalQuantity;
    }

    /** The quantity the market may see (IC-002). */
    long displayedQuantity() {
        return displayedQuantity;
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

    /** Whether any resting order here belongs to a self-trade prevention group (SP-006). */
    boolean holdsGroup(long group) {
        for (Order order : orders) {
            if (order.selfTrade.group() == group) {
                return true;
            }
        }
        return false;
    }

    /** The order with time priority at this price. */
    Order first() {
        return orders.peekFirst();
    }

    /** Joins the back of the queue (CT-005). */
    void add(Order order) {
        orders.addLast(order);
        totalQuantity += order.remainingQuantity;
        displayedQuantity += order.displayed;
    }

    /**
     * Fills the first order, at most its displayed quantity (IC-003). A fully filled order leaves the
     * queue; a partly filled one keeps its place; an iceberg whose peak is gone shows a new peak at the
     * back of the queue (IC-004).
     *
     * @return whether the first order was an iceberg that refilled its peak
     */
    boolean fillFirst(long quantity) {
        Order first = orders.peekFirst();
        first.remainingQuantity -= quantity;
        first.displayed -= quantity;
        totalQuantity -= quantity;
        displayedQuantity -= quantity;
        if (first.remainingQuantity == 0) {
            orders.pollFirst();
            return false;
        }
        if (first.displayed == 0) {
            orders.pollFirst();
            first.displayed = first.peak(first.remainingQuantity);
            displayedQuantity += first.displayed;
            orders.addLast(first);
            return true;
        }
        return false;
    }

    /**
     * Lowers an order's quantity without moving it in the queue; hidden quantity goes first (IC-005).
     * A fully visible order shows its whole remainder, so the same formula keeps it fully visible.
     */
    void reduce(Order order, long newQuantity) {
        long newDisplayed = Math.min(order.displayed, newQuantity);
        totalQuantity -= order.remainingQuantity - newQuantity;
        displayedQuantity -= order.displayed - newDisplayed;
        order.remainingQuantity = newQuantity;
        order.displayed = newDisplayed;
    }

    /** Removes an order from anywhere in the queue. O(n) in v1, by decision (ADR-0001 §5). */
    void remove(Order order) {
        orders.remove(order);
        totalQuantity -= order.remainingQuantity;
        displayedQuantity -= order.displayed;
    }
}
