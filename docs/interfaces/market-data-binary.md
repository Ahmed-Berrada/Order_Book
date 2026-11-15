# Binary market data feed (version 1)

A sequenced binary feed on UDP, modelled on Nasdaq ITCH over MoldUDP64, with a TCP service for
retransmission and snapshots. Rules: rulebook chapter [`feeds.md`](../rulebook/feeds.md); design:
[ADR-0008](../adr/0008-binary-feed-and-drop-copy.md).

All integers are big-endian. Prices are signed 64-bit **ticks**; the directory gives each
instrument's tick size. Quantities are lots.

## Transport

| Channel | Default | Notes |
|---|---|---|
| Real-time stream | UDP to `127.0.0.1:9900` | Multicast group or unicast address, configurable |
| Retransmission and snapshot | TCP `127.0.0.1:9901` | Request / response |

## Packet (UDP)

| Offset | Size | Field |
|---|---|---|
| 0 | 10 | session, ASCII, space-padded (one per venue start) |
| 10 | 8 | sequence number of the first message |
| 18 | 2 | message count; `0` = heartbeat (sent after 1 s idle; sequence = next expected) |
| 20 | … | message blocks: `[u16 length][message]` |

Packets stay below 1,400 bytes. An update is never split across packets (BF-003); updates are
batched while the next one fits.

## Messages

Every message starts with its type (1 byte, ASCII) and the instrument's locate code (u16).

| Type | Message | Fields after type and locate |
|---|---|---|
| `R` | Instrument directory | symbol (8, ASCII, space-padded), tick size mantissa (i64), tick size exponent (i8): tick size = mantissa × 10^exponent |
| `L` | Level | side (`B`/`S`), price (i64), quantity (i64), orders (i32). Quantity 0 removes the level |
| `T` | Trade | aggressor side (`B`/`S`), price (i64), quantity (i64) |
| `U` | End of update | feed sequence (i64), command sequence (i64), timestamp (i64, µs since epoch UTC) |
| `K` | End of snapshot (TCP only) | feed sequence (i64), command sequence (i64) |

An update is: its `L` messages, its `T` messages, then `U`. Apply them when `U` arrives.

## TCP service

Each request is one frame: `[u32 length][payload]`; each response likewise.

| Request | Payload | Response |
|---|---|---|
| Retransmit | `'X'`, from sequence (u64), count (u16, at most 1,000) | a packet (same layout as UDP) with the same messages and sequence numbers |
| Snapshot | `'S'` | a packet with sequence 0: for each instrument `R`, its `L` levels best first, then `K` |

A retransmit outside the retention window (default: the last 1,000,000 messages) is answered with a
packet whose count is `0xFFFF` (refused). The session field identifies the stream; retransmission is
only valid within the same session.

## Recovering

1. Listen to the stream; buffer what arrives.
2. Request a snapshot; for each instrument, drop buffered updates whose feed sequence is not above the
   snapshot's.
3. Apply updates in order. On a gap in the stream sequence, request a retransmission of the missing
   range; if it is refused, start again from step 2.
