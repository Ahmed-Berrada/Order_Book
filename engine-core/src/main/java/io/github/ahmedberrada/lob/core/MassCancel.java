package io.github.ahmedberrada.lob.core;

/**
 * Cancels all the open orders of the member the command is processed for, on one side or both (RK-006). From
 * member {@code 0}, the operator, every member's open orders.
 *
 * @param side the side to cancel, or {@code null} for both
 */
public record MassCancel(Side side) implements Command {

    /** Both sides. */
    public static MassCancel all() {
        return new MassCancel(null);
    }
}
