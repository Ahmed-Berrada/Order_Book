# Rulebook: day orders

- **Status:** Draft

An order that is good for the trading day only expires when the day ends. The venue has no clock
(CT-015); the day ends when the instrument is **closed** (TP-004), so a `SetPhase(CLOSED)` command is the
end of the day. This chapter adds the `DAY` time in force to [order types](order-types.md) and
[trading phases](trading-phases.md). Every rule ID is cited by tests through `@Rulebook("DY-xxx")`.
Design: [ADR-0014](../adr/0014-venue-controls.md).

## Rules

**DY-001: DAY orders.** A LIMIT order may have time in force `DAY`. It rests like a `GTC` order: it
matches on arrival, its remainder rests (CT-005), and it can be post-only (OT-004), an iceberg (IC-001),
replaced and cancelled. Wherever a rule says an order needs `GTC` to rest (OT-004, IC-001, TP-005), `DAY`
does too. A MARKET order with `DAY` is rejected with `INVALID_TIME_IN_FORCE`. A replace keeps the
order's `DAY` attribute.

**DY-002: Expiry.** When the instrument moves to `CLOSED`, every resting `DAY` order is cancelled with
`OrderCancelled(DAY_EXPIRED)` and its whole remaining quantity, hidden quantity included, in order of
order ID. The events come after `PhaseChanged` (TP-003) and after the uncross when leaving `CALL`. `GTC`
orders and stop orders stay.

**DY-003: State.** The attribute is part of a resting order in a snapshot, and a restored engine expires
the same orders (RS-010).
