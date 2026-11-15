package io.github.ahmedberrada.lob.marketdata.binary;

import io.github.ahmedberrada.lob.core.Side;

/** One message of the binary feed. Every message names its instrument by locate code. */
public sealed interface BinaryMessage {

    int locate();

    /** {@code R}: an instrument's symbol and tick size (mantissa × 10^exponent). */
    record Directory(int locate, String symbol, long tickMantissa, byte tickExponent) implements BinaryMessage {
    }

    /** {@code L}: a level's new state; quantity 0 removes it. */
    record Level(int locate, Side side, long priceTicks, long quantity, int orders) implements BinaryMessage {
    }

    /** {@code T}: an execution. */
    record Trade(int locate, Side aggressor, long priceTicks, long quantity) implements BinaryMessage {
    }

    /** {@code U}: closes an update; apply the instrument's preceding levels and trades now (BF-003). */
    record UpdateEnd(int locate, long feedSequence, long commandSequence, long timestampMicros)
            implements BinaryMessage {
    }

    /** {@code K}: closes an instrument's snapshot (TCP only). */
    record SnapshotEnd(int locate, long feedSequence, long commandSequence) implements BinaryMessage {
    }
}
