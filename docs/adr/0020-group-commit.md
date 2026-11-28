# ADR-0020: Group commit: one device flush for the commands that wait together

- **Status:** Accepted
- **Date:** 2026-11-28

## Context

Phase 6 measured the journal ([performance](../performance.md)): about 1.3 µs per command when the operating
system buffers writes, about 160 µs when each command is flushed to the device (`fsync: EVERY_COMMAND`). That
flush caps one instrument at roughly 6,000 commands a second, and it is the default because it is the only
policy under which an acknowledged order survives a power loss (RS-001). ADR-0016 §3 named group commit as the
lever, and ADR-0018 put it with replication. Replication is still a design; group commit does not depend on it.

## Decisions

### 1. A third policy, `GROUP`, with the promise of `EVERY_COMMAND`

`GROUP` does not trade durability for speed. An acknowledged command is on the storage device exactly as with
`EVERY_COMMAND`; what changes is how many commands one flush covers. `OS` remains the only policy that
acknowledges before the device has the data.

### 2. The group is whatever is already waiting: no timer, no added latency

The instrument's thread takes the first task from its queue and every task already behind it, up to 256. It
processes them in queue order, journaling each command (written, not flushed) and applying it to the engine,
then flushes the command journal once, then completes the tasks: acknowledgements, fills, listeners (market
data, drop copy). It never waits for a group to fill. With one client at a time, each group holds one command
and costs what `EVERY_COMMAND` costs. Under load, the commands that queued during one flush share the next.
Throughput then grows with load instead of the queue filling up.

The bound of 256 caps how long the first task of a group waits for the others to be processed (a few hundred
microseconds of matching), and the memory a group holds.

### 3. Nothing leaves before the flush

A command of the group is applied to the in-memory engine before the flush; later commands of the group see its
effects, as they would one at a time. No effect leaves the instrument's thread before the flush. That covers:

- the acknowledgement;
- the events given to listeners, and through them to the market-data feeds and drop copy;
- the metrics;
- the answer to a read queued in the same group.

Otherwise a trade could be published and then lost on a power loss, and the public tape would disagree with the
journal.

The commands' event batches, and a snapshot when one is due, are written only after the flush. Written before,
the operating system could put the event journal on the device ahead of its commands. Recovery refuses such a
journal as corrupt (ADR-0003 §5), so a power loss in the middle of a group would have stopped the instrument
from starting. Written after, the event journal can only be behind, which recovery completes (RS-007).

### 4. A failed flush halts the instrument, and the whole group's outcome is unknown

If the flush fails, the instrument halts (OE-006). Each command of the group fails as a failed write does today:
outcome unknown (`500 OUTCOME_UNKNOWN` over REST). The data may or may not be on the device, and recovery
decides. Commands of earlier groups were flushed and stay acknowledged.

The in-memory engine has applied the group's commands and cannot take them back. So from then on its state is
not trusted. Reads are refused with `HALTED` until the restart, and so are reads queued in the failed group,
since they would show commands of unknown outcome. Under `EVERY_COMMAND` this cannot happen: a command is
applied only after its flush.

An `Error` while a group is being processed (a stack overflow in a listener, say) fails every task of the group
that was not yet reported, and halts the instrument. Without that, those tasks would wait forever.

### 5. `GROUP` becomes the venue's default

It keeps `EVERY_COMMAND`'s guarantee and costs the same with one client, so there is no case where
`EVERY_COMMAND` is the better choice for a venue. `EVERY_COMMAND` stays available, and the engine's own default
(`JournalOptions.defaults()`, used by tools and tests) is unchanged.

## Consequences

- An instrument under load flushes once per group instead of once per command; the measured effect is in
  [performance](../performance.md).
- The time a command takes now includes waiting for the rest of its group to be processed, and for the flush.
  `lob_command_duration_seconds` measures processing only. The flush has its own metrics:
  `lob_journal_flush_duration_seconds` (the device's latency) and `lob_journal_group_size` (commands per
  flush).
- The tasks of the group being processed are no longer in the queue, so `lob_queue_depth` does not count up to
  255 of them. With a capacity of 10,000 that does not change what `QueueNearlyFull` means.
- A command and the commands queued with it share one fate if the device fails, where before only the command
  being written was in doubt.
