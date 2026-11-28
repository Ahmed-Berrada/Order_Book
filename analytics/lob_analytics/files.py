"""Reading the venue's CSV files, with their columns checked against the Java tools that write them."""

import csv
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from typing import Iterable, List, TextIO

#: Columns of the trade file (PostTradeTool.TRADES_HEADER).
TRADE_COLUMNS = ("trade_id,trade_date,timestamp_utc,instrument,member_id,order_id,side,liquidity,price_ticks,"
                 "price,quantity,notional,fee_rate_bps,fee").split(",")

#: Columns of the order records (RecordsExport.HEADER).
RECORD_COLUMNS = ("timestamp_utc,instrument,command_sequence,event_sequence,member_id,order_id,record,side,"
                  "order_type,time_in_force,price_ticks,quantity,counterparty_order_id,counterparty_member_id,"
                  "detail").split(",")


class FormatError(ValueError):
    """A file that is not what the venue writes."""


@dataclass(frozen=True)
class TradeLine:
    """One side of a trade, as in docs/interfaces/trade-file.md."""
    trade_id: str
    trade_date: str
    timestamp: datetime
    instrument: str
    member_id: int
    order_id: int
    side: str
    liquidity: str
    price: Decimal
    quantity: int
    notional: Decimal
    fee: Decimal


@dataclass(frozen=True)
class OrderRecord:
    """One line of the order records, as in docs/compliance/order-records.md."""
    timestamp: datetime
    instrument: str
    member_id: int
    order_id: str
    record: str
    side: str
    quantity: str
    counterparty_member_id: str
    detail: str

    @property
    def counterparty(self) -> int:
        """The maker's member of a TRADE record (checked when read)."""
        return int(self.counterparty_member_id)


def _timestamp(text: str) -> datetime:
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def _rows(source: TextIO, columns: List[str], what: str) -> Iterable[dict]:
    try:
        reader = csv.DictReader(source)
        if reader.fieldnames != columns:
            raise FormatError(f"not a {what}: expected the columns {','.join(columns)}, got "
                              f"{','.join(reader.fieldnames or [])}")
        for number, row in enumerate(reader, start=2):
            if None in row or None in row.values():
                raise FormatError(f"{what} line {number} does not have {len(columns)} fields")
            yield number, row
    except (UnicodeDecodeError, csv.Error) as e:          # not UTF-8 text, or not CSV
        raise FormatError(f"not a {what}: {e}") from e


def read_trades(source: TextIO) -> List[TradeLine]:
    """Every line of a trade file, in file order."""
    lines = []
    for number, row in _rows(source, TRADE_COLUMNS, "trade file"):
        try:
            lines.append(TradeLine(row["trade_id"], row["trade_date"], _timestamp(row["timestamp_utc"]),
                                   row["instrument"], int(row["member_id"]), int(row["order_id"]), row["side"],
                                   row["liquidity"], Decimal(row["price"]), int(row["quantity"]),
                                   Decimal(row["notional"]), Decimal(row["fee"])))
        except (ArithmeticError, ValueError) as e:
            raise FormatError(f"trade file line {number}: {e}") from e
        if lines[-1].side not in ("BUY", "SELL") or lines[-1].liquidity not in ("TAKER", "MAKER", "AUCTION"):
            raise FormatError(f"trade file line {number}: unknown side or liquidity")
    return lines


def read_records(source: TextIO) -> List[OrderRecord]:
    """Every line of an order records export, in file order."""
    records = []
    for number, row in _rows(source, RECORD_COLUMNS, "records export"):
        try:
            records.append(OrderRecord(_timestamp(row["timestamp_utc"]), row["instrument"], int(row["member_id"]),
                                       row["order_id"], row["record"], row["side"], row["quantity"],
                                       row["counterparty_member_id"], row["detail"]))
            if row["record"] == "TRADE":
                _ = records[-1].counterparty, int(row["quantity"])    # a trade names its maker's member
        except ValueError as e:
            raise FormatError(f"records export line {number}: {e}") from e
    return records
