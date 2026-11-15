# Rulebook: market data

- **Status:** Draft

What the venue publishes about its order books, and the guarantees a subscriber can rely on. As in the
other chapters, each rule ID is cited by at least one test through `@Rulebook("MD-xxx")`. Design:
[ADR-0007](../adr/0007-market-data.md); wire format:
[`market-data-websocket.md`](../interfaces/market-data-websocket.md).

## Content

**MD-001: Anonymous level-2 book.** The feed shows, per price level and side, the total resting
quantity and the number of orders. It never shows order IDs, members or client order IDs.

**MD-002: Every trade is published.** Each execution is published with its price, quantity and the
aggressor's side, in execution order, in the same message as the book change it caused. This is the
venue's post-trade transparency (MiFID II RTS 1: trades are made public as close to real time as is
technically possible).

**MD-003: Top of book.** Every update carries the best bid and best ask, with their quantities, as
they stand after the command.

## Consistency

**MD-004: Snapshot, then updates.** A new subscriber first receives a snapshot of the whole book, then
every update after it, with none missing and none repeated.

**MD-005: Gap-free feed sequence.** Messages of an instrument carry a feed sequence number that
increases by 1 per update; the snapshot carries the sequence of the last update it includes. A
subscriber detects any loss by a gap. Updates also carry the command sequence of the journal (RS-001),
for correlation with order entry. Commands that change neither the book nor trades (rejected orders,
rejected cancels) produce no update.

**MD-006: Exact reconstruction.** Applying the updates to the snapshot, in order, reproduces exactly
the engine's book at every step. An update lists only the levels that changed, with their new total
quantity and order count; a quantity of 0 means the level is gone.

**MD-007: Recovery.** After a restart, the feed starts from the recovered book (RS-002).

## Fairness

**MD-008: Slow consumers.** Publishing never waits for a subscriber. A subscriber that falls too far
behind is disconnected instead of delaying the matching engine or other subscribers; it can subscribe
again and receives a fresh snapshot.
