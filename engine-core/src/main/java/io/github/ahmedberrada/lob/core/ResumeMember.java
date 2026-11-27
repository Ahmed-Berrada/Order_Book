package io.github.ahmedberrada.lob.core;

/** Ends a member's suspension (RK-005). */
public record ResumeMember(long memberId) implements Command {
}
