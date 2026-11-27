package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.TradingPhase;
import io.github.ahmedberrada.lob.service.InstrumentConfig;

/** A listed instrument and whether it is halted (OE-006). Prices are decimal strings. */
public record InstrumentResponse(
        String symbol, String tickSize, long maxOrderQuantity, String maxPrice, boolean halted, TradingPhase phase) {

    static InstrumentResponse of(InstrumentConfig instrument, boolean halted, TradingPhase phase) {
        return new InstrumentResponse(instrument.symbol(), instrument.tickSize().toPlainString(),
                instrument.maxOrderQuantity(), instrument.maxPrice().toPlainString(), halted, phase);
    }
}
