# Rulebook: volatility interruptions

- **Status:** Draft

A market that moves too far too fast is paused, and reopened with an auction (MiFID II RTS 7: trading
halts and circuit breakers; ESMA requires venues to have them). The engine decides from prices only, so
it stays deterministic (CT-015); *how long* the pause lasts is decided outside it, as for any phase
change (TP-002). This chapter extends [continuous trading](continuous-trading.md) and
[trading phases](trading-phases.md). Every rule ID is cited by tests through `@Rulebook("VI-xxx")`.
Design: [ADR-0014](../adr/0014-venue-controls.md).

Terms used below:
- **Band**: the largest move from the reference price, in basis points (1/100 of a percent), that
  continuous trading accepts. `0` switches the check off.
- **Reference price**: the price new trades are compared with.

## Rules

**VI-001: Reference price.** The reference price is `0` until a first trade. Then it is the price of the
latest auction, or, as long as there has been none, of the first trade of the instrument. It stays the
same until the next auction trades (AU-005): continuous trades do not move it, so a series of small
steps cannot walk away from it.

**VI-002: The breach.** While the instrument is `CONTINUOUS`, the band is not `0` and there is a reference
price, a fill that would execute at a price *p* with `|p − reference| × 10 000 > band × reference` does
not happen. Matching of the order that reached it stops there, and the interruption of VI-003 follows.
Fills made before the breach stand.

**VI-003: Interruption.** At the end of the command that breached, the engine emits
`VolatilityInterruption(reference, price)` with the reference price and the price that breached, then
moves to `CALL` as TP-003 describes (`PhaseChanged`, then the icebergs shown in full). Stop orders are not
released (TP-007). The remainder of the order that breached is treated as follows:

| Order | Remainder |
|---|---|
| LIMIT `GTC` or `DAY` (including a replace, and a released stop-limit) | rests, shown in full (TP-008); the book may be crossed (TP-005) |
| LIMIT `IOC`, MARKET, released stop-market | cancelled with `VOLATILITY_INTERRUPTION` |

**VI-004: Fill or kill.** A FOK order counts only the liquidity within the band (and within its own
limit) when it checks whether it can fill (OT-003). If that is not enough, it is cancelled with
`FILL_OR_KILL` and trades nothing: a FOK order never starts an interruption.

**VI-005: End.** The interruption is an ordinary `CALL` phase: it ends with a `SetPhase` command, whose
uncross sets the new reference price (VI-001). An uncross that trades nothing leaves the reference price
as it was. The auction itself is not subject to the band.

**VI-006: State.** The band is part of the instrument; the reference price is part of a snapshot and a
restored engine interrupts at the same trades (RS-010).
