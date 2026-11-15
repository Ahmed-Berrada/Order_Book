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
- **Unknown fields are refused** with `400`: a misspelt or unsupported field (for example a time in
  force, which does not exist yet) must not be silently ignored on an order.
- Timestamps are UTC ISO-8601 with microseconds, e.g. `2026-10-08T09:11:38.669599Z`. All events of a
  command share the command's timestamp (RS-006).

## Endpoints

### `GET /api/v1/instruments`

Listed instruments.

```json
[{"symbol":"AAPL","tickSize":"0.01","maxOrderQuantity":1000000,"maxPrice":"100000.00","halted":false}]
```

### `POST /api/v1/instruments/{symbol}/orders`

```json
{"side":"BUY","type":"LIMIT","price":"185.25","quantity":10}
{"side":"SELL","type":"MARKET","quantity":5}
```

`price` is required for `LIMIT` and must be absent for `MARKET`.

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
| `CANCELLED` | A market order's unfilled remainder was cancelled (CT-006), possibly after fills |

### `DELETE /api/v1/instruments/{symbol}/orders/{orderId}`

`200 OK`:

```json
{"orderId": 7, "cancelledQuantity": 6, "commandSequence": 43, "timestamp": "2026-10-08T09:11:39.000001Z"}
```

`404` if the order is not resting (CT-008). The cancel request is still journaled.

### `GET /api/v1/instruments/{symbol}/orders/{orderId}`

```json
{"orderId": 7, "resting": true, "restingQuantity": 6}
```

An order that is not resting (filled, cancelled or unknown) returns `resting: false`.

### `GET /api/v1/instruments/{symbol}/book?depth=10`

`depth` is 1 to 100 (default 10).

```json
{
  "symbol": "AAPL",
  "commandSequence": 43,
  "bids": [{"price": "185.20", "quantity": 12, "orders": 2}],
  "asks": [{"price": "185.25", "quantity": 4, "orders": 1}]
}
```

## Errors

| Status | When | `reason` |
|---|---|---|
| `400` | Malformed JSON, missing or unknown field, fractional quantity, price on a market order, depth out of range | — |
| `404` | Unknown symbol (OE-001) | `UNKNOWN_INSTRUMENT` |
| `404` | Cancel of an order that is not resting (CT-008) | `UNKNOWN_ORDER` |
| `422` | Off-tick price (OE-002) | `OFF_TICK_PRICE` |
| `422` | Rejected by the engine (CT-007, CT-009); the body carries the `orderId` it consumed | `INVALID_QUANTITY`, `QUANTITY_ABOVE_MAXIMUM`, `INVALID_PRICE`, `PRICE_ABOVE_MAXIMUM`, `NO_LIQUIDITY` |
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
