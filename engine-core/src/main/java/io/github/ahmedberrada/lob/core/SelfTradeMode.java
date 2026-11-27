package io.github.ahmedberrada.lob.core;

/** What happens when an incoming order meets a resting order of its own group (SP-002 to SP-004). */
public enum SelfTradeMode {
    /** The incoming order's remaining quantity is cancelled; the resting order stays (SP-002). */
    CANCEL_NEWEST,
    /** The resting order is cancelled and matching continues (SP-003). */
    CANCEL_OLDEST,
    /** The resting order and the incoming order's remaining quantity are both cancelled (SP-004). */
    CANCEL_BOTH
}
