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
| 6 | Performance | **6a ✅** `engine-bench` (JMH and an HdrHistogram latency run), O(1) cancel, [`docs/performance.md`](performance.md), Disruptor / Aeron evaluated and deferred ([ADR-0016](adr/0016-performance-and-the-queue.md)). Later: group commit with replication (phase 9), end-to-end load test, primitive maps, GC comparison | ⏳ |
| 7 | Regulatory evidence | **7a ✅** generated [rule matrix](compliance/rule-matrix.md) (rule → chapter → tests, checked by the build), [regulation-to-rules mapping](compliance/regulatory-mapping.md) with honest gaps, order-record export ([RTS 24 fields](compliance/order-records.md)). Later: RTS 22 transaction reports (ISO 20022, needs LEI and client data), post-trade transparency deferrals, surveillance detectors (MAR), US mapping depth | ⏳ |
| 8 | Security & supply chain | **8a ✅** access control for REST (HTTP Basic, roles, member binding) and FIX (Logon password), fail-closed start-up ([access control](rulebook/access-control.md), ADR-0017); CodeQL, dependency review, Dependabot, CycloneDX SBOM, release workflow with signed provenance ([supply chain](security/supply-chain.md)); [STRIDE threat model](security/threat-model.md), [ASVS self-assessment](security/asvs.md). Later: TLS and mutual TLS (deployment, phase 9), OAuth2 / OIDC for an operator console, throttling of failed logins, signed journals | ⏳ |
| 9 | Operations & resilience | **9a ✅** Micrometer / Prometheus metrics, health probes (readiness down while an instrument is halted), alerts and a Grafana dashboard ([observability](operations/observability.md)); [runbook](operations/runbook.md); one writer per journal directory (RS-011); restart-and-compare soak test; container image, compose stack and Helm chart; primary/standby design with replicated journal, group commit, RPO 0 and RTO target (ADR-0018). **9b ✅** chaos tests of the whole venue in a second JVM: `kill -9` while clients trade loses no acknowledged order, a full journal disk halts only its instrument (RS-012). Later: replication and failover built and tested (then network-partition tests), OpenTelemetry tracing | ⏳ |
| 10 | Post-trade & analytics *(optional)* | **10a ✅** clearing trade file and maker/taker/auction fees computed from the journals, with member statements and a balance check ([post-trade](rulebook/post-trade.md), ADR-0019, [trade file](interfaces/trade-file.md)). **10b ✅** Python market-microstructure analytics: daily summary, fee revenue, trade sizes, realized volatility, price impact, order-to-trade ratio ([analytics](analytics.md)). Out of scope: option chains (a new product type, ADR-0019) | ✅ |

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
