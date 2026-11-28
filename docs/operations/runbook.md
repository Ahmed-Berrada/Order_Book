# Runbook

What an operator does when the venue needs attention. Each alert in
[`deploy/prometheus/alerts.yml`](../../deploy/prometheus/alerts.yml) links to a section here. Metrics and
probes: [observability](observability.md).

The venue is one process holding every instrument; each instrument has its own thread and its own
journal directory under `LOB_DATA_DIR` (`./data`, `/data` in the container). Operator actions go through
the REST API with an `ADMIN` user ([REST API](../interfaces/rest-api.md)).

## Before you act

1. Check the health: `GET /actuator/health/readiness` answers `200` with `UP`, or `503` with `DOWN`.
   Details are not shown to anonymous callers; the logs and the `lob_instrument_halted` metric say which
   instrument is down.
2. Read the last log lines of the process. A halt, a refused start or a failed logon is logged with its
   reason.
3. Do not delete or edit a journal file. Journals are the record of what the venue did (RS-008); the
   engine cuts a torn tail itself (RS-003).

## An instrument is halted

**Alert:** `InstrumentHalted` (`lob_instrument_halted == 1`). **Effect:** the instrument refuses every
command with `HALTED`; its book can still be read; other instruments trade normally (OE-006).

**Cause:** a write or flush of the instrument's journal failed, usually a full or failing disk. The engine
stops rather than trade without a record. Which commands have an unknown outcome (`500 OUTCOME_UNKNOWN` over
REST) depends on what failed (RS-012):

- **a command's write:** that command;
- **a group's flush** (`fsync: GROUP`): every command of the group, up to 256. Reads of the instrument are also
  refused with `HALTED` until the restart, since its memory holds those commands;
- **the event journal or a snapshot:** none; the command was journaled and acknowledged.

Members must check their orders after the restart.

1. Find the cause in the log (`Instrument <symbol> halted: its journal could not be written`, with the
   I/O error) and fix it: free or extend the
   volume, or replace the disk.
2. Restart the venue. Recovery replays the command journal (RS-002), discards a torn last record (RS-003),
   and the instrument trades again. The readiness probe goes back to `UP`.
3. Optional: check the journal with `lob-replay <instrument-dir> --verify` (README, "Replaying a journal")
   before reopening to members.
4. Tell members which instrument was halted and from when to when; the command journal shows the last
   command accepted.

## The venue does not start

**Alert:** `VenueDown` (Prometheus cannot scrape the venue for a minute). In Kubernetes the startup probe
fails and the pod restarts.

The venue refuses to start rather than start in an unsafe state. The log names the reason:

| Log message | Cause | Fix |
|---|---|---|
| `lob.security.enabled is true but no user is configured` | Access control is on and has no users (AC-001) | Configure `lob.security.users` (README, "Security"), or disable security explicitly for a test venue |
| `... is not a hash with an algorithm prefix such as {bcrypt}; plain text is refused` | A password is in plain text | Store a `{bcrypt}` hash (`HashPassword`, README) |
| `these ports would listen beyond loopback without TLS: [...]` | A port is bound beyond loopback in clear with security on (AC-010) | Configure TLS (`server.ssl.*`, `lob.fix.tls.*`), or, if an ingress or proxy terminates it, set `lob.security.tls-terminated-in-front=true` |
| `lob.fix.tls.enabled needs lob.fix.tls.key-store ...` / `FIX key store not readable` | FIX TLS without the venue's key store, or a path the process cannot read (AC-011) | Mount the key store and point `lob.fix.tls.key-store` at it |
| `the journal in <dir> is in use by another engine; only one writer may open it` | Another process holds the journal directory (RS-011) | Stop the other process. Two writers would corrupt the journal. A crashed process leaves no lock: the operating system releases it |
| `unsupported format version <n>` | The journal was written in an older format (before v8) | Replay it with the release that wrote it; formats are not migrated |
| `<file> belongs to <instrument>, not <instrument>` | The tick size, limits, tick regime or band of an instrument that already has a journal changed in the configuration | Restore the definition. A changed definition is a new instrument with a new directory |
| `checksum mismatch at offset <n>` or `invalid frame length` in the middle of a journal | The disk returned damaged data (RS-004) | Restore the instrument's directory from a backup; do not cut the file |
| `Address already in use` | Another process listens on the REST, FIX or feed port | Stop it or change the port |

## A volatility interruption

**Alert:** `VolatilityInterruption` (info). **Effect:** a trade would have been outside the instrument's
band around its reference price, so the instrument moved to `CALL` (VI-003). Orders queue and do not
match; the book may be crossed.

The engine has no clock, so the interruption lasts until an operator ends it (VI-005):

1. Look at the book (`GET /api/v1/instruments/{symbol}/book`) and decide whether the market is orderly.
   Members may cancel and enter orders during the call.
2. End the call: `PUT /api/v1/instruments/{symbol}/phase` with `{"phase":"CONTINUOUS"}`. The uncross trades
   at one price, sets the new reference price, and continuous trading resumes. The auction itself is not
   held to the band.
