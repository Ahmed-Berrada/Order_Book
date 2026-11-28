"""The metrics of docs/analytics.md. Money is exact (Decimal); statistics are floats."""

import math
from datetime import timezone
from collections import defaultdict
from dataclasses import dataclass
from decimal import Decimal
from typing import Dict, List, Optional, Sequence, Tuple

from .files import OrderRecord, TradeLine


def _trades(lines: Sequence[TradeLine]) -> List[TradeLine]:
    """One line per trade: the buyer's. The trade file has the buyer first (PT-002)."""
    return [line for line in lines if line.side == "BUY"]


# ---- Daily summary ---------------------------------------------------------------------------------------

@dataclass(frozen=True)
class DailySummary:
    trade_date: str
    instrument: str
    trades: int
    volume: int
    notional: Decimal
    vwap: Decimal
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    auction_volume: int
    fees: Decimal
    rebates: Decimal

    @property
    def auction_share(self) -> float:
        return self.auction_volume / self.volume

    @property
    def net_revenue(self) -> Decimal:
        """What the venue keeps: fees charged plus rebates paid (negative)."""
        return self.fees + self.rebates


def daily_summary(lines: Sequence[TradeLine], vwap_places: int = 4) -> List[DailySummary]:
    """Per trade date and instrument, in that order: activity, prices and fees."""
    days: Dict[Tuple[str, str], List[TradeLine]] = defaultdict(list)
    for line in lines:
        days[(line.trade_date, line.instrument)].append(line)
    summaries = []
    for (date, instrument), day in sorted(days.items()):
        trades = _trades(day)
        volume = sum(t.quantity for t in trades)
        notional = sum((t.notional for t in trades), Decimal(0))
        prices = [t.price for t in trades]
        quantum = Decimal(1).scaleb(-vwap_places)
        summaries.append(DailySummary(
            date, instrument, len(trades), volume, notional, (notional / volume).quantize(quantum),
            prices[0], max(prices), min(prices), prices[-1],
            sum(t.quantity for t in trades if t.liquidity == "AUCTION"),
            sum((line.fee for line in day if line.fee > 0), Decimal(0)),
            sum((line.fee for line in day if line.fee < 0), Decimal(0))))
    return summaries


# ---- Order-to-trade ratio --------------------------------------------------------------------------------

#: Records that are a message from the member: an order, a change or a cancel it sent, accepted or not.
#: MEMBER_COMMAND_REJECTED is not one: it refuses an operator's command about the member (limits, suspension).
MESSAGES = {"NEW_ORDER", "NEW_ORDER_REJECTED", "ORDER_REPLACED", "REPLACE_REJECTED", "CANCEL_REJECTED"}


@dataclass(frozen=True)
class OrderToTrade:
    trade_date: str
    instrument: str
    member_id: int
    messages: int
    trades: int

    @property
    def ratio(self) -> Optional[float]:
        """Messages per trade, or None for a member that did not trade."""
        return self.messages / self.trades if self.trades else None


def order_to_trade(records: Sequence[OrderRecord]) -> List[OrderToTrade]:
    """Per UTC date, instrument and member: messages sent and trades taken part in (RTS 9, after the fact)."""
    messages: Dict[Tuple[str, str, int], int] = defaultdict(int)
    trades: Dict[Tuple[str, str, int], int] = defaultdict(int)
    for r in records:
        date = r.timestamp.astimezone(timezone.utc).date().isoformat()
        if r.record in MESSAGES or (r.record == "ORDER_CANCELLED" and r.detail == "CLIENT_REQUEST"):
            messages[(date, r.instrument, r.member_id)] += 1
        elif r.record == "TRADE":
            for member in {r.member_id, r.counterparty}:                   # a trade with itself counts once
                trades[(date, r.instrument, member)] += 1
    keys = sorted(set(messages) | set(trades))
    return [OrderToTrade(d, i, m, messages[(d, i, m)], trades[(d, i, m)]) for d, i, m in keys]


# ---- Buckets: volatility and price impact ----------------------------------------------------------------

@dataclass(frozen=True)
class Bucket:
    start: int            # seconds since the epoch, a multiple of the bucket length
    last_price: float
    signed_volume: int    # bought minus sold by takers; auction trades have no aggressor


def buckets(lines: Sequence[TradeLine], instrument: str, trade_date: str, seconds: int) -> List[Bucket]:
    """Buckets of one instrument and day that had at least one trade, in time order."""
    if seconds <= 0:
        raise ValueError("bucket length must be positive")
    found: Dict[int, List] = {}
    for line in lines:
        if line.instrument != instrument or line.trade_date != trade_date:
            continue
        start = int(line.timestamp.timestamp()) // seconds * seconds
        bucket = found.setdefault(start, [0.0, 0])
        if line.side == "BUY":
            bucket[0] = float(line.price)                          # trades are in time order
        if line.liquidity == "TAKER":
            bucket[1] += line.quantity if line.side == "BUY" else -line.quantity
    return [Bucket(start, price, signed) for start, (price, signed) in sorted(found.items())]


@dataclass(frozen=True)
class Volatility:
    returns: int
    stdev: float          # sample standard deviation of log returns between consecutive traded buckets


def realized_volatility(series: Sequence[Bucket]) -> Optional[Volatility]:
    """None with fewer than two returns: one return has no spread."""
    returns = [math.log(b.last_price / a.last_price) for a, b in zip(series, series[1:])]
    if len(returns) < 2:
        return None
    mean = sum(returns) / len(returns)
    variance = sum((r - mean) ** 2 for r in returns) / (len(returns) - 1)
    return Volatility(len(returns), math.sqrt(variance))


@dataclass(frozen=True)
class PriceImpact:
    observations: int
    slope: float          # price change per lot of net taker buying (Kyle's lambda)
    r_squared: float


def price_impact(series: Sequence[Bucket]) -> Optional[PriceImpact]:
    """Least squares of each bucket's price change on its net taker volume. None if it cannot be fitted."""
    pairs = [(b.signed_volume, b.last_price - a.last_price) for a, b in zip(series, series[1:])]
    if len(pairs) < 3:
        return None
    n = len(pairs)
    mean_x = sum(x for x, _ in pairs) / n
    mean_y = sum(y for _, y in pairs) / n
    sxx = sum((x - mean_x) ** 2 for x, _ in pairs)
    syy = sum((y - mean_y) ** 2 for _, y in pairs)
    sxy = sum((x - mean_x) * (y - mean_y) for x, y in pairs)
    if sxx == 0:
        return None
    slope = sxy / sxx
    r_squared = (sxy * sxy) / (sxx * syy) if syy else 0.0
    return PriceImpact(n, slope, r_squared)


# ---- Trade sizes -----------------------------------------------------------------------------------------

def size_percentiles(lines: Sequence[TradeLine], percentiles=(50, 90, 99, 100)) -> Dict[int, int]:
    """Nearest-rank percentiles of trade quantity; empty for no trades."""
    sizes = sorted(t.quantity for t in _trades(lines))
    if not sizes:
        return {}
    return {p: sizes[max(1, math.ceil(p / 100 * len(sizes))) - 1] for p in percentiles}
