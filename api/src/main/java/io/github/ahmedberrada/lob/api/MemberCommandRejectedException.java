package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.RejectReason;

/** The engine refused a limits, suspend or resume command (RK-001, RK-004, RK-005). */
final class MemberCommandRejectedException extends RuntimeException {

    private final RejectReason reason;

    MemberCommandRejectedException(long memberId, RejectReason reason) {
        super("member " + memberId + ": " + reason);
        this.reason = reason;
    }

    RejectReason reason() {
        return reason;
    }
}
