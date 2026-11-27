# Rulebook: members and pre-trade risk

- **Status:** Draft

Every order is entered on behalf of a **member** (a trading firm), and a venue must stop a member's
orders before they can do harm: MiFID II RTS 6 requires pre-trade limits and a kill switch, and the US
market access rule (SEC 15c3-5) requires the same of brokers. This chapter adds members and their limits
to [continuous trading](continuous-trading.md). Every rule ID is cited by tests through `@Rulebook("MB-xxx")`
or `@Rulebook("RK-xxx")`. Design: [ADR-0015](../adr/0015-members-and-risk.md).

Terms used below:
- **Member ID**: a non-negative number. `0` is the operator: it has no limits, cannot be suspended, and is
  who a command is processed for when none is named.
- **Net position**: for a member, the lots it bought minus the lots it sold in this instrument.
- **Open orders**: a member's resting orders and parked stop orders.

## Members

**MB-001: Commands are for a member.** `process(command, member)` processes a command on behalf of a
member; `process(command)` is `process(command, 0)`. A negative member ID is not accepted (the call
throws). Nothing else about a command changes.

**MB-002: Attribution.** A resting order and a parked stop order keep the member that entered them, through
replaces and releases. `TradeExecuted` carries `takerMemberId` and `makerMemberId`.

**MB-003: Ownership of cancels and replaces.** A cancel or a replace made by a member other than `0` of an
order that belongs to another member is refused as if the order did not exist (`UNKNOWN_ORDER`), so a
member cannot learn that another's order exists. Member `0` may cancel and replace any order.

**MB-004: State.** Members' limits, suspensions and net positions are part of the engine's state; a
snapshot restores them (RS-010).

## Limits

**RK-001: Limits.** A member has limits, set by the `SetMemberLimits(member, limits)` command, which emits
`MemberLimitsChanged(member, limits)`. The limits are, each with `0` meaning "none":

| Limit | Meaning |
|---|---|
| `maxOrderQuantity` | largest quantity of one order, in lots |
| `maxOrderNotional` | largest price × quantity of one order, in ticks × lots |
| `priceCollarBps` | largest distance of a limit price from the last trade price, in basis points |
| `maxOpenOrders` | most open orders at once |
| `maxNetPosition` | largest long or short position, counting open orders as if filled |

A negative limit, or member `0`, is refused with `MemberCommandRejected(member, INVALID_MEMBER_LIMITS)`
and changes nothing. A new member starts with no limits.

**RK-002: Checks.** After the checks of CT-007, CT-009, TK-002, OT-004, IC-001, ST-002, SP-001 and TP-004/TP-005,
and in this order, a new order of a member other than `0` is rejected (and a replace refused) with:

| Reason | When |
|---|---|
| `MEMBER_SUSPENDED` | the member is suspended (RK-004) |
| `RISK_ORDER_QUANTITY` | the quantity exceeds `maxOrderQuantity` |
| `RISK_ORDER_NOTIONAL` | price × quantity exceeds `maxOrderNotional`; the price is the limit price, the stop price of a stop order without a limit, and the last trade price for a MARKET order (no check before the first trade) |
| `RISK_PRICE_COLLAR` | a limit price, or the limit price of a stop-limit order, is more than `priceCollarBps` from the last trade price (no check before the first trade) |
| `RISK_OPEN_ORDERS` | the member already has `maxOpenOrders` open orders (a replace is not a new open order) |
| `RISK_POSITION` | a buy would take `net position + open buy quantity + quantity` above `maxNetPosition`, a sell `−net position + open sell quantity + quantity` (a replace counts its new quantity instead of the order's remaining one) |

A rejected order consumes an order ID as any rejection (CT-010).

**RK-003: Position.** On every trade the engine adds the quantity to the net position of the buyer and
subtracts it from the seller's, in an auction as in continuous trading. A trade between two orders of one
member changes nothing (the position is net), although SP-001 normally prevents it.

## Kill switch

**RK-004: Suspension.** `SuspendMember(member)` suspends the member and cancels all its open orders: it emits
`MemberSuspended(member, cancelledOrders)`, then an `OrderCancelled` with reason `KILL_SWITCH` for each of its
resting orders and parked stops, in order of order ID. While suspended, its new orders and replaces are
rejected (`MEMBER_SUSPENDED`); cancels are accepted. Suspending member `0`, or a member that is already
suspended, is refused with `MemberCommandRejected(member, INVALID_MEMBER)`.

**RK-005: Resuming.** `ResumeMember(member)` ends the suspension: `MemberResumed(member)`. Resuming a member
that is not suspended is refused with `INVALID_MEMBER`. Cancelled orders do not come back.
