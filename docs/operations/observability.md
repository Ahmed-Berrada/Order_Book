# Observability

What the venue reports about itself, and what each signal means. Actions: [runbook](runbook.md).

## Endpoints

| Path | Access | Purpose |
|---|---|---|
| `/actuator/health/liveness` | anyone | `UP` while the process runs. Kubernetes restarts the pod if it fails |
| `/actuator/health/readiness` | anyone | `UP` when the venue can trade: the application is ready **and** no instrument is halted (`lob` indicator). Kubernetes takes the pod out of the service while it is `DOWN` |
| `/actuator/health` | anyone | The overall status, without details (`show-details: never`) |
| `/actuator/prometheus` | `READ` | Metrics in the Prometheus text format |
| `/actuator/info` | `READ` | Build information |

No other Actuator endpoint is exposed (`env`, `heapdump` and the rest answer `404`): they would show
configuration and memory to anyone who holds a role. The probes are open because Kubernetes and load
balancers call them without credentials, and they say nothing but `UP` or `DOWN`.

## Metrics

Every venue metric carries a `symbol` tag. Prometheus names are shown (dots become underscores, units are
suffixed).

| Metric | Type | Meaning |
|---|---|---|
| `lob_command_duration_seconds` | histogram | Time the instrument's thread takes for one command: journal write and matching, and the device flush with `fsync: EVERY_COMMAND`. Queueing time and, with `GROUP`, the group's flush are not included. Buckets from 500 ns to 500 ms |
| `lob_trades_total` | counter | Trades executed |
| `lob_rejections_total` | counter | Orders and replaces the engine rejected (`OrderRejected`, `ReplaceRejected`): a member's risk limit, a price off the tick grid, a phase that does not allow the order |
| `lob_volatility_interruptions_total` | counter | Interruptions by the volatility band (VI-003) |
| `lob_queue_depth` | gauge | Commands waiting for the instrument's thread |
| `lob_queue_capacity` | gauge | Commands the queue holds before it refuses with `OVERLOADED` (OE-005) |
| `lob_journal_flushes_total` | counter | Device flushes of the command journal: one per command with `fsync: EVERY_COMMAND`, one per group of waiting commands with `GROUP` (ADR-0020), none with `OS`. Commands per flush is the group size |
| `lob_journal_flush_duration_seconds` | histogram | Time of one device flush of a group of commands (`GROUP`): the device's latency, which bounds the instrument's throughput |
| `lob_journal_group_size` | summary | Commands covered by one flush (`GROUP`): about 1 at low load, growing with the load |
| `lob_throttled_total` | counter | Members' orders and replaces refused before the queue by a throttle, by `member` and `reason`: `THROTTLED` (message rate, OE-008) or `ORDER_TO_TRADE_LIMIT` (OE-009). Appears with the first refusal |
| `lob_request_seconds` | histogram | What a REST client waits for, from submission to acknowledgement: queueing, the engine, and the journal's flush with `fsync: GROUP` or `EVERY_COMMAND`. Tagged `symbol` (`unknown` for a symbol that is not listed), `lob_command` (the command's type), `lob_outcome` `accepted`, `rejected` (by the engine), `refused` (before the journal) or `unknown` (a failed write), and `error` (the exception's class, or `none`). Buckets from 50 µs to 10 s. The same measurement as the command's span (Tracing, below); `lob_request_active_seconds` counts those in progress. FIX commands are not in it |
| `lob_instrument_halted` | gauge | `1` if the instrument stopped after a journal failure (OE-006), else `0` |

The JVM, process and HTTP server metrics that Spring Boot provides (`jvm_*`, `process_*`,
`http_server_requests_seconds`) are exported too.

Refusals before the engine (`OVERLOADED`, `HALTED`, `STOPPED`, authentication and validation errors) are
not engine rejections; they show in `http_server_requests_seconds` by status code.

### How the metrics are taken

The service calls a `CommandObserver` on the instrument's thread after each command, with the event batch
and the time `process` took (`System.nanoTime`). The engine itself stays free of clocks and metrics, so
replays are unchanged. The observer increments counters and records one timer sample; a failing observer is logged and does not affect
the command.

## Alerts

[`deploy/prometheus/alerts.yml`](../../deploy/prometheus/alerts.yml):

| Alert | Condition | Severity | Runbook |
|---|---|---|---|
| `InstrumentHalted` | `lob_instrument_halted == 1` | page | [An instrument is halted](runbook.md#an-instrument-is-halted) |
| `VenueDown` | the scrape fails for 1 minute | page | [The venue does not start](runbook.md#the-venue-does-not-start) |
| `QueueNearlyFull` | depth over 80% of capacity for 1 minute | warn | [The queue is nearly full](runbook.md#the-queue-is-nearly-full-or-commands-are-slow) |
| `CommandLatencyHigh` | p99 command time over 5 ms for 5 minutes | warn | [Commands are slow](runbook.md#the-queue-is-nearly-full-or-commands-are-slow) |
| `VolatilityInterruption` | any interruption in 5 minutes | info | [A volatility interruption](runbook.md#a-volatility-interruption) |

## Dashboard

[`deploy/grafana/lob-engine.json`](../../deploy/grafana/lob-engine.json): commands per second, p50 / p99 /
p99.9 command time, queue depth, trades and rejections per second, and halted instruments, per symbol.
`deploy/docker-compose.yml` starts the venue, Prometheus and Grafana with it loaded.

## Tracing

OpenTelemetry traces (ADR-0023), through Micrometer's tracing bridge. A REST request is a trace: the HTTP span,
Spring Security's, and one span per command, named `lob <command type>` (`lob LimitOrder`), from submission to
acknowledgement. The command's span carries the same keys as `lob_request_seconds`, plus `lob.member`, the
`lob.refusal` reason, and `lob.command.sequence`, the command's position in the instrument's journal: from a slow
or failed request, the trace leads to the journal record (`replay` or the order records, at that sequence).

| Setting | Default | |
|---|---|---|
| `management.tracing.sampling.probability` | `0.1` | share of requests traced |
| `management.opentelemetry.tracing.export.otlp.endpoint` | none | where to send spans over OTLP/HTTP, e.g. `http://collector:4318/v1/traces`; without it nothing is exported |

Each log line carries the trace and span IDs of the request it was written for, so logs and traces join on them.
An incoming `traceparent` header (W3C; B3 is not read, and no baggage is carried) continues the caller's trace.
The sampler follows the caller's decision: a request marked sampled is traced whatever the probability, so a
caller can have its own requests traced, not anyone else's. A collector that cannot take every request should
drop at its own edge (tail sampling).

## Not yet

- **FIX tracing.** FIX messages carry no trace context, and the gateway does not start traces: a FIX order shows
  in the logs and the journal, not in traces or in `lob_request_seconds`.
- **Spans inside the instrument's thread.** The command's span covers the whole wait; splitting it into queueing,
  matching and flush would need the trace context carried into the engine service, which stays free of
  frameworks. `lob_command_duration_seconds` and `lob_journal_flush_duration_seconds` give those parts in
  aggregate.
- **FIX session metrics** (sessions logged on, messages per second, logon failures). Logon failures are
  logged today (AC-006).
