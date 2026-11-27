package io.github.ahmedberrada.lob.core;

/** A member was suspended; {@code cancelledOrders} cancels with reason KILL_SWITCH follow (RK-004). */
public record MemberSuspended(long sequence, long memberId, long cancelledOrders) implements Event {
}
