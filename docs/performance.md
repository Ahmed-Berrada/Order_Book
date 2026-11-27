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

## Reading the numbers

- **Matching is not the bottleneck.** One thread matches over two million commands a second with a
  99.9th percentile of a few microseconds. Per instrument, this is far above what a REST or FIX front end
  can feed it.
- **Durability is.** Writing the journal takes about 1.3 µs a command when the operating system buffers it, and
  about 160 µs when each command is forced to disk, a 25-fold difference in throughput. The default
  `fsync` policy (`EVERY_COMMAND`) therefore caps one instrument at roughly 6,000 commands a second on this
  machine. The honest levers are group commit (one `fsync` for the commands that queued together), faster storage,
  or replicating to a standby in memory (phase 9), not a faster matching loop.
- **Allocation.** Each command allocates its event list and events; the numbers above include the garbage
  collector's work. Whether that matters is a question for the end-to-end load test, not for the engine alone.

## Not done, and why

| Candidate | Why not yet |
|---|---|
| Primitive `long` maps for orders by ID | The map shows up in no profile above the journal; measure after group commit |
| Zero-allocation events | Changes every consumer; the gain is invisible next to the `fsync` |
| Disruptor / Aeron | The engine is not the limit; see ADR-0016 §3 |
| GC comparison (G1, ZGC, Shenandoah) | Needs the end-to-end load test and a machine with stable cores |
| A speed gate in CI | Shared runners vary more than the regressions worth catching |
| End-to-end load test through REST and FIX | Phase 9 has the deployment it should run against |
