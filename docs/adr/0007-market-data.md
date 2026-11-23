# ADR-0007: Market data feed

- **Status:** Accepted
- **Date:** 2026-11-15

## Context

Phase 3d publishes the order books. Rules: [`market-data.md`](../rulebook/market-data.md); wire
format: [`market-data-websocket.md`](../interfaces/market-data-websocket.md).

## Decisions

### 1. The feed is built from the event stream, not by reading the engine

A `market-data` module (plain Java) listens to the service's events (ADR-0004 §7) and maintains its own
image of each book: the resting orders it has seen (`OrderRested`), reduced by fills and removed by
cancels, aggregated into levels. Reading the engine's book after each command would require the
instrument's thread to copy it; deriving the image from events costs nothing on that thread beyond
the listener call, is deterministic, and can be replayed from the journal. A property test checks the
image against the engine's depth after every command (MD-006).

The image starts from the recovered engine state, taken on the instrument's thread, with the command
sequence it reflects; events already included in that state are skipped (MD-007).

### 2. Snapshot plus incremental updates, with a feed sequence

This is how exchange feeds work: a snapshot gives the starting point, updates give only what changed,
and a gap-free sequence number lets subscribers detect loss. The feed sequence is separate from the
command sequence because commands that change nothing publish nothing (MD-005). A subscription is
added under the same lock that publishes, so its snapshot and its first update are contiguous (MD-004).

### 3. Publishing never blocks the matching thread

The listener runs on the instrument's writer thread. Each subscriber therefore gets a bounded queue,
drained by its own virtual thread that writes to the network. If the queue is full, the subscriber
is disconnected (MD-008). A slow or malicious client can only lose its own connection.

### 4. WebSocket, JSON, decimal prices

The first transport is WebSocket with JSON, using the REST API's conventions (decimal strings,
microsecond timestamps), so browsers and scripts can consume it directly. A binary multicast feed
(ITCH-like, with a replay service), the transport of low-latency members, comes later with the
performance work; it will publish the same messages from the same module.

## Consequences

- ➕ The feed is correct by construction relative to the engine, and the property test enforces it.
- ➕ Market data adds no work to the matching path beyond one listener call per command.
- ➖ The image duplicates the book's resting orders in memory (a few tens of bytes per order).
- ➖ JSON over WebSocket is not a latency-competitive feed; the binary feed addresses that later.
- ➖ No authentication or entitlements yet (phase 8); the endpoint listens on localhost by default,
  like the rest of the venue.
