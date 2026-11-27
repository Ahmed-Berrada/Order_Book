package io.github.ahmedberrada.lob.api;

/** The instrument is already in the requested phase (TP-002). */
final class PhaseChangeRejectedException extends RuntimeException {

    PhaseChangeRejectedException(String symbol, String phase) {
        super(symbol + " is already in phase " + phase);
    }
}
