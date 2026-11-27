package io.github.ahmedberrada.lob.core;

/** The kill switch: suspends a member and cancels all its open orders (RK-004). */
public record SuspendMember(long memberId) implements Command {
}
