# ADR-0021: Ingress throttles: message rate and order-to-trade ratio

- **Status:** Accepted
- **Date:** 2026-11-28

## Context

A venue must be able to slow down or stop one member's flow of orders before it degrades the venue for
everyone (MiFID II art. 48(1), RTS 7 art. 18), and must limit the ratio of a member's unexecuted orders to
its transactions (art. 48(6), RTS 9). The per-order limits of RK-002 do not do this: a member can send
thousands of small, valid orders a second, each of which costs a journal write. The regulatory mapping has
listed "no message-rate limit" as a gap since phase 5. Rules: OE-008 and OE-009.

## Decisions

### 1. In the service, before the queue, not in the engine

The limits depend on the clock, and the engine never reads one (RS-006). Put in the engine, they would also
have to be journaled to replay the same way. So they are checked by `MatchingService.submit`, the one entry
point that REST and FIX share, before the command is queued. A refused command never reaches the journal,
like `OVERLOADED` (OE-005): it costs nothing but the check, which is the point of a throttle.

The consequence is that the counts are not part of the engine's state. A restart starts them from zero
(OE-009 says so). Rebuilding them from the journal at start would be possible, and is not worth the
complexity while the ratio is a protection rather than a fee: RTS 9 asks the venue to monitor and act on
the ratio, and a restart is rare.

### 2. A token bucket per member for the rate

The bucket holds up to one second's worth of messages and refills continuously, so a member can send a
short burst but not sustain more than the rate. It is per member, across instruments and protocols: the
cost a member imposes is on the venue, not on one book. The refusal says how long until the next message
is accepted, which REST sends as `Retry-After`.

### 3. The ratio is per member and instrument, per UTC day, and live

RTS 9 computes the ratio per instrument. Counting it as the day goes, rather than in the end-of-day
analytics, lets the venue stop a member while it is happening. A minimum number of messages keeps the
limit from firing on the first orders of the day, when one unfilled order is already an infinite ratio.
Trades are counted from the events once they are durable (ADR-0020), from both sides.

### 4. Cancels are never refused

A throttle that refused cancels would leave a member unable to withdraw its orders exactly when it is
sending too much, which is when it most needs to. Cancels are not counted for the rate; they are counted in
the ratio's messages, as RTS 9 counts them.

### 5. The operator is exempt

Member `0` (ADR-0015) is the operator and is never throttled, as it has no risk limits.

## Consequences

- Two new refusal reasons, `THROTTLED` and `ORDER_TO_TRADE_LIMIT`: REST answers `429 Too Many Requests` with
  `Retry-After`; FIX rejects the order or the replace with `OrdRejReason`/`CxlRejReason` `OTHER` and the reason
  as `Text`.
- The checks are approximate under concurrency: commands a member submits at the same moment as the one that
  reaches its limit may still be queued.
- A command the instrument refuses before queueing it (`OVERLOADED`, `HALTED`, `STOPPED`) gives back what it
  counted, so a member retrying on `OVERLOADED` does not drain its own limits.
- The rate refills from the venue's clock. If the clock steps back, the bucket refills nothing for that step
  and then refills from the new time; it never stops refilling. Messages are dated by the clock when they are
  submitted, trades by their journal timestamp, which never goes back; if the clock steps back across
  midnight, the two can be counted on different days until it catches up.
- A metric counts the refusals by member and reason, so the operator sees who is throttled.
