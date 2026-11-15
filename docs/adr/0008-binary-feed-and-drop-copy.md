# ADR-0008: Binary market data feed and FIX drop copy

- **Status:** Accepted
- **Date:** 2026-11-15

## Context

Phase 3e adds the two feeds professional members expect besides order entry: a low-latency binary
market data feed, and a drop copy of their executions for risk and back-office systems. Rules:
[`feeds.md`](../rulebook/feeds.md).

## Decisions

### 1. ITCH over MoldUDP64, simplified to level 2

Nasdaq's ITCH messages on MoldUDP64 packets are the reference design for exchange feeds: fixed binary
layouts, one sequenced stream, UDP for speed and a TCP side channel to recover losses. The feed keeps
that shape but publishes level-2 messages (levels and trades), the same content as the WebSocket feed,
because the venue does not disclose individual orders (MD-001). Messages are written by hand with
`ByteBuffer`; generated codecs (SBE) are a performance-phase option once the format is measured.

### 2. One stream, sequenced by a single sender

All instruments share one stream. The binary publisher subscribes to every instrument through the
existing `MarketDataPublisher` with a `QueuedSubscriber` each, all feeding one sender thread, which
assigns stream sequence numbers and packs updates into packets. Publishing therefore never touches the
instruments' threads (MD-008), and the stream order is the order the sender received the updates in;
each instrument's own order is preserved.

### 3. Recovery by retransmission and snapshot

The sender keeps the last messages (one million by default) in a ring, encoded, so a retransmission
returns the exact bytes first sent (BF-004). Snapshots come from the same book images, with each
instrument's feed sequence, so a consumer can line them up with the stream (BF-005). Refusing a
request outside the window, rather than answering partially, keeps the consumer's logic simple: it
falls back to a snapshot.

### 4. UDP unicast by default, multicast by configuration

Multicast needs network support that development machines and CI runners usually lack, so the default
target is a unicast address on the loopback interface. A multicast group is a configuration change; the
packets are the same.

### 5. Drop copy inside the FIX gateway

The gateway already produces every ExecutionReport in one place, so the copy is a second send from
there to each provisioned drop copy session, marked with `CopyMsgIndicator` and `OnBehalfOfCompID`.
Drop copy sessions are read-only. Per-member drop copy entitlements come with members in phase 5;
until then a drop copy session sees every member's executions, which suits a venue operations desk.

## Consequences

- ➕ Low-latency consumers get a compact sequenced feed with standard recovery.
- ➕ Risk systems get every execution in real time, through the protocol they already speak.
- ➖ The stream order across instruments is not the matching order across instruments; there is no
  such order, since instruments are independent (ADR-0001).
- ➖ No entitlements yet: anyone who can reach the feed or a drop copy session sees everything.
  Everything listens on localhost by default until phase 8.
