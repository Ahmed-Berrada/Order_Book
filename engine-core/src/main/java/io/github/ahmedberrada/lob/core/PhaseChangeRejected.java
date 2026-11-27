package io.github.ahmedberrada.lob.core;

/** A phase change was refused; the phase is unchanged (TP-002). */
public record PhaseChangeRejected(long sequence, RejectReason reason) implements Event {
}
