import io
import math
import unittest
from decimal import Decimal

from lob_analytics import metrics
from lob_analytics.files import read_records, read_trades
from tests.fixtures import RECORDS, TRADES


class DailySummaryTest(unittest.TestCase):

    def test_prices_volume_and_fees_per_day(self):
        first, second = metrics.daily_summary(read_trades(io.StringIO(TRADES)))
        self.assertEqual((first.trade_date, first.instrument, first.trades, first.volume), ("2026-11-26", "PTX", 5, 400))
        self.assertEqual(first.notional, Decimal("4011.00"))            # 1000 + 495 + 2020 + 300 + 196
        self.assertEqual(first.vwap, Decimal("10.0275"))                # 4011 / 400
        self.assertEqual((first.open, first.high, first.low, first.close),
                         (Decimal("10.00"), Decimal("10.10"), Decimal("9.80"), Decimal("9.80")))
        self.assertEqual(first.auction_volume, 30)
        self.assertAlmostEqual(first.auction_share, 0.075)
        self.assertEqual(first.fees, Decimal("0.14"))                   # four takers and two auction sides
        self.assertEqual(first.rebates, Decimal("-0.08"))               # four makers
        self.assertEqual(first.net_revenue, Decimal("0.06"))
        self.assertEqual((second.trade_date, second.trades, second.vwap, second.fees, second.rebates),
                         ("2026-11-27", 1, Decimal("10.0000"), Decimal(0), Decimal(0)))

    def test_trade_size_percentiles_are_nearest_rank(self):
        day = [line for line in read_trades(io.StringIO(TRADES)) if line.trade_date == "2026-11-26"]
        self.assertEqual(metrics.size_percentiles(day), {50: 50, 90: 200, 99: 200, 100: 200})   # 20 30 50 100 200
        self.assertEqual(metrics.size_percentiles([]), {})


class BucketTest(unittest.TestCase):

    def setUp(self):
        self.lines = read_trades(io.StringIO(TRADES))
        self.series = metrics.buckets(self.lines, "PTX", "2026-11-26", 60)

    def test_last_price_and_net_taker_volume_per_traded_minute(self):
        self.assertEqual([(b.last_price, b.signed_volume) for b in self.series],
                         [(9.90, 50), (10.10, 200), (10.00, 0), (9.80, -20)])   # the auction has no aggressor
        self.assertEqual(self.series[0].start % 60, 0)
        self.assertEqual(metrics.buckets(self.lines, "PTX", "2026-11-25", 60), [])
        self.assertEqual(metrics.buckets(self.lines, "OTHER", "2026-11-26", 60), [])
        with self.assertRaises(ValueError):
            metrics.buckets(self.lines, "PTX", "2026-11-26", 0)

    def test_realized_volatility_is_the_sample_deviation_of_log_returns(self):
        vol = metrics.realized_volatility(self.series)
        self.assertEqual(vol.returns, 3)
        self.assertAlmostEqual(vol.stdev, 0.020890527203037086, places=12)
        self.assertIsNone(metrics.realized_volatility(self.series[:2]))      # one return

    def test_price_impact_is_the_least_squares_slope(self):
        impact = metrics.price_impact(self.series)
        self.assertEqual(impact.observations, 3)                       # (200, 0.2) (0, -0.1) (-20, -0.2)
        self.assertAlmostEqual(impact.slope, 50 / 29600, places=12)
        self.assertAlmostEqual(impact.r_squared, 0.9745322245322244, places=9)
        self.assertIsNone(metrics.price_impact(self.series[:3]))           # two observations
        flat = [metrics.Bucket(60 * i, 10.0, 5) for i in range(5)]
        self.assertIsNone(metrics.price_impact(flat))                       # no variation in volume
        still = [metrics.Bucket(60 * i, 10.0, i) for i in range(5)]
        self.assertEqual(metrics.price_impact(still).r_squared, 0.0)        # the price never moved
        self.assertEqual(metrics.price_impact(still).slope, 0.0)


class OrderToTradeTest(unittest.TestCase):

    def test_counts_the_members_own_messages_and_each_trade_once(self):
        ratios = metrics.order_to_trade(read_records(io.StringIO(RECORDS)))
        by_member = {o.member_id: o for o in ratios}
        self.assertEqual(sorted(by_member), [1, 2, 3])                  # the kill switch and limits are not messages
        self.assertEqual((by_member[1].messages, by_member[1].trades, by_member[1].ratio), (2, 2, 1.0))
        self.assertEqual((by_member[2].messages, by_member[2].trades), (4, 3))   # a trade with itself counts once
        self.assertAlmostEqual(by_member[2].ratio, 4 / 3)
        self.assertEqual((by_member[3].messages, by_member[3].trades, by_member[3].ratio), (1, 0, None))


if __name__ == "__main__":
    unittest.main()
