"""The analytics on files written by the venue's own tools (tests/data), checked against each other."""

import unittest
from collections import Counter
from decimal import Decimal
from pathlib import Path

from lob_analytics import metrics
from lob_analytics.files import read_records, read_trades

DATA = Path(__file__).parent / "data"


class SampleTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        with open(DATA / "sample-trades.csv", encoding="utf-8", newline="") as f:
            cls.trades = read_trades(f)
        with open(DATA / "sample-records.csv", encoding="utf-8", newline="") as f:
            cls.records = read_records(f)

    def test_every_trade_has_a_buyer_then_a_seller(self):
        self.assertEqual(set(Counter(t.trade_id for t in self.trades).values()), {2})
        for buy, sell in zip(self.trades[::2], self.trades[1::2]):
            self.assertEqual((buy.trade_id, buy.side, sell.side), (sell.trade_id, "BUY", "SELL"))
            self.assertEqual((buy.quantity, buy.price, buy.notional), (sell.quantity, sell.price, sell.notional))
            self.assertEqual(buy.liquidity == "AUCTION", sell.liquidity == "AUCTION")

    def test_the_trade_file_and_the_order_records_agree(self):
        # Two exports of one journal, by two tools: the same trades, the same quantities.
        record_trades = [r for r in self.records if r.record == "TRADE"]
        file_trades = [t for t in self.trades if t.side == "BUY"]
        self.assertEqual(len(record_trades), len(file_trades))
        self.assertEqual(sum(int(r.quantity) for r in record_trades), sum(t.quantity for t in file_trades))

    def test_the_summary_is_consistent(self):
        (day,) = metrics.daily_summary(self.trades)
        self.assertGreater(day.trades, 100)
        self.assertTrue(day.low <= day.vwap <= day.high)
        self.assertGreater(day.auction_volume, 0)
        self.assertEqual(day.fees + day.rebates, sum((t.fee for t in self.trades), Decimal(0)))
        series = metrics.buckets(self.trades, day.instrument, day.trade_date, 60)
        self.assertEqual(sum(b.signed_volume for b in series),
                         sum(t.quantity if t.side == "BUY" else -t.quantity for t in self.trades
                             if t.liquidity == "TAKER"))
        self.assertIsNotNone(metrics.realized_volatility(series))
        self.assertIsNotNone(metrics.price_impact(series))

    def test_every_member_has_a_ratio(self):
        ratios = metrics.order_to_trade(self.records)
        self.assertEqual([o.member_id for o in ratios], [1, 2, 3, 4, 5])
        new_orders = sum(1 for r in self.records if r.record in ("NEW_ORDER", "NEW_ORDER_REJECTED"))
        self.assertGreaterEqual(sum(o.messages for o in ratios), new_orders)
        self.assertTrue(all(o.trades > 0 for o in ratios))
