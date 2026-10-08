# ADR-0004: Service layer — one writer thread per instrument

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

Phase 3 connects the engine to the outside world: a REST API first, then a FIX gateway and market data.
Each access protocol needs the same things: route a command to its instrument, run it on the single
thread that owns that instrument (ADR-0001 §2), wait for the result, and convert decimal prices to
ticks. The rules are in the rulebook chapter [`order-entry.md`](../rulebook/order-entry.md).

## Decisions

### 1. A protocol-independent `engine-service` module

The routing and threading live in a plain-Java module, `engine-service`, that depends on
`engine-journal`. The REST adapter (`api`, Spring Boot) and the future FIX gateway are thin layers on
top of it. Spring stays out of the service, as it stays out of the core and the journal.

### 2. One platform thread and one bounded queue per instrument

`SymbolWorker` owns one `JournaledEngine` and one dedicated platform thread named `lob-<symbol>`. Any
thread can submit a command; it is placed in the worker's `ArrayBlockingQueue` and the caller gets a
`CompletableFuture<EventBatch>` that completes once the command is journaled and processed.

- A dedicated platform thread, not a virtual thread: the worker is long-lived, always busy under load,
  and is the thread later pinned to a CPU core (phase 6). Virtual threads suit the request side.
- A *bounded* queue: an unbounded one hides overload until memory runs out and latency explodes. When
  the queue is full the command is refused immediately with `OVERLOADED` (OE-005). Rejecting quickly
  is what exchanges do (message throttles, RTS 7), and it keeps latency predictable for everyone else.
- Read queries (book depth, order status) also run on the worker thread, because the book is not
  thread-safe. They return copies, never the live book.

The LMAX Disruptor would replace the queue if phase 6 measurements justify it.

### 3. Refusals are typed, not generic errors

Commands that never reach the engine fail their future with a `RequestRefusedException` carrying a
reason: `UNKNOWN_INSTRUMENT`, `OFF_TICK_PRICE`, `OVERLOADED`, `HALTED` or `STOPPED`. Each access
protocol maps the reason to its own vocabulary (HTTP status, FIX reject code). Engine rejections, such
as an invalid quantity, are still ordinary `OrderRejected` events.

### 4. Halt on journal failure

`JournaledEngine` is fail-stop (ADR-0003 §8). When a command fails with an I/O error, the worker marks
the instrument `HALTED`: further commands are refused, while book queries still work, because the
in-memory book matches what was journaled. Only that instrument is affected.

### 5. Prices are converted at the edge of the service

`InstrumentConfig` holds the decimal tick size and converts prices exactly, with `BigDecimal`, refusing
off-tick prices (OE-002). Conversion is done once, before the queue, so the worker thread never handles
decimals. The symbol must match `[A-Z0-9._-]{1,16}`, because it also names the instrument's journal
directory and must never be able to point outside it.

### 6. Orderly shutdown

Closing the service stops accepting commands (`STOPPED`), lets each worker process what is already
queued, then closes the journals (OE-007). A command that races with shutdown is either processed or
refused, never left waiting forever.

## Consequences

- ➕ REST and FIX share one tested path to the engine; adding a protocol adds no concurrency code.
- ➕ Overload and disk failures produce explicit, protocol-neutral refusals.
- ➖ A caller thread blocks on the future in the synchronous REST API (accepted in ADR-0001).
- ➖ One thread per instrument does not scale to thousands of instruments; sharding instruments over a
  pool of workers would be the next step.
