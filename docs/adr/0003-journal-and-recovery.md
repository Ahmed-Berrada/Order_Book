# ADR-0003: Journal, snapshots and recovery

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

Phase 2 makes the engine durable. The guarantees are specified in the rulebook chapter
[`resilience.md`](../rulebook/resilience.md) (RS-001 to RS-010) and the file formats in
[`journal-format.md`](../interfaces/journal-format.md). Determinism (CT-015) is what makes the design
simple: the state of an instrument is a pure function of its command stream, so storing the commands
is enough to rebuild everything else.

## Decisions

### 1. A separate `engine-journal` module

Durability needs file I/O, which `engine-core` forbids (ADR-0001, enforced by ArchUnit). The journal is
therefore a separate module that depends on the core. Like the core, it is plain Java: Spring is
banned by `maven-enforcer`, and there is no logging framework (outcomes such as a recovery report
are returned to the caller instead).

The core only gained `MatchingEngine.snapshot()` and `MatchingEngine.restore(EngineSnapshot)`, which
copy state in and out without doing any I/O.

### 2. Write-ahead command journal, one per instrument

`JournaledEngine.process(command)`:
1. stamps the command with the ingress time (RS-006);
2. appends it to `commands.journal` and, with `FsyncPolicy.EVERY_COMMAND`, flushes it to the device;
3. processes it with the in-memory engine;
4. appends the events to `events.journal` (not flushed: they can be regenerated);
5. returns the events, which is the acknowledgement.

Every command is journaled, including those the engine rejects, because a rejected order is still an
order the venue must keep a record of (MiFID II RTS 24).

One journal per instrument matches the single-writer model: each instrument's thread owns its files,
with no locks and no interleaving between instruments.

### 3. Timestamps belong to the command, not to each event

ADR-0002 deferred timestamps. A timestamp read inside the engine would make it non-deterministic, so
time is read once, at ingress, through an injected `TimeSource`, and journaled with the command.
All events of a command share it, which is also how venues report `TransactTime` for the fills of one
aggressive order. The core's event records therefore stay unchanged; the timestamp lives in
`EventBatch`. If the clock steps backwards, the previous timestamp is reused, so timestamps never
decrease.

The granularity is one microsecond, as RTS 25 requires of a venue. The *accuracy* (RTS 25 asks for
100 µs divergence from UTC) depends on how the host clock is synchronised (PTP or NTP), which is an
operations decision for phase 9.

### 4. Frames with checksums; torn tails versus corruption

Every record is a frame `[length][CRC32C][payload]`. On recovery:
- an incomplete last frame, or a last frame that fails its checksum, is a **torn tail**: a write that
  was interrupted by the crash, so it was never acknowledged. It is cut off (RS-003);
- a damaged frame followed by more data, a gap in sequence numbers or a decreasing timestamp is
  **corruption**: recovery stops with `JournalCorruptedException` (RS-004). Starting on a journal that
  is silently missing records would be worse than not starting.

This is the same policy as the write-ahead logs of most databases.

### 5. The event journal is derived but kept

Events can always be regenerated from the commands, so the event journal is not needed for recovery.
It is kept because it is the record of what the venue *actually* told its members (RS-007), and
because comparing it with regenerated events proves that a new version of the engine still reproduces
history exactly (`lob-replay --verify`, RS-009).

If the process dies after journaling a command but before writing its events, recovery regenerates
the missing batches. An event journal that goes *beyond* the command journal can only mean data loss,
and is treated as corruption.

### 6. Snapshots speed up recovery; they are never the source of truth

A snapshot holds the full engine state after a given command. It is written to a temporary file,
flushed, atomically renamed and the directory flushed, so a crash leaves either the old set of
snapshots or the new one. The two newest are kept.

On recovery the newest snapshot that loads cleanly is used, provided the event journal reaches it:
otherwise the event batches before it could not be regenerated. Recovery then replays the commands
after it. Any doubtful snapshot is skipped, which costs time, never correctness (RS-005).

### 7. Journals are never truncated

MiFID II requires order records to be kept for five years. The engine therefore never deletes or
rewrites journal records; the only bytes it ever removes are a torn tail. Splitting journals into
segments and archiving them is an operations task (phase 9).

### 8. Fail-stop on I/O errors

If a write fails, the `JournaledEngine` refuses every further command. It can no longer prove what it
has recorded, and continuing could acknowledge orders that would be lost. Restarting runs recovery,
which re-establishes a known state.

A command whose write failed may or may not have reached the journal. Its sender got an error, not an
acknowledgement, and must check the order's status after recovery, as with any exchange. Order status
queries arrive with the FIX gateway (phase 3).

## Testing

| What | How |
|---|---|
| Process crash | `ProcessCrashTest` starts a second JVM, kills it with SIGKILL after N acknowledgements (sometimes during a snapshot), recovers, and checks that no acknowledged command is lost and the state matches a fresh replay |
| Crash at any byte | jqwik property: random command streams, random snapshot intervals, both journals cut at random lengths; recovery must rebuild exactly the complete commands |
| Corruption | Flipped bytes, sequence gaps, decreasing timestamps, swapped files and a foreign instrument are each refused |
| Snapshots | Fallback when damaged, foreign, empty or ahead of the event journal; temporary leftovers cleaned |
| Formats | Round-trip properties for every command, event and snapshot; invalid codes and lengths rejected |

**Limits of what the tests show.** A SIGKILL leaves the operating system's page cache intact, so it
proves process-crash safety only. Power-loss safety depends on `fsync` doing what the operating system
promises; no test here can show it, and a mutation test that removes an `fsync` call cannot be caught
either. Removing a flush or a `close()` therefore accounts for most of the surviving mutants in this
module.

## Consequences

- ➕ No acknowledged order is lost in a process crash, or, with `EVERY_COMMAND`, in a power loss.
- ➕ The book can be reconstructed at any command, for audits and incident analysis.
- ➕ A code change that alters past behaviour is caught by `lob-replay --verify`.
- ➖ `EVERY_COMMAND` costs one device flush per command, which is typically tens of microseconds on an
  NVMe drive and far more on a network disk. Phase 6 will measure it and evaluate batching (group commit).
- ➖ Recovery time grows with the journal since the last snapshot. The snapshot interval controls it.
- ➖ Records are encoded with `DataOutputStream`, which allocates. That is acceptable until phase 6
  replaces it with a zero-allocation encoding.
