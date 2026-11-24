# ADR-0012: Self-trade prevention

- **Status:** Accepted
- **Date:** 2026-11-24

## Context

A member trading with itself creates volume without risk. Market abuse regulation treats that as a
possible sign of wash trading, and RTS 6 expects members to control it. Venues therefore offer
self-match prevention. Rules: [`self-trade-prevention.md`](../rulebook/self-trade-prevention.md)
(SP-001 to SP-009).

## Decisions

### 1. A group on the order, not a member in the engine

The core has no notion of member yet (ADR-0002 §5), and phase 5 will add a full hierarchy of members,
traders and sessions. Prevention only needs one fact: *do these two orders belong together?* A numeric
group answers that without committing to the phase 5 model: when members arrive, the group is simply
the member ID. Group `0` means "no prevention", which is what every order had until now.

The group is set by the gateway from the authenticated session, never by the member. A member that
could pick its own group could dodge prevention by picking another one. The FIX gateway uses the
member's position in the configured list; the REST API has no identity until phases 5 and 8, so its
orders have group `0`.

### 2. The instruction is on the incoming order

The mode applies to the order that is *arriving*, as on Eurex and most venues, and a resting order
remembers the mode it came with, for the case where a replace or a released stop turns it into an
incoming order. A member's two orders may therefore have different modes, and the mode of the order
that arrives decides.

### 3. Three modes; decrement-and-cancel is left out

`CANCEL_NEWEST`, `CANCEL_OLDEST` and `CANCEL_BOTH` each remove whole orders, so every existing consumer
(market data, FIX reports, the REST responses) already handles the events: they are `OrderCancelled`
with a new reason. Decrement-and-cancel reduces both orders by the quantity that would have traded.
That needs a partial cancel of a resting order, which the book image of market data cannot follow from
events today, and it would be a second way of changing a resting order's quantity beside fills and
replaces. It can be added when a member needs it.

### 4. A fill or kill order never triggers prevention

A FOK order is all or nothing and must leave the book untouched when it fails (OT-003). Cancelling a
member's resting order for an order that then dies would violate that. The check therefore counts only
the price levels strictly better than the first one that holds an own order, and kills the order if they
cannot fill it. This is conservative at the level of a price (an own order behind other orders at the
same price also stops the count), which keeps the check exact for icebergs, whose refills move within
their level but never out of it.

### 5. Journal format version 5

Commands carry group and mode, and snapshots carry them for resting orders and parked stops. Two codes
are added (a cancel reason and a reject reason). Earlier versions are refused, as in ADR-0009 §7.

## Consequences

- ➕ A member's own orders cannot trade with each other over FIX, and the venue records the cancel.
- ➕ Phase 5 can map the member to the group without changing the engine.
- ➖ Until phase 5, groups exist only for FIX sessions. REST orders are not covered.
- ➖ A FOK order is killed in some cases where an exact simulation would fill it (an own order in the
  same level as the last liquidity it needs).
