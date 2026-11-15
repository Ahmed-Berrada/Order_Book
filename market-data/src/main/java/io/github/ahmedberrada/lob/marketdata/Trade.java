package io.github.ahmedberrada.lob.marketdata;

import io.github.ahmedberrada.lob.core.Side;

/** A published execution (MD-002): no order IDs, only what the market may see. */
public record Trade(long priceTicks, long quantity, Side aggressor) {
}
