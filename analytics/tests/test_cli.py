import io
import json
import tempfile
import unittest
from pathlib import Path

from lob_analytics.__main__ import main
from tests.fixtures import RECORDS, TRADES


class CliTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.trades = Path(self.dir.name, "trades.csv")
        self.records = Path(self.dir.name, "records.csv")
        self.trades.write_text(TRADES, encoding="utf-8")
        self.records.write_text(RECORDS, encoding="utf-8")

    def tearDown(self):
        self.dir.cleanup()

    def run_cli(self, *args):
        out, err = io.StringIO(), io.StringIO()
        code = main(list(args), out, err)
        return code, out.getvalue(), err.getvalue()

    def test_json_report_holds_every_metric(self):
        code, out, err = self.run_cli("--trades", str(self.trades), "--records", str(self.records), "--json")
        self.assertEqual((code, err), (0, ""))
        data = json.loads(out)
        first = data["days"][0]
        self.assertEqual((first["vwap"], first["net_revenue"], first["traded_buckets"]), ("10.0275", "0.06", 4))
        self.assertEqual(first["trade_size_percentiles"], {"p50": 50, "p90": 200, "p99": 200, "p100": 200})
        self.assertEqual(first["realized_volatility"]["returns"], 3)
        self.assertEqual(first["price_impact"]["observations"], 3)
        self.assertIsNone(data["days"][1]["realized_volatility"])
        self.assertIsNone(data["days"][1]["price_impact"])
        self.assertEqual([(o["trade_date"], o["member_id"]) for o in data["order_to_trade"]],
                         [("2026-11-26", 1), ("2026-11-26", 2), ("2026-11-26", 3), ("2026-11-27", 1)])

    def test_markdown_report(self):
        code, out, _ = self.run_cli("--trades", str(self.trades), "--records", str(self.records), "--bucket", "30")
        self.assertEqual(code, 0)
        self.assertIn("## PTX on 2026-11-26", out)
        self.assertIn("| 5 | 400 | 4011.00 | 10.0275 | 10.00 | 10.10 | 9.80 | 9.80 | 7.5% | 0.06 |", out)
        self.assertIn("over 30 s buckets", out)
        self.assertIn("not enough traded buckets", out)
        self.assertIn("| 2026-11-26 | PTX | 3 | 1 | 0 | no trades |", out)
        self.assertIn("| 2026-11-26 | PTX | 2 | 4 | 3 | 1.33 |", out)
        code, out, _ = self.run_cli("--trades", str(self.trades))
        self.assertNotIn("Order-to-trade", out)

    def test_alerts_are_reported_from_the_records(self):
        code, out, _ = self.run_cli("--trades", str(self.trades), "--records", str(self.records), "--alerts", "--json")
        self.assertEqual(code, 0)
        kinds = [a["kind"] for a in json.loads(out)["alerts"]]
        self.assertEqual(kinds, ["WASH_TRADE"])                    # member 2 traded with itself in the fixture
        code, out, _ = self.run_cli("--trades", str(self.trades), "--records", str(self.records), "--alerts")
        self.assertIn("## Surveillance alerts", out)
        self.assertIn("| WASH_TRADE | 2026-11-26 | PTX | 2 |", out)
        code, _, err = self.run_cli("--trades", str(self.trades), "--alerts")
        self.assertEqual((code, err), (2, "error: --alerts needs --records\n"))

    def test_errors(self):
        code, out, err = self.run_cli()
        self.assertEqual((code, out), (2, ""))
        self.assertIn("the following arguments are required: --trades", err)
        code, _, err = self.run_cli("--trades", str(self.trades), "--bucket", "0")
        self.assertEqual((code, err), (2, "error: --bucket must be positive\n"))
        code, _, err = self.run_cli("--trades", str(Path(self.dir.name, "missing.csv")))
        self.assertEqual(code, 1)
        self.assertIn("error:", err)
        code, _, err = self.run_cli("--trades", str(self.records))
        self.assertEqual(code, 1)
        self.assertIn("not a trade file", err)
        latin = Path(self.dir.name, "latin.csv")
        latin.write_bytes(b"\xff\xfetrade_id\n")                  # UTF-16 from a PowerShell redirect
        code, _, err = self.run_cli("--trades", str(latin))
        self.assertEqual(code, 1)
        self.assertIn("not a trade file", err)
        code, out, _ = self.run_cli("--help")
        self.assertEqual(code, 0)
        self.assertIn("Market-microstructure report", out)
