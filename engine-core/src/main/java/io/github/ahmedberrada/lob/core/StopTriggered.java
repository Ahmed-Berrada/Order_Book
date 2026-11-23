package io.github.ahmedberrada.lob.core;

/**
 * A parked stop was triggered and is released as an incoming order: its trades, and its rest or
 * cancel, follow (ST-005).
 */
public record StopTriggered(long sequence, long orderId) implements Event {
}
