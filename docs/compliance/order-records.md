# Order records

A trading venue keeps a record of every order and what happened to it (MiFID II art. 25(2); RTS 24, Delegated
Regulation 2017/580). The journal already holds everything the engine knew, in binary; `lob-records` turns it
into flat records for storage and reporting. This page says what the export contains and what it cannot,
using the regulation's field *categories* in plain words: check the exact fields against the text of RTS 24.
Not legal advice; see [the regulatory mapping](regulatory-mapping.md).

## Running it

```bash
java -cp engine-journal/target/classes:engine-core/target/classes \
  io.github.ahmedberrada.lob.journal.RecordsExport <data-directory>/AAPL > AAPL-records.csv
```

Read-only: the journals are never modified. Exit code `0` on success, `1` for an unreadable journal or a
command journal that does not match the event journal, `2` for bad usage.

## Columns

| Column | Content |
|---|---|
| `timestamp_utc` | The command's ingress time, ISO-8601 UTC with microseconds. Every event of a command shares it (RS-006) |
| `instrument` | The instrument's symbol |
| `command_sequence`, `event_sequence` | Positions in the command and event journals: gap-free, so a missing record is detectable |
| `member_id` | The member whose command produced the record; for a trade, the taker's member; for a member event, that member |
| `order_id` | The venue's order identifier, unique per instrument |
| `record` | `NEW_ORDER`, `NEW_ORDER_REJECTED`, `ORDER_RESTED`, `ORDER_REPLACED`, `REPLACE_REJECTED`, `ORDER_CANCELLED` (with the reason), `CANCEL_REJECTED`, `STOP_PARKED`, `STOP_TRIGGERED`, `ICEBERG_REPLENISHED`, `TRADE`, and venue events: `PHASE_CHANGED`, `PHASE_CHANGE_REJECTED`, `AUCTION_PRICE`, `VOLATILITY_INTERRUPTION`, `MEMBER_LIMITS_CHANGED`, `MEMBER_SUSPENDED`, `MEMBER_RESUMED`, `MEMBER_COMMAND_REJECTED`, `MASS_CANCELLED` (with the number of orders cancelled as the quantity; the cancels follow as `ORDER_CANCELLED` with reason `MASS_CANCEL`) |
| `side` | `BUY` or `SELL`; for a trade, the aggressor's side; `BOTH` for a mass cancel of both sides |
| `order_type`, `time_in_force` | `LIMIT`, `MARKET`, `STOP`, `STOP_LIMIT`; `GTC`, `IOC`, `FOK`, `DAY` (on `NEW_ORDER`) |
| `price_ticks`, `quantity` | Price in ticks of the instrument and quantity in lots, as journaled; the tick size is in the instrument's configuration |
| `counterparty_order_id`, `counterparty_member_id` | On a `TRADE`, the resting order and its member |
| `detail` | The reason of a rejection or cancellation, whether a replace kept its priority, the stop price of a stop order |

## What it covers, and what it cannot

| Category of the regulation | Covered | Note |
|---|---|---|
| Date and time of each event, to the microsecond | Yes | Stamped once at ingress; keeping the clock within the divergence RTS 25 allows is an operational control |
| Order identification code, assigned by the venue | Yes | `order_id` with `instrument` |
| Instrument | Partly | The symbol; an ISIN or other identifier must come from reference data the venue does not hold |
| Buy or sell, price, quantity | Yes | In ticks and lots |
| Order type, validity period, restrictions (post-only, iceberg display size) | Partly | Type and time in force are exported; post-only and display size are in the journal but not yet columns |
| Order events: new, modification, cancellation, rejection, trade | Yes | With reasons |
| Member (the participant) | Partly | The venue's member number; the member's LEI is reference data the venue does not hold |
| Client, trader, algorithm and decision-maker identification, direct electronic access flag, trading capacity | **No** | The venue does not know them: FIX tags such as `PartyID` are not accepted yet. A venue needs them from its members |
| Trading phase and venue events | Yes | Extra, useful for reconstruction |

## Retention

The regulation requires five years. The journal directory is the record; backup, retention and write-once
storage are operational controls (phase 9), not something the engine does.
