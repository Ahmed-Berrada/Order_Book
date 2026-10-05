# ADR-0001: Foundational architecture

- **Status:** Accepted
- **Date:** 2026-10-05

## Context

We are building a limit order book matching engine as a learning and portfolio project. The bar is
the rigour of a trading firm: correctness first, determinism, and measured performance.
The design must stay simple (KISS). Every component has to justify its existence.

## Decisions

### 1. Two Maven modules: `engine-core` and `api`

`engine-core` is pure Java. `api` is a Spring Boot adapter that depends on it.
`maven-enforcer` bans any `org.springframework*` dependency in `engine-core`, so the build fails if
the boundary is crossed.

*Benchmarks will live in a third module, `engine-bench`, added in step 3. This is the standard JMH
layout with Maven, because Maven has no clean support for an extra source set. It is not added before
it is needed.*

### 2. Single writer per symbol

Each instrument owns one thread and one `OrderBook`. The core is **not thread-safe by contract**.
The `api` routes each command to the worker that owns its symbol and waits on a `Future`.

- Within a book, processing is sequential. Price-time priority requires a total order of events.
- Across books, processing is parallel. Instruments never interact.
- Shared state is limited to the order ID generator (`AtomicLong`).

### 3. Command → Events

`MatchingEngine.process(Command) : List<Event>` with sealed interfaces and records. Exhaustive
`switch` statements make a missed case a compile error. Tests express scenarios as inputs and outputs.
This also prepares journaling and replay (step 5).

### 4. Prices and quantities as `long`

Prices are integer ticks and quantities are integer lots. There is no `double`, because of rounding
errors. There is no `BigDecimal` in the core, because it allocates. The API converts decimals to ticks
using each instrument's tick size. Notional computations use `Math.multiplyExact` to fail on overflow.

### 5. Simple data structures first

`TreeMap<Long, PriceLevel>` holds each side, `ArrayDeque<Order>` holds each level, and
`HashMap<Long, Order>` indexes orders by ID. Cancel is **O(n) within a price level in v1**.
This is accepted on purpose: it will be measured with JMH (step 3) and optimised (step 4).

### 6. Determinism

The core never reads the system clock or uses randomness. Time is injected and every event carries
a sequence number. The same command stream always produces the same event stream.

### 7. Rejections are events, not exceptions

Invalid input (unknown symbol, non-positive quantity, off-tick price) produces
`OrderRejected(reason)`. Exceptions are reserved for programming errors.

## Consequences

- ➕ The core can be tested and benchmarked without Spring, and deterministic replay is possible.
- ➕ There are no locks on the matching path, and adding instruments scales across cores.
- ➖ One thread per symbol does not scale to thousands of instruments. That would need a worker pool
  with symbol sharding, which is acceptable for this project.
- ➖ The synchronous REST API blocks a request thread while the order is processed. This is acceptable
  because the engine is fast and HTTP is not the latency path. Benchmarks bypass it.

## Rejected alternatives

| Alternative | Why not (now) |
|---|---|
| Lock-based concurrent book | Locks, non-determinism, and harder reasoning, for no gain |
| One global engine thread | Correct, but it wastes parallelism across independent instruments |
| Strategy pattern for matching | Only one algorithm exists (price-time) |
| Kafka / database | No requirement for them yet; the journal comes in step 5 |
| LMAX Disruptor | Premature. Considered in step 9, only if benchmarks justify it |
