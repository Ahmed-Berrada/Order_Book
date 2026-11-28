"""python -m lob_analytics --trades TRADES.csv [--records RECORDS.csv] [--bucket SECONDS] [--json]"""

import argparse
import contextlib
import json
import sys
from typing import List, Optional, TextIO

from . import metrics
from .files import FormatError, read_records, read_trades


def report(trades_file: TextIO, records_file: Optional[TextIO], bucket_seconds: int) -> dict:
    """Every metric, as plain data."""
    lines = read_trades(trades_file)
    days = []
    for day in metrics.daily_summary(lines):
        series = metrics.buckets(lines, day.instrument, day.trade_date, bucket_seconds)
        day_lines = [l for l in lines if l.instrument == day.instrument and l.trade_date == day.trade_date]
        vol = metrics.realized_volatility(series)
        impact = metrics.price_impact(series)
        days.append({
            "trade_date": day.trade_date, "instrument": day.instrument, "trades": day.trades,
            "volume": day.volume, "notional": str(day.notional), "vwap": str(day.vwap),
            "open": str(day.open), "high": str(day.high), "low": str(day.low), "close": str(day.close),
            "auction_share": round(day.auction_share, 6), "fees": str(day.fees), "rebates": str(day.rebates),
            "net_revenue": str(day.net_revenue),
            "trade_size_percentiles": {f"p{p}": q for p, q in metrics.size_percentiles(day_lines).items()},
            "bucket_seconds": bucket_seconds, "traded_buckets": len(series),
            "realized_volatility": None if vol is None else {"returns": vol.returns, "stdev": vol.stdev},
            "price_impact": None if impact is None else {"observations": impact.observations,
                                                         "slope": impact.slope, "r_squared": impact.r_squared},
        })
    result = {"days": days}
    if records_file is not None:
        result["order_to_trade"] = [{"trade_date": o.trade_date, "instrument": o.instrument, "member_id": o.member_id, "messages": o.messages,
                                     "trades": o.trades, "ratio": o.ratio}
                                    for o in metrics.order_to_trade(read_records(records_file))]
    return result


def markdown(data: dict) -> str:
    out: List[str] = ["# Market report", ""]
    for d in data["days"]:
        out += [f"## {d['instrument']} on {d['trade_date']}", "",
                "| Trades | Volume | Notional | VWAP | Open | High | Low | Close | Auction share | Net revenue |",
                "|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
                f"| {d['trades']} | {d['volume']} | {d['notional']} | {d['vwap']} | {d['open']} | {d['high']} "
                f"| {d['low']} | {d['close']} | {d['auction_share']:.1%} | {d['net_revenue']} |", ""]
        sizes = ", ".join(f"{k} {v}" for k, v in d["trade_size_percentiles"].items())
        out.append(f"- Trade sizes (lots): {sizes}")
        vol = d["realized_volatility"]
        out.append(f"- Realized volatility over {d['bucket_seconds']} s buckets: "
                   + ("not enough traded buckets" if vol is None
                      else f"{vol['stdev']:.6f} per bucket ({vol['returns']} returns)"))
        impact = d["price_impact"]
        out.append("- Price impact: " + ("not enough data" if impact is None
                                         else f"{impact['slope']:.6g} per lot of net taker buying, "
                                              f"R² {impact['r_squared']:.3f} ({impact['observations']} buckets)"))
        out.append("")
    if "order_to_trade" in data:
        out += ["## Order-to-trade ratio", "", "| Date | Instrument | Member | Messages | Trades | Ratio |",
                "|---|---|---:|---:|---:|---:|"]
        for o in data["order_to_trade"]:
            ratio = "no trades" if o["ratio"] is None else f"{o['ratio']:.2f}"
            out.append(f"| {o['trade_date']} | {o['instrument']} | {o['member_id']} | {o['messages']} | {o['trades']} | {ratio} |")
        out.append("")
    return "\n".join(out)


def main(argv: Optional[List[str]] = None, stdout: TextIO = sys.stdout, stderr: TextIO = sys.stderr) -> int:
    parser = argparse.ArgumentParser(prog="lob_analytics", description="Market-microstructure report")
    parser.add_argument("--trades", required=True, help="trade file from lob-post-trade")
    parser.add_argument("--records", help="order records from lob-records, for the order-to-trade ratio")
    parser.add_argument("--bucket", type=int, default=60, help="bucket length in seconds (default 60)")
    parser.add_argument("--json", action="store_true", help="print JSON instead of Markdown")
    try:
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            args = parser.parse_args(argv)
    except SystemExit as e:
        return 2 if e.code else 0
    if args.bucket <= 0:
        print("error: --bucket must be positive", file=stderr)
        return 2
    try:
        with open(args.trades, encoding="utf-8", newline="") as trades:
            if args.records:
                with open(args.records, encoding="utf-8", newline="") as records:
                    data = report(trades, records, args.bucket)
            else:
                data = report(trades, None, args.bucket)
    except (OSError, FormatError) as e:
        print(f"error: {e}", file=stderr)
        return 1
    print(json.dumps(data, indent=2) if args.json else markdown(data), file=stdout)
    return 0


if __name__ == "__main__":
    sys.exit(main())
