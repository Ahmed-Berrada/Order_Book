# ADR-0013: Trading phases and call auctions

- **Status:** Proposed
- **Date:** 2026-11-24

## Context

Continuous matching alone is not a market. Venues open and close with an auction, because a single price
formed from all orders is fairer and more robust than the first trade of the day, and they need to
suspend trading. Volatility interruptions (RTS 7) will reuse the call auction. Rules:
[`trading-phases.md`](../rulebook/trading-phases.md) (TP-001 to TP-008, AU-001 to AU-006).

## Decisions

### 1. Phases change by command, never by clock

The engine's determinism (CT-015) rests on never reading time. A schedule ("open at 09:00") is therefore
outside it: something sends `SetPhase` at 09:00, and the journal records that it did. Replaying the
journal reproduces every auction exactly, whatever the clock said. The scheduler itself is an operations
concern (phase 9); for now an operator or a test sends the commands.

### 2. Three phases

`CONTINUOUS`, `CALL` and `CLOSED` cover opening, closing and intraday auctions, and a halt. Which of
them an auction is (opening, closing, volatility) is a matter of when it is called, not of the engine.
A separate `HALTED` phase would behave exactly like `CLOSED`.

### 3. A call phase refuses orders that cannot wait

MARKET, IOC, FOK and post-only orders are rejected in a call phase. Each of them asks about *now*, and
in a call phase there is no matching to answer it. Accepting a market order would also need a place for
it in a book keyed by price, and a rule for where it sits among limit orders at the uncross; venues that
accept them define one, and this venue does not need it yet.

### 4. Icebergs are shown in full during a call phase

Hidden quantity protects an order's size while it waits for trades; in a call phase nothing trades until
the uncross, which matches whole orders. Showing them in full also keeps market data simple: a trade
reduces a visible quantity by its size, as always. The cost is two `OrderReplenished` events per
iceberg per auction, which `BookImage` already understands.

### 5. Self-trade prevention at entry, not at the uncross

Preventing self-trades while pairing orders at the uncross would change the executed volume after the
price was chosen, and could leave the book crossed. Instead, the engine applies SP-001 when an order
enters a call phase, against the own orders it crosses (AU-004). Two orders of one group that cross never
coexist, and, since two orders can only trade at one price if they cross, the uncross never pairs them. The
price and the volume are then exactly those of AU-001.

### 6. One price, a fixed tie-break, and a taker by arrival

The price rules are the ones used by Xetra and Euronext (maximum volume, minimum surplus, market
pressure, reference price), reduced to a total order so that the result is unique, hence deterministic.
Candidates are limit prices only: with market orders refused, the volume only changes at an order's price.

Existing consumers expect every trade to have a taker and a maker. An auction has no aggressor, so the
later-arriving order of a pair is reported as the taker. This keeps fees, drop copy and the market data
trade side meaningful without a new trade shape; a feed that wants to mark auction trades can use the
`AuctionPriceDetermined` event that precedes them.

### 7. Journal format version 6

`SetPhase`, three events, the phase in snapshots and two codes change the layouts. Earlier versions are
refused, as in ADR-0009 §7.

## Consequences

- ➕ Opening and closing auctions, and the machinery for volatility interruptions.
- ➕ Auctions replay exactly, like everything else.
- ➖ Market orders cannot take part in an auction.
- ➖ The book is crossed during a call phase, so anything that assumed CT-013 (a best bid below the best
  ask) must check the phase first. Market data publishes the crossed book as it is; an indicative price
  feed comes with the next step.
- ➖ The DAY time in force, which needs a close to expire at, is still not offered.
