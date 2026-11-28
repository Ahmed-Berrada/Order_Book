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
book can still be read, except after a failed write or flush of the command journal (RS-001) or a failed
replication to the standby (RS-015): the instrument's memory may then disagree with its journal about commands
whose outcome is unknown, so reads are refused with `HALTED` too until the restart. Other instruments are not affected.

**OE-007: Orderly shutdown.** When the venue shuts down, the commands already queued are processed and
journaled before the journals are closed. Commands arriving after the shutdown has started are refused
with `STOPPED`.

## Throttles

These two rules protect the venue and its other members from one member's flow of messages (MiFID II
art. 48(1) and (6), RTS 7 art. 18, RTS 9). They apply to members other than `0`, whatever the protocol, and
are checked when a command is submitted, before it is queued, so a refused command is not journaled and
does not consume an order ID. A cancel, of one order or of all (RK-006), is never refused by them: a member must
always be able to reduce what it has in the market. Design: [ADR-0021](../adr/0021-ingress-throttles.md).

**OE-008: Message rate.** Each member may submit at most `messagesPerSecond` new orders and replaces a
second, all instruments together, with bursts of up to one second's worth (a token bucket that refills
continuously). A command over the limit is refused with `THROTTLED`, which says how long to wait before
the next is accepted. `0` means no limit. Cancels are not counted.

**OE-009: Order-to-trade ratio.** For each member and instrument the venue counts, per UTC day, the
messages it submitted (new orders, replaces, cancels and mass cancels, each one message) and the trades it took part in, as taker or maker.
Once a member has submitted at least `orderToTradeMinimum` messages in an instrument that day, a new order
or replace that would take its messages above `maxOrderToTradeRatio` times its trades (at least one trade)
is refused with `ORDER_TO_TRADE_LIMIT`, which says how long until the end of the UTC day. The refusal lasts
until the member's trades bring the ratio back under the limit, or at most until the day ends; cancels are
still accepted and counted. A command that the instrument refuses before queueing it (OE-005 to OE-007) is
not counted by either rule. `0` means no limit. The counts are kept in memory: a restart of the venue starts
them again from zero.
