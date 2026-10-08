package io.github.ahmedberrada.lob.core;

/** Side of an order. */
public enum Side {
    BUY,
    SELL;

    /** The side this order matches against. */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
