# ADR-0006: FIX order-entry gateway

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

Exchanges take orders over FIX, not REST. Phase 3c adds a FIX 4.4 acceptor in front of the
`MatchingService` (ADR-0004). The member-facing contract is the
[Rules of Engagement](../interfaces/fix-rules-of-engagement.md).

## Decisions

### 1. QuickFIX/J in its own module

The session layer (logon, heartbeats, sequence numbers, resend, validation against the data dictionary)
is a solved problem; QuickFIX/J is the standard open-source implementation. The gateway lives in a
plain-Java `fix-gateway` module that depends on `engine-service`, so it can run on its own or be hosted
by the Spring Boot application next to the REST API, sharing the same `MatchingService`.

### 2. FIX 4.4, sessions declared in configuration

FIX 4.4 is still the most widely deployed version for equity order entry. Each member session
(`TargetCompID`) is declared in the venue configuration, as exchanges provision sessions in advance;
there are no dynamic sessions. Message stores are files, so sequence numbers and unsent messages
survive restarts and disconnections.

### 3. The gateway owns order identity for FIX

The engine knows orders by `(symbol, orderId)`. FIX members know them by `ClOrdID`. The gateway keeps
the mapping, and the owner of every order it created, so it can:
- reject duplicate `ClOrdID`s per session;
- resolve `OrigClOrdID` on cancels and status requests, and refuse to cancel another member's order;
- route a maker's fill to the maker's session, whichever session caused the trade.

The mapping is in memory for now (see Consequences). Updates happen on the instrument's writer thread,
in the future's completion, so all the reports of one order are produced in event order.

### 4. Reports follow the event stream

Each event becomes at most one ExecutionReport. `ExecID` is `<symbol>-<event sequence>`, which is
unique and stable across replays. `CumQty`, `LeavesQty` and `AvgPx` are tracked per order, with exact
decimal arithmetic. `TransactTime` is the command's journaled timestamp, at microsecond precision.

### 5. Never claim a rejection that may not be true

Refusals before the engine (unknown symbol, off tick, overload, halt, shutdown) and engine rejections
are `ExecType=Rejected`. A failed journal write is not a rejection: the order may exist. It is
reported as `Pending New` with `Text=OUTCOME_UNKNOWN`, and the member resolves it with a status
request after recovery.

### 6. A conformance suite with a real FIX client

Tests run a QuickFIX/J initiator against the acceptor over a socket, like a member's certification:
logon, orders, fills on both sides, cancels, status, rejections, malformed messages, and delivery of
fills that happened while a member was disconnected.

## Consequences

- ➕ Members get `ClOrdID`, fills on their resting orders and status requests, which REST lacks.
- ➕ Messages missed during a disconnection are recovered by standard FIX resend.
- ➖ Order ownership is not journaled: after a restart, FIX cannot report fills of orders entered
  before it. Phase 5 adds the member to each journaled command and removes this gap.
- ➖ No authentication until phase 8: sessions are identified by CompIDs only. The acceptor therefore
  listens on the loopback interface by default (`lob.fix.bind-address`, `LOB_BIND_ADDRESS`).
