# Rulebook: order types and amendments

- **Status:** Draft

This chapter extends [continuous trading](continuous-trading.md) with time in force, post-only orders
and cancel/replace. Every rule ID is cited by tests through `@Rulebook("OT-xxx")`. Design:
[ADR-0009](../adr/0009-order-types-and-replace.md).

Terms used below:
- **Time in force**: how long the unfilled part of an order stays active.
- **Replace** (amend): a change to the price or quantity of a resting order, which keeps its order ID.

Hidden (iceberg) and stop orders, self-trade prevention, auctions and the DAY time in force are
specified in later chapters.

## Time in force

**OT-001: Time in force.** A LIMIT order has one of three times in force:

| Time in force | Unfilled quantity |
|---|---|
| `GTC` good till cancelled (default) | rests in the book (CT-005) |
| `IOC` immediate or cancel | is cancelled with reason `IMMEDIATE_OR_CANCEL` |
| `FOK` fill or kill | the order trades in full on arrival, or not at all (OT-003) |

A MARKET order never rests: its time in force is `IOC` (default) or `FOK`. A MARKET order sent with
`GTC` is rejected with `INVALID_TIME_IN_FORCE`.

**OT-002: IOC.** An IOC order matches exactly as a LIMIT or MARKET order would (CT-001 to CT-004). The
remainder of a LIMIT IOC is cancelled with reason `IMMEDIATE_OR_CANCEL`; the remainder of a MARKET IOC
keeps reason `UNFILLED_MARKET_REMAINDER` (CT-006).

**OT-003: FOK.** On arrival, the engine adds up the opposite quantity within the order's limit (all of
it for a MARKET order). If it covers the order's full quantity, the order matches as an IOC and is
filled completely. Otherwise the order is accepted and its whole quantity cancelled at once with reason
`FILL_OR_KILL`, without any trade. The book is unchanged. A MARKET FOK that arrives on an empty
opposite side is rejected with `NO_LIQUIDITY` (CT-007).

## Post-only

**OT-004: Post-only.** A post-only LIMIT order only ever adds liquidity. If it would trade on arrival
(its price reaches the best opposite price), it is rejected with `POST_ONLY_WOULD_TRADE` and never
reaches the book. Otherwise it rests like any GTC order. Post-only requires `GTC`: a post-only IOC
or FOK order is rejected with `INVALID_TIME_IN_FORCE`. The post-only attribute stays with the order
for as long as it rests, including after a replace.

## Cancel/replace

**OT-005: Replace.** A replace names a resting order, a new limit price and a new quantity. The new
quantity is the order's new *remaining* quantity; what has already been filled is not affected. The
order keeps its order ID, side, time in force and post-only attribute. A replace is refused with
`ReplaceRejected`, and the order is left exactly as it was, when:

| Condition | Reason |
|---|---|
| the order is not resting (unknown, filled or cancelled) | `UNKNOWN_ORDER` |
| new quantity ≤ 0, or above the instrument maximum | `INVALID_QUANTITY`, `QUANTITY_ABOVE_MAXIMUM` |
| new price ≤ 0, or above the instrument maximum | `INVALID_PRICE`, `PRICE_ABOVE_MAXIMUM` |
| the order is post-only and the new price would trade | `POST_ONLY_WOULD_TRADE` |

Reducing an order to zero is a cancel, not a replace.

**OT-006: Priority.** An order whose price is unchanged and whose quantity is reduced, or unchanged,
keeps its place in the queue. Any other replace (a new price, or a larger quantity) loses priority:
the order leaves the book and is re-entered with the new price and quantity, behind every order
already resting at that price.

**OT-007: Aggressive replace.** An order that loses priority is re-entered as an incoming order: if its
new price crosses the opposite side, it trades as the aggressor (taker), at the makers' prices
(CT-001 to CT-004), and only its remainder rests.

**OT-008: Replace events.** A successful replace emits `OrderReplaced` with the order ID, side, new
price, new quantity and whether priority was kept. If priority was kept, nothing follows. Otherwise
the order is no longer in the book at that point, and the engine emits, in this order: one
`TradeExecuted` per fill, then `OrderRested` with the remainder if any quantity is left.

**OT-009: Conservation with replaces.** For an order that has been replaced, CT-014 holds with the
order's quantity taken as its filled quantity at the time of the last replace plus the new quantity.
