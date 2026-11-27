# ADR-0015: Members and pre-trade risk in the engine

- **Status:** Accepted
- **Date:** 2026-11-27

## Context

Phase 5 adds the member hierarchy and the pre-trade controls regulators ask for. Rules:
[`members-and-risk.md`](../rulebook/members-and-risk.md) (MB-001 to MB-004, RK-001 to RK-005).

## Decisions

### 1. The checks are part of the engine

A check that depends on state (a net position, an open-order count) must give the same answer when the
journal is replayed. The easiest way to guarantee that is the way everything else is guaranteed: the state
is the engine's, the limits are set by journaled commands, and the decision is made by the same code in
the same order. A gate in front of the engine would need its own journal, snapshot and recovery, and would
be a second place where a replay could differ.

### 2. Rejections are ordinary rejections

A risk rejection is an `OrderRejected` (or `ReplaceRejected`) with a new reason. It consumes an order ID
and a sequence number like every other rejection, appears in the event journal, and reaches every channel
through the existing mapping. That is also the audit trail RTS 6 asks for.

### 3. The member is an argument, not a field of every command

`process(command, member)` keeps the commands as they are and records the member where it belongs: with
the order, and in the journal entry. Members are unauthenticated numbers until phase 8; what the engine
guarantees is that, given a member, the limits hold.

### 4. Only what is deterministic

Position, open orders, quantity, notional and price distance are functions of commands. Rates (orders per
second) and the order-to-trade ratio per day are functions of time. The engine has no clock (CT-015), and a
limit enforced against a clock outside it could not be replayed. Rate limiting belongs at ingress, in
phase 9 with the other operational controls, where throwing a request away needs no journal.

### 5. The kill switch is a command

Suspending a member cancels its orders, which must appear in the event stream and in the journal; it is
therefore a command (`SuspendMember`), not an operator action beside the engine.

### 6. Ownership of cancels

Without it, any member could cancel any order by guessing an ID. `UNKNOWN_ORDER` is also what an absent
order gives, so the answer reveals nothing. Member `0` is the operator and the only one that may act on
others' orders.

### 7. Journal format version 8

The entry carries the member; commands, events, orders and snapshots change. Earlier versions are refused
(ADR-0009 §7).

## Consequences

- ➕ Limits, positions and the kill switch replay exactly and are in every snapshot.
- ➕ One mechanism for REST, FIX and any later channel.
- ➖ No rate or order-to-trade limits yet (phase 9).
- ➖ Notional and position limits are per instrument: a venue-wide exposure would need state across the
  instruments' threads.
