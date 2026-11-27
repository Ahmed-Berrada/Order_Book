# Rulebook: post-trade

- **Status:** Draft

After the trading day, the venue hands its trades to clearing and charges its members. Both are computed
from the journals, never from the running engine: the journals are the record (RS-006, RS-007), and a
computation that only reads them can be rerun, checked and corrected without touching trading. This
chapter defines the clearing trade file and the fees. Every rule ID is cited by tests through
`@Rulebook("PT-xxx")`. Design: [ADR-0019](../adr/0019-post-trade.md). Tool and file format:
[trade file](../interfaces/trade-file.md).

## Trades

**PT-001: Trade identity.** A trade is identified by its instrument and the event sequence number of its
`TradeExecuted` (`AAPL-42`). The identifier never changes: replaying the same journal gives the same
trades with the same identifiers (RS-009).

**PT-002: Two sides.** Every trade gives two records, one for the buyer and one for the seller, each with
its own member and order. The buyer is the taker if the aggressor side is `BUY`, and the maker otherwise.
A trade between two orders of one member gives two records for that member.

**PT-003: Liquidity.** Each record is marked with how its order took part in the trade:

- `TAKER`: the order that arrived and traded against the book (the aggressor of CT-001), including a
  released stop and an order whose replace made it trade;
- `MAKER`: the resting order it traded with;
- `AUCTION`: both orders of a trade of an uncross. A trade is part of an uncross if it follows an
  `AuctionPriceDetermined` and precedes the `PhaseChanged` of the same command (TP-003, AU-002). Trades
  later in that command, from stops released when trading continues, are `TAKER` and `MAKER`.

The taker and maker fields of an auction trade only follow the convention of AU-002; they say nothing
about who provided liquidity, so neither side is charged as a taker.

**PT-004: Price and notional.** A record carries the price in ticks, as the journal holds it, and in
currency: the ticks times the instrument's tick size. Its notional is the price in currency times the
quantity, computed exactly (no rounding).

**PT-005: Trade date.** The trade date is the UTC calendar date of the timestamp of the command that made
the trade (RS-006). The engine reads no clock (CT-015 needs it to be deterministic); the timestamp is the journal's.

## Fees

**PT-006: Schedule.** A fee schedule gives one rate per liquidity (`MAKER`, `TAKER`, `AUCTION`), in basis
points of the notional, and the number of decimal places of the currency. A rate may be negative: the
member is paid a rebate. A rate is between -100 and 100 basis points.

**PT-007: Fee of a record.** The fee of a record is its notional times the rate of its liquidity divided
by 10,000, rounded to the currency's decimal places, half to even. Each record is rounded once, on its
own; totals are sums of rounded fees, so a statement always equals the sum of its records.

## Checks and statements

**PT-008: Balance.** For each instrument, the quantity bought equals the quantity sold and the notional
bought equals the notional sold, in total and per trade date. A journal whose trades do not balance is
refused: no file is produced and the tool reports the first imbalance.

**PT-009: Statement.** A member's statement gives, per trade date and instrument: the number of records,
the quantity and notional bought and sold, and the total fee. It is computed from the same records as the
trade file.

**PT-010: Read-only and repeatable.** Producing the trade file or a statement never writes to a journal.
The same journals and the same schedule always give byte-identical output.
