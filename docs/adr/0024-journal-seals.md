# ADR-0024: Sealing the command journal

- **Status:** Accepted
- **Date:** 2026-11-28

## Context

The journal is the venue's record of every order (RTS 24 keeps it five years) and the source of truth from which
the book, the trades and every report are rebuilt. Frames carry a CRC, which finds accidental damage but not a
deliberate edit: anyone who can write the files can change a command and its CRC, and recovery accepts it. The
threat model listed this as open. Rule: RS-013.

## Decisions

### 1. Sign the SHA-256 of the journal's prefix

A seal signs the SHA-256 of the journal's first *n* bytes, with the length, the last command sequence and the time.
Any change to those bytes, a removed byte or a rewritten frame, breaks every seal from there on. Hashing the raw
bytes covers the header and every frame alike, and needs no change to the journal format: seals live in their own
file, and a journal that was never sealed reads as before.

A chain of per-record hashes would let one check a single record; the venue reads whole journals anyway (recovery
reads the command journal from its start), so the prefix hash is simpler and enough.

### 2. Ed25519, the venue's key

An HMAC would need the verifier to hold the secret that also forges seals. With a signature, the venue keeps the
private key and an auditor or a regulator checks with the public key. Ed25519 is in the JDK, fast (tens of
microseconds a signature) and has no parameters to get wrong.

### 3. At most every second, over flushed bytes only

Signing every command would add its cost to every command. Sealing at most every interval (1 s), plus at every
snapshot and at close, keeps the cost to one signature a second per instrument; the commands since the last seal
are the window an attacker could change unnoticed. A seal is written only after the journal is flushed, so a crash
can never leave a seal over bytes recovery then cuts away as a torn tail, which would look like tampering.

### 4. Checked at start-up, refused on mismatch

Recovery reads the whole command journal; the hash comes with it. A journal that does not match its seals stops the
instrument from starting, like a corrupt journal (RS-004): continuing would build the book, and every later record,
on altered history.

## Consequences

- An edited, truncated or replaced command journal is found at the next start and by `JournalVerify`, except in
  its last unsealed second.
- The private key becomes something to protect: whoever holds it can rewrite history and reseal it. It belongs in a
  secret store, apart from the journals.
- The event journal and snapshots are not sealed: they are derived, and recovery checks them against the commands
  (RS-007, RS-010).
