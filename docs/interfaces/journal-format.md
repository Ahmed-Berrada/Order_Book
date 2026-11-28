# Journal file formats (version 8)

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
| 2 | format version | `8` |
| 1 | file kind | `1` commands, `2` events, `3` snapshot |
| 2+n | symbol | modified UTF-8 |
| 8 | max order quantity | lots |
| 8 | max price | ticks |
| 4 | number of tick bands | |
| 16 each | tick band | lowest price (8), step (8), in ticks |
| 8 | volatility band | basis points; `0` for none |

A journal must name the instrument it is opened for, or recovery stops (RS-004).

## Command record (`commands.journal`, after the header)

| Size | Field | Notes |
|---|---|---|
| 8 | sequence | 1, 2, 3… with no gaps |
| 8 | timestamp | microseconds since 1970-01-01T00:00:00Z, never decreasing |
| 8 | member ID | the member the command was processed for; `0` for the operator |
| 1 | type | `1` limit, `2` market, `3` cancel, `4` replace, `5` stop |

Then, by type:

| Type | Fields |
|---|---|
| 1 LIMIT | side (1), price ticks (8), quantity (8), time in force (1), post-only (1), display quantity (8), self-trade instruction (9) |
| 2 MARKET | side (1), quantity (8), time in force (1), self-trade instruction (9) |
| 3 CANCEL | order ID (8) |
| 4 REPLACE | order ID (8), new price ticks (8), new quantity (8) |
| 5 STOP | side (1), stop price ticks (8), limit price ticks (8; `0` for stop-market), quantity (8), self-trade instruction (9) |
| 6 SET_PHASE | trading phase (1) |
| 7 SET_MEMBER_LIMITS | member ID (8), the five limits (8 each: quantity, notional, collar, open orders, position) |
| 8 SUSPEND_MEMBER | member ID (8) |
| 9 RESUME_MEMBER | member ID (8) |
| 10 MASS_CANCEL | side (1; `0` for both) |

A **self-trade instruction** is a group (8; `0` for none) followed by a mode (1, see below).

A boolean is one byte, `0` or `1`; any other value is corruption.

## Event batch (`events.journal`, after the header)

| Size | Field | Notes |
|---|---|---|
| 8 | command sequence | the command that produced these events; 1, 2, 3… with no gaps |
| 8 | timestamp | the command's timestamp |
| 8 | member ID | the member of the command |
| 4 | count | number of events that follow |

Each event starts with its type (1 byte) and its event sequence number (8 bytes):

| Type | Event | Further fields |
|---|---|---|
| 1 | `OrderAccepted` | order ID (8) |
| 2 | `OrderRejected` | order ID (8), reject reason (1) |
| 3 | `TradeExecuted` | taker order ID (8), maker order ID (8), aggressor side (1), price (8), quantity (8), taker remaining (8), maker remaining (8), taker member (8), maker member (8) |
| 4 | `OrderRested` | order ID (8), side (1), price (8), quantity (8), displayed quantity (8) |
| 5 | `OrderCancelled` | order ID (8), cancelled quantity (8), cancel reason (1) |
| 6 | `CancelRejected` | order ID (8), reject reason (1) |
| 7 | `OrderReplaced` | order ID (8), side (1), price (8), quantity (8), priority kept (1) |
| 8 | `ReplaceRejected` | order ID (8), reject reason (1) |
| 9 | `OrderReplenished` | order ID (8), side (1), price (8), displayed quantity (8) |
| 10 | `OrderParked` | order ID (8), side (1), stop price (8), quantity (8) |
| 11 | `StopTriggered` | order ID (8) |
| 12 | `PhaseChanged` | from phase (1), to phase (1) |
| 13 | `PhaseChangeRejected` | reject reason (1) |
| 14 | `AuctionPriceDetermined` | price (8), volume (8) |
| 15 | `VolatilityInterruption` | reference price (8), breaching price (8) |
| 16 | `MemberLimitsChanged` | member ID (8), the five limits (8 each) |
| 17 | `MemberSuspended` | member ID (8), cancelled orders (8) |
| 18 | `MemberResumed` | member ID (8) |
| 19 | `MemberCommandRejected` | member ID (8), reject reason (1) |
| 20 | `MassCancelled` | member ID (8), side (1; `0` for both), cancelled orders (8) |

## Snapshot (`snapshot-*.snap`: a single frame)

| Size | Field |
|---|---|
| … | header, with file kind `3` |
| 8 | last command sequence included |
| 8 | next order ID |
| 8 | next event sequence |
| 4 | number of resting orders |

Then each resting order, in priority order (bids best first, then asks best first, oldest first within
a price): order ID (8), side (1), price (8), remaining quantity (8), post-only (1), display quantity (8;
`0` for a fully visible order), displayed quantity (8), self-trade instruction (9), day (1), member (8).

