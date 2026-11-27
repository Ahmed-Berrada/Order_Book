package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.MemberLimits;
import io.github.ahmedberrada.lob.core.MemberState;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import java.math.BigDecimal;
import java.util.List;

/** A member's limits, suspension and net position in every instrument (RK-001, RK-003, RK-004). */
public record MemberResponse(long memberId, List<InstrumentState> instruments) {

    /** One instrument's view of the member; limits in venue units, {@code 0} meaning none. */
    public record InstrumentState(
            String symbol, boolean suspended, long netPosition, long maxOrderQuantity, BigDecimal maxOrderNotional,
            long priceCollarBps, long maxOpenOrders, long maxNetPosition) {

        static InstrumentState of(InstrumentConfig instrument, MemberState state) {
            MemberLimits limits = state.limits();
            return new InstrumentState(instrument.symbol(), state.suspended(), state.netPosition(),
                    limits.maxOrderQuantity(), instrument.tickSize().multiply(BigDecimal.valueOf(limits.maxOrderNotional())),
                    limits.priceCollarBps(), limits.maxOpenOrders(), limits.maxNetPosition());
        }
    }
}
