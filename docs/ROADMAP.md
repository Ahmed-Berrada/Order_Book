# Roadmap

The goal is a matching engine built to the technical standards a regulated trading venue must meet,
with every claim backed by code, a test and a document.

A personal project cannot be an authorised venue: that requires an MTF licence under MiFID II, or a
Regulation ATS filing in the US. What it can do is **implement the technical controls those regimes
require and document them against the actual rules**, the way exchange-technology vendors do. The
reference regime is **EU MiFID II / MiFIR**; a US mapping follows in phase 7.

Principles: correctness before speed; determinism (same input journal, same output); every control
traceable to a rule; every performance number measured and reproducible.

> This roadmap replaces the original step numbering of ADR-0001. The persistence work moved before the
> API, because nothing in a trading system is real until it survives a crash.

## Phases

| # | Phase | Content | Status |
|---|---|---|---|
| 0 | Foundations | Maven multi-module, wrapper, CI, enforced module boundary, ADR-0001 | ✅ |
| 1 | Core engine | Rulebook, LIMIT / MARKET / cancel, scenario tests, jqwik invariants, reference model, JaCoCo / PIT / ArchUnit gates | ✅ |
| 2 | Durability & audit | Write-ahead command journal (CRC, sequence), snapshots, crash recovery proof, event store, µs timestamps (RTS 25), replay tool | ✅ |
| 3 | Connectivity | Single writer per symbol service (`engine-service`); REST API + OpenAPI; FIX 4.4 order entry (QuickFIX/J) with conformance suite and drop copy; WebSocket level-2 market data; ITCH-style binary feed over UDP with TCP retransmission and snapshots | ✅ |
| 4 | Venue functionality | **4a ✅** IOC, FOK, post-only; cancel/replace priority rules ([order types](rulebook/order-types.md)). **4b ✅** iceberg orders ([iceberg orders](rulebook/iceberg-orders.md)). **4c ✅** stop and stop-limit orders ([stop orders](rulebook/stop-orders.md)). **4d ✅** self-trade prevention ([self-trade prevention](rulebook/self-trade-prevention.md)). **4e ✅** trading phases and call auctions ([trading phases](rulebook/trading-phases.md)). **4f ✅** tick-size regime (RTS 11, [tick sizes](rulebook/tick-size-regime.md)), [DAY orders](rulebook/day-orders.md), [volatility interruptions](rulebook/volatility-interruptions.md) (RTS 7); order-to-trade ratio, throttles and the kill switch (RTS 9) move to phase 5, with members | ⏳ |
| 5 | Pre-trade risk & members | **5a ✅** members in the engine and journal, per-instrument limits (quantity, notional, price collar, open orders, position), kill switch, ownership of cancels ([members and risk](rulebook/members-and-risk.md)). Later: member / trader / session hierarchy, restricted list, order-to-trade ratio and throttles (need a clock: ingress, phase 9), venue-wide credit exposure | ⏳ |
| 6 | Performance | `engine-bench` (JMH), HdrHistogram end-to-end load tests, O(1) cancel, primitive maps, zero-allocation hot path, Aeron / Disruptor evaluation, GC comparison, regression gate in CI, `docs/performance.md` | |
| 7 | Regulatory evidence | Compliance matrix (rule → code → test → evidence); RTS 24 order records; RTS 22 transaction reports (ISO 20022); post-trade transparency; surveillance detectors (MAR); US mapping (Reg NMS, LULD, Reg SCI, CAT, 15c3-5) | |
| 8 | Security & supply chain | mTLS + FIX logon, OAuth2/OIDC admin API, RBAC, OWASP ASVS L2, STRIDE threat model, CodeQL, dependency scanning, CycloneDX SBOM, signed releases, SLSA provenance. Runs alongside phases 3 onwards | |
| 9 | Operations & resilience | Micrometer / Prometheus / OpenTelemetry, Grafana dashboards, primary/standby with replicated journal, RPO 0 / RTO target, runbooks, chaos and 24 h soak tests, container images and Helm chart | |
| 10 | Post-trade & analytics *(optional)* | CCP trade files, maker/taker fees, Python market-microstructure analytics, option chains | |

## Phase exit criteria

- **1:** every rulebook rule cited by a test; property tests at 1,000 tries; JaCoCo ≥ 90 %; PIT ≥ 80 %.
- **2:** `kill -9` at any point loses no acknowledged order; replay rebuilds the book at any sequence number.
- **3:** an external FIX client connects, trades, and recovers after a disconnect.
- **4:** the rulebook covers auctions and trading phases, with rule IDs traced to tests.
- **5:** no order breaching a limit ever reaches the book (property-tested).
- **6:** published, reproducible latency and throughput with a CI regression gate.
- **7:** compliance matrix complete for the targeted RTS articles.
- **8:** signed release with SBOM and provenance; no high-severity findings open.
- **9:** tested failover within the RTO; 24 h soak with no memory growth or latency drift.

## Documentation set (target)

```
docs/
├── adr/            one record per significant decision
├── rulebook/       matching rules, auctions, trading phases, order types (rule IDs)
├── interfaces/     FIX rules of engagement, market data spec, OpenAPI
├── compliance/     EU and US matrices, record formats, report samples
├── security/       threat model, access control, SBOM policy
├── operations/     runbooks, SLOs, DR plan
└── performance.md  methodology and results
```
