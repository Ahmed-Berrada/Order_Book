# ADR-0018: Operations, and a design for high availability that is not built yet

- **Status:** Accepted (sections 1 to 4 built; section 5 is a design)
- **Date:** 2026-11-27

## Context

Phase 9 makes the venue operable: an operator must see what it does, be told when it fails, know what to do,
and run it the way production systems are run. The roadmap also asks for failover: a primary and a standby
with a replicated journal, no acknowledged command lost (RPO 0), and a recovery-time target.

The engine is a deterministic state machine fed by a command journal (ADR-0002, ADR-0003). That makes
replication simple in principle (copy the commands and replay them) and hard in practice (decide who is
primary, and acknowledge only what the standby holds). This ADR decides what is built now and what is
designed.

## Decisions

### 1. Metrics are taken outside the engine

The engine has no clock (ADR-0002) and must stay replayable. The service calls a `CommandObserver` after each
command on the instrument's thread, with the event batch and the time `process` took; the API module adapts
it to Micrometer. Counters are derived from the events, not from separate instrumentation, so a metric
cannot disagree with the journal. Gauges read the queue and the halt flag. Catalogue:
[observability](../operations/observability.md).

### 2. Readiness means "can trade"

Liveness is the process. Readiness adds a `lob` health indicator that is `DOWN` while any instrument is
halted (OE-006): a halted instrument needs a restart, and a load balancer should not send orders to it.
Only liveness restarts the pod, so a halt does not cause a restart loop; the operator restarts once the
cause is fixed ([runbook](../operations/runbook.md#an-instrument-is-halted)). The probes are anonymous and
show no details; metrics need the `READ` role (AC-004).

### 3. One writer per journal

Two processes on one volume (a rolling update, a mis-set replica count) would interleave frames and corrupt
the journal. Each engine takes an exclusive lock on `journal.lock` in its directory before it reads it
(RS-011). The operating system releases the lock when the process dies, so a crash leaves nothing to clean
up. The Helm chart runs a `StatefulSet` with one replica and a `ReadWriteOnce` volume for the same reason.

### 4. Packaging

A multi-stage `Dockerfile` builds the jar and runs it on a JRE as a non-root user (uid 10001) with the
journal on a volume. `deploy/` holds a compose stack (venue, Prometheus, Grafana) for a laptop and a Helm
chart for Kubernetes, with a restricted security context, the three probes, and a `ServiceMonitor`. Neither
can be run in this repository's CI (no container runtime); the image build is a release step.

TLS is terminated in front of the venue (an ingress or a sidecar), not in it: certificates are rotated by
the platform. The FIX port is exposed by its own service; mutual TLS for FIX belongs to the same
terminating proxy.

### 5. High availability (design, not built)

**Model.** One primary per instrument group and one standby. The primary journals a command, sends it to
the standby, and acknowledges it only when the standby has written it (synchronous replication). The
standby applies the commands to its own engine and journal; being deterministic, it reaches the same state
and emits the same events without receiving them. Its event journal is checked against the primary's by
the replay verification that exists today (RS-009).

**RPO 0.** A command is acknowledged only once two journals hold it. Losing either machine loses no
acknowledged command. With `fsync: EVERY_COMMAND` on both, a power loss of both machines loses none
either.

**Group commit.** Synchronous replication adds a network round trip to each command, on top of the `fsync`
that already dominates (about 160 µs, [performance](../performance.md)). The instrument's thread would
write the commands that are waiting, flush and replicate them once, and then acknowledge them all. Matching
order is unchanged (the journal order is the processing order); only acknowledgements are batched. This is
the lever phase 6 found (ADR-0016 §3).

**Who is primary.** The two nodes cannot decide it alone without risking two primaries after a network
split. A lease in a consensus store (etcd, or the Kubernetes Lease API) names the primary; the primary
stops accepting commands when it cannot renew it, before the lease can be granted to the standby. A
standby promoted after a lease expiry replays any command it holds that it has not applied, and starts.
Each journal record would carry the leader's term, so a deposed primary's late writes are refused.

**RTO.** The lease duration plus the standby's catch-up. The standby applies commands continuously, so
catch-up is the commands in flight: a target of under 10 seconds with a 5 second lease.

**Transport.** Aeron (with Aeron Cluster providing the consensus above) or a plain TCP stream of journal
frames; the frames are already checksummed and sequenced (ADR-0003), so the standby detects a gap or
damage the same way recovery does.

**Why it is not built.** A correct implementation needs a consensus store, network fault injection to
test it (partitions, a slow standby, a primary paused by GC past its lease), and a second machine. A
replication layer that has not been tested against those faults is worse than none: it promises RPO 0 and
may lose orders or run two primaries. Until it is built, the venue's promise is the one it can prove:
no acknowledged command is lost if the volume survives (RS-002), the venue restarts on the same volume
in seconds, and an operator can verify every journal (RS-009).

## Consequences

- An operator has metrics, alerts linked to a runbook, probes and a dashboard; the engine is unchanged.
- A failed disk means downtime until the volume is restored or replaced, not lost orders: the RTO is the
  operator's.
- The soak test (`SoakRun`) checks recovery over many restarts and reports memory per restart; a 24 hour
  run is a release step ([runbook](../operations/runbook.md#soak-test)).
- Chaos tests run the whole venue in a second JVM (`VenueCrashTest`, RS-012): killed with SIGKILL while four
  clients enter orders, it restarts with every order a client saw acknowledged; with a journal's storage full
  (a per-file size limit, which fails writes exactly as a full disk does), only that instrument halts, readiness
  goes down, and a restart recovers it. In-process fault tests cover a journal directory deleted under a running
  engine, a write interrupted mid-command (OE-006), and damaged or zero-filled tails (RS-003).
- Group commit, replication and tracing remain on the roadmap; a network partition can only be tested once
  there is a standby to partition from.
