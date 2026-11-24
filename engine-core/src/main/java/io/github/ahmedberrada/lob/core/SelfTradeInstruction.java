package io.github.ahmedberrada.lob.core;

import java.util.Objects;

/**
 * Who an order must not trade with, and what to do about it (rulebook SP-001). Orders of the same
 * non-zero group never trade with each other; group 0 means no prevention.
 *
 * <p>A negative group is not rejected here: like other invalid values it is a business outcome,
 * reported by the engine as {@code INVALID_SELF_TRADE_GROUP}.
 */
public record SelfTradeInstruction(long group, SelfTradeMode mode) {

    /** No prevention: the order may trade with anyone. */
    public static final SelfTradeInstruction NONE = new SelfTradeInstruction(0, SelfTradeMode.CANCEL_NEWEST);

    public SelfTradeInstruction {
        Objects.requireNonNull(mode, "mode");
    }

    /** Whether a resting order with this instruction is in the same group as an order with {@code other}. */
    boolean conflictsWith(SelfTradeInstruction other) {
        return group > 0 && group == other.group;
    }
}
