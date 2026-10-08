package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.service.InstrumentConfig;

/** Decimal-string prices for responses: exact, never binary floating point (ADR-0005 §3). */
final class Prices {

    private Prices() {
    }

    static String format(InstrumentConfig instrument, long ticks) {
        return instrument.toPrice(ticks).toPlainString();
    }
}
