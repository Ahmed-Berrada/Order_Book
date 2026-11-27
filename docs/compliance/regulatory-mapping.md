# Regulation to rules

Which obligations of the European regime for trading venues, and a few US ones, the venue meets and where.
It is a map for engineers and reviewers, **not legal advice and not a certification**: no regulator has
assessed this project, and a real venue needs its own legal analysis. Every rule ID below exists in the
[rulebook](../rulebook/) and is verified by tests ([rule matrix](rule-matrix.md)); a test fails the build if
this page names a rule that does not exist.

Status: **Implemented** (the engine does it, with tests), **Partial** (a part does, the gap is named),
**Not implemented** (a gap, said plainly).

## European Union

| Provision | What it asks of a venue | What the venue does | Rules | Status |
|---|---|---|---|---|
| MiFID II art. 48(1), RTS 7 (Delegated Regulation 2017/584) | Orderly trading; stop trading or constrain it when prices move abnormally | A fill outside a band around the reference price does not happen; the instrument moves to a call phase and reopens with an auction | VI-001, VI-002, VI-003, VI-004, VI-005, VI-006, TP-001, TP-005, AU-001 | Partial: one static band per instrument, set by configuration; no dynamic calibration, no per-instrument volatility model |
| MiFID II art. 48(6), RTS 11 (2017/588) | Tick sizes by price band | A table of bands, each with its step, checked on every price | TK-001, TK-002, TK-003 | Partial: the venue applies the table it is given; it does not load ESMA's liquidity-based tables |
| MiFID II art. 48(4), art. 17, RTS 6 (2017/589) | Pre-trade controls on algorithmic orders: price collars, maximum order value and volume, maximum position | Per-member limits on quantity, notional, distance from the last trade, open orders and net position; a rejection is journaled | RK-001, RK-002, RK-003 | Partial: limits are per instrument; no message-rate limit (needs a clock, phase 9); no venue-wide exposure |
| RTS 6 art. 12, RTS 7 | Kill functionality: cancel all of a member's unexecuted orders | The operator suspends a member and all its open orders are cancelled and journaled; the member cannot enter or replace orders until resumed | RK-004, RK-005, MB-003 | Partial: operator-initiated only; no member-initiated mass cancel; no per-trader or per-algorithm kill |
| RTS 9 (2017/566) | Order-to-trade ratio limits | — | — | Not implemented: needs time windows, outside the deterministic engine (ADR-0015 §4) |
| RTS 24 (2017/580) | Keep order records for five years, with the fields of its annex | The journal records every command with its microsecond UTC time and member, and every event it produced; `RecordsExport` turns it into order records | CT-011, CT-012, CT-015, RS-001, RS-006, RS-007, MB-001 | Partial: see [order-records.md](order-records.md) for the fields covered and the ones that need data the venue does not hold (LEIs, client and trader IDs, algorithm IDs) |
| RTS 25 (2017/574) | Clocks synchronised to UTC, timestamps to the microsecond for the fastest venues | Time is stamped once at ingress, in microseconds since the epoch (UTC), and never read again | CT-015, RS-006 | Partial: the stamp is the system clock; keeping that clock within the required divergence is an operational control (phase 9) |
| RTS 22 (2017/590) | Transaction reports to the regulator | Trade events carry price, quantity, and the taker's and maker's member | MB-002 | Not implemented: reports need the buyer and seller LEIs, decision makers and venue identifiers the venue does not hold |
| RTS 1 (2017/587) | Pre- and post-trade transparency for equities | A public level-2 feed of displayed depth and every trade; hidden quantity never leaks | MD-002, MD-003, MD-004, MD-006, IC-002 | Partial: no waivers or deferrals are modelled |
| MiFID II art. 48(9), RTS 10 (2017/573) | Fee structures that are transparent, fair and non-discriminatory, including rebates | One published schedule of maker, taker and auction rates for every member, applied from the journals to every side of every trade; statements add up to the trade file | PT-003, PT-006, PT-007, PT-009 | Partial: a flat schedule; no volume tiers, caps, or higher fees for cancelled orders and high order-to-trade ratios |
| MAR art. 12 and 16 | Detect and prevent market manipulation, including wash trading | Orders of one group never trade with each other | SP-001, SP-002, SP-003, SP-004 | Partial: prevention of self-trades only; no surveillance of layering, spoofing or other patterns |
| MiFID II art. 48(1), art. 16(5) | Resilience and business continuity | A write-ahead journal and deterministic recovery; every instrument recovers to the state it had | RS-001, RS-005, RS-007, RS-010, RS-011 | Partial: single node; one writer per journal (RS-011); a standby is designed, not built (ADR-0018) |

## United States (for orientation only)

| Rule | What it asks | What the venue does | Rules | Status |
|---|---|---|---|---|
| SEC Rule 15c3-5 (market access) | Pre-trade risk controls for brokers with market access, including erroneous-order and credit checks | The per-member limits and the kill switch above | RK-001, RK-002, RK-004 | Partial: no capital or credit model, and no broker-dealer hierarchy |
| Limit Up-Limit Down plan | Price bands around a reference price, with a limit state and a trading pause | The volatility band above, ending in an auction | VI-001, VI-002, VI-003 | Partial: one band, no limit state, no straddle handling |
| Regulation NMS (order protection) | Protection of quotes across venues | — | — | Not implemented: the venue is a single market |
| Reg SCI, CAT | Systems compliance and integrity; consolidated audit trail reporting | — | — | Not implemented |
