import io
import unittest
from datetime import datetime, timedelta, timezone

from lob_analytics import surveillance
from lob_analytics.files import OrderRecord
from lob_analytics.surveillance import Thresholds

T0 = datetime(2026, 11, 26, 9, 0, tzinfo=timezone.utc)


def at(seconds):
    return T0 + timedelta(seconds=seconds)


def new(t, member, order, side, quantity, price=10_000):
    return OrderRecord(at(t), "PTX", member, str(order), "NEW_ORDER", side, str(quantity), "", "", str(price), "")


def cancel(t, member, order, quantity, reason="CLIENT_REQUEST"):
    return OrderRecord(at(t), "PTX", member, str(order), "ORDER_CANCELLED", "", str(quantity), "", reason, "", "")


def trade(t, taker, taker_order, aggressor_side, quantity, maker, maker_order):
    return OrderRecord(at(t), "PTX", taker, str(taker_order), "TRADE", aggressor_side, str(quantity), str(maker), "",
                       "10000", str(maker_order))


def background(start_order, members=(1, 2, 3, 4), count=20):
    """Ordinary orders of 10 lots that set the day's median order size."""
    return [new(i, members[i % len(members)], start_order + i, "BUY" if i % 2 else "SELL", 10) for i in range(count)]


class WashTradeTest(unittest.TestCase):

    def test_a_trade_between_two_orders_of_one_member_is_flagged_once_per_day(self):
        records = [trade(1, 7, 2, "BUY", 5, 7, 1), trade(2, 7, 4, "SELL", 3, 7, 3), trade(3, 7, 6, "BUY", 2, 8, 5)]
        (alert,) = surveillance.wash_trades(records)
        self.assertEqual((alert.kind, alert.member_id, alert.evidence["trades"], alert.evidence["quantity"]),
                         ("WASH_TRADE", 7, 2, 8))
        self.assertEqual(alert.evidence["orders"], [1, 2, 3, 4])

    def test_trades_between_members_are_not(self):
        self.assertEqual(surveillance.wash_trades([trade(1, 7, 2, "BUY", 5, 8, 1)]), [])


class SpoofingTest(unittest.TestCase):

    def scenario(self, *, size=100, cancelled_after=10, filled=0, other_side=True, reason="CLIENT_REQUEST",
                 maker_on_other_side=False):
        records = background(100)
        records.append(new(30, 5, 1, "BUY", size, 9_990))          # the large bid
        if filled:
            records.append(trade(31, 6, 50, "SELL", filled, 5, 1))  # part of it traded
        if other_side and maker_on_other_side:
            records.append(new(32, 5, 2, "SELL", 20, 10_010))
            records.append(trade(33, 6, 51, "BUY", 20, 5, 2))       # member 5 sells as the maker
        elif other_side:
            records.append(trade(33, 5, 3, "SELL", 20, 6, 52))      # member 5 sells as the taker
        records.append(cancel(30 + cancelled_after, 5, 1, size - filled, reason))
        return surveillance.spoofing(records)

    def test_a_large_order_cancelled_soon_after_its_member_traded_on_the_other_side(self):
        (alert,) = self.scenario()
        self.assertEqual((alert.kind, alert.member_id), ("SPOOFING", 5))
        self.assertEqual(alert.evidence["order_id"], 1)
        self.assertEqual(alert.evidence["quantity"], 100)
        self.assertEqual((alert.evidence["other_side_trades"], alert.evidence["other_side_quantity"]), (1, 20))
        self.assertIn("x10.0 the median", alert.summary)

    def test_trading_on_the_other_side_as_the_maker_counts_too(self):
        self.assertEqual(len(self.scenario(maker_on_other_side=True)), 1)

    def test_near_misses_are_not_flagged(self):
        self.assertEqual(self.scenario(size=40), [], "not large: x4 the median")
        self.assertEqual(self.scenario(cancelled_after=61), [], "held longer than the window")
        self.assertEqual(self.scenario(filled=20), [], "a fifth of it traded")
        self.assertEqual(self.scenario(other_side=False), [], "no trade on the other side")
        self.assertEqual(self.scenario(reason="KILL_SWITCH"), [], "cancelled by the venue, not the member")

    def test_thresholds_can_be_calibrated(self):
        records = background(100)
        records += [new(30, 5, 1, "BUY", 40), trade(33, 5, 3, "SELL", 20, 6, 52), cancel(40, 5, 1, 40)]
        self.assertEqual(surveillance.spoofing(records), [])
        self.assertEqual(len(surveillance.spoofing(records, Thresholds(spoof_size_multiple=3.0))), 1)


class ExcessiveCancellationTest(unittest.TestCase):

    def day(self, orders, cancels):
        records = [new(i, 9, i + 1, "BUY", 1) for i in range(orders)]
        records += [cancel(1_000 + i, 9, i + 1, 1) for i in range(cancels)]
        return surveillance.excessive_cancellation(records)

    def test_a_member_who_cancels_nearly_everything(self):
        (alert,) = self.day(50, 45)
        self.assertEqual((alert.kind, alert.member_id, alert.evidence), ("EXCESSIVE_CANCELLATION", 9,
                                                                         {"orders": 50, "cancelled": 45}))
        self.assertIn("90%", alert.summary)

    def test_a_mass_cancel_is_the_members_own_cancel(self):
        records = [new(i, 9, i + 1, "BUY", 1) for i in range(50)]
        records += [cancel(1_000, 9, i + 1, 1, reason="MASS_CANCEL") for i in range(45)]
        (alert,) = surveillance.excessive_cancellation(records)
        self.assertEqual(alert.evidence, {"orders": 50, "cancelled": 45})
        venue = [new(i, 9, i + 1, "BUY", 1) for i in range(50)] + [
            cancel(1_000, 9, i + 1, 1, reason="KILL_SWITCH") for i in range(50)]
        self.assertEqual(surveillance.excessive_cancellation(venue), [], "the operator's kill switch is not")
        operator = [new(i, 9, i + 1, "BUY", 1) for i in range(50)] + [new(100 + i, 0, 100 + i, "BUY", 1) for i in range(50)]
        operator += [cancel(1_000, 0, i + 1, 1, reason="MASS_CANCEL") for i in range(50)]
        operator += [cancel(1_000, 0, 100 + i, 1, reason="MASS_CANCEL") for i in range(45)]
        (alert,) = surveillance.excessive_cancellation(operator)
        self.assertEqual((alert.member_id, alert.evidence), (0, {"orders": 50, "cancelled": 45}),
                         "the operator's mass cancel of member 9's orders is not member 9's, nor counted for the operator")

    def test_below_either_threshold_nothing(self):
        self.assertEqual(self.day(49, 49), [], "too few orders to judge")
        self.assertEqual(self.day(50, 44), [], "88% cancelled")


class AllAlertsTest(unittest.TestCase):

    def test_the_kinds_come_in_order(self):
        records = background(100) + [trade(1, 7, 2, "BUY", 5, 7, 1)]
        records += [new(30, 5, 1, "BUY", 100), trade(33, 5, 3, "SELL", 20, 6, 52), cancel(40, 5, 1, 100)]
        self.assertEqual([a.kind for a in surveillance.alerts(records)], ["WASH_TRADE", "SPOOFING"])


if __name__ == "__main__":
    unittest.main()
