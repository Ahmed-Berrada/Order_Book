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
| Authentication | The Logon must carry a `Password` (tag 554) matching the hash configured for the session's CompID (`lob.fix.passwords`, AC-007); otherwise the Logon is rejected and the connection closed. After 5 failed Logons in a row for a CompID from one address, its Logons from that address are rejected for a minute whatever the password (AC-009); both numbers are the venue's defaults. Mutual TLS is not built in: terminate TLS in front of the acceptor (phase 9). The acceptor listens on `127.0.0.1` unless `lob.fix.bind-address` opens it |
| Validation | Messages are checked against the FIX 4.4 data dictionary; a malformed message gets a session `Reject` (`35=3`). A field the venue requires but FIX 4.4 makes optional, such as `OrderQty`, gets a `BusinessMessageReject` with reason `5`, naming the field in `Text` |

Unsupported application messages get a `BusinessMessageReject` (`35=j`).

## Order entry

### NewOrderSingle (`35=D`)

| Tag | Field | Rules |
|---|---|---|
| 11 | ClOrdID | Required. Unique per session; a duplicate is rejected (`OrdRejReason=6`) |
| 55 | Symbol | Required. A listed instrument (OE-001) |
| 54 | Side | `1` Buy, `2` Sell |
| 40 | OrdType | `1` Market, `2` Limit, `3` Stop, `4` Stop limit ([stop orders](../rulebook/stop-orders.md)) |
| 44 | Price | Required for Limit and Stop limit, forbidden otherwise. Multiple of the tick size (OE-002) |
| 99 | StopPx | Required for Stop and Stop limit, forbidden otherwise. Multiple of the tick size |
| 38 | OrderQty | Whole lots |
| 59 | TimeInForce | Optional, not allowed on stop orders. Limit: `0` Day (default) rests until the instrument closes ([day orders](../rulebook/day-orders.md)), `1` GTC until cancelled, `3` IOC, `4` FOK. Market: `0` Day (default) and `3` mean IOC, `4` FOK; `1` GTC is rejected (OT-001). Other values are refused |
| 18 | ExecInst | Optional, limit orders only: `6` Participate don't initiate, i.e. post-only (OT-004). Other values are refused |
| 111 | MaxFloor | Optional, limit GTC orders only: the peak of an [iceberg](../rulebook/iceberg-orders.md) order, in whole lots, at most `OrderQty` (IC-001) |
| 60 | TransactTime | Required by FIX; the venue's own timestamp is authoritative |

### OrderCancelRequest (`35=F`)

`41 OrigClOrdID` names the order to cancel; `11 ClOrdID` identifies the request; `55` and `54` must
match the original order. A member can only cancel its own orders.

### OrderCancelReplaceRequest (`35=G`)

Changes the price and quantity of a resting limit order (OT-005 to OT-008). `11 ClOrdID` is the
request's new identifier, `41 OrigClOrdID` any ClOrdID the order has carried; `55` and `54` must match
the order; `40` must be `2` Limit, with `44 Price`. `38 OrderQty` is the new **total** quantity,
fills included: the order's remaining quantity becomes `OrderQty − CumQty`, which must be positive.
TimeInForce and ExecInst cannot be changed and are ignored. A parked stop order cannot be replaced
(ST-007): the request is answered with `CxlRejReason=0`, `NOT_RESTING`; cancel it and send a new one.

A successful replace is reported with `150=5` (Replaced), the new `ClOrdID`, the previous one in
`OrigClOrdID`, the new `OrderQty`, `Price` and `LeavesQty`. If the new price crosses the book, `Trade`
reports follow at once (OT-007). From then on the order answers to its new ClOrdID. Keeping the same
price with a smaller or equal quantity keeps the order's place in the queue; anything else loses it
(OT-006).

### OrderMassCancelRequest (`35=q`)

