package io.github.ahmedberrada.lob.core;

import java.util.Iterator;
import java.util.NoSuchElementException;

/** All resting orders of one side at one price, in arrival order (CT-002). */
final class PriceLevel {

    private final long priceTicks;
    private Order head;
    private Order tail;
    private int size;
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
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    /** The queue, oldest order first. Read-only: do not change the level while iterating. */
    Iterable<Order> orders() {
        return () -> new Iterator<>() {
            private Order cursor = head;

            @Override
            public boolean hasNext() {
                return cursor != null;
            }

            @Override
            public Order next() {
                if (cursor == null) {
                    throw new NoSuchElementException();
                }
                Order current = cursor;
                cursor = cursor.next;
                return current;
            }
        };
    }

    /** Whether any resting order here belongs to a self-trade prevention group (SP-006). */
    boolean holdsGroup(long group) {
        for (Order order = head; order != null; order = order.next) {
            if (order.selfTrade.group() == group) {
                return true;
            }
        }
        return false;
    }

    /** The order with time priority at this price. */
    Order first() {
        return head;
    }

    /** Joins the back of the queue (CT-005). */
    void add(Order order) {
        link(order);
        totalQuantity += order.remainingQuantity;
        displayedQuantity += order.displayed;
    }

    private void link(Order order) {
        order.previous = tail;
        order.next = null;
        if (tail == null) {
            head = order;
        } else {
            tail.next = order;
        }
        tail = order;
        size++;
    }

    private void unlink(Order order) {
        if (order.previous == null) {
            head = order.next;
        } else {
            order.previous.next = order.next;
        }
        if (order.next == null) {
            tail = order.previous;
        } else {
            order.next.previous = order.previous;
        }
        order.previous = null;
        order.next = null;
        size--;
    }

    /**
     * Fills the first order, at most its displayed quantity (IC-003). A fully filled order leaves the
     * queue; a partly filled one keeps its place; an iceberg whose peak is gone shows a new peak at the
     * back of the queue (IC-004).
     *
     * @return whether the first order was an iceberg that refilled its peak
     */
    boolean fillFirst(long quantity) {
        Order first = head;
        first.remainingQuantity -= quantity;
        first.displayed -= quantity;
        totalQuantity -= quantity;
        displayedQuantity -= quantity;
        if (first.remainingQuantity == 0) {
            unlink(first);
            return false;
        }
        if (first.displayed == 0) {
            unlink(first);
            first.displayed = first.peak(first.remainingQuantity);
            displayedQuantity += first.displayed;
            link(first);
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

    /** Changes how much of an order is shown (TP-003, TP-008). */
    void show(Order order, long newDisplayed) {
        displayedQuantity += newDisplayed - order.displayed;
        order.displayed = newDisplayed;
    }

    /** Removes an order from anywhere in the queue, in constant time (ADR-0016). */
    void remove(Order order) {
        unlink(order);
        totalQuantity -= order.remainingQuantity;
        displayedQuantity -= order.displayed;
    }
}
