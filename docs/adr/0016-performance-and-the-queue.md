# ADR-0016: Measuring first; a constant-time queue; no new transport yet

- **Status:** Accepted
- **Date:** 2026-11-27

## Context

Phase 6 asks whether the engine is fast enough and what to change if it is not. Measurements and method:
[`performance.md`](../performance.md).

## Decisions

### 1. Measure with two instruments, and say what each cannot tell

JMH (`engine-bench`) isolates the cost of one operation with the book at a steady size. A closed-loop
HdrHistogram run (`LatencyRun`) records every command of a market-like stream, tails included. Neither is
a load test with a fixed arrival rate, which would add queueing delay; the document says so rather than
quote service time as client latency.

### 2. Cancel is O(1)

ADR-0001 §5 accepted an O(n) cancel for version 1 and promised to measure it. Measured, a cancel in the
middle of a 10,000-order level cost about 2 µs against 0.15 µs at a depth of 10. Each price level now keeps
its orders in an intrusive doubly linked list (`previous` and `next` in the order itself), so removal from
anywhere is a few pointer writes and allocates nothing. After the change the same cancel costs about 0.15 µs
at every depth.

### 3. Not adopted: Disruptor, Aeron, off-heap maps

The engine does about 2.2 million commands per second on one thread, with a 99.9th percentile of
3 µs. The journal costs about 1.3 µs per command with buffered writes, and 160 µs per command when every
command is forced to disk. The bottleneck is durability, not matching, and neither a ring buffer in front
of the engine nor a faster transport changes the cost of an `fsync`. The lever that does is group commit
(one `fsync` for the commands that arrived together), which belongs with the replication design of
phase 9, where the durability promise changes anyway. Aeron is evaluated there for the same reason.

### 4. A regression check that does not depend on the machine

Wall-clock numbers on a shared runner vary by more than the changes worth catching. The repository keeps
the benchmarks runnable on demand (a manually triggered workflow) and relies on the deterministic
properties for correctness; a speed gate would be a flaky test.

## Consequences

- ➕ Cancel no longer degrades with the depth of a price level.
- ➕ A baseline and a method for every later change.
- ➖ No automated speed gate.
- ➖ Primitive maps, zero-allocation events and GC tuning are listed in the document as measured candidates, not
  done.
