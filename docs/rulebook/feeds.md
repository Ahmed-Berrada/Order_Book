# Rulebook: binary market data feed and drop copy

Two feeds for professional members: a sequenced binary market data feed for low-latency consumers,
and a FIX drop copy for members' risk and back-office systems. Rule IDs are cited by tests through
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
