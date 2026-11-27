# ADR-0019: Post-trade is computed from the journals, in a module of its own

- **Status:** Accepted
- **Date:** 2026-11-27

## Context

Phase 10 asks for the venue's post-trade outputs: a trade file for the central counterparty (CCP) and
maker/taker fees. Rules: [`post-trade.md`](../rulebook/post-trade.md). A venue sends each trade to clearing
at the end of the day (or in near real time) and bills its members monthly; both must agree, to the cent,
with what the venue says it executed.

## Decisions

### 1. From the journals, after the fact

The trade file and the fees are computed by reading an instrument's journals, never inside the engine or
the service. The engine stays as it is: no fee state in snapshots, no new events, no change to the journal
format. A fee schedule can change and the day can be recomputed. A dispute is settled by rerunning the
same tool on the same journal (PT-010). Real-time drop copy already exists for members who want trades as
they happen (ADR-0008).

### 2. A separate module, without frameworks

`post-trade` depends on `engine-journal` only and is plain Java, like the engine and the service (the same
enforcer rule bans Spring). Clearing and billing are separate concerns from matching and journaling, and
are often owned by another team; a module boundary makes that visible.

### 3. Exact arithmetic

Prices and notionals are `BigDecimal`, computed exactly from ticks, the tick size and the quantity. Each
fee is rounded once, half to even, to the currency's decimal places; totals are sums of rounded fees
(PT-007), so a statement always equals the sum of its lines, which is what a member reconciling an invoice
checks first. `double` would lose cents on large notionals.

### 4. The tick size is an input

The journal holds prices in ticks; the tick size belongs to the instrument's configuration (it is not in
the journal header, and changing it means a new instrument, ADR-0014). The tool takes it as an argument.
Writing it into the journal header would need a new journal format version for a value the venue already
has in its configuration; that is not worth it for this phase.

### 5. Auction trades are neither maker nor taker

In an uncross there is no aggressor; the engine reports the later order as the taker only so that the
fields keep a meaning (AU-002). Charging that order the taker rate would make a fee depend on an
arbitrary convention, so both sides of an auction trade are `AUCTION` and pay the auction rate (PT-003).
An auction trade is recognised from the event order alone: after `AuctionPriceDetermined`, before
`PhaseChanged`.

### 6. A neutral file format, with the counterparty hidden

Every CCP has its own format (FIXML, CSV variants, proprietary binary). The file here is a documented CSV
with one line per side of a trade ([trade file](../interfaces/trade-file.md)), from which an adapter for a
given CCP is a mapping. The counterparty member is not in the file: the CCP becomes the counterparty of
both sides (novation), and members do not learn who they traded with. The venue's own records
(RTS 24 export) keep it.

### 7. Refuse what clearing would reject, before writing anything

Two checks run before a line is printed (PT-008). The first catches bad input: each event batch must carry
the timestamp and member of the command with its sequence number, so an event journal copied from another
day or directory is refused, and so is one that lags behind its commands. The second, that each day's
trades balance, cannot fail with this code, since every trade gives one buy and one sell record; it is an
assertion on the tool's own output, kept because a file that does not balance must never reach a CCP and
it costs one pass. The output is printed only once everything is computed, so a refusal leaves no partial
file.

## Not done

- **Option chains.** Options are a new product (strikes, expiries, exercise, a price model), not an
  order type; they would change the instrument model of every module. Out of scope for this project.
- **Billing periods, tiers and caps** (volume tiers, monthly minimums, fee caps per trade). The schedule is
  flat per liquidity. Tiers need a month of trades and are a reporting step on top of the statement.
- **Python analytics** are phase 10b ([analytics](../analytics.md)): they read the records export and this trade file.
