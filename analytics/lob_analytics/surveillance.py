"""Market-abuse indicators over the order records (docs/analytics.md, "Surveillance").

These are alerts for a person to review, the first stage of surveillance under MAR art. 16: each names the
member, the orders and the times that triggered it. None of them is a finding that abuse took place.
"""

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import timezone
from statistics import median
from typing import Dict, List, Optional, Sequence, Tuple

from .files import OrderRecord


@dataclass(frozen=True)
class Alert:
    kind: str                 # WASH_TRADE, SPOOFING, EXCESSIVE_CANCELLATION
    trade_date: str
    instrument: str
    member_id: int
    summary: str
    evidence: Dict[str, object] = field(default_factory=dict)


@dataclass(frozen=True)
class Thresholds:
    """What triggers an alert. The defaults are starting points for a venue to calibrate, not standards."""
    spoof_size_multiple: float = 5.0      # a large order: at least this many times the day's median order size
    spoof_window_seconds: float = 60.0    # entered and cancelled within this time
    spoof_max_filled_share: float = 0.1   # at most this share of it traded before the cancel
    cancel_min_orders: int = 50           # excessive cancellation needs at least this many orders in the day
    cancel_ratio: float = 0.9             # and at least this share of them cancelled by the member


def _date(r: OrderRecord) -> str:
    return r.timestamp.astimezone(timezone.utc).date().isoformat()


def _opposite(side: str) -> str:
    return "SELL" if side == "BUY" else "BUY"


def wash_trades(records: Sequence[OrderRecord]) -> List[Alert]:
    """Trades whose buyer and seller are the same member, per day, instrument and member."""
    found: Dict[Tuple[str, str, int], List[OrderRecord]] = defaultdict(list)
    for r in records:
        if r.record == "TRADE" and r.member_id == r.counterparty:
            found[(_date(r), r.instrument, r.member_id)].append(r)
    alerts = []
    for (date, instrument, member), trades in sorted(found.items()):
        lots = sum(int(t.quantity) for t in trades)
        alerts.append(Alert("WASH_TRADE", date, instrument, member,
                            f"{len(trades)} trade(s) with itself, {lots} lots",
                            {"trades": len(trades), "quantity": lots,
                             "orders": sorted({int(t.order_id) for t in trades}
                                              | {int(t.counterparty_order_id) for t in trades
                                                 if t.counterparty_order_id})}))
    return alerts


def spoofing(records: Sequence[OrderRecord], thresholds: Thresholds = Thresholds()) -> List[Alert]:
    """A large order cancelled unfilled soon after entry, while its member traded on the other side."""
    orders: Dict[Tuple[str, int], OrderRecord] = {}                 # (instrument, order ID) -> NEW_ORDER
    filled: Dict[Tuple[str, int], int] = defaultdict(int)
    sizes: Dict[Tuple[str, str], List[int]] = defaultdict(list)    # (date, instrument) -> order sizes
    member_trades: Dict[Tuple[str, int], List[Tuple[object, str, int]]] = defaultdict(list)
    for r in records:
        if r.record == "NEW_ORDER" and r.order_id:
            orders[(r.instrument, int(r.order_id))] = r
            sizes[(_date(r), r.instrument)].append(int(r.quantity))
        elif r.record == "TRADE":
            lots = int(r.quantity)
            filled[(r.instrument, int(r.order_id))] += lots
            if r.counterparty_order_id:
                filled[(r.instrument, int(r.counterparty_order_id))] += lots
            member_trades[(r.instrument, r.member_id)].append((r.timestamp, r.side, lots))          # the taker
            member_trades[(r.instrument, r.counterparty)].append((r.timestamp, _opposite(r.side), lots))  # maker
    medians = {key: median(values) for key, values in sizes.items()}
    alerts = []
    for r in records:
        if r.record != "ORDER_CANCELLED" or r.detail != "CLIENT_REQUEST" or not r.order_id:
            continue
        entry = orders.get((r.instrument, int(r.order_id)))
        if entry is None or entry.member_id != r.member_id:
            continue
        size = int(entry.quantity)
        day_median = medians[(_date(entry), entry.instrument)]
        held = (r.timestamp - entry.timestamp).total_seconds()
        traded = filled[(r.instrument, int(r.order_id))]
        if (size < thresholds.spoof_size_multiple * day_median or held > thresholds.spoof_window_seconds
                or traded > thresholds.spoof_max_filled_share * size):
            continue
        other_side = [(t, lots) for t, side, lots in member_trades[(r.instrument, r.member_id)]
                      if side == _opposite(entry.side) and entry.timestamp <= t <= r.timestamp]
        if not other_side:
            continue
        lots = sum(q for _, q in other_side)
        alerts.append(Alert("SPOOFING", _date(entry), entry.instrument, entry.member_id,
                            f"{entry.side} {size} lots (x{size / day_median:.1f} the median) cancelled after "
                            f"{held:.1f} s, {lots} lots traded on the other side meanwhile",
                            {"order_id": int(entry.order_id), "side": entry.side, "quantity": size,
                             "price_ticks": entry.price_ticks, "entered": entry.timestamp.isoformat(),
                             "cancelled": r.timestamp.isoformat(), "filled_before_cancel": traded,
                             "other_side_trades": len(other_side), "other_side_quantity": lots}))
    return sorted(alerts, key=lambda a: (a.trade_date, a.instrument, a.member_id, a.evidence["order_id"]))


def excessive_cancellation(records: Sequence[OrderRecord], thresholds: Thresholds = Thresholds()) -> List[Alert]:
    """Members who cancel nearly every order they enter, per day and instrument."""
    entered: Dict[Tuple[str, str, int], int] = defaultdict(int)
    cancelled: Dict[Tuple[str, str, int], int] = defaultdict(int)
    for r in records:
        key = (_date(r), r.instrument, r.member_id)
        if r.record == "NEW_ORDER":
            entered[key] += 1
        elif r.record == "ORDER_CANCELLED" and r.detail == "CLIENT_REQUEST":
            cancelled[key] += 1
    alerts = []
    for key in sorted(entered):
        n, c = entered[key], cancelled[key]
        if n >= thresholds.cancel_min_orders and c >= thresholds.cancel_ratio * n:
            date, instrument, member = key
            alerts.append(Alert("EXCESSIVE_CANCELLATION", date, instrument, member,
                                f"{c} of {n} orders cancelled ({c / n:.0%})", {"orders": n, "cancelled": c}))
    return alerts


def alerts(records: Sequence[OrderRecord], thresholds: Optional[Thresholds] = None) -> List[Alert]:
    """Every alert, wash trades first, then spoofing, then excessive cancellation."""
    t = thresholds or Thresholds()
    return wash_trades(records) + spoofing(records, t) + excessive_cancellation(records, t)
