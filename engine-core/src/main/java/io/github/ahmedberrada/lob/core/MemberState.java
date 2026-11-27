package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/** What the engine keeps about a member, as captured in an {@link EngineSnapshot} (MB-004). */
public record MemberState(long memberId, MemberLimits limits, boolean suspended, long netPosition) {

    public MemberState {
        Objects.requireNonNull(limits, "limits");
    }
}
