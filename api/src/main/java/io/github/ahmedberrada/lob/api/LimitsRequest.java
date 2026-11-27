package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.MemberLimits;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Body of {@code PUT /api/v1/members/{memberId}/limits}: every limit, {@code 0} meaning none (RK-001). */
public record LimitsRequest(
        @PositiveOrZero long maxOrderQuantity, @NotNull @PositiveOrZero BigDecimal maxOrderNotional,
        @PositiveOrZero long priceCollarBps, @PositiveOrZero long maxOpenOrders, @PositiveOrZero long maxNetPosition) {

    MemberLimits toLimits(InstrumentConfig instrument) {
        return new MemberLimits(maxOrderQuantity,
                maxOrderNotional.divide(instrument.tickSize(), RoundingMode.FLOOR).longValue(), priceCollarBps,
                maxOpenOrders, maxNetPosition);
    }
}
