# REST API (version 1)

Order entry and market data over HTTP/JSON, for integration and administration. Design rationale:
[ADR-0005](../adr/0005-rest-api.md). The generated OpenAPI description is served at
`/v3/api-docs`, with a browsable UI at `/swagger-ui.html`.

REST is not the low-latency path: members trading at speed will use FIX (phase 3c).

## Conventions

- Base path `/api/v1`. JSON in and out (`application/json`); errors are RFC 9457 problem details
  (`application/problem+json`).
- **Prices are decimal strings** in responses (`"185.25"`), never binary floating point. Requests may
  send a price as a string or a JSON number; it must be an exact multiple of the tick size (OE-002).
- **Quantities are whole lots.** A fractional quantity such as `10.5` is a `400`, never truncated.
- **Unknown fields are refused** with `400`: a misspelt or unsupported field (for example a trailing
  offset, which does not exist) must not be silently ignored on an order.
- Timestamps are UTC ISO-8601 with microseconds, e.g. `2026-10-08T09:11:38.669599Z`. All events of a
  command share the command's timestamp (RS-006).

## Authentication

With security on (the default; [access control](../rulebook/access-control.md), ADR-0017) every request
carries HTTP Basic credentials, ideally over TLS terminated in front of the venue. Roles, from lowest:

| Role | May |
|---|---|
| `READ` | every `GET`: instruments, books, order status, the market-data feed |
| `MEMBER` | also enter, replace and cancel orders, for its own member |
| `ADMIN` | also set the trading phase, members' limits, and the kill switch; may act for any member with `X-Member-Id` |

No credentials or wrong ones: `401`. After 5 failures in a row for one user name from one address (the defaults, `lob.security.max-failures` and `lob.security.lockout`), that name is refused from that address for a minute with `429`, `Retry-After` and reason `LOCKED_OUT`, even with the right password (AC-009). A role too low, another member named, or a write the browser marks as coming from another site or with a form body (AC-008): `403`. Everything the API does not
name is refused. For local development only, the `dev` profile turns all of it off.

## Endpoints

### `GET /api/v1/instruments`

Listed instruments.

```json
[{"symbol":"AAPL","tickSize":"0.01","maxOrderQuantity":1000000,"maxPrice":"100000.00","halted":false,"phase":"CONTINUOUS"}]
```

`phase` is the [trading phase](../rulebook/trading-phases.md): `CONTINUOUS`, `CALL` or `CLOSED`.

### `PUT /api/v1/instruments/{symbol}/phase`

Moves an instrument to another trading phase (TP-002). Leaving `CALL` runs the uncross (AU-001).

```json
{"phase":"CONTINUOUS"}
```

```json
{"symbol":"AAPL","from":"CALL","to":"CONTINUOUS","auctionPrice":"185.50","auctionVolume":5,"trades":1,
 "commandSequence":6,"timestamp":"2026-10-08T09:11:38.669599Z"}
```

`auctionPrice` is `null`, and `auctionVolume` and `trades` are `0`, when nothing traded. A request for the
current phase is a `409` (`INVALID_PHASE_TRANSITION`). Until members and authentication exist (phases 5
and 8) this is an operator action that anyone who reaches the port can take.

In a `CALL` phase the book can be crossed. Market, IOC, FOK and post-only orders are refused with `422`
(`NOT_ALLOWED_IN_PHASE`); in `CLOSED` every new order and every replace is.

### `POST /api/v1/instruments/{symbol}/orders`

```json
{"side":"BUY","type":"LIMIT","price":"185.25","quantity":10}
{"side":"SELL","type":"MARKET","quantity":5}
```

`type` is `LIMIT`, `MARKET`, `STOP` or `STOP_LIMIT` ([stop orders](../rulebook/stop-orders.md)).
`price` (the limit) is required for `LIMIT` and `STOP_LIMIT` and must be absent otherwise;
`stopPrice` is required for `STOP` and `STOP_LIMIT` and must be absent otherwise. Optional fields
for `LIMIT` and `MARKET` orders
([order types](../rulebook/order-types.md)):

| Field | Values | Default |
|---|---|---|
| `timeInForce` | `GTC`, `IOC`, `FOK`, `DAY` (`GTC` and `DAY` are rejected on a `MARKET` order) | `GTC` for `LIMIT`, `IOC` for `MARKET` |
| `postOnly` | `true` or `false`; `LIMIT` only, and only with `GTC` | `false` |
| `displayQuantity` | The peak of an [iceberg](../rulebook/iceberg-orders.md) order, at most `quantity`; `LIMIT` `GTC` only | `0`, fully visible |

