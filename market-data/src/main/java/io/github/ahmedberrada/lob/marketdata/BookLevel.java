package io.github.ahmedberrada.lob.marketdata;

/**
 * Aggregate of one price level: total resting quantity and number of orders, never their owners
 * (MD-001). In an update, a quantity of 0 means the level is gone (MD-006).
 */
public record BookLevel(long priceTicks, long quantity, int orders) {
}