`11 ClOrdID`, `530 MassCancelRequestType`, `60 TransactTime`, and optionally `55 Symbol` and `54 Side`. Cancels
all the session's member's open orders, resting and parked stops, on that side or on both (RK-006):

| `530` | Scope |
|---|---|
| `1` | the instrument named in `55` |
| `7` | every instrument |

Each order cancelled that the session entered over FIX since the gateway started is reported first, as an
ExecutionReport `150=4` with `Text=MASS_CANCEL`; orders entered over REST or before a restart are cancelled and
counted but have no ExecutionReport (see Current limitations). Then an OrderMassCancelReport (`35=r`) gives
`531 MassCancelResponse` (the request type), `533 TotalAffectedOrders`, `37=NONE` and `60 TransactTime`, the
venue's time of the cancel. It goes to the session only, not to drop copies, which see each cancel. If an
instrument refused the request (a halt, say) while others accepted it, `58 Text` lists it after
`NOT_CANCELLED_IN`; one whose outcome is unknown (a failed journal write) is listed after `OUTCOME_UNKNOWN_IN`,
and its orders may have been cancelled: check them with OrderStatusRequest. The request is rejected only when
every instrument refused it. A rejected request gets `531=0`, with `532 MassCancelRejectReason` when FIX 4.4 has a
value for the reason (`1` unknown security, `0` an unsupported request type) and the reason in `58 Text`
(`UNKNOWN_INSTRUMENT`, `UNSUPPORTED_MASS_CANCEL_REQUEST_TYPE`, `INVALID_SIDE`, `DUPLICATE_CLORDID`). A mass cancel
is accepted in every phase and while the member is suspended, and does not suspend it.

### OrderStatusRequest (`35=H`)

`11 ClOrdID` (the order's ClOrdID), `55`, `54`. Answered with an ExecutionReport `150=I`.

### Self-trade prevention

Every member session is its own [self-trade prevention](../rulebook/self-trade-prevention.md) group:
orders sent on one session never trade with each other, whichever instrument they are for. Nothing is
sent in the order; the venue applies the group of the session it arrives on, so a member cannot choose
or dodge it. The *mode* is a venue setting (`lob.fix.self-trade-mode`), the same for every member:

| Mode | When an order meets one of its own session's resting orders |
|---|---|
| `CANCEL_NEWEST` (default) | The new order's remaining quantity is cancelled |
| `CANCEL_OLDEST` | The resting order is cancelled, and matching continues |
| `CANCEL_BOTH` | Both are cancelled |

Each cancel is an ExecutionReport `150=4 / 39=4` with `Text=SELF_TRADE_PREVENTION`, sent to the owner of
the cancelled order, in event order (SP-005). The group is derived from the session's CompID (see
`FixGatewayConfig.selfTradeGroup`), so it does not change when members are added or reordered. Orders
from other channels, such as REST, have no group and trade with anyone, members' orders included.

## Execution reports (`35=8`)

| Event | ExecType (150) | OrdStatus (39) |
|---|---|---|
| Order accepted and resting | `0` New | `0` New |
| Fill | `F` Trade | `1` Partially filled or `2` Filled |
| Remainder of a market, IOC or FOK order cancelled (CT-006, OT-002, OT-003), or an order cancelled by self-trade prevention (SP-002 to SP-004); `Text` gives the reason | `4` Canceled | `4` Canceled |
| Replace confirmed | `5` Replaced | `0` New or `1` Partially filled |
| Stop order accepted (parked, ST-003) | `0` New | `0` New |
| Stop order triggered (ST-005); `378 ExecRestatementReason=99`, `Text=STOP_TRIGGERED`. FIX 4.4 has no ExecType for a trigger | `D` Restated | `0` New |
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
cancelled or replaced by another channel (for example an operator over REST) gets an unsolicited
`Canceled` or `Replaced` report on its owner's session.

