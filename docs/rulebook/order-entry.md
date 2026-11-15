# Rulebook: order entry

This chapter specifies how commands reach the matching engine of each instrument, whatever the access
protocol (REST today, FIX next). As in the other chapters, each rule ID is cited by at least one test
through `@Rulebook("OE-xxx")`. The design is in [ADR-0004](../adr/0004-service-layer.md).

## Instruments and prices

**OE-001: Listed instruments only.** Only instruments declared in the venue configuration can be
traded. A command for any other symbol is refused with `UNKNOWN_INSTRUMENT`; it is not journaled.

**OE-002: Tick size.** Members send prices as decimals. A price must be an exact multiple of the
instrument's tick size (for example 0.01): `100.25` is valid, `100.255` is not. An off-tick price is
refused before it reaches the engine, so it is not journaled and consumes no order ID. Prices in
responses are converted back from ticks with the same tick size. Quantities are whole lots.

## Processing

**OE-003: One queue per instrument.** The commands of an instrument are processed one at a time, in
the order they entered its queue. Instruments are independent: a busy instrument never delays another.

**OE-004: Acknowledgement.** A response is sent only once the command has been journaled and processed
(RS-001). It carries the command's sequence number, its timestamp and all of its events.

**OE-005: Backpressure.** Each instrument's queue has a fixed capacity. When it is full, a new command
is refused at once with `OVERLOADED` rather than made to wait; the refused command is not journaled.
Members may resend it.

**OE-006: Halt.** If an instrument's journal cannot be written, the instrument stops accepting
commands and refuses them with `HALTED` until the venue is restarted and has recovered (RS-002). Its
book can still be read. Other instruments are not affected.

**OE-007: Orderly shutdown.** When the venue shuts down, the commands already queued are processed and
journaled before the journals are closed. Commands arriving after the shutdown has started are refused
with `STOPPED`.
