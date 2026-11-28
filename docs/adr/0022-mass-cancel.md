# ADR-0022: Mass cancel as an engine command

- **Status:** Accepted
- **Date:** 2026-11-28

## Context

RTS 6 art. 12 expects a member to be able to withdraw all its orders at once, and FIX members expect
`OrderMassCancelRequest` (`35=q`). The venue had only the operator's kill switch (RK-004), which also suspends the
member. Rule: RK-006.

## Decisions

### 1. One command in the engine, not a loop in the gateway

The gateway could send a cancel for each order it knows. It would miss orders it does not know (entered over REST,
or before a restart: the gateway's registry is in memory), and orders entered while the loop runs. A `MassCancel`
command is processed by the instrument's thread like any other: it cancels exactly the orders open at that point
of the sequence, is journaled once, and replays the same way. It reuses the kill switch's cancel loop.

### 2. For the member it is processed for; for the operator, everyone

The command names no member: it applies to the member that submits it, so a member cannot cancel another's
orders (MB-003). Submitted by the operator (member `0`), it cancels every member's orders on that side: the
operator's tool to empty a book without suspending anyone. Over REST, an `ADMIN` who forgets `X-Member-Id` acts as
the operator, so emptying the book needs `allMembers=true` as well: a forgotten header must not do it.

### 3. Never refused

It is accepted in every phase and while the member is suspended, and the throttles (OE-008, OE-009) never refuse
it, like a single cancel: withdrawing must always be possible.

### 4. Journal format 9 reads format 8

The command and its event add codes; nothing changes in the existing layouts. Earlier format changes refused the
previous version because no production journal existed. Now that the venue can be deployed, format 9 also reads
format 8 files, so an upgrade does not lose a venue's journals. A version 8 file is not rewritten: version 9
records are appended to it under its version 8 header, so the header says what a file was created with, not what
it contains. Gating each code on the header's version would protect a rollback, but a rollback after a mass cancel
cannot work anyway (the old binary cannot apply the command), so the journal-format page says to back up before an
upgrade instead.

## Consequences

- REST: `DELETE /api/v1/instruments/{symbol}/orders`, optionally `?side=`.
- FIX: `35=q` for one instrument (`530=1`) or all (`530=7`), each order reported cancelled, then one
  OrderMassCancelReport (`35=r`) with the count. FIX 4.4 has no "other" reject reason, so `532` is sent only when a
  FIX 4.4 value fits, with the reason always in `Text`.
- A mass cancel over every instrument is one command per instrument: each is atomic in its book, not across books.
  If an instrument's outcome is unknown (a failed write), FIX never reports the request rejected, since the
  orders may be cancelled once the instrument recovers (ADR-0006 §5): the report names that instrument in `Text`.
