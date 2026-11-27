package io.github.ahmedberrada.lob.core;

/** A member's suspension ended (RK-005). */
public record MemberResumed(long sequence, long memberId) implements Event {
}
