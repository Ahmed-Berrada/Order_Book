# Rulebook: stop orders

A stop order waits outside the book until the market trades at or through its stop price, then enters
the book as a market order (stop) or a limit order (stop-limit). This chapter extends
[continuous trading](continuous-trading.md); every rule ID is cited by tests through
`@Rulebook("ST-xxx")`. Design: [ADR-0011](../adr/0011-stop-orders.md).

Terms used below:
- **Parked**: a stop order that has been accepted and not yet triggered.
- **Last trade price**: the price of the instrument's most recent trade; there is none before the
  first trade.

## Entry

**ST-001: Stop orders.** A stop order has a side, a quantity, a stop price and, for a stop-limit
order, a limit price. When triggered it becomes a MARKET IOC order (stop) or a LIMIT GTC order
(stop-limit) with the same order ID.

**ST-002: Validation.** A stop order is rejected, with a reason, when its quantity fails CT-009, its
stop price or limit price is not positive (`INVALID_PRICE`) or above the instrument maximum
(`PRICE_ABOVE_MAXIMUM`), or its stop price has already been reached: a buy stop at or below the last
trade price, or a sell stop at or above it (`STOP_PRICE_ALREADY_REACHED`). Before the first trade,
any valid stop price is accepted.

**ST-003: Parking.** An accepted stop order emits `OrderAccepted` then `OrderParked`. A parked order
is not in the book: it is invisible to market data, cannot trade, and takes no queue position.

## Triggering

**ST-004: Trigger condition.** A buy stop is triggered by a trade at a price at or above its stop
price; a sell stop by a trade at or below it. Only trades count: quotes, cancels and replaces never
trigger a stop.

**ST-005: Release.** Once a command's own matching is complete, every stop its trades triggered is
released, one at a time, in order of order ID. A released order emits `StopTriggered` and is then
processed exactly as a new incoming order of its type would be, from its first trade to its rest or
cancel (CT-012), except that it is never rejected: a released stop with an empty opposite side has
its whole quantity cancelled with `UNFILLED_MARKET_REMAINDER`.

**ST-006: Cascades.** Trades of a released stop can trigger further stops, which are released after
it under the same rule, until no triggered stop is left. All of them belong to the command that
started the cascade and share its timestamp (RS-006).

## Management

**ST-007: Cancel.** A parked stop can be cancelled (CT-008); its whole quantity is cancelled with
`CLIENT_REQUEST`. A parked stop cannot be replaced: a replace is refused with `UNKNOWN_ORDER`, as
replace applies to orders resting in the book (OT-005).

**ST-008: State.** The parked stops and the last trade price are part of the engine's state: a
snapshot restores them (RS-010), and CT-014 holds with parked quantity counted as resting.
