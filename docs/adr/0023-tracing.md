# ADR-0023: Tracing with OpenTelemetry, one span per command

- **Status:** Accepted
- **Date:** 2026-11-28

## Context

The metrics (ADR-0018) say how long the instrument's thread spends on a command, and how long the journal's flush
takes, but not what one client waited for, nor where. Since group commit (ADR-0020) a command's wait includes the
rest of its group and the flush, which `lob_command_duration_seconds` leaves out by design. Observability named
tracing as missing.

## Decisions

### 1. OpenTelemetry through Micrometer's bridge, configured by Spring Boot

Spring Boot's OpenTelemetry starter gives HTTP and security spans, W3C propagation, log correlation and an OTLP
exporter with no code. The OTLP metrics registry it brings is left out: metrics stay on Prometheus, the one path
alerts and the dashboard read (ADR-0018). Logs stay on standard output, with trace and span IDs.

### 2. One span per command, opened in the API module

Each REST command is a Micrometer `Observation` from submission to acknowledgement (`TracedCommands`). One
observation is both the span and the `lob.request` timer, so the trace and the metric measure the same interval. Its
attributes are the instrument (as `symbol`, like the other meters; `unknown` for a symbol that is not listed, so a
caller cannot create metric series by inventing symbols), the command's type, the outcome (accepted, rejected, refused, unknown), the member
and the journal's command sequence, which joins a trace to the journal.

The engine service is not instrumented. It stays free of frameworks (ADR-0004), and its thread would only add
spans that the existing histograms already summarise. A span per phase (queue, match, flush) would need the context
carried into `SymbolWorker`; it can come later without changing what is recorded now.

### 3. Sampled, and exported only when asked

10% of requests are sampled by default, and nothing leaves the process until an OTLP endpoint is configured: a
venue without a collector pays for the spans of sampled requests and nothing else. Only W3C `traceparent` is read
from callers and no baggage is carried, so a caller cannot put its own values into the venue's context. The sampler
is parent-based: a caller that marks its request sampled has it traced. That lets a member trace its own requests,
which is useful, and costs at most a span per request it sends anyway, within the throttles (OE-008). Tracing never changes an
answer: a failure to export is the exporter's, outside the request.

### 4. FIX is not traced

FIX messages carry no trace context, and the gateway is framework-free (ADR-0006). FIX orders are followed through
the logs and the journal, by ClOrdID and order ID.

## Consequences

- A new histogram, `lob_request_seconds`, for what REST clients wait for, by instrument, command and outcome.
- Traces join logs (trace IDs in each line) and the journal (`lob.command.sequence`).
- Every REST command now opens an observation, sampled or not; its cost has not been measured separately from
  the request's HTTP handling, and the phase 6 numbers, taken on the engine service, do not include it. The
  observation stops on the thread that completes the command, the instrument's: one timer sample and, when
  sampled, a span end, as a metric is recorded there today.
