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
acknowledged only after that write. With the `EVERY_COMMAND` sync policy each write is flushed to the
storage device before its command is acknowledged, so an acknowledged command survives a power loss. The
`GROUP` policy makes the same promise for less: the commands waiting together are written, processed in
order, flushed to the device with one flush, and only then acknowledged; nothing about a command of the
group (its acknowledgement, its fills, its market data, a read that sees it) leaves the venue before that
flush, and its events are written to the event journal only after it, so the event journal is never ahead of
the command journal on the device. If the flush fails, no command of the group is acknowledged, each has an
unknown outcome, and the instrument halts (OE-006). With the `OS` policy an
acknowledged command survives a crash of the process, but not of the machine.

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

**RS-011: One writer.** An instrument's journal directory is opened by one engine at a time. Opening a
directory that another engine, in this process or another, holds fails with an error and changes nothing:
two writers would interleave frames and corrupt the journal. The lock is released when the engine is
closed, or by the operating system if the process dies, so a crash leaves no stale lock.

**RS-012: The venue's acknowledgement.** An order the venue has acknowledged to a member (`201 Created` over
REST, an `ExecutionReport` over FIX, both answered from the same journaled command) is in its instrument's
command journal. If the venue's process is killed at any moment (`kill -9`), the order is there after the
restart, with the same order ID, and order IDs continue after it. If an instrument's journal can no longer be
written, for example because its storage is full, that instrument halts (OE-006) and the others keep trading:
- if the command journal cannot be written, that command is reported with an unknown outcome;
- if a later write fails (the event journal or a snapshot), the command is journaled, so it is acknowledged,
  and the commands after it are refused with `HALTED`; recovery completes the event journal (RS-007).

The orders acknowledged before the failure are kept, and the log names the I/O error. A restart once the cause
is fixed recovers the instrument.

## Tamper evidence

**RS-013: Sealed command journal.** With a sealing key configured, the venue seals each instrument's command journal
in `commands.seal`: a record, signed with the venue's Ed25519 key, of the journal's length, the SHA-256 of the
journal up to that length, the last command sequence and the time. It seals at most every interval (1 s by
default) while commands arrive, at every snapshot, at close and after 10,000 commands, and only over bytes already
flushed to the device. At start-up every seal is checked against the journal as recovered; the instrument does not
start (`JournalCorruptedException`), since its journal was changed after it was sealed, when a seal does not match,
is not signed by the key, covers bytes the journal no longer holds, or is damaged; or when a journal that holds
commands has no seal at all, unless sealing is being turned on for it (`seal-existing-journal`). A last seal cut
short by a crash is discarded. `JournalVerify` checks a copy of a journal with the public key alone.

Not detected by the seals alone: changes after the last seal (at most one interval or 10,000 commands); a journal
cut back exactly to an earlier seal, or replaced by an older copy together with its seal file, which loses the
commands since; and anything done by someone who holds the private key. Rollback is detected against a command
sequence recorded outside the venue, which `JournalVerify --expect` compares with the latest seal.

## Replication

**RS-014: Synchronous replication.** With a standby configured, an instrument's command journal (and its seal file,
RS-013) is copied byte for byte to the standby before any command is acknowledged: at each acknowledgement point
(each command with `EVERY_COMMAND` or `OS`, each group with `GROUP`) the primary sends the bytes the standby lacks and
waits until the standby has them on its device. An acknowledged command is therefore on two machines: losing either
loses no acknowledged command. A standby that connects or reconnects is first checked to hold a prefix of the
primary's files, then brought up to date; one that holds anything else is refused. The link is authenticated with a
secret both sides share. The standby stores the bytes and nothing else; promoted, its directory is opened as any
venue's, and recovery rebuilds the event journal, snapshots and book from the commands (RS-002, RS-007).

**RS-015: No standby, no acknowledgement.** If the standby cannot be reached, refuses, or does not confirm within the
timeout (2 s by default), the commands waiting have an unknown outcome (they are on the primary, perhaps not on the
standby) and the instrument halts, as on a failed flush (OE-006); the venue never goes on acknowledging with one copy
by itself. Running without a standby is a restart without one configured.

**RS-016: Promotion and fencing.** Each instrument's directory holds a term, the number of its latest promotion (1
until the first). Promoting a standby raises the term of every instrument it holds; it is done by an operator, with
the standby stopped, and the promoted standby is then started as the venue on the same directory. A standby refuses a
primary whose term is lower than its own, so a deposed primary cannot have its commands confirmed anywhere, and
therefore acknowledges none (RS-015). Who is primary is the operator's decision: nothing promotes automatically.

