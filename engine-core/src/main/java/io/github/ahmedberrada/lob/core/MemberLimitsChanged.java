package io.github.ahmedberrada.lob.core;

/** A member's limits were set (RK-001). */
public record MemberLimitsChanged(long sequence, long memberId, MemberLimits limits) implements Event {
}
