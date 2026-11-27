package io.github.ahmedberrada.lob.core;

/**
 * A member's pre-trade limits, each with {@code 0} meaning none (RK-001).
 *
 * @param maxOrderQuantity largest quantity of one order, in lots
 * @param maxOrderNotional largest price × quantity of one order, in ticks × lots
 * @param priceCollarBps   largest distance of a limit price from the last trade price, in basis points
 * @param maxOpenOrders    most open orders (resting orders and parked stops) at once
 * @param maxNetPosition   largest long or short position, counting open orders as if filled
 */
public record MemberLimits(
        long maxOrderQuantity, long maxOrderNotional, long priceCollarBps, long maxOpenOrders, long maxNetPosition) {

    /** No limit at all: what a member has until limits are set. */
    public static final MemberLimits NONE = new MemberLimits(0, 0, 0, 0, 0);

    /** Whether every limit is zero or positive (RK-001). */
    boolean isValid() {
        return maxOrderQuantity >= 0 && maxOrderNotional >= 0 && priceCollarBps >= 0 && maxOpenOrders >= 0
                && maxNetPosition >= 0;
    }
}
