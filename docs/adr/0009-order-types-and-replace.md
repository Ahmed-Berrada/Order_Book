# ADR-0009: Time in force, post-only and cancel/replace

- **Status:** Accepted
- **Date:** 2026-11-15

## Context

Phase 4 turns the matching engine into a venue. Its first part adds what every member's order
management system expects: IOC and FOK orders, post-only orders for market makers, and amending a
resting order without cancelling it. Rules: [`order-types.md`](../rulebook/order-types.md)
(OT-001 to OT-009).

## Decisions

### 1. Attributes on the existing commands, with backward-compatible constructors

`LimitOrder` gains `timeInForce` and `postOnly`; `MarketOrder` gains `timeInForce`. Both keep their
previous constructors, which default to `GTC` (limit) and `IOC` (market). A new command type per
combination (`IocLimitOrder`, …) would multiply the cases of every `switch` for no gain: the matching
algorithm is the same, only the treatment of the remainder differs.

`DAY` is not offered yet. Without a trading day (phase 4, trading phases) it would behave exactly like
`GTC`, and accepting it would promise an expiry the venue does not implement.

### 2. FOK is checked before matching, not rolled back after

The engine adds up the opposite quantity within the limit before it matches anything, and only then
decides. Undoing trades would mean undoing sequence numbers and queue positions, which the event
stream has already committed to. The check walks price levels from the best one and stops as soon as
the quantity is covered, so it costs at most the levels that the order would have traded through.

An unfilled FOK is *cancelled* (`OrderAccepted` then `OrderCancelled(FILL_OR_KILL)`), not rejected: the
order was valid, the market just could not fill it. This is how FIX reports it (`ExecType=Canceled`),
and it keeps CT-014 trivially true.

### 3. Post-only orders are rejected, not repriced

When a post-only order would trade, venues either reject it or slide its price one tick away from the
opposite side. Repricing hides a change of price from the member and needs a tick table, which the
venue does not have yet (tick-size regime, later in phase 4). Rejection is explicit and is what
Eurex ("book or cancel") and most crypto venues do.

### 4. Replace keeps the order ID; quantity means remaining quantity

A replace changes the order in place and keeps its ID, so that market data, drop copy and the
member's own records follow one order through its life (FIX keeps `OrderID` and changes `ClOrdID`).
The engine works with *remaining* quantity. FIX expresses the new `OrderQty` as a total including
what has been filled; the gateway subtracts `CumQty` before sending the command, and refuses a total
that does not exceed it.

### 5. Priority: only an unchanged price with a smaller or equal quantity keeps its place

This is the rule of most venues: a larger quantity would let a member jump the queue with size it did
not commit when it joined. Every other replace is processed as *remove, then a new aggressive entry
with the same ID*. That reuses the matching code, so a replace that crosses trades exactly like a new
order would (OT-007).

The engine emits `OrderReplaced(priorityKept)` before any trade. A consumer that keeps a book from
events (`market-data`'s `BookImage`) can therefore update the level in place when priority is kept, and
remove the order and wait for the following `OrderRested` otherwise.

### 6. Resting orders remember post-only

A post-only order replaced to a crossing price must be refused (OT-005), so `Order` and the
`RestingOrder` of a snapshot carry the attribute. Time in force does not need to be stored: only GTC
orders rest.

### 7. Journal format version 2

The new attributes, command and events change the journal layouts, so the format version becomes 2
(see [`journal-format.md`](../interfaces/journal-format.md)). The project has no production journal
yet, so version 2 readers refuse version 1 files instead of carrying a migration path; from the first
release on, every version change will ship with a reader for the previous one.

## Consequences

- ➕ Members can send the order types their systems already use, over REST and FIX.
- ➕ An amendment cannot be used to gain priority, and cannot make a post-only order take liquidity.
- ➖ A FOK order scans price levels twice (check, then match). Phase 6 will measure whether it matters.
- ➖ Version 1 journals can no longer be read by the current build.
