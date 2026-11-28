package io.github.ahmedberrada.lob.core;

/**
 * A member's mass cancel; {@code cancelledOrders} cancels with reason MASS_CANCEL follow (RK-006).
 *
 * @param side the side cancelled, or {@code null} for both
 */
public record MassCancelled(long sequence, long memberId, Side side, long cancelledOrders) implements Event {
}
