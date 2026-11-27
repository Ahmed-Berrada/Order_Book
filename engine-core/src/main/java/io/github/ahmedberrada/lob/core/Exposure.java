package io.github.ahmedberrada.lob.core;

/** What a member has open in one place: how many orders and how much they could buy and sell (RK-002). */
final class Exposure {
    long orders;
    long buyQuantity;
    long sellQuantity;

    void add(Side side, long quantity) {
        orders++;
        change(side, quantity);
    }

    void remove(Side side, long quantity) {
        orders--;
        change(side, -quantity);
    }

    void change(Side side, long delta) {
        if (side == Side.BUY) {
            buyQuantity += delta;
        } else {
            sellQuantity += delta;
        }
    }
}
