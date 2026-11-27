# ADR-0014: Tick-size regime, day orders and volatility interruptions

- **Status:** Accepted
- **Date:** 2026-11-27

## Context

The venue now has phases and auctions (ADR-0013). Three controls that regulators and members expect
build on them: a tick-size regime (RTS 11), orders that expire at the end of the day, and a pause when
the price moves too far (RTS 7). Rules: [`tick-size-regime.md`](../rulebook/tick-size-regime.md),
[`day-orders.md`](../rulebook/day-orders.md),
[`volatility-interruptions.md`](../rulebook/volatility-interruptions.md).

## Decisions

### 1. Everything stays a function of commands and prices

The engine never reads a clock. A "day" therefore ends when the instrument closes (`SetPhase(CLOSED)`),
and a volatility interruption ends when someone sends the next `SetPhase`. The engine decides *that*
trading must pause (from prices, deterministically); the venue decides *for how long*. Replaying the
journal reproduces every expiry and every interruption.

### 2. The tick table lives in the instrument

The core works in integer ticks of the finest step. A regime is a table of bands (lowest price, step in
ticks), part of `Instrument`, so it is in every journal header and cannot change under a running
journal. A band's step is a multiple of the base tick, so the check is one remainder. The REST layer's
`OFF_TICK_PRICE` check (a decimal that is not a multiple of the base tick) stays as it is.

### 3. DAY is a flag, not a phase of life

A resting `DAY` order is a `GTC` order with one extra bit, kept in the order and its snapshot form.
Expiry is a loop at the moment the instrument closes. Stop orders are not expired: a stop is an
instruction, not liquidity, and its lifetime is a matter for the member's risk controls.

### 4. A static reference price, set by auctions

RTS 7 compares with a reference that does not move with each trade; otherwise a series of small steps
walks the price away. The reference is the last auction price, or the first trade of an instrument that
has never had an auction. Both are facts in the event stream, so the reference is part of the snapshot
and nothing else is needed to recompute it.

### 5. The breaching fill does not happen

Executing a trade and then pausing would publish the very price the control exists to prevent. The
engine stops *before* the fill and moves to `CALL`; the order's remainder rests in the call book, where
the auction can price it. A FOK order checks only liquidity inside the band, so it can never leave a
partial fill behind an interruption.

### 6. Order-to-trade ratio, throttles and the kill switch move to phase 5

They are per-member controls, and the engine does not know members until phase 5 records them with
every command. Building them on a group number now would be a placeholder to remove.

### 7. Journal format version 7

The instrument header, resting orders, the snapshot and a few codes change. Earlier versions are refused
(ADR-0009 §7).

## Consequences

- ➕ A regime, expiry and a circuit breaker, all replayed exactly.
- ➕ The auction machinery of ADR-0013 is reused for the interruption.
- ➖ The reference price of an instrument without auctions is its first trade of the engine's life.
- ➖ No per-member limits yet (phase 5).
