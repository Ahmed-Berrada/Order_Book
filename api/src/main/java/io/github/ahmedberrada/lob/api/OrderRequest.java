package io.github.ahmedberrada.lob.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Body of {@code POST /api/v1/instruments/{symbol}/orders}. The quantity is not range-checked here:
 * a non-positive or oversized quantity is an engine rejection (CT-009), journaled with an order ID.
 *
 * @param price decimal price; required for LIMIT, absent for MARKET
 */
public record OrderRequest(@NotNull Side side, @NotNull OrderType type, BigDecimal price, @NotNull Long quantity) {

    @JsonIgnore
    @AssertTrue(message = "price is required for LIMIT orders and must be absent for MARKET orders")
    public boolean isPriceConsistentWithType() {
        return type == null || (type == OrderType.LIMIT) == (price != null);
    }

    /** The engine command, with the price converted to ticks (OE-002). */
    Command toCommand(InstrumentConfig instrument) {
        return switch (type) {
            case LIMIT -> new LimitOrder(side, instrument.toTicks(price), quantity);
            case MARKET -> new MarketOrder(side, quantity);
        };
    }
}
