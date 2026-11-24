# Rulebook: self-trade prevention

A member must not trade with itself: such a trade moves no risk, still counts as volume, and is the
mark of wash trading (MAR art. 12; MiFID II RTS 6 art. 15 requires members to have controls against it).
The venue gives members the control: orders carry a **group**, and orders of one group never trade
with each other. This chapter extends [continuous trading](continuous-trading.md),
[order types](order-types.md) and [stop orders](stop-orders.md); every rule ID is cited by tests through
`@Rulebook("SP-xxx")`. Design: [ADR-0012](../adr/0012-self-trade-prevention.md).

Terms used below:
- **Group**: a positive number naming who must not trade with whom, normally a member. Group `0` means
  no prevention. The gateway sets it from the authenticated session: a member cannot choose it.
- **Mode**: what the venue does when an incoming order meets a resting order of its own group.

## Instruction

**SP-001: Group and mode.** LIMIT, MARKET and STOP orders carry a group and a mode (`CANCEL_NEWEST`,
`CANCEL_OLDEST` or `CANCEL_BOTH`). Two orders of the same non-zero group never trade with each other.
A resting order keeps its group and mode for as long as it rests, and a stop order until it is released
(ST-005). A negative group is rejected with `INVALID_SELF_TRADE_GROUP`, after the checks of CT-009,
OT-004 and IC-001 and before those of ST-002.

## Modes

**SP-002: Cancel newest.** When the best resting order the incoming order could trade with belongs to
its own group, there is no trade, and the incoming order's remaining quantity is cancelled with
`OrderCancelled(SELF_TRADE_PREVENTION)`. This holds for every incoming order: a limit order's remainder
does not rest, and a market order's remainder is cancelled with this reason instead of
`UNFILLED_MARKET_REMAINDER`. Fills made before the own order was reached stand.

**SP-003: Cancel oldest.** The resting order of the incoming order's own group is cancelled, with its
whole remaining quantity (hidden quantity included, IC-002) and `OrderCancelled(SELF_TRADE_PREVENTION)`.
Matching continues with the next resting order, which may belong to the same group and be cancelled
too, until the incoming order is filled, meets no order it could trade with, or reaches its limit.

**SP-004: Cancel both.** The resting order is cancelled as in SP-003, and the incoming order's
remaining quantity as in SP-002.

**SP-005: Event order.** A cancelled resting order's event comes at the point where the incoming order
reached it: after the trades made before and before the trades made after it. The incoming order's own
cancel comes last, in the place of its rest or cancel (CT-012).

## Interaction with other rules

**SP-006: Fill or kill.** A FOK order never causes self-trade prevention. Only the price levels that are
strictly better than the best level holding an order of the FOK order's own group count towards filling
it (all levels within its limit if there is none). If they cannot fill it in full, it is cancelled with
`FILL_OR_KILL` and the book is unchanged (OT-003).

**SP-007: Post-only.** Post-only orders are unchanged: one that reaches the best opposite price is
rejected with `POST_ONLY_WOULD_TRADE` (OT-004), whoever owns that order.

**SP-008: Replace.** A replace keeps the order's group and mode (OT-005). A replace that loses priority
re-enters the order as an incoming order (OT-007), so it applies its mode if it meets an own order.
A parked stop order releases with the group and mode it was entered with.

**SP-009: State.** The group and mode of resting orders and parked stops are part of the engine's state:
a snapshot restores them (RS-010).
