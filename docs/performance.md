# Performance

How fast the engine is, how that was measured, and what the numbers do and do not say. Design decisions:
[ADR-0016](adr/0016-performance-and-the-queue.md).

## Method

- **JMH** (`engine-bench`, `MatchingEngineBenchmark`): average time per operation, one fork, 3 warm-up and
  5 measured iterations of 1 s, with a fresh engine per iteration so that the book has a steady size.
- **HdrHistogram** (`LatencyRun`): the time of every `process` call over a reproducible, market-like stream
  (`Workload`: 62% passive limit orders, 30% cancels, 6% marketable limit orders, 2% market orders, around a
  drifting mid price), after a warm-up. Three modes: the bare `engine`, the `journal` (command and event
  files, buffered writes) and `journal-fsync` (every command forced to disk).
- Run them yourself:

  ```bash
  ./mvnw -B -ntp -DskipTests package -pl engine-bench -am
  java -jar engine-bench/target/benchmarks.jar                                   # JMH
  java -cp engine-bench/target/benchmarks.jar io.github.ahmedberrada.lob.bench.LatencyRun engine
  java -cp engine-bench/target/benchmarks.jar io.github.ahmedberrada.lob.bench.LatencyRun journal
  java -cp engine-bench/target/benchmarks.jar io.github.ahmedberrada.lob.bench.LatencyRun journal-fsync
  ```

**Machine:** 4 vCPU Intel Xeon at 2.3 GHz in a shared cloud container, OpenJDK 21.0.12, default GC (G1),
no CPU pinning. Treat every figure as an order of magnitude: JMH's own error bars are 10 to 40%.

**What the runs cannot tell.** Both are closed loops: the next command is sent when the previous returns.
They measure *service time*. A client sending at a fixed rate would also wait behind a slow command
(coordinated omission), and the latency it saw would be worse in the tail. They run in one thread, in
process: no network, no REST, no FIX parsing, no market data.

## Results

### Cancel from the middle of a price level (JMH, ns per cancel and re-entry)

| Orders at the level | Before (array queue) | After (linked queue) |
|---:|---:|---:|
| 10 | 145 | 120 |
| 1,000 | 284 | 129 |
| 10,000 | **2,015** | **150** |

The array queue scanned to find the order; cost grew with the level. The linked queue removes in constant
time (ADR-0016 §2).

### Other operations (JMH, ns per operation, 10,000-order level, after)

| Operation | ns |
|---|---:|
| rest a passive order and cancel one | 149 |
| take one resting order with a market order, and its replacement | 188 |
| one command of the mixed stream | 383 |

### Latency of one command (HdrHistogram, 2,000,000 commands, after)

| Path | Commands per second | p50 | p99 | p99.9 | p99.99 |
|---|---:|---:|---:|---:|---:|
| engine only | 2,200,000 | 191 ns | 1.8 µs | 3.3 µs | 45 µs |
| with the journal, buffered writes | 500,000 | 1.5 µs | 4.4 µs | 21 µs | 61 µs |
| with the journal, `fsync` per command (100,000 commands) | 5,900 | 160 µs | 322 µs | 1.8 ms | 3.3 ms |

The worst single command in every run was 12 to 20 ms: a JIT compilation or a GC pause on a shared machine.
That is what a tail looks like without tuning, not a figure to quote as a guarantee.

### Durable throughput with clients waiting (group commit, ADR-0020)

`GroupCommitRun`: closed-loop clients, each sending a resting limit order through the real service (queue,
instrument thread, journal) and waiting for its acknowledgement before the next; 1,000 commands per client after
a warm-up of 2,000; one instrument, the same 4 vCPU container and disk as above. Which policy runs first
alternates from one client count to the next, so neither always gets the warmer JVM. Every acknowledged command is on the
device under both policies.

| Policy | Clients | Acknowledged commands per second | p50 | p99 | Flushes per command |
|---|---:|---:|---:|---:|---:|
| EVERY_COMMAND | 1 | 5,270 | 173 µs | 378 µs | 1.00 |
| GROUP | 1 | 4,343 | 206 µs | 465 µs | 1.00 |
| EVERY_COMMAND | 4 | 5,240 | 673 µs | 2.4 ms | 1.00 |
| GROUP | 4 | 12,128 | 324 µs | 580 µs | 0.45 |
| EVERY_COMMAND | 16 | 6,014 | 2.6 ms | 5.7 ms | 1.00 |
| GROUP | 16 | 36,007 | 418 µs | 805 µs | 0.09 |
| EVERY_COMMAND | 64 | 5,855 | 10.6 ms | 18.1 ms | 1.00 |
| GROUP | 64 | 63,797 | 933 µs | 2.1 ms | 0.02 |

- With `EVERY_COMMAND` throughput stops at the device's flush rate, about 6,000 a second, whatever the load, and
  latency grows with the queue: at 64 clients each command waits for the flushes of the commands ahead of it.
- With `GROUP` the commands that queue during one flush share the next one, so throughput grows with load: about
  11 times more at 64 clients, with a p99 about 9 times lower.
- With one client both policies flush once per command and do the same work. The difference in that row is
  run-to-run variation, about ±20% on this shared machine: an earlier run, with `EVERY_COMMAND` always first,
  had `GROUP` ahead by as much. Group commit costs nothing at low load, because it never waits for a group to
  fill.

