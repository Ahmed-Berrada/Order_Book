# ADR-0010: Iceberg orders

- **Status:** Accepted
- **Date:** 2026-11-15

## Context

Large orders reveal intentions: a member who shows 50,000 lots moves the market against itself.
Every major venue offers iceberg (reserve) orders, which show a small peak and refill it from a hidden
reserve. Rules: [`iceberg-orders.md`](../rulebook/iceberg-orders.md) (IC-001 to IC-006).

## Decisions

### 1. Hidden quantity trades only through the peak

Venues differ on how an aggressor larger than the visible book meets hidden quantity. Some (Xetra)
let it trade against hidden reserves at a price level once the visible quantity there is exhausted,
in the order of the icebergs' time priority; others only ever trade the peak. This venue takes the
simpler rule: an aggressor fills at most the current peak, the iceberg then refills and goes to the
back of the queue, and the aggressor keeps matching. An aggressor that sweeps a level therefore still
reaches the hidden quantity, after the orders that were queued behind the iceberg, which is what
the peak's lost priority means. The outcome is the same in a quiet book and differs only in who
trades first, and the rule needs no second pass over the level.

### 2. The engine knows two quantities; the feed sees one

`Order` gains a display size and a current peak. The price level's total includes hidden quantity,
because that is the executable liquidity a FOK check needs (OT-003). `OrderBook.depth` keeps
returning the full quantity for risk and tests; the new `OrderBook.displayedDepth` returns what the
market may see, and is what market data must match.

`OrderRested` carries the peak next to the remaining quantity, and the new `OrderReplenished` event
reports each refill. `market-data`'s `BookImage` follows peaks from these events alone, so it never
learns a hidden quantity: a feed that cannot know a number cannot leak it.

### 3. Peaks shrink from the hidden side first

A replace that keeps priority (OT-006) takes the reduction from the hidden reserve, so the peak only
shrinks when the new quantity is below it. The member keeps the visible size it chose, and the event
stream lets a book builder compute the new peak as `min(peak, new quantity)` without knowing the
display size.

### 4. Journal format version 3

`LimitOrder`, `OrderRested`, the snapshot's resting orders and the new event change the layouts, so
the format version becomes 3. As with version 2 (ADR-0009 §7), older files are refused until the
first release.

## Consequences

- ➕ Members can work large orders without showing them.
- ➕ The public feed cannot disclose hidden quantity, by construction.
- ➖ Each refill costs a queue move (`ArrayDeque` remove from the head, add at the tail: O(1)).
- ➖ `OrderRested` and `TradeExecuted` no longer suffice to rebuild the visible book;
  consumers must also apply `OrderReplenished`.