After the resting orders: the last trade price (8; `0` before the first trade), the number of parked
stops (4), then each stop in order of order ID: order ID (8), side (1), stop price (8), limit price (8),
quantity (8), self-trade instruction (9), member (8). The snapshot ends with the trading phase (1), the volatility reference price (8; `0` before the first trade), the number of members (4) and each member with limits, a suspension or a position, in order of member ID: member ID (8), the five limits (8 each), suspended (1), net position (8).

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
| INVALID_TIME_IN_FORCE | 7 |
| POST_ONLY_WOULD_TRADE | 8 |
| INVALID_DISPLAY_QUANTITY | 9 |
| STOP_PRICE_ALREADY_REACHED | 10 |
| INVALID_SELF_TRADE_GROUP | 11 |
| NOT_ALLOWED_IN_PHASE | 12 |
| INVALID_PHASE_TRANSITION | 13 |
| INVALID_TICK_SIZE | 14 |
| MEMBER_SUSPENDED | 15 |
| RISK_ORDER_QUANTITY | 16 |
| RISK_ORDER_NOTIONAL | 17 |
| RISK_PRICE_COLLAR | 18 |
| RISK_OPEN_ORDERS | 19 |
| RISK_POSITION | 20 |
| INVALID_MEMBER_LIMITS | 21 |
| INVALID_MEMBER | 22 |

| Trading phase | Code |
|---|---|
| CONTINUOUS | 1 |
| CALL | 2 |
| CLOSED | 3 |

| Cancel reason | Code |
|---|---|
| CLIENT_REQUEST | 1 |
| UNFILLED_MARKET_REMAINDER | 2 |
| IMMEDIATE_OR_CANCEL | 3 |
| FILL_OR_KILL | 4 |
| SELF_TRADE_PREVENTION | 5 |
| DAY_EXPIRED | 6 |
| VOLATILITY_INTERRUPTION | 7 |
| KILL_SWITCH | 8 |
| MASS_CANCEL | 9 |

| Self-trade mode | Code |
|---|---|
| CANCEL_NEWEST | 1 |
| CANCEL_OLDEST | 2 |
| CANCEL_BOTH | 3 |

| Time in force | Code |
|---|---|
| GTC | 1 |
| IOC | 2 |
| FOK | 3 |
| DAY | 4 |

## Compatibility

Any change to these layouts or codes increments the format version. A reader refuses versions it
does not know, and versions older than the oldest it reads: a version that only adds codes is read by the next
one, so journals written before an upgrade recover after it.

| Version | Change |
|---|---|
| 1 | Limit, market and cancel commands (phase 2) |
| 2 | Time in force and post-only on orders, replace command, `OrderReplaced` and `ReplaceRejected` events, post-only in snapshots (ADR-0009). Version 1 files are refused: no production journal exists yet |
| 3 | Iceberg orders: display quantity on limit orders and in snapshots, displayed quantity on `OrderRested`, `OrderReplenished` event (ADR-0010). Earlier versions are refused |
| 4 | Stop orders: stop command, `OrderParked` and `StopTriggered` events, last trade price and parked stops in snapshots (ADR-0011). Earlier versions are refused |
| 5 | Self-trade prevention: group and mode on limit, market and stop commands, on resting orders and on parked stops in snapshots; `INVALID_SELF_TRADE_GROUP` and `SELF_TRADE_PREVENTION` codes (ADR-0012). Earlier versions are refused |
| 6 | Trading phases: `SetPhase` command, `PhaseChanged`, `PhaseChangeRejected` and `AuctionPriceDetermined` events, the phase in snapshots, `NOT_ALLOWED_IN_PHASE` and `INVALID_PHASE_TRANSITION` codes (ADR-0013). Earlier versions are refused |
| 7 | Venue controls: the instrument header carries the tick table and the volatility band; `DAY` time in force; `DAY_EXPIRED`, `VOLATILITY_INTERRUPTION` and `INVALID_TICK_SIZE` codes; `VolatilityInterruption` event; the day attribute of resting orders and the reference price in snapshots (ADR-0014). Earlier versions are refused |
| 8 | Members and risk: the member ID in every command record and event batch; `SetMemberLimits`, `SuspendMember` and `ResumeMember` commands and their events; the maker and taker member in `TradeExecuted`; the member of resting orders and parked stops; members' limits, suspensions and positions in snapshots; the risk reject codes and `KILL_SWITCH` (ADR-0015). Earlier versions are refused |
| 9 | Mass cancel: `MassCancel` command, `MassCancelled` event, `MASS_CANCEL` code (RK-006). Version 8 files are read too: version 9 only adds codes |