Reproduce: `java -cp engine-bench/target/benchmarks.jar io.github.ahmedberrada.lob.bench.GroupCommitRun 1000 1,4,16,64`.

### The whole venue over REST (end-to-end load test)

`LoadRun`: an open-loop client against a running venue (`java -jar api/target/api-*.jar`, `fsync: GROUP`, one
instrument), sending at a fixed rate whatever the answers: 60% resting limit orders, 30% cancels of orders it
left resting, 10% market orders. Latency runs from the moment each request was due, so a stall delays every request
scheduled behind it and is counted (no coordinated omission). Requests over 30 s count as failures; when more than
twice the rate is in flight, new requests are dropped and counted. The client and the venue share the same 4 vCPU
container, so the client takes CPU from the venue: these are lower bounds. 20 s per rate after a warm-up.

Without authentication (`dev` profile):

| Target /s | Achieved /s | p50 | p99 | p99.9 | max | Failures | Dropped |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 500 | 500 | 1.9 ms | 27.8 ms | 51.9 ms | 63.2 ms | 0 | 0 |
| 1,000 | 1,000 | 1.4 ms | 20.7 ms | 66.0 ms | 83.6 ms | 0 | 0 |
| 2,000 | 2,000 | 1.6 ms | 23.5 ms | 46.7 ms | 64.0 ms | 0 | 0 |
| 3,000 | 3,000 | 2.4 ms | 56.5 ms | 80.9 ms | 105.6 ms | 0 | 0 |
| 5,000 | 3,706 | 2.2 s | 5.7 s | 30.0 s | 30.1 s | 10,557 | 25,887 |

With HTTP Basic and one user whose password is a `{bcrypt}` hash at the default cost (10), as `HashPassword` makes,
first checking every request with bcrypt (`lob.security.credential-cache=0`, as before AC-012):

| Target /s | Achieved /s | p50 | p99 | max | Answers |
|---:|---:|---:|---:|---:|---|
| 50 | 50 | 187 ms | 2.0 s | 2.2 s | 853 served, 142 × `429 AUTHENTICATION_BUSY` |
| 200 | 165 | 6.1 s | 10.0 s | 11.5 s | 1,089 served, 2,220 × `429`, 691 dropped |

then remembering verified credentials (AC-012, the default):

| Target /s | Achieved /s | p50 | p99 | p99.9 | max | Failures | Dropped |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 200 | 200 | 2.5 ms | 10.2 ms | 60.9 ms | 71.9 ms | 0 | 0 |
| 1,000 | 1,000 | 1.6 ms | 15.8 ms | 29.9 ms | 41.5 ms | 0 | 0 |
| 2,000 | 2,000 | 2.0 ms | 34.9 ms | 58.0 ms | 73.6 ms | 0 | 0 |

- **The engine is not the limit.** At saturation the instrument's thread spent 24 µs a command
  (`lob_command_duration_seconds`), and the journal flushed 2.5 commands at a time. The limit, between 3,000 and
  5,000 requests a second here, is HTTP handling on 4 vCPUs shared with the client.
- **Checking every request with bcrypt limits a user to about 50 a second:** HTTP Basic sends the secret with
  every request, bcrypt takes 70 to 100 ms of CPU at cost 10, and at most five checks run at once for one user and
  address (AC-009). Remembering verified credentials (AC-012) removes that limit: bcrypt runs once per user, and an
  authenticated user goes as fast as an unauthenticated one. FIX checks the password once per session either way.
- The p99 at moderate rates (20 to 30 ms) is the JIT and the garbage collector on a shared machine, and the flush:
  the server's own `lob_request_seconds` histogram shows the same shape.

Reproduce: start a venue, then
`java -cp engine-bench/target/benchmarks.jar io.github.ahmedberrada.lob.bench.LoadRun http://127.0.0.1:8080 2000 20 AAPL [user:password]`.

## Reading the numbers

- **Matching is not the bottleneck.** One thread matches over two million commands a second with a
  99.9th percentile of a few microseconds. Per instrument, this is far above what a REST or FIX front end
  can feed it.
- **Durability is.** Writing the journal takes about 1.3 µs a command when the operating system buffers it, and
  about 160 µs when each command is forced to disk, a 25-fold difference in throughput. The default
  `fsync` policy then (`EVERY_COMMAND`) capped one instrument at roughly 6,000 commands a second on this
  machine. Group commit, now the default (one flush for the commands that queued together, above), lifts that cap
  under load; the other levers are faster storage and replicating to a standby in memory (ADR-0018), not a faster
  matching loop.
- **Allocation.** Each command allocates its event list and events; the numbers above include the garbage
  collector's work. End to end (above), HTTP handling dominates it.

## Not done, and why

| Candidate | Why not yet |
|---|---|
| Primitive `long` maps for orders by ID | The map shows up in no profile above the journal, and end to end HTTP handling dominates |
| Zero-allocation events | Changes every consumer; the gain is invisible next to the `fsync` |
| Disruptor / Aeron | The engine is not the limit; see ADR-0016 §3 |
| GC comparison (G1, ZGC, Shenandoah) | Needs the end-to-end load test and a machine with stable cores |
| A speed gate in CI | Shared runners vary more than the regressions worth catching |
| A load test through FIX | REST is the front end the venue's limits showed up in; a FIX client adds a session per connection and is the next step |
