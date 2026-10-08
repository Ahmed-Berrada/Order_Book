# Rulebook: continuous trading

This document is the venue's specification of continuous matching. It is written before the code
and is the reference for it: every rule has an ID, and every ID is referenced by at least one test
through `@Rulebook("CT-xxx")`. A build check (`RulebookTraceabilityTest`) fails if a rule has no test.

Terms used below:
- **Order book**: the resting buy orders (bids) and sell orders (asks) of one instrument.
- **Aggressor** (taker): the incoming order. **Resting order** (maker): an order already in the book.
- **Ticks** and **lots**: prices and quantities are integers. The access layer converts decimals.

Scope: one instrument, continuous trading phase, LIMIT and MARKET orders, cancels. Auctions,
trading phases, time in force and other order types are specified in later rulebook chapters.

## Matching

**CT-001: Price priority.** An aggressor is matched against the best opposite price first: the
lowest ask for a buy, the highest bid for a sell. It moves to the next price level only once the
current one is exhausted.

**CT-002: Time priority.** At the same price, resting orders are matched in the order they arrived
(first in, first out). A partially filled resting order keeps its place in the queue.

**CT-003: Execution price.** Every trade executes at the resting order's price. An aggressor that
crosses several price levels therefore receives several prices.

**CT-004: Limit price.** A LIMIT buy only matches asks priced at or below its limit. A LIMIT sell only
matches bids priced at or above its limit.

**CT-005: Resting remainder.** The unfilled part of a LIMIT order rests in the book at its limit
price, behind every order already resting at that price.

**CT-006: Market orders.** A MARKET order matches at any price and never rests. If the opposite side
runs out before it is fully filled, the remainder is cancelled with reason
`UNFILLED_MARKET_REMAINDER`.

**CT-007: No liquidity.** A MARKET order that arrives when the opposite side is empty is rejected with
reason `NO_LIQUIDITY`.

## Cancels

**CT-008: Cancel.** A resting order can be cancelled at any time. Its remaining quantity leaves the
book and its place in the queue is lost. Cancelling an order that is not resting (unknown, already
filled or already cancelled) is answered with `CancelRejected(UNKNOWN_ORDER)` and changes nothing.

## Validation

**CT-009: Order validation.** An order is rejected, with a reason, when:

| Condition | Reason |
|---|---|
| quantity ≤ 0 | `INVALID_QUANTITY` |
| quantity > instrument maximum order quantity | `QUANTITY_ABOVE_MAXIMUM` |
| LIMIT price ≤ 0 | `INVALID_PRICE` |
| LIMIT price > instrument maximum price | `PRICE_ABOVE_MAXIMUM` |

A rejected order never reaches the book. The instrument maxima are chosen so that
`price × quantity` can never overflow a 64-bit integer; this is checked when the instrument is
created.

## Identification and sequencing

**CT-010: Order IDs.** Every new order, including a rejected one, receives an order ID assigned by the
engine. IDs start at 1 and increase by 1 in arrival order. They are unique within an instrument; the
global identity of an order is the pair (instrument, order ID).

**CT-011: Sequence numbers.** Every event carries a sequence number. Numbers start at 1 for each
instrument and increase by 1 with no gaps.

**CT-012: Event order.** For a new order the engine emits, in this order:
1. `OrderAccepted` or `OrderRejected` (and nothing else if rejected);
2. one `TradeExecuted` per fill, in execution order;
3. `OrderRested` or `OrderCancelled` if any quantity remains.

## Invariants

**CT-013: Uncrossed book.** After every command, the best bid is strictly lower than the best ask.

**CT-014: Conservation of quantity.** For every accepted order, at all times:
`accepted quantity = filled quantity + cancelled quantity + resting quantity`.

**CT-015: Determinism.** The same sequence of commands, applied to a new engine for the same
instrument, always produces exactly the same sequence of events.
