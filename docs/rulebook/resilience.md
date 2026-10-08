# Rulebook: resilience and record keeping

This chapter specifies what the venue guarantees when it crashes and restarts, and which records it
keeps. As in the other chapters, each rule ID is cited by at least one test through
`@Rulebook("RS-xxx")`. The design behind it is in
[ADR-0003](../adr/0003-journal-and-recovery.md); the binary formats are in
[`docs/interfaces/journal-format.md`](../interfaces/journal-format.md).

Terms used below:
- **Command journal**: append-only file of every command an instrument received, in order.
- **Event journal**: append-only file of the events each command produced.
- **Acknowledged**: the engine has returned the command's events to the caller.

## Durability

**RS-001: Write-ahead.** A command is written to the command journal *before* it is processed, and is
acknowledged only after that write. With the `EVERY_COMMAND` sync policy the write is flushed to the
storage device first, so an acknowledged command survives a power loss. With the `OS` policy it
survives a crash of the process, but not of the machine.

**RS-002: Recovery.** After a restart, the engine is in exactly the state obtained by applying the
commands of the command journal, in order, to a new engine. No acknowledged command is lost, and
order IDs and event sequence numbers continue where they stopped.

**RS-003: Torn tail.** If the last record of a journal is incomplete or fails its checksum, it belongs to
a command that was never acknowledged. Recovery discards it and continues.

**RS-004: Corruption.** A damaged record that is *not* the last one, a gap in command sequence numbers,
or a journal written for a different instrument stops recovery with an error. The engine never starts
on a journal it cannot read completely.

**RS-005: Snapshots.** Restoring a snapshot and replaying the commands that follow it gives the same
state as replaying the whole journal. A snapshot that is damaged, belongs to another instrument, or
is ahead of the journal is ignored, and an older snapshot or a full replay is used instead.

## Records

**RS-006: Timestamps.** Every command is stamped once, when it enters the engine, in microseconds since
the Unix epoch (UTC). Timestamps never decrease: if the clock steps backwards, the previous timestamp
is reused. The timestamp is journaled with the command and applies to all of its events. MiFID II
RTS 25 requires this granularity; the accuracy of the clock itself is an operations matter.

**RS-007: Event record.** The events of every processed command are stored in the event journal, with
the command's sequence number and timestamp. After recovery, the event journal holds the events of
every command in the command journal.

**RS-008: Retention.** Journals are append-only. The engine never deletes or rewrites a journal record,
except to discard a torn tail (RS-003). Archiving old journals is an operations task (phase 9).

**RS-009: Replay verification.** The replay tool rebuilds the book at any command sequence number from
the command journal alone, and can verify that regenerating the events gives exactly the contents of
the event journal.

**RS-010: Snapshot fidelity.** An engine restored from a snapshot cannot be distinguished from the
engine the snapshot was taken from: same resting orders and queue positions, same next order ID, same
next event sequence number.
