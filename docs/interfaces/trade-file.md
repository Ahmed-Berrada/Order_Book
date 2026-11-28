# Trade file and member statements

The venue's post-trade outputs, produced from an instrument's journals by `lob-post-trade`
([post-trade rules](../rulebook/post-trade.md), [ADR-0019](../adr/0019-post-trade.md)). Both are CSV with a
header line, UTF-8, `\n` line endings, no quoting (no field can contain a comma).

## Running it

```bash
./mvnw -q install -DskipTests
CP=engine-core/target/engine-core-0.1.0-SNAPSHOT.jar:engine-journal/target/engine-journal-0.1.0-SNAPSHOT.jar:post-trade/target/post-trade-0.1.0-SNAPSHOT.jar
java -cp "$CP" io.github.ahmedberrada.lob.posttrade.PostTradeTool trades data/AAPL \
    --tick-size 0.01 --fees maker=-0.20,taker=0.30,auction=0.10 > AAPL-trades.csv
java -cp "$CP" io.github.ahmedberrada.lob.posttrade.PostTradeTool statement data/AAPL \
    --tick-size 0.01 --fees maker=-0.20,taker=0.30,auction=0.10 > AAPL-statement.csv
```

| Argument | Meaning |
|---|---|
| `trades` or `statement` | Which output |
| `<instrument-directory>` | The instrument's journal directory (`<data-directory>/<symbol>`) |
| `--tick-size` | The instrument's tick size, as configured (`lob.instruments[].tick-size`). The journal holds prices in ticks only |
| `--fees` | Rates in basis points of the notional for `maker`, `taker` and `auction`, each between -100 and 100; negative is a rebate |
| `--currency-scale` | Decimal places fees are rounded to (default `2`) |

The tool only reads. Run it when no commands arrive, after the close or with the venue stopped: the command and
event journals of a command are written one after the other, so a command recorded while the tool reads makes
the event journal look behind, and the tool refuses rather than miss trades. Run it again in that case; if it
still refuses, the venue stopped before recording some events, and opening the venue once recovers them
(RS-007). It also refuses an event journal that belongs to other commands (PT-008). A refusal prints nothing
on standard output. Exit codes: `0`
success, `1` unreadable, incomplete or unbalanced journal, `2` usage error.

## Trade file

One line per side of every trade, in the order of the trades, buyer before seller.

| Column | Example | Meaning |
|---|---|---|
| `trade_id` | `AAPL-4` | Instrument and the trade's event sequence number; the two sides share it (PT-001) |
| `trade_date` | `2026-11-26` | UTC date of the command that made the trade (PT-005) |
| `timestamp_utc` | `2026-11-26T09:00:00.000001Z` | The command's timestamp, ISO 8601 in UTC, always six fractional digits |
| `instrument` | `AAPL` | Symbol |
| `member_id` | `2` | The member of this side |
| `order_id` | `2` | The member's order |
| `side` | `BUY` | `BUY` or `SELL` |
| `liquidity` | `TAKER` | `TAKER`, `MAKER` or `AUCTION` (PT-003) |
| `price_ticks` | `18550` | Price in ticks |
| `price` | `185.50` | Price in currency: ticks times the tick size |
| `quantity` | `100` | Lots |
| `notional` | `18550.00` | Price times quantity, exact |
| `fee_rate_bps` | `0.3` | The rate applied, without trailing zeros (`--fees taker=0.30` prints `0.3`) |
| `fee` | `0.56` | The member's fee; negative for a rebate (PT-007) |

The counterparty is not in the file: the CCP becomes the counterparty of both sides. The venue's own record of
who traded with whom is the [order records export](../compliance/order-records.md).

```
trade_id,trade_date,timestamp_utc,instrument,member_id,order_id,side,liquidity,price_ticks,price,quantity,notional,fee_rate_bps,fee
PTX-4,2026-11-26,2026-11-26T09:00:00.000001Z,PTX,2,2,BUY,TAKER,18550,185.50,100,18550.00,0.3,0.56
PTX-4,2026-11-26,2026-11-26T09:00:00.000001Z,PTX,1,1,SELL,MAKER,18550,185.50,100,18550.00,-0.2,-0.37
PTX-9,2026-11-26,2026-11-26T09:00:00.000004Z,PTX,3,3,BUY,AUCTION,18550,185.50,50,9275.00,0.1,0.09
PTX-9,2026-11-26,2026-11-26T09:00:00.000004Z,PTX,1,1,SELL,AUCTION,18550,185.50,50,9275.00,0.1,0.09
```

## Statement

One line per trade date, instrument and member, in that order (PT-009). The fees are the sum of the rounded
fees of the member's lines in the trade file, so the two always agree.

| Column | Meaning |
|---|---|
| `trade_date`, `instrument`, `member_id` | The key |
| `records` | Lines of the trade file for this member |
| `bought_quantity`, `bought_notional` | Bought, in lots and in currency |
| `sold_quantity`, `sold_notional` | Sold, in lots and in currency |
| `fees` | Total fee; negative if rebates exceed fees |

```
trade_date,instrument,member_id,records,bought_quantity,bought_notional,sold_quantity,sold_notional,fees
2026-11-26,PTX,1,2,0,0,150,27825.00,-0.28
2026-11-26,PTX,2,1,100,18550.00,0,0,0.56
2026-11-26,PTX,3,1,50,9275.00,0,0,0.09
```