An order accepted and filled by the same command gets a `New` report, then one `Trade` report per
fill, in execution order. An iceberg is reported like any order: `LeavesQty` is its whole remainder,
hidden quantity included, and its refills produce no report. A triggered stop's fills are reported to
the stop's owner, as its own, even though another member's order caused the trigger.

### Rejections

| Cause | OrdRejReason (103) | Text (58) |
|---|---|---|
| Unknown symbol (OE-001) | `1` Unknown symbol | `UNKNOWN_INSTRUMENT` |
| Venue shutting down (OE-007) | `2` Exchange closed | `STOPPED` |
| Quantity above the instrument maximum | `3` Order exceeds limit | `QUANTITY_ABOVE_MAXIMUM` |
| Price above the instrument maximum | `3` Order exceeds limit | `PRICE_ABOVE_MAXIMUM` |
| Duplicate ClOrdID | `6` Duplicate order | `DUPLICATE_CLORDID` |
| Unsupported field value (Side other than buy/sell, TimeInForce, ExecInst, OrdType), price or ExecInst on a market order, price missing on a limit order | `11` Unsupported order characteristic | `UNSUPPORTED_SIDE`, `UNSUPPORTED_TIME_IN_FORCE`, `UNSUPPORTED_EXEC_INST`, `UNSUPPORTED_ORD_TYPE`, `PRICE_NOT_ALLOWED_FOR_MARKET`, `POST_ONLY_NOT_ALLOWED_FOR_MARKET`, `MAX_FLOOR_NOT_ALLOWED_FOR_MARKET`, `PRICE_REQUIRED_FOR_LIMIT`, `STOP_PRICE_REQUIRED`, `STOP_PRICE_NOT_ALLOWED`, `STOP_ORDER_ATTRIBUTES` (TimeInForce, ExecInst or MaxFloor on a stop order) |
| Time in force not allowed for the order (GTC market order, post-only IOC or FOK) | `11` Unsupported order characteristic | `INVALID_TIME_IN_FORCE` |
| Zero, negative or fractional quantity or MaxFloor; MaxFloor above the quantity or on an IOC or FOK order | `13` Incorrect quantity | `INVALID_QUANTITY`, `FRACTIONAL_QUANTITY`, `INVALID_DISPLAY_QUANTITY` |
| Off-tick or non-positive price, no liquidity, post-only order that would trade, stop price already reached, overload, halt, the member's message rate (OE-008) or order-to-trade ratio (OE-009) | `99` Other | `OFF_TICK_PRICE`, `INVALID_PRICE`, `NO_LIQUIDITY`, `POST_ONLY_WOULD_TRADE`, `STOP_PRICE_ALREADY_REACHED`, `OVERLOADED`, `HALTED`, `THROTTLED`, `ORDER_TO_TRADE_LIMIT` |

Rejections by the engine carry the `OrderID` they consumed (CT-010); refusals before the engine carry
`OrderID=NONE` and were not journaled.

### OrderCancelReject (`35=9`)

`434 CxlRejResponseTo` is `1` for a cancel request and `2` for a cancel/replace request, and
`102 CxlRejReason`:

