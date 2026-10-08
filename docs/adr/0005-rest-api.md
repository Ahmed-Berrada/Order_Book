# ADR-0005: REST API adapter

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

Phase 3b exposes the `MatchingService` (ADR-0004) over HTTP for integration, testing and
administration. The contract is specified in [`rest-api.md`](../interfaces/rest-api.md).

## Decisions

### 1. A thin adapter in the `api` module

The `api` module (Spring Boot) only translates: JSON to commands, decimal prices to ticks through
`InstrumentConfig`, events to responses, and refusals to HTTP statuses. It holds no trading logic and
no state; everything goes through `MatchingService`. Spring stays out of the engine modules.

### 2. Non-blocking controllers

Controllers return the service's `CompletableFuture`, so Spring MVC releases the servlet thread while
the instrument's writer thread works. A request timeout (`spring.mvc.async.request-timeout`, 5 s)
bounds how long a client waits. A timed-out command may still be processed: its outcome is unknown,
as the specification states.

### 3. Strict JSON

Order entry is where silent leniency costs money:
- unknown fields are refused (`FAIL_ON_UNKNOWN_PROPERTIES`), so a misspelt or unsupported field cannot
  be ignored;
- floats are not coerced to integers (`ACCEPT_FLOAT_AS_INT` off), so `10.5` lots is an error, not 10;
- prices are `BigDecimal` in and decimal strings out, never `double`.

### 4. Errors are RFC 9457 problem details with a machine-readable `reason`

The `reason` reuses the vocabulary of the engine (`RejectReason`) and the service
(`RequestRefusedException.Reason`), so clients see the same codes whatever the protocol. Statuses:
validation `400`, unknown instrument or order `404`, business rejection `422`, refusal under load or
halt `503` (with `Retry-After` when overloaded), unknown outcome `500`.

Engine rejections are journaled commands and consume an order ID, which the problem body returns.
Off-tick prices and unknown instruments are refused before the engine (OE-001, OE-002).

### 5. Configuration declares the venue

Instruments, data directory, queue capacity, sync policy and snapshot interval are bound from
`application.yml` (`lob.*`) into a validated record, and the `MatchingService` is a singleton bean
closed on shutdown (OE-007). Instruments are fixed at startup, as on an exchange.

### 6. OpenAPI from the code

springdoc generates the OpenAPI document from the controllers; a test checks it is served and lists
the endpoints, so the published contract cannot silently drift from the implementation.

## Not in this step

Authentication and authorisation (phase 8: OAuth2/OIDC, mutual TLS) and per-member rate limits
(phase 4/5). Until then the server listens on the loopback interface by default (`server.address`,
overridable with `LOB_BIND_ADDRESS`), so exposing it is a deliberate act, and must stay within a
trusted network.

## Consequences

- ➕ One HTTP mapping of every engine and service outcome, documented and tested.
- ➕ Strict parsing removes a class of costly client mistakes.
- ➖ No client order ID: unknown outcomes can only be resolved by inspecting the book until FIX.
- ➖ JSON over HTTP adds tens to hundreds of microseconds; it is not the latency path.