3. To stop trading instead: `{"phase":"CLOSED"}`. The uncross still runs; DAY orders expire.

## A member must be stopped

The kill switch suspends a member and cancels its open orders in every instrument:
`PUT /api/v1/members/{memberId}/suspension`. The member's new orders are refused until
`DELETE /api/v1/members/{memberId}/suspension`. Its FIX sessions receive a cancel for each order
(`Text=KILL_SWITCH`). Limits are changed with `PUT /api/v1/members/{memberId}/limits`.

## A member is throttled

`lob_throttled_total` grows when a member's orders or replaces are refused before the queue: `THROTTLED`, more
than `lob.throttle.messages-per-second` (OE-008), or `ORDER_TO_TRADE_LIMIT`, too many messages per trade in an
instrument today (OE-009). Its cancels are still accepted, so the member can always withdraw. Nothing is
journaled for a refused command. The `member` tag says who. A rate refusal lasts a fraction of a second; a
ratio refusal lasts until the member's trades bring the ratio back under the limit, at most until the end of
the UTC day or a restart, which resets the counts. Contact the member before raising a limit: a sudden
flow is often a runaway algorithm, and the kill switch is the next step. The limits are venue settings
(`lob.throttle.*`, `0` switches one off) and need a restart to change.

## A user or FIX session is locked out

After `lob.security.max-failures` failed authentications in a row (5 by default) for one name from one address,
that name is refused from that address for `lob.security.lockout` (1 minute): REST answers `429` with reason
`LOCKED_OUT`, a FIX Logon is rejected (AC-009). The log has a `WARN` line for each failure and one when the
lockout starts (`REST 'alice' locked out from 10.0.0.1 …`, or `FIX 'MEMBER1' …`). Behind a proxy, check that the address
is the caller's and not the proxy's (ADR-0017 §6). A member that is locked
out has usually been configured with an old secret: fix its configuration and wait out the minute. Many
names locked out from one address is someone guessing: block the address in front of the venue. A restart
clears the lockouts. `AUTHENTICATION_BUSY` means a client sends many REST requests at once and the secret
checks queue up behind each other: the client should use fewer connections in parallel, or FIX.

## The queue is nearly full or commands are slow

**Alerts:** `QueueNearlyFull`, `CommandLatencyHigh`. A full queue refuses with `OVERLOADED` and
`Retry-After: 1` (OE-005); nothing is lost. The usual cause is the journal's device flush (about 160 µs on the
reference machine, [performance](../performance.md)). With the default `fsync: GROUP` one flush covers every
command waiting, so a full queue means the device itself is slow: check
`lob_journal_flush_duration_seconds` (the device's latency) and `lob_journal_group_size` (how many commands each
flush carries). If the venue runs
`fsync: EVERY_COMMAND`, switching to `GROUP` keeps the same durability promise (ADR-0020). Switching to `OS`
changes the promise and is a decision, not a fix.

## Restart and shutdown

A shutdown (`SIGTERM`, as Kubernetes sends) processes and journals every command already queued, then
closes the journals (OE-007); commands arriving during it are refused with `STOPPED`. Allow it the pod's
termination grace period. A `SIGKILL` loses no acknowledged command either (RS-002),
but in-flight requests get no answer.

## Checking the journals have not been changed

With `lob.journal-seal.*` configured (RS-013), the venue checks every seal when an instrument starts, and refuses
to start one whose journal does not match (`... changed after they were sealed`, `... sealed data is missing`,
`... not signed by the key`). Treat it as an incident: the journal was edited, truncated or replaced after it was
written. Do not delete `commands.seal` to get the venue started; restore the instrument's directory from a backup
and compare. An auditor checks a copy of the journals with the public key alone:
`java -cp "$CP" io.github.ahmedberrada.lob.journal.JournalVerify <instrument-dir> seal.pub`, with `CP` as for
`ReplayTool` in the README (exit 0: every seal holds). Keys: `openssl genpkey -algorithm ed25519 -out seal.pem` and
`openssl pkey -in seal.pem -pubout -out seal.pub`; keep `seal.pem` in a secret store, never with the journals.

## Backups and retention

Journals grow without bound; the engine never deletes them (RS-008). Back up each instrument's directory
with a file-system snapshot of the volume. A plain copy of a running venue is also usable: recovery
discards a record cut in the middle (RS-003). Keep order records as long as the regulation requires
(five years for RTS 24, [order records](../compliance/order-records.md)); the export is generated from the
journals, so the journals are what must be kept.

## Soak test

Run before a release, and after a change to the journal or recovery:

```bash
./mvnw -q -pl engine-bench -am package -DskipTests
java -cp engine-bench/target/benchmarks.jar io.github.ahmedberrada.lob.bench.SoakRun 86400 100000
```

It runs a market-like stream for the given seconds and, every given number of commands, closes the engine
and recovers it from its journals; after each recovery the state must equal that of an in-memory engine fed
the same commands. It prints the number of commands, restarts and the heap after each restart, and exits
with `1` on the first mismatch. Pass: exit code `0`, and no upward trend in the heap after the first hour.
A short version runs in CI (`LatencyRunTest`).
