package io.github.ahmedberrada.lob.core;

/**
 * One fill between an incoming order (taker) and a resting order (maker). The price is always the
 * maker's (CT-003). The remaining quantities correspond to FIX {@code LeavesQty}.
 *
 * @param aggressorSide         side of the taker; the buyer and seller follow from it
 * @param takerRemainingQuantity quantity of the taker still unfilled after this trade
 * @param makerRemainingQuantity quantity of the maker still resting after this trade
 */
public record TradeExecuted(
        long sequence,
        long takerOrderId,
        long makerOrderId,
        Side aggressorSide,
        long priceTicks,
        long quantity,
        long takerRemainingQuantity,
        long makerRemainingQuantity) implements Event {
}
