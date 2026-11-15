package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.service.InstrumentConfig;

/** A listed instrument and whether it is halted (OE-006). Prices are decimal strings. */
public record InstrumentResponse(
        String symbol, String tickSize, long maxOrderQuantity, String maxPrice, boolean halted) {

    static InstrumentResponse of(InstrumentConfig instrument, boolean halted) {
        return new InstrumentResponse(instrument.symbol(), instrument.tickSize().toPlainString(),
                instrument.maxOrderQuantity(), instrument.maxPrice().toPlainString(), halted);
    }
}
