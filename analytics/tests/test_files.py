import io
import re
import unittest
from pathlib import Path

from lob_analytics.files import RECORD_COLUMNS, TRADE_COLUMNS, FormatError, read_records, read_trades
from tests.fixtures import RECORDS, TRADES

REPO = Path(__file__).resolve().parents[2]


def java_header(path: str, constant: str) -> list:
    """The columns a Java tool writes, read from its source: the two sides of the format cannot drift."""
    source = (REPO / path).read_text(encoding="utf-8")
    match = re.search(constant + r'\s*=\s*((?:"[^"]*"\s*\+?\s*)+);', source)
    return "".join(re.findall(r'"([^"]*)"', match.group(1))).split(",")


class ContractTest(unittest.TestCase):

    def test_the_columns_are_those_the_java_tools_write(self):
        self.assertEqual(TRADE_COLUMNS, java_header(
            "post-trade/src/main/java/io/github/ahmedberrada/lob/posttrade/PostTradeTool.java", "TRADES_HEADER"))
        self.assertEqual(RECORD_COLUMNS, java_header(
            "engine-journal/src/main/java/io/github/ahmedberrada/lob/journal/RecordsExport.java", "HEADER"))


class ReadingTest(unittest.TestCase):

    def test_reads_every_line_with_its_types(self):
        lines = read_trades(io.StringIO(TRADES))
        self.assertEqual(len(lines), 12)
        self.assertEqual(lines[2].timestamp.microsecond, 1)
        self.assertEqual(lines[2].timestamp.utcoffset().total_seconds(), 0)
        records = read_records(io.StringIO(RECORDS))
        (limits,) = [r for r in records if r.record == "MEMBER_LIMITS_CHANGED"]
        self.assertEqual(limits.detail, "MemberLimits[maxOrderQuantity=5, maxOrderNotional=0]")         # quoted

    def test_refuses_another_file(self):
        with self.assertRaisesRegex(FormatError, "not a trade file"):
            read_trades(io.StringIO(RECORDS))
        with self.assertRaisesRegex(FormatError, "not a records export"):
            read_records(io.StringIO(TRADES))
        with self.assertRaisesRegex(FormatError, "not a trade file"):
            read_trades(io.StringIO(""))

    def test_refuses_a_damaged_line(self):
        header = TRADES.splitlines()[0]
        cases = {
            "PTX-4,2026-11-26,2026-11-26T09:00:00Z,PTX,2,2,BUY,TAKER,1000,10.00,100,1000.00,0.3": "fields",
            "PTX-4,2026-11-26,2026-11-26T09:00:00Z,PTX,2,2,BUY,TAKER,1000,ten,100,1000.00,0.3,0.03": "line 2",
            "PTX-4,2026-11-26,yesterday,PTX,2,2,BUY,TAKER,1000,10.00,100,1000.00,0.3,0.03": "line 2",
            "PTX-4,2026-11-26,2026-11-26T09:00:00Z,PTX,2,2,HOLD,TAKER,1000,10.00,100,1000.00,0.3,0.03": "side",
            "PTX-4,2026-11-26,2026-11-26T09:00:00Z,PTX,2,2,BUY,BOTH,1000,10.00,100,1000.00,0.3,0.03": "liquidity",
            "PTX-4,2026-11-26,2026-11-26T09:00:00Z,PTX,2,2,BUY,TAKER,1000,10.00,100,1000.00,0.3,0.03,x": "fields",
        }
        for line, message in cases.items():
            with self.subTest(line=line), self.assertRaisesRegex(FormatError, message):
                read_trades(io.StringIO(header + "\n" + line + "\n"))
        record_header = RECORDS.splitlines()[0]
        with self.assertRaisesRegex(FormatError, "records export line 2"):         # a trade names its maker
            read_records(io.StringIO(record_header + "\n2026-11-26T09:00:00Z,PTX,2,4,2,2,TRADE,BUY,,,1000,100,1,,\n"))
        with self.assertRaisesRegex(FormatError, "not a trade file"):              # not CSV a reader accepts
            read_trades(io.StringIO(TRADES.splitlines()[0] + "\n\"" + "x" * 200_000 + "\"\n"))
        with self.assertRaisesRegex(FormatError, "records export line 2"):
            read_records(io.StringIO(record_header + "\nnow,PTX,1,1,1,1,NEW_ORDER,,,,,,,,\n"))
