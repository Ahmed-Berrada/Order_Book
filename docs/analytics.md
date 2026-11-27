# Market analytics

`analytics/` is a Python package (standard library only, Python 3.11+) that reads the venue's post-trade
files and reports how the market behaved: activity and prices per day, the venue's fee revenue, trade sizes,
volatility, price impact, and each member's order-to-trade ratio. It is a reader of the files the Java
tools write, nothing more: it never sees the engine or the journals.

| Input | Written by | Needed for |
|---|---|---|
| Trade file | `lob-post-trade trades` ([format](interfaces/trade-file.md)) | Everything but the order-to-trade ratio |
| Order records | `lob-records` ([format](compliance/order-records.md)) | The order-to-trade ratio |

```bash
cd analytics
python3 -m lob_analytics --trades AAPL-trades.csv --records AAPL-records.csv            # Markdown
python3 -m lob_analytics --trades AAPL-trades.csv --bucket 300 --json                   # JSON, 5 minute buckets
python3 -m unittest discover -s tests -t .                                               # the tests
```

Exit codes: `0` success, `1` a file is missing or not in the venue's format, `2` usage error. A test reads the
column lists from the Java sources, so the two sides of each format cannot drift apart.

## Definitions

All figures are per instrument and trade date (the UTC date of PT-005). A **trade** is one trade, counted
once, not one line of the trade file (which has a line per side).

| Metric | Definition |
|---|---|
| Trades, volume, notional | Count, total quantity and total notional of the day's trades |
| VWAP | Notional / volume, to 4 decimal places |
| Open, high, low, close | Prices of the first trade, the highest, the lowest and the last |
| Auction share | Volume traded in uncrosses / volume (PT-003) |
| Fees, rebates, net revenue | Sum of the positive fees, sum of the negative ones, and their total: what the venue keeps |
| Trade size percentiles | Nearest-rank p50, p90, p99 and maximum of trade quantity |
| Buckets | The day is cut into fixed intervals (`--bucket`, 60 s by default) from the epoch; only intervals with a trade count. Each has its last trade price and its **net taker volume**: lots bought by takers minus lots sold by takers. Auction trades have no aggressor and add nothing |
| Realized volatility | Sample standard deviation of the log returns between consecutive traded buckets, per bucket (not annualised). Needs at least two returns |
| Price impact | Least-squares slope of each bucket's price change (from the previous traded bucket) on its net taker volume, with R² (Kyle's lambda, a standard measure of how much a lot of aggressive buying moves the price). Needs at least three observations and some variation in volume |
| Order-to-trade ratio | Messages a member sent (new orders, replaces and its own cancels, including those the venue refused) / trades it took part in, as buyer or seller. A trade between two orders of the same member counts once. Cancels by the venue (expiry, kill switch, IOC remainders) are not the member's messages |

The order-to-trade ratio is the measure of MiFID II RTS 9. The venue does not limit it as orders arrive (that
needs time windows in the engine, ADR-0015 §4); this report measures it after the fact, which is what
surveillance does with it.

## Limits

- No quote-based measures (quoted, effective and realised spreads, depth). They need the state of the book at
  each trade, which the order records hold only implicitly; rebuilding the book in Python would duplicate the
  engine. The market-data feed or a replay (RS-009) is where those belong.
- One instrument and one tick size per trade file, as `lob-post-trade` writes them; reports for several
  instruments are run once per file.
