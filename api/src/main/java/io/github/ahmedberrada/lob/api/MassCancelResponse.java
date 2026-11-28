package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.Side;
import java.time.Instant;
import java.util.List;

/**
 * Outcome of a mass cancel (RK-006).
 *
 * @param side             the side cancelled, or {@code null} for both
 * @param cancelledOrderIds the orders cancelled, in order of order ID
 */
public record MassCancelResponse(String symbol, Side side, long cancelledOrders, List<Long> cancelledOrderIds,
        long commandSequence, Instant timestamp) {
}
