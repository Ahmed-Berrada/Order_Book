package io.github.ahmedberrada.lob.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.core.StopOrder;
import io.github.ahmedberrada.lob.core.TimeInForce;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Body of {@code POST /api/v1/instruments/{symbol}/orders}. The quantity is not range-checked here:
 * a non-positive or oversized quantity is an engine rejection (CT-009), journaled with an order ID.
 * Likewise a time in force the order type does not allow is an engine rejection (OT-001, OT-004).
 *
 * @param price       decimal limit price; required for LIMIT and STOP_LIMIT, absent otherwise
 * @param timeInForce optional; GTC for a LIMIT order and IOC for a MARKET order by default
 * @param postOnly    optional, LIMIT orders only; false by default
 * @param displayQuantity optional, LIMIT orders only: the peak of an iceberg order (IC-001)
 * @param stopPrice   required for STOP and STOP_LIMIT orders, absent otherwise (ST-001)
 */
public record OrderRequest(
        @NotNull Side side,
        @NotNull OrderType type,
        BigDecimal price,
        @NotNull Long quantity,
        TimeInForce timeInForce,
        Boolean postOnly,
        Long displayQuantity,
        BigDecimal stopPrice) {

    @JsonIgnore
    @AssertTrue(message = "price is required for LIMIT and STOP_LIMIT orders and must be absent otherwise")
    public boolean isPriceConsistentWithType() {
        return type == null || type.hasLimitPrice() == (price != null);
    }

    @JsonIgnore
    @AssertTrue(message = "stopPrice is required for STOP and STOP_LIMIT orders and must be absent otherwise")
    public boolean isStopPriceConsistentWithType() {
        return type == null || type.isStop() == (stopPrice != null);
    }

    @JsonIgnore
    @AssertTrue(message = "postOnly and displayQuantity apply to LIMIT orders only; timeInForce not to stop orders")
    public boolean isLimitOnlyFieldsConsistentWithType() {
        return type == null || type == OrderType.LIMIT
                || (postOnly == null && displayQuantity == null && (type == OrderType.MARKET || timeInForce == null));
    }

    /** The engine command, with the price converted to ticks (OE-002). */
    Command toCommand(InstrumentConfig instrument) {
        return switch (type) {
            case LIMIT -> new LimitOrder(side, instrument.toTicks(price), quantity,
                    timeInForce == null ? TimeInForce.GTC : timeInForce, Boolean.TRUE.equals(postOnly),
                    displayQuantity == null ? 0 : displayQuantity);
            case MARKET -> new MarketOrder(side, quantity, timeInForce == null ? TimeInForce.IOC : timeInForce);
            case STOP -> new StopOrder(side, instrument.toTicks(stopPrice), 0, quantity);
            case STOP_LIMIT -> new StopOrder(side, instrument.toTicks(stopPrice), instrument.toTicks(price), quantity);
        };
    }
}
