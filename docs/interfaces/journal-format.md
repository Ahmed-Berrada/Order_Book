# Journal file formats (version 1)

Binary formats of the files written by `engine-journal`. Rules and rationale are in the rulebook
chapter [`resilience.md`](../rulebook/resilience.md) and [ADR-0003](../adr/0003-journal-and-recovery.md).

All integers are big-endian and signed. Strings use Java's modified UTF-8 (`DataOutputStream.writeUTF`:
a 2-byte length followed by the bytes).

## Directory layout

One directory per instrument:

```
<instrument>/
├── commands.journal                         every command received, in order
├── events.journal                           the events of every command
└── snapshot-<20-digit sequence>.snap        engine state after that command (the 2 newest are kept)
```

## Frames

Every file is a sequence of frames:

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 4 | `length` | Payload size in bytes, 1 to 1,048,576 |
| 4 | 4 | `crc32c` | CRC-32C (Castagnoli) of the payload |
| 8 | `length` | `payload` | One record, as defined below |

## Header (first frame of every file)

| Size | Field | Value |
|---|---|---|
| 4 | magic | `0x4C4F424A` (`"LOBJ"`) |
| 2 | format version | `1` |
| 1 | file kind | `1` commands, `2` events, `3` snapshot |
| 2+n | symbol | modified UTF-8 |
| 8 | max order quantity | lots |
| 8 | max price | ticks |

A journal must name the instrument it is opened for, or recovery stops (RS-004).

## Command record (`commands.journal`, after the header)

| Size | Field | Notes |
|---|---|---|
| 8 | sequence | 1, 2, 3… with no gaps |
| 8 | timestamp | microseconds since 1970-01-01T00:00:00Z, never decreasing |
| 1 | type | `1` limit, `2` market, `3` cancel |

Then, by type:

| Type | Fields |
|---|---|
| 1 LIMIT | side (1), price ticks (8), quantity (8) |
| 2 MARKET | side (1), quantity (8) |
| 3 CANCEL | order ID (8) |

## Event batch (`events.journal`, after the header)

| Size | Field | Notes |
|---|---|---|
| 8 | command sequence | the command that produced these events; 1, 2, 3… with no gaps |
| 8 | timestamp | the command's timestamp |
| 4 | count | number of events that follow |

Each event starts with its type (1 byte) and its event sequence number (8 bytes):

| Type | Event | Further fields |
|---|---|---|
| 1 | `OrderAccepted` | order ID (8) |
| 2 | `OrderRejected` | order ID (8), reject reason (1) |
| 3 | `TradeExecuted` | taker order ID (8), maker order ID (8), aggressor side (1), price (8), quantity (8), taker remaining (8), maker remaining (8) |
| 4 | `OrderRested` | order ID (8), side (1), price (8), quantity (8) |
| 5 | `OrderCancelled` | order ID (8), cancelled quantity (8), cancel reason (1) |
| 6 | `CancelRejected` | order ID (8), reject reason (1) |

## Snapshot (`snapshot-*.snap`: a single frame)

| Size | Field |
|---|---|
| … | header, with file kind `3` |
| 8 | last command sequence included |
| 8 | next order ID |
| 8 | next event sequence |
| 4 | number of resting orders |

Then each resting order, in priority order (bids best first, then asks best first, oldest first within
a price): order ID (8), side (1), price (8), remaining quantity (8).

## Enumeration codes

Codes are fixed here and never derived from Java enum order, so old journals keep their meaning.

| Side | Code |
|---|---|
| BUY | 1 |
| SELL | 2 |

| Reject reason | Code |
|---|---|
| INVALID_QUANTITY | 1 |
| QUANTITY_ABOVE_MAXIMUM | 2 |
| INVALID_PRICE | 3 |
| PRICE_ABOVE_MAXIMUM | 4 |
| NO_LIQUIDITY | 5 |
| UNKNOWN_ORDER | 6 |

| Cancel reason | Code |
|---|---|
| CLIENT_REQUEST | 1 |
| UNFILLED_MARKET_REMAINDER | 2 |

## Compatibility

Any change to these layouts or codes increments the format version. A reader refuses versions it
does not know.
