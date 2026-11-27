# Rulebook: tick-size regime

- **Status:** Draft

A venue lets prices move in steps, the **tick size**, and a regime may make the step coarser as the
price rises (MiFID II RTS 11: tick sizes by price band). This chapter adds the regime to
[continuous trading](continuous-trading.md). Every rule ID is cited by tests through
`@Rulebook("TK-xxx")`. Design: [ADR-0014](../adr/0014-venue-controls.md).

Terms used below:
- **Band**: a lowest price and a step. A price belongs to the band with the highest lowest price that
  does not exceed it.
- **Step**: the number of core ticks a price in the band must be a multiple of.

## Rules

**TK-001: The table.** An instrument has a table of bands, with strictly increasing lowest prices; the
first one starts at price 1, and every step is at least 1. The default table is one band of step 1: any
whole number of ticks is allowed, as before this chapter. The table is part of the instrument and does
not change while the venue runs.

**TK-002: Checked prices.** The price of a LIMIT order, the stop price and the limit price of a stop
order, and the new price of a replace must be a multiple of the step of their band. Otherwise a new
order is rejected, and a replace refused, with `INVALID_TICK_SIZE`. The check comes after those of CT-009
(positive, within the maximum) and before every other check of the order.

**TK-003: Matching is unaffected.** Trades happen at resting orders' prices, which already follow the
table (TK-002), and the price of an auction (AU-001) is a limit price of an order: it follows it too.
