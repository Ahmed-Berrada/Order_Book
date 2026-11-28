# Rulebook: binary market data feed, drop copy and session status

Feeds for professional members: a sequenced binary market data feed for low-latency consumers,
a FIX drop copy for members' risk and back-office systems, and the trading status of each instrument over FIX. Rule IDs are cited by tests through
`@Rulebook`. Design: [ADR-0008](../adr/0008-binary-feed-and-drop-copy.md); formats:
[`market-data-binary.md`](../interfaces/market-data-binary.md) and the
[FIX Rules of Engagement](../interfaces/fix-rules-of-engagement.md#drop-copy).

## Binary market data feed

**BF-001: Same content.** The binary feed publishes the same updates as the WebSocket feed (MD-001 to
MD-006): changed levels, trades and each instrument's feed sequence, for every instrument, on one
stream.

**BF-002: Sequenced packets.** Every message has a stream sequence number, gap-free across the whole
stream; each packet carries the sequence of its first message and its message count. When the stream is
idle, heartbeats carry the next sequence, so a consumer detects a loss within a second.

**BF-003: Atomic updates.** An update (its levels, its trades and its end marker) is never split
across packets. A consumer applies an instrument's levels and trades when it reads the update's end
marker.

**BF-004: Retransmission.** Any message still in the retention window can be requested again over
TCP, and is resent with the same sequence number and the same bytes. A request outside the window is
refused, never answered with partial data.

**BF-005: Snapshot.** The snapshot service returns every instrument's directory entry and whole book,
with its feed sequence. With BF-004, a consumer that joins late or loses messages rebuilds every book
exactly.

## Drop copy

**DC-001: Every execution is copied.** Every ExecutionReport sent to a member session is also sent to
each drop copy session, marked as a copy (`CopyMsgIndicator=Y`) and naming the member it was sent to
(`OnBehalfOfCompID`).

**DC-002: Read only.** A drop copy session cannot trade: every application message it sends is
rejected.

## Trading status over FIX

FIX 4.4's `TradingSessionStatus` (`35=h`) carries no instrument, so each instrument is its own trading session:
`336 TradingSessionID` is the instrument's symbol. Its `340 TradSesStatus` is `2` Open in continuous trading,
`4` Pre-open in a call phase, `3` Closed when closed, and `1` Halted when the instrument stopped after a journal
failure (OE-006).

**FS-001: Phase changes are announced.** When an instrument's trading phase changes (TP-001), every member and
drop copy session that is logged on receives a `TradingSessionStatus` for it, with `325 UnsolicitedIndicator=Y`,
after the reports of the command that changed it. It gives the phase at the end of the command: a command that
changes the phase twice (an auction that reopens trading, then a volatility interruption) is announced once.
A session never receives a status older than one it already has, whichever reaches the gateway first. A change caused by the volatility band (VI-003) carries
`58 Text=VOLATILITY_INTERRUPTION`.

**FS-002: Status at logon and on request.** A session receives the status of every instrument when it logs on.
A `TradingSessionStatusRequest` (`35=g`) is answered with the status of the instrument named in `336`, or of
every instrument without one, with `335 TradSesReqID` echoed and `325=N`; an unknown `336` is answered with
`340=6` Request rejected and `567 TradSesStatusRejReason=1` (unknown or invalid TradingSessionID). When the phase
cannot be read at that moment (the instrument's queue is full, or the venue is stopping), the status is `0` Unknown
with the reason in `58`.

