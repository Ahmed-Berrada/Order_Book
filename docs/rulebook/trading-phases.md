# Rulebook: trading phases and call auctions

- **Status:** Draft

A venue does not trade continuously all day: it opens and closes with an **auction**, in which orders are
collected and then matched at one price, and it can pause trading. This chapter adds trading phases and
the call auction to [continuous trading](continuous-trading.md). Every rule ID is cited by tests through
`@Rulebook("TP-xxx")` or `@Rulebook("AU-xxx")`. Design: [ADR-0013](../adr/0013-trading-phases-and-auctions.md).

Terms used below:
- **Call phase**: orders are accepted and queue up, but nothing trades. It ends with an **uncross**.
- **Uncross**: the single event at the end of a call phase in which the auction price is determined and
  every order that can trade at that price does.
- **Reference price**: the price of the instrument's last trade (ST-002), or none before the first trade.

## Phases

**TP-001: Phases.** An instrument is in one of three phases: `CONTINUOUS` (CT-001 to CT-015), `CALL`, or
`CLOSED`. A new engine starts in `CONTINUOUS`.

**TP-002: Phase changes.** A `SetPhase` command moves the instrument to another phase. It is a command
like any other: journaled, stamped, and replayed. The engine never reads a clock, so *when* to change
phase is decided outside it (the venue's schedule, an operator); only the change itself is recorded. Any
phase can follow any other. A request for the current phase is refused with
`PhaseChangeRejected(INVALID_PHASE_TRANSITION)` and changes nothing.

**TP-003: Events of a phase change.** In this order:
1. when leaving `CALL`: the uncross, with its events (AU-001 to AU-004);
2. `PhaseChanged(from, to)`;
3. when entering `CALL`: an `OrderReplenished` for each iceberg order, which is shown in full for the
   whole call phase (TP-008); when leaving it: one for each iceberg order, with its new peak, in order
   of order ID;
4. when entering `CONTINUOUS`: the stop orders triggered by the uncross, if any (TP-007).

**TP-004: Closed.** In `CLOSED`, a new LIMIT, MARKET or STOP order is rejected with
`NOT_ALLOWED_IN_PHASE`, and a replace is refused with the same reason. Cancels are accepted. Orders that
rest, and parked stops, stay where they are.

**TP-005: Call phase entry.** In `CALL`, a LIMIT order with time in force GTC and without the post-only
attribute is accepted and rests **without matching**, even if its price crosses the book. A MARKET order,
an IOC or FOK order and a post-only order are rejected with `NOT_ALLOWED_IN_PHASE`: each of them asks
to trade or not to trade *now*, which a call phase cannot answer. A STOP order is accepted and parked as
usual. Cancels are accepted. The book can be crossed during `CALL`: CT-013 holds in `CONTINUOUS` and
`CLOSED` only.

**TP-006: Replace in a call phase.** A replace follows OT-005 and OT-006, but an order that loses
priority is re-entered like any order of a call phase: it rests without matching (OT-007 does not apply).

**TP-007: Stop orders.** A parked stop is released only while the instrument is `CONTINUOUS`. The
trades of an uncross count as trades for ST-004 (they set the last trade price, AU-005); a stop they
trigger is released when the uncross ends in `CONTINUOUS`. If it ends in `CLOSED`, the stop stays parked
and is released by the first later trade that reaches it.

**TP-008: Icebergs.** During a call phase an iceberg order is shown in full: hidden quantity would have
no priority to protect, since nothing trades until the uncross, and an uncross trades whole orders.
Peaks are restored when the call phase ends (TP-003).

## Auction

**AU-001: Auction price.** The candidate prices are the limit prices of the resting orders. For a
candidate *p*, the executable volume is the smaller of the total remaining quantity of buy orders priced
at or above *p* and of sell orders priced at or below *p*, hidden quantity included. The auction price is
chosen by these rules, each applied to the candidates left by the previous one:
1. the highest executable volume;
2. the lowest surplus (the difference between the two totals);
3. if every remaining candidate has a surplus on the same side, the highest price when buy orders are
   in surplus and the lowest when sell orders are; otherwise the price closest to the reference price
   (the highest of two equally close ones; the highest of all if there is no reference price);
4. the highest price.

If the highest executable volume is zero there is no auction price: nothing trades.

**AU-002: Execution.** When there is an auction price *p*, the engine emits
`AuctionPriceDetermined(p, volume)`, then matches the executable buy orders against the executable sell
orders at *p*, each side by price priority (best price first) and then by arrival, until one side is
exhausted. The matched quantity equals the volume of AU-001. Each pair produces a `TradeExecuted` at *p*.
There is no aggressor in an auction: in every pair, the order that arrived **later** is reported as the
taker and the other as the maker, so that the fields keep their meaning for fees and reports.

**AU-003: Uncrossed.** After an uncross, no buy order is priced at or above a sell order: the book is
uncrossed (CT-013). The surplus side keeps its unfilled quantity, in its original priority.

**AU-004: Self-trade prevention.** In `CALL`, SP-001 is applied when an order **enters** (and when a
replace re-enters it), against the opposite orders of its own group that it crosses, in the same sense
as CT-004. Mode `CANCEL_NEWEST` cancels the entering order with `SELF_TRADE_PREVENTION`; `CANCEL_OLDEST`
cancels every such resting order and lets the entering order rest; `CANCEL_BOTH` does both. The book
therefore never holds two orders of one group that cross, so an uncross never pairs them.

**AU-005: Last trade price.** The auction price is the last trade price from the uncross on.

**AU-006: State.** The phase is part of the engine's state; a snapshot restores it (RS-010). A snapshot
taken during `CALL` may hold a crossed book.
