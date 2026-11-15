# Rulebook: iceberg orders

- **Status:** Draft

An iceberg order shows only part of its quantity, the **peak**, in the book, and refills it from a
hidden reserve as it trades. This chapter extends [continuous trading](continuous-trading.md) and
[order types](order-types.md); every rule ID is cited by tests through `@Rulebook("IC-xxx")`. Design:
[ADR-0010](../adr/0010-iceberg-orders.md).

**IC-001: Display quantity.** A LIMIT GTC order may carry a display quantity: the size of its peak.
A display quantity of zero, or one equal to the order's quantity, means the whole order is visible,
and the order is not an iceberg. A display quantity that is negative or larger than the order's
quantity, or a non-zero display quantity on an IOC or FOK order, is rejected with
`INVALID_DISPLAY_QUANTITY`; quantity and price are checked first. MARKET orders have no display
quantity.

**IC-002: Peak.** An iceberg rests with a peak of `min(display quantity, remaining quantity)`. Only the
peak is published in market data; the hidden rest is never disclosed (MD-001). `OrderRested` carries
both the remaining quantity and the peak.

**IC-003: Matching a peak.** An aggressor trades with an iceberg's peak at the iceberg's place in the
queue, like any other order (CT-002). Hidden quantity is not available at that place: a fill is at
most the current peak.

**IC-004: Replenishment.** When a peak is filled completely and hidden quantity remains, a new peak of
`min(display quantity, remaining quantity)` is shown and the order moves to the back of the queue at
its price, with `OrderReplenished`. The same aggressor may keep matching: it reaches the new peak
after the orders that were behind the iceberg. FOK checks (OT-003) count hidden quantity, which is
executable.

**IC-005: Replacing an iceberg.** A replace keeps the display quantity. The priority rule (OT-006) is
unchanged. When priority is kept, the reduction is taken from the hidden quantity first: the peak
becomes `min(peak, new quantity)`. When priority is lost, the order re-enters with a full peak.

**IC-006: Iceberg events.** After a trade that empties a peak with hidden quantity left, the engine
emits `OrderReplenished` with the order ID, side, price and new peak, immediately after that
`TradeExecuted`.
