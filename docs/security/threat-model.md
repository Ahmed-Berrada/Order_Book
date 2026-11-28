# Threat model (STRIDE)

What the venue protects, who might attack it, and what stops them. It is a working document: a status of
**Mitigated** means a control exists and is tested, **Partial** that a control exists with a named gap,
**Open** that nothing stops the threat yet. The system is described in the [README](../../README.md); the
controls are in the [rulebook](../rulebook/access-control.md) and the ADRs.

## Assets

1. **The integrity of the order book and of the journal**: a wrong trade, a lost or altered command.
2. **The availability of trading**: an instrument that stops matching.
3. **Confidentiality of members' orders**: hidden iceberg quantity, who trades what.
4. **Credentials** of members and the operator.
5. **The release artifacts**: what a user runs is what the source says.

## Trust boundaries

```
 members' systems ──FIX──▶ ┐
 members / operator ─REST─▶├─▶ edges (authn, roles, member binding) ─▶ per-instrument writer thread ─▶ journal files
 consumers ──WebSocket / UDP / TCP feeds ◀── market-data (events only) ◀──────────────────────────────┘
 build / release pipeline ──▶ artifacts
```

The engine trusts what reaches it: the member number in a command is whatever the edge decided. Everything
below the edges assumes the edges did their job.

## Threats

| # | Threat | STRIDE | Where | Control | Status |
|---|---|---|---|---|---|
| 1 | A caller acts as another member or as the operator | Spoofing | REST, FIX | HTTP Basic or FIX Logon password; hashed secrets; member bound to the user; header naming another member refused (AC-001 to AC-005, AC-007) | Mitigated, over TLS only (see 5) |
| 2 | Guessing credentials | Spoofing | REST, FIX | Bcrypt hashes make each guess expensive (credentials already verified are remembered as a keyed HMAC for ten minutes, so a guess still pays bcrypt, AC-012); failures are logged (AC-006); after 5 failures in a row a name is locked out from that address for a minute, without its secret being checked (AC-009) | Partial: an attacker with many IPv4 addresses or /64 networks gets 5 guesses a minute from each; no lockout across addresses, which would let anyone lock a member out; behind a proxy, the caller's address must be forwarded and trusted (ADR-0017 §6); a heap dump gives fast HMACs of recently used passwords with their key, beside the recent `Authorization` headers, so memory is as secret as the passwords (ADR-0017 §7) |
| 2a | A page on another site makes a logged-in browser enter or cancel orders (cross-site request forgery with cached Basic credentials) | Spoofing, Tampering | REST | Writes a browser marks as cross-site (`Sec-Fetch-Site`, or `Origin` from old browsers), and writes with a form body, are refused (AC-008); a JSON body from another site needs a CORS preflight, never granted | Mitigated |
| 3 | Cancel or replace another member's order | Tampering | engine | Ownership of cancels and replaces; the answer is `UNKNOWN_ORDER` (MB-003) | Mitigated |
| 4 | A member's runaway algorithm floods or distorts the book | Tampering, DoS | engine | Pre-trade limits, collar, kill switch (RK-001 to RK-005); the bounded queue refuses overload (OE-005); a message rate per member and a live order-to-trade limit, cancels never refused (OE-008, OE-009) | Partial: one rate for every member; operator-triggered kill only |
| 5 | Credentials or orders read or altered on the network | Information disclosure, Tampering | REST, FIX, feeds | The ports listen on loopback by default; beyond loopback a secured venue's REST, management and FIX ports speak only TLS 1.2 or 1.3, or the venue does not start, checked before any port opens (AC-010); mutual TLS for FIX (AC-011), the certificate bound to its CompID (AC-013) | Partial: TLS terminated in front is trusted on the operator's word; the market data feeds are in clear (public data) |
| 6 | The journal is altered after the fact | Tampering, Repudiation | files | CRC on every frame detects accidental damage; replay with `--verify` shows whether the current code reproduces the recorded events (RS-009) | With a sealing key, every command journal is sealed: Ed25519 signatures over its SHA-256 at least every second, checked at start-up and by `JournalVerify` (RS-013) | Partial: the last second of commands (at most 10,000) is unsealed; a rollback to an older journal with its seals, or a cut exactly at a seal, is found only against a sequence kept outside the venue (`JournalVerify --expect`); whoever holds the private key can reseal a rewritten journal, so the key belongs in a secret store, away from the journals; the event journal and snapshots are not sealed, as they are rebuilt from the commands |
| 7 | A member denies an order it sent | Repudiation | journal | Every command is recorded with its member and microsecond time; order records can be exported (compliance/order-records.md) | Partial: the member is what the edge decided; FIX message signing is not used |
| 8 | Hidden iceberg quantity leaks through the public feed | Information disclosure | market data | The feed is rebuilt from events and shows displayed quantity only (IC-002) | Mitigated |
| 9 | One member learns that another's order exists | Information disclosure | engine | Another member's cancel gets the same answer as an unknown order (MB-003) | Mitigated |
| 10 | Resource exhaustion through huge messages or many connections | DoS | REST, FIX, feeds | Size limits of the framework defaults; bounded per-instrument queue; maximum order quantity and price | Partial: no connection limits or per-client rate limit |
| 11 | A slow consumer stalls matching | DoS | market data | Subscribers have queues; a full queue drops that subscriber, never the writer | Mitigated |
| 12 | An operator endpoint is reached by a non-operator | Elevation of privilege | REST | `ADMIN` required for phase, member, limit and suspension endpoints; everything not named is denied (AC-004) | Mitigated |
| 13 | A dependency has a known vulnerability | Elevation of privilege | build | Dependency review on pull requests, Dependabot, CodeQL | Mitigated as a process; no guarantee against unknown flaws |
| 14 | A tampered build or release | Tampering | pipeline | Releases are built by one workflow with signed provenance and an SBOM (supply-chain.md) | Partial: the repository itself is the root of trust |
| 15 | Secrets in the repository or in logs | Information disclosure | repo, logs | Secrets are configured as hashes; logs never carry a secret (AC-006) | Partial: no automated secret scanning beyond what GitHub provides |
| 16 | The operator's own mistakes: a wrong phase, a wrong kill | — | REST | Every action is a journaled command and shows in the records | Accepted: no four-eyes control |
| 17 | A rogue process writes to the standby or raises its term | Tampering, Spoofing | replication | The link starts with an HMAC-SHA256 challenge over a shared secret; the standby appends only after what it holds; a primary of a lower term is refused (RS-014, RS-016) | Partial: the link is not encrypted (orders are readable on the wire): a private network or a tunnel is required |

## What this does not cover

Physical and host security, the operating system, the container image (phase 9), social engineering, and
regulators' requirements beyond [the mapping](../compliance/regulatory-mapping.md). A real venue needs an
independent penetration test and a security review of its deployment.