| Reason | When | Text (58) |
|---|---|---|
| `0` Too late to cancel | The order exists but no longer rests (filled or already cancelled) | `NOT_RESTING` |
| `1` Unknown order | No such ClOrdID on this session (another member's orders are invisible), or symbol or side mismatch | `UNKNOWN_ORDER` |
| `3` Pending status | The order's acknowledgement has not been sent yet | `PENDING` |
| `6` Duplicate ClOrdID | The cancel request reuses a ClOrdID | `DUPLICATE_CLORDID` |
| `99` Other | Refused by the venue (overload, halt, shutdown) | `OVERLOADED`, `HALTED`, `STOPPED` |
| `99` Other | Replace only: the member's message rate or order-to-trade ratio (OE-008, OE-009); cancels are never refused this way | `THROTTLED`, `ORDER_TO_TRADE_LIMIT` |
| `99` Other | Replace only: invalid new values, or refused by the engine (OT-005); the order is unchanged | `QUANTITY_NOT_ABOVE_FILLED`, `FRACTIONAL_QUANTITY`, `OFF_TICK_PRICE`, `ONLY_LIMIT_PRICE_AND_QUANTITY`, `QUANTITY_ABOVE_MAXIMUM`, `INVALID_PRICE`, `PRICE_ABOVE_MAXIMUM`, `POST_ONLY_WOULD_TRADE` |

A ClOrdID used by a refused cancel or replace request stays used. A cancel or replace whose journal
write fails is answered with an ExecutionReport `150=6 / 39=6` (Pending cancel) or `150=E / 39=E`
(Pending replace) and `Text=OUTCOME_UNKNOWN`, never with a reject.

An OrderStatusRequest on an unknown ClOrdID is answered with `150=I`, `39=8`, `103=5` (Unknown order);
on an order still awaiting its acknowledgement with `39=A`.

## Unknown outcomes

If the venue cannot journal a command, its outcome is unknown (ADR-0003 §8). The member receives an
ExecutionReport `150=A / 39=A` with `Text=OUTCOME_UNKNOWN`, and must send an OrderStatusRequest once
the venue has recovered.

## Drop copy

Drop copy sessions are provisioned like member sessions (`lob.fix.drop-copy-sessions`) and carry no
orders. Every ExecutionReport sent to any member is copied to each drop copy session (DC-001), with:

| Tag | Field | Where | Value |
|---|---|---|---|
| 797 | CopyMsgIndicator | body | `Y` |
| 115 | OnBehalfOfCompID | header | the member the report was sent to |

The body is identical to the member's report. Any application message a drop copy session sends is
answered with a `BusinessMessageReject`, reason `6` (Not authorized) (DC-002).

## Current limitations

- Order ownership (which session owns which order) and ClOrdIDs are held in memory. After a venue restart,
  orders entered before it still rest and trade, and the journal knows their member, but their fills cannot
  be reported over FIX and their ClOrdIDs are forgotten.
- A Day order that expires is reported as `ExecType=C` (Expired), `OrdStatus=C`, `Text=DAY_EXPIRED`, when the
  venue sets the instrument to closed. There is no trading calendar: an operator closes the instrument (REST).
- A volatility interruption (a fill that would execute outside the band) is not announced over FIX; the
  affected order's remainder is cancelled with `Text=VOLATILITY_INTERRUPTION`, or rests, and later orders are
  collected as in any call phase.
- Trading phases ([trading phases](../rulebook/trading-phases.md)) are set by an operator over REST, not
  announced over FIX: the gateway sends no `TradingSessionStatus`. In a call phase the venue accepts
  limit GTC orders and stops, and rejects the rest with `OrdRejReason=2` (Exchange closed) and text
  `NOT_ALLOWED_IN_PHASE`; the same reason is given for every new order while the instrument is closed.
  Auction fills are reported as ordinary `ExecType=Trade` reports at the auction price.
- The operator's kill switch (REST) cancels all of a member's orders and suspends it; each order is reported as
  `ExecType=4` with `Text=KILL_SWITCH`. A member's own mass cancel is `35=q` (above).

## Members and limits

A member session acts for a **member**: the ID configured in `lob.members` for its CompID, or else a number
derived from the CompID. The ID is also the session's self-trade prevention group. The member's pre-trade
limits ([members and risk](../rulebook/members-and-risk.md)) apply to its orders: an order that breaks one is
rejected with `OrdRejReason=3` (Order exceeds limit) and `Text` naming the limit (`RISK_ORDER_QUANTITY`,
`RISK_ORDER_NOTIONAL`, `RISK_PRICE_COLLAR`, `RISK_OPEN_ORDERS`, `RISK_POSITION`); a suspended member's orders
are rejected with `OrdRejReason=99` and `Text=MEMBER_SUSPENDED`.
