package io.github.ahmedberrada.lob.core;

/**
 * The uncross found its price: {@code volume} trades at {@code priceTicks}, in the trades that follow
 * (AU-001, AU-002).
 */
public record AuctionPriceDetermined(long sequence, long priceTicks, long volume) implements Event {
}
