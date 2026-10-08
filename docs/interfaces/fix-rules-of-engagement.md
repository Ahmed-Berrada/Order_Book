# FIX Rules of Engagement (FIX 4.4)

How members trade on the venue over FIX: the document an exchange gives its members before
certification. Design rationale: [ADR-0006](../adr/0006-fix-gateway.md). Matching behaviour is
specified in the [rulebook](../rulebook/); this document only covers the protocol.

## Session layer

| Item | Value |
|---|---|
| Protocol | FIX 4.4 over TCP |
| Venue `SenderCompID` / member `TargetCompID` | `LOB` / one per member, declared in the venue configuration |
| Heartbeat interval | Set by the member in Logon (`108`), 30 s recommended |
| Sequence numbers | Persisted by the venue across restarts and reconnections |
| Resend | Standard `ResendRequest` / `SequenceReset`. Messages sent while a member is disconnected (for example fills of its resting orders) are delivered by resend after it logs on again |
| Authentication | Not yet (phase 8: mutual TLS and Logon credentials). Use on a trusted network only |
| Validation | Messages are checked against the FIX 4.4 data dictionary; a malformed message gets a session `Reject` (`35=3`). A field the venue requires but FIX 4.4 makes optional, such as `OrderQty`, gets a `BusinessMessageReject` with reason `5`, naming the field in `Text` |

Unsupported application messages get a `BusinessMessageReject` (`35=j`).

## Order entry

### NewOrderSingle (`35=D`)

| Tag | Field | Rules |
|---|---|---|
| 11 | ClOrdID | Required. Unique per session; a duplicate is rejected (`OrdRejReason=6`) |
| 55 | Symbol | Required. A listed instrument (OE-001) |
| 54 | Side | `1` Buy, `2` Sell |
| 40 | OrdType | `1` Market, `2` Limit |
| 44 | Price | Required for Limit, forbidden for Market. Multiple of the tick size (OE-002) |
| 38 | OrderQty | Whole lots |
| 59 | TimeInForce | Optional; only `0` (Day) is accepted |
| 60 | TransactTime | Required by FIX; the venue's own timestamp is authoritative |

### OrderCancelRequest (`35=F`)

`41 OrigClOrdID` names the order to cancel; `11 ClOrdID` identifies the request; `55` and `54` must
match the original order. A member can only cancel its own orders.

### OrderStatusRequest (`35=H`)

`11 ClOrdID` (the order's ClOrdID), `55`, `54`. Answered with an ExecutionReport `150=I`.

## Execution reports (`35=8`)

| Event | ExecType (150) | OrdStatus (39) |
|---|---|---|
| Order accepted and resting | `0` New | `0` New |
| Fill | `F` Trade | `1` Partially filled or `2` Filled |
| Market order remainder cancelled (CT-006) | `4` Canceled | `4` Canceled |
| Cancel confirmed | `4` Canceled | `4` Canceled |
| Order rejected | `8` Rejected | `8` Rejected |
| Status request answered | `I` Order status | current status |
| Outcome unknown (journal failure) | `A` Pending new | `A` Pending new |

Every report carries `37 OrderID` (`<symbol>-<engine order ID>`, or `NONE` when no order was created),
`17 ExecID` (`<symbol>-<event sequence>`, unique), `11 ClOrdID`, `55`, `54`, `38`, `14 CumQty`,
`151 LeavesQty`, `6 AvgPx` and `60 TransactTime` (the command's timestamp, microseconds, RS-006).
Fills add `31 LastPx` and `32 LastQty`. **Both** sides of a trade receive a fill report: the taker in
reply to its order, the maker on its own session, whoever caused the trade (another FIX member or the
REST API). The maker's report has the same ExecID as the taker's with a `-M` suffix. An order
cancelled by another channel (for example an operator over REST) gets an unsolicited `Canceled`
report on its owner's session.

An order accepted and filled by the same command gets a `New` report, then one `Trade` report per
fill, in execution order.

### Rejections

| Cause | OrdRejReason (103) | Text (58) |
|---|---|---|
| Unknown symbol (OE-001) | `1` Unknown symbol | `UNKNOWN_INSTRUMENT` |
| Venue shutting down (OE-007) | `2` Exchange closed | `STOPPED` |
| Quantity above the instrument maximum | `3` Order exceeds limit | `QUANTITY_ABOVE_MAXIMUM` |
| Price above the instrument maximum | `3` Order exceeds limit | `PRICE_ABOVE_MAXIMUM` |
| Duplicate ClOrdID | `6` Duplicate order | `DUPLICATE_CLORDID` |
| Unsupported field value (Side other than buy/sell, TimeInForce, OrdType), price on a market order or missing on a limit order | `11` Unsupported order characteristic | `UNSUPPORTED_SIDE`, `UNSUPPORTED_TIME_IN_FORCE`, `UNSUPPORTED_ORD_TYPE`, `PRICE_NOT_ALLOWED_FOR_MARKET`, `PRICE_REQUIRED_FOR_LIMIT` |
| Zero, negative or fractional quantity | `13` Incorrect quantity | `INVALID_QUANTITY`, `FRACTIONAL_QUANTITY` |
| Off-tick or non-positive price, no liquidity, overload, halt | `99` Other | `OFF_TICK_PRICE`, `INVALID_PRICE`, `NO_LIQUIDITY`, `OVERLOADED`, `HALTED` |

Rejections by the engine carry the `OrderID` they consumed (CT-010); refusals before the engine carry
`OrderID=NONE` and were not journaled.

### OrderCancelReject (`35=9`)

`434 CxlRejResponseTo=1`, and `102 CxlRejReason`:

| Reason | When | Text (58) |
|---|---|---|
| `0` Too late to cancel | The order exists but no longer rests (filled or already cancelled) | `NOT_RESTING` |
| `1` Unknown order | No such ClOrdID on this session (another member's orders are invisible), or symbol or side mismatch | `UNKNOWN_ORDER` |
| `3` Pending status | The order's acknowledgement has not been sent yet | `PENDING` |
| `6` Duplicate ClOrdID | The cancel request reuses a ClOrdID | `DUPLICATE_CLORDID` |
| `99` Other | Refused by the venue (overload, halt, shutdown) | `OVERLOADED`, `HALTED`, `STOPPED` |

A cancel whose journal write fails is answered with an ExecutionReport `150=6 / 39=6` (Pending cancel)
and `Text=OUTCOME_UNKNOWN`, never with a reject.

An OrderStatusRequest on an unknown ClOrdID is answered with `150=I`, `39=8`, `103=5` (Unknown order);
on an order still awaiting its acknowledgement with `39=A`.

## Unknown outcomes

If the venue cannot journal a command, its outcome is unknown (ADR-0003 §8). The member receives an
ExecutionReport `150=A / 39=A` with `Text=OUTCOME_UNKNOWN`, and must send an OrderStatusRequest once
the venue has recovered.

## Current limitations

- Order ownership (which session owns which order) is held in memory. After a venue restart, orders
  entered before it still rest and trade, but their fills cannot be reported over FIX, and their
  ClOrdIDs are forgotten. Phase 5 records the member with each command in the journal, which removes
  this limitation.
- No drop copy session yet; no mass cancel or cancel/replace (phase 4).