```json
{"side":"BUY","type":"LIMIT","price":"185.25","quantity":10,"timeInForce":"IOC"}
{"side":"SELL","type":"LIMIT","price":"185.40","quantity":10,"postOnly":true}
{"side":"SELL","type":"LIMIT","price":"185.40","quantity":5000,"displayQuantity":100}
{"side":"SELL","type":"STOP","stopPrice":"180.00","quantity":10}
{"side":"BUY","type":"STOP_LIMIT","stopPrice":"190.00","price":"190.50","quantity":10}
```

`201 Created`, with `Location: /api/v1/instruments/{symbol}/orders/{orderId}`:

```json
{
  "orderId": 7,
  "status": "PARTIALLY_FILLED",
  "commandSequence": 42,
  "timestamp": "2026-10-08T09:11:38.669599Z",
  "filledQuantity": 4,
  "restingQuantity": 6,
  "cancelledQuantity": 0,
  "trades": [{"sequence": 103, "makerOrderId": 3, "price": "185.20", "quantity": 4}]
}
```

`status` follows FIX `OrdStatus`, as the order stands once the command is processed:

| Status | Meaning |
|---|---|
| `NEW` | Rests in the book, nothing filled |
| `PARTIALLY_FILLED` | Partly filled, the rest rests in the book |
| `FILLED` | Completely filled |
| `CANCELLED` | The unfilled remainder of a market, IOC or FOK order was cancelled (CT-006, OT-002, OT-003), possibly after fills |
| `PARKED` | A stop order waits for its trigger, outside the book (ST-003) |

The response describes this order only. A command can also release stop orders (ST-005), whose
events are not in it; if a released stop trades with this order, that fill is included.

### `DELETE /api/v1/instruments/{symbol}/orders/{orderId}`

`200 OK`:

```json
{"orderId": 7, "cancelledQuantity": 6, "commandSequence": 43, "timestamp": "2026-10-08T09:11:39.000001Z"}
```

`404` if the order is not resting (CT-008). The cancel request is still journaled.

### `PUT /api/v1/instruments/{symbol}/orders/{orderId}`

Replaces a resting order's price and **remaining** quantity (OT-005). The order keeps its ID.

```json
{"price":"185.50","quantity":6}
```

`200 OK`:

```json
{
  "orderId": 1,
  "price": "185.50",
  "priorityKept": false,
  "commandSequence": 4,
  "timestamp": "2026-11-15T10:00:00.000001Z",
  "filledQuantity": 4,
  "restingQuantity": 2,
  "trades": [{"sequence": 9, "makerOrderId": 2, "price": "185.50", "quantity": 4}]
}
```

`priorityKept` is true only when the price is unchanged and the quantity not increased (OT-006). A
replace that crosses the book trades immediately (OT-007). `404 UNKNOWN_ORDER` if the order is not
resting; `422` with the engine's reason if the replace is refused, in which case the order is
unchanged.

### `GET /api/v1/instruments/{symbol}/orders/{orderId}`

```json
{"orderId": 7, "resting": true, "restingQuantity": 6}
```

An order that is not resting (filled, cancelled or unknown) returns `resting: false`.
`restingQuantity` is the full remainder, hidden quantity included: it is meant for the order's owner.

### Members

[Members and risk](../rulebook/members-and-risk.md): an order, a replace or a cancel is entered for the member
named by the header `X-Member-Id` (a positive number); without it the operator (member `0`) acts, with no
limits. A member can cancel and replace only its own orders (MB-003): another's order is a `404`. With
[authentication](#authentication) on, a `MEMBER` user acts for its own member whatever the header says (naming
another member is a `403`), and the endpoints below need `ADMIN`.

| Endpoint | Does |
|---|---|
| `GET /api/v1/members/{memberId}` | The member's state in every instrument: `suspended`, `netPosition` and each limit (`0` means none) |
| `PUT /api/v1/members/{memberId}/limits` | Sets the limits in every instrument. Body: `maxOrderQuantity`, `maxOrderNotional` (a price times a quantity, e.g. `1000.00`), `priceCollarBps`, `maxOpenOrders`, `maxNetPosition`, all `0` or more. Returns the member's state |
| `PUT /api/v1/members/{memberId}/suspension` | The kill switch: suspends the member and cancels its open orders in every instrument. Returns `{"memberId":2,"suspended":true,"cancelledOrders":2}`. `409 INVALID_MEMBER` if already suspended |
| `DELETE /api/v1/members/{memberId}/suspension` | Lifts the suspension. `409 INVALID_MEMBER` if the member is not suspended |

