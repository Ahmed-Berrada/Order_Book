# ADR-0002: Core engine model

- **Status:** Accepted
- **Date:** 2026-10-08
- **Supersedes:** the order ID generator in ADR-0001 §2

## Context

Phase 1 implements the matching engine specified in
[`docs/rulebook/continuous-trading.md`](../rulebook/continuous-trading.md). Reviewing ADR-0001 before
writing it showed one decision that conflicts with determinism, and several details the domain
model left open.

## Decisions

### 1. Order IDs are assigned by the engine, per instrument

ADR-0001 planned a shared `AtomicLong` in the API to number orders across all symbols. IDs would then
depend on how request threads interleave across symbols, so replaying one symbol's commands would not
reproduce its IDs unless they were journaled separately.

Instead, each `MatchingEngine` numbers its own orders from 1, inside its single writer thread. IDs are
deterministic and unique within an instrument. The global identity of an order is
`(symbol, orderId)`, which is what the API and, later, FIX (`OrderID`) will expose.

### 2. LIMIT and MARKET are separate command types

`LimitOrder(side, priceTicks, quantity)` and `MarketOrder(side, quantity)` are separate records. A
market order has no price field, so no sentinel value such as `0` can be misread as a price.

### 3. Market order behaviour

A market order matches until it is filled or the opposite side is empty. Its remainder is cancelled,
never rested (CT-006). If the opposite side is empty on arrival, it is rejected with `NO_LIQUIDITY`
(CT-007). There is no price protection yet; price collars arrive with pre-trade risk (phase 5).

### 4. Event model

| Event | Meaning |
|---|---|
| `OrderAccepted` | Order passed validation and received its ID |
| `OrderRejected` | Order failed validation; carries a `RejectReason` |
| `TradeExecuted` | One fill: taker and maker IDs, aggressor side, price, quantity, and the remaining quantity of both orders (FIX `LeavesQty`) |
| `OrderRested` | The remainder of a limit order now rests in the book |
| `OrderCancelled` | Quantity left the book or was never rested; carries a `CancelReason` |
| `CancelRejected` | A cancel targeted an order that is not resting |

Each event carries its own sequence number. Trades name the **maker and taker** rather than buyer and
seller, because that is what fee schedules, market data and surveillance need; the buyer and seller
follow from the aggressor side. `OrderRested` makes the event stream sufficient to rebuild level 2
market data without reading the book.

### 5. Deferred on purpose

| Topic | Deferred to | Why not now |
|---|---|---|
| Event timestamps | Journal (phase 2) | For replay, a timestamp must be taken at ingress and journaled with the command. A clock read inside the engine now would be replaced. |
| Client order ID and duplicate detection | FIX gateway (phase 3) | Uniqueness of `ClOrdID` is defined per member session, and the core has no notion of member yet. |
| Time in force (DAY, GTC) | Trading phases (phase 4) | Without an end-of-day event, DAY and GTC behave identically. |

### 6. Instrument limits prevent overflow

An `Instrument` declares a maximum order quantity and a maximum price. Its constructor checks with
`Math.multiplyExact` that their product fits in a `long`, so no notional value computed later can
overflow. Orders above either limit are rejected (CT-009).

### 7. Testing against a reference model

Besides scenario tests (JUnit) and invariant properties (jqwik), the engine is compared to a
deliberately naive reference implementation that lives in the test sources: a flat list of orders
scanned on every command. Random command streams must produce the **exact same events** in both. The
reference is too slow for production and too simple to share the engine's bugs.

Code quality gates on `engine-core`: JaCoCo line and branch coverage of at least 90 %, PIT mutation
score of at least 80 % (in a separate `mutation` profile and CI job, because it is slow), and
ArchUnit rules banning clocks, randomness, threads and framework code from the core.

## Consequences

- ➕ Replaying one instrument's commands reproduces its IDs and events exactly.
- ➕ Market orders cannot carry a meaningless price.
- ➖ Clients must name the instrument when cancelling, since order IDs are not globally unique.
- ➖ The event stream carries no timestamps until the journal step.
