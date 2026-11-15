package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.ReplaceOrder;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Body of {@code PUT /api/v1/instruments/{symbol}/orders/{orderId}}: the new price and the new
 * <em>remaining</em> quantity of a resting order (OT-005).
 */
public record ReplaceRequest(@NotNull BigDecimal price, @NotNull Long quantity) {

    /** The engine command, with the price converted to ticks (OE-002). */
    ReplaceOrder toCommand(long orderId, InstrumentConfig instrument) {
        return new ReplaceOrder(orderId, instrument.toTicks(price), quantity);
    }
}