Limits can also be configured (`lob.members`, see `application.yml`); the venue applies them when it starts
and journals a change only if the configured limits differ from the instrument's. An order that breaks a
limit is a `422` with `RISK_ORDER_QUANTITY`, `RISK_ORDER_NOTIONAL`, `RISK_PRICE_COLLAR`, `RISK_OPEN_ORDERS`,
`RISK_POSITION` or `MEMBER_SUSPENDED`.

### Self-trade prevention

REST orders have no [self-trade prevention](../rulebook/self-trade-prevention.md) group: the API has no
notion of who is calling until phases 5 and 8, and a group chosen by the caller would be no protection.
A REST order therefore trades with anyone, including the resting orders of FIX members; an order of a
FIX member that is cancelled by prevention shows up in the book and in `restingQuantity` like any other
cancel.

### `GET /api/v1/instruments/{symbol}/book?depth=10`

`depth` is 1 to 100 (default 10). Quantities are what the market may see: an iceberg counts with its
peak only (IC-002).

```json
{
  "symbol": "AAPL",
  "commandSequence": 43,
  "phase": "CONTINUOUS",
  "bids": [{"price": "185.20", "quantity": 12, "orders": 2}],
  "asks": [{"price": "185.25", "quantity": 4, "orders": 1}]
}
```

## Errors

| Status | When | `reason` |
|---|---|---|
| `400` | Malformed JSON, missing or unknown field, fractional quantity, price, `postOnly` or `displayQuantity` on a market order, depth out of range | — |
| `404` | Unknown symbol (OE-001) | `UNKNOWN_INSTRUMENT` |
| `409` | Phase change to the current phase (TP-002); suspending a suspended member or resuming one that is not (RK-004, RK-005) | `INVALID_PHASE_TRANSITION`, `INVALID_MEMBER` |
| `404` | Cancel or replace of an order that is not resting (CT-008, OT-005) | `UNKNOWN_ORDER` |
| `422` | Off-tick price (OE-002) | `OFF_TICK_PRICE` |
| `422` | Rejected by the engine (CT-007, CT-009, OT-001, OT-004); the body carries the `orderId` it consumed | `INVALID_QUANTITY`, `QUANTITY_ABOVE_MAXIMUM`, `INVALID_PRICE`, `PRICE_ABOVE_MAXIMUM`, `NO_LIQUIDITY`, `INVALID_TIME_IN_FORCE`, `POST_ONLY_WOULD_TRADE`, `INVALID_DISPLAY_QUANTITY`, `STOP_PRICE_ALREADY_REACHED`, `NOT_ALLOWED_IN_PHASE`, `INVALID_TICK_SIZE`, `MEMBER_SUSPENDED`, `RISK_ORDER_QUANTITY`, `RISK_ORDER_NOTIONAL`, `RISK_PRICE_COLLAR`, `RISK_OPEN_ORDERS`, `RISK_POSITION` |
| `422` | Replace refused by the engine (OT-005); the order is unchanged | the same reasons |
| `429` | The member is over its message rate (OE-008) or its order-to-trade ratio for the day (OE-009), with `Retry-After` in seconds: until the next message is accepted, or at most until the end of the UTC day (new trades can lift the ratio refusal sooner). Cancels are never refused this way | `THROTTLED`, `ORDER_TO_TRADE_LIMIT` |
| `503` | Queue full (OE-005), with `Retry-After: 1` | `OVERLOADED` |
| `503` | Instrument halted (OE-006) or venue shutting down (OE-007) | `HALTED`, `STOPPED` |
| `500` | The command's journal write failed: **its outcome is unknown** (ADR-0003 §8) | `OUTCOME_UNKNOWN` |
| `503` | No response within the request timeout: **outcome unknown** | — |

Example:

```json
{
  "type": "about:blank",
  "title": "Order rejected",
  "status": 422,
  "detail": "Order 9 rejected: QUANTITY_ABOVE_MAXIMUM",
  "reason": "QUANTITY_ABOVE_MAXIMUM",
  "orderId": 9
}
```

**Unknown outcomes.** After a `500 OUTCOME_UNKNOWN` or a timeout, the order may or may not exist. REST
has no client order ID yet, so the member must inspect the book. The FIX gateway (phase 3c) solves this
with `ClOrdID` and order status requests.
