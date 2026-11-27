package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/** Sets a member's pre-trade limits (RK-001). */
public record SetMemberLimits(long memberId, MemberLimits limits) implements Command {
    public SetMemberLimits {
        Objects.requireNonNull(limits, "limits");
    }
}
