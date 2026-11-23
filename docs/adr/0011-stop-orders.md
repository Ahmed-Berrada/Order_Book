# ADR-0011: Stop orders

- **Status:** Accepted
- **Date:** 2026-11-15

## Context

Stop orders let a member act when the market moves: a stop-loss sells once the price falls to a
level, a breakout buy enters once it rises. Rules: [`stop-orders.md`](../rulebook/stop-orders.md)
(ST-001 to ST-008).

## Decisions

### 1. A separate command, parked outside the book

`StopOrder(side, stopPrice, limitPrice, quantity)` is its own command; a limit price of zero means a
stop-market order. A parked stop is held in the engine next to the book, not in it: it has no queue
position and no visibility, so keeping it out of `OrderBook` keeps the book's invariants (CT-013,
IC-002) unchanged.

### 2. Trades trigger, and only trades

The trigger is the trade price, as on Eurex and the CME. A best-bid or best-ask trigger would let a
member trigger other members' stops by quoting, without trading. A command's trades all count, not
only its last one, so a sweep through a stop price triggers it even if the last fill is beyond it.

### 3. Release after the command, in order of order ID

Releasing a stop in the middle of an aggressor's matching would interleave two takers' fills. Stops
are therefore released once the triggering command has finished, one at a time, each fully processed
before the next. The order between stops triggered together is the order ID: deterministic, and the
same as arrival order. Venues differ here (some sort by stop price); arrival order is the simplest
rule that is fair to each member and easy to verify.

A cascade, where released stops trigger more stops, belongs to the command that started it: the
journal records one command, and replaying it reproduces the whole cascade (CT-015). A stop triggers
at most once, so a cascade ends.

### 4. Stops already reached are rejected

A buy stop at or below the last trade price would trigger at once, which is almost always a member's
mistake (a stop-loss on the wrong side). Rejecting it with `STOP_PRICE_ALREADY_REACHED` is safer than
executing it, and a member who wants to trade at once can send a market or limit order.

### 5. Journal format version 4

The command, two events, the last trade price and the parked stops in snapshots change the layouts,
so the format version becomes 4. Earlier versions are refused until the first release.

## Consequences

- ➕ Members can protect positions without watching the market.
- ➕ A cascade is one command in the journal and is reproduced exactly on replay.
- ➖ A command's response can carry other orders' events (released stops). Every consumer must
  attribute events by order ID, never by position in the batch.
- ➖ Triggered stops scan the parked list once per command with trades: O(parked stops). A sorted
  structure per side can replace it if phase 6 shows the need.
