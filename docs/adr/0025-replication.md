# ADR-0025: Synchronous replication to one standby, promoted by an operator

- **Status:** Accepted
- **Date:** 2026-11-28

## Context

ADR-0018 §5 designed high availability and left it unbuilt: the venue's promise stopped at the volume. If the machine
or its disk is lost, so are the commands it acknowledged. Rules: RS-014 to RS-016.

## Decisions

### 1. Replicate the command journal's bytes, nothing else

The engine is deterministic (RS-006): the command journal is the whole state. The standby keeps a byte-for-byte copy
of it, and of its seals (RS-013), and never interprets it. Promoted, its directory is opened like any venue's, and
recovery rebuilds the event journal, the snapshots and the book (RS-002, RS-007). There is no second engine to keep in
step, no protocol of events, and the copy is checked the way any journal is: frames, CRCs, seals.

### 2. Synchronous, at the acknowledgement points that exist

The primary acknowledges nothing until the standby has the bytes on its device (RPO 0). Group commit (ADR-0020)
already batches acknowledgements, so a group costs one round trip to the standby as it costs one flush: under load
the round trip is shared like the flush. The primary keeps no copy of what it sends: it reads the bytes the standby
lacks from its own files, from the page cache, so catching up after a reconnection is the same code as replicating
live. Comparing whole files and catching up can take far longer than one acknowledgement may, so it is done when the
instrument opens, under a deadline of its own (10 minutes); every exchange has a deadline, enforced by closing the
connection, so a standby that stops reading cannot hold the instrument's thread either.

### 3. Fail closed

If the standby is gone, slow beyond the timeout, or refuses, the instrument halts (RS-015) and the waiting commands
have an unknown outcome, as on a failed flush. Going on with one copy would quietly break the promise that made the
standby worth having. Running alone is a restart without a standby, a decision an operator makes and can see.

### 4. An operator promotes; terms fence

Automatic failover needs a consensus store and fault testing that this repository cannot run (ADR-0018 §5): done
without them it can make two primaries. Promotion is therefore a command an operator runs, with the standby stopped.
It raises the term in each instrument's directory; a standby refuses any primary of a lower term. A deposed primary
that is still running can reach no standby that has seen the new term, and a new standby is seeded with it before
it is reachable, so none will confirm its commands, so by RS-015 it acknowledges none: two
venues cannot both acknowledge.

### 5. Authenticated, not encrypted

Each side proves a shared secret to the other with an HMAC over both sides' fresh nonces, the instrument and the term,
and from these derives a key for the connection. Every later message carries an HMAC under that key of its direction,
its number in that direction, its type and its body. So nothing but the primary can write to the standby or raise its
term, nothing but the standby can confirm a command, and nothing can be inserted, replayed or changed on the way. A
newer authenticated connection for an instrument, at its term or higher, replaces the one the standby holds, so a
restarted primary is not locked out by its own dead connection; connections are capped, and one that has not
authenticated 10 seconds after it opened is closed. An authenticated connection has no read timeout, since a quiet
instrument sends nothing for hours: TCP keepalive finds a dead peer. The standby forces the commands before the seals
that cover them, and every new file and directory before its first confirmation. It is not encrypted: it runs on a private network or a tunnel between the two machines, like the journal's
storage network would.

## Consequences

- Losing the primary's machine or disk loses no acknowledged command; the standby holds them all.
- Each group of commands waits for one round trip to the standby as well as one flush.
- Failover is manual: the outage lasts until an operator promotes the standby (the runbook gives the steps), plus
  its recovery from the journal (seconds for a day of commands, from the start of the journal: snapshots are not
  replicated).
- The old primary, once deposed, is not a standby of the new one: its journal may hold commands that were never
  confirmed. It is resynchronised from empty.
