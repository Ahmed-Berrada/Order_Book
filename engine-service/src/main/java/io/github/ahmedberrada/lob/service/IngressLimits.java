package io.github.ahmedberrada.lob.service;

/**
 * The venue's throttles on members' messages (OE-008, OE-009, ADR-0021). Each value {@code 0} means no limit.
 *
 * @param messagesPerSecond new orders and replaces a member may submit a second, all instruments together
 * @param maxOrderToTradeRatio messages per trade a member may reach in an instrument in a UTC day
 * @param orderToTradeMinimum messages a member must have submitted in an instrument that day before the ratio
 *        applies
 */
public record IngressLimits(int messagesPerSecond, int maxOrderToTradeRatio, int orderToTradeMinimum) {

    /** No throttle. */
    public static final IngressLimits NONE = new IngressLimits(0, 0, 0);

    public IngressLimits {
        if (messagesPerSecond < 0 || maxOrderToTradeRatio < 0 || orderToTradeMinimum < 0) {
            throw new IllegalArgumentException("ingress limits must not be negative: " + messagesPerSecond + ", "
                    + maxOrderToTradeRatio + ", " + orderToTradeMinimum);
        }
    }
}
