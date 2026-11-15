# Market data over WebSocket (version 1)

A level-2 feed per instrument: one snapshot, then incremental updates. Rules: rulebook chapter
[`market-data.md`](../rulebook/market-data.md); design: [ADR-0007](../adr/0007-market-data.md).

## Connection

`ws://<host>:8080/api/v1/market-data/{symbol}`: one connection per instrument. An unknown symbol is
refused at the handshake. The server only sends; messages from the client are ignored.

Prices are decimal strings on the instrument's tick grid, quantities whole lots, timestamps UTC
ISO-8601 with microseconds, as in the REST API.

## Messages

### Snapshot (first message)

```json
{"type":"snapshot","symbol":"AAPL","feedSequence":41,"commandSequence":118,
 "bids":[{"price":"185.20","quantity":12,"orders":2}],
 "asks":[{"price":"185.25","quantity":4,"orders":1}]}
```

The whole book, best price first on each side. `feedSequence` is the last update it includes.

### Update

```json
{"type":"update","symbol":"AAPL","feedSequence":42,"commandSequence":119,
 "timestamp":"2026-11-15T14:31:07.123456Z",
 "bids":[],
 "asks":[{"price":"185.25","quantity":0,"orders":0}],
 "trades":[{"price":"185.25","quantity":4,"aggressor":"BUY"}],
 "bestBid":{"price":"185.20","quantity":12,"orders":2},
 "bestAsk":null}
```

- `feedSequence` is the snapshot's plus 1, then plus 1 per update (MD-005).
- `bids` / `asks` list only changed levels, best first; `quantity: 0` removes the level (MD-006).
- `trades` are the executions of the command, in order (MD-002).
- `bestBid` / `bestAsk` are the top of book after the command, `null` for an empty side (MD-003).

## Subscriber obligations

- **Check the sequence.** If an update's `feedSequence` is not the previous one plus 1, the local book is
  wrong: reconnect for a new snapshot.
- **Keep up.** The server buffers a bounded number of messages per connection. If the buffer fills,
  the connection is closed with status `1008` and reason `slow consumer` (MD-008).
