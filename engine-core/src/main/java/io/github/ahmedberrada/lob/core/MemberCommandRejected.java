package io.github.ahmedberrada.lob.core;

/** A limits, suspend or resume command was refused and changed nothing (RK-001, RK-004, RK-005). */
public record MemberCommandRejected(long sequence, long memberId, RejectReason reason) implements Event {
}
