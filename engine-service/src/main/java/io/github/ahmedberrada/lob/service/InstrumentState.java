package io.github.ahmedberrada.lob.service;

import io.github.ahmedberrada.lob.core.EngineSnapshot;
import java.util.Objects;

/**
 * The complete state of an instrument's engine, copied on its writer thread, and the last command it
 * reflects. Consumers of the event stream start from it and skip the events it already includes.
 */
public record InstrumentState(long lastCommandSequence, EngineSnapshot engine) {

    public InstrumentState {
        Objects.requireNonNull(engine, "engine");
    }
}
