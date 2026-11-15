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
| 3 | Connectivity | Single writer per symbol service; FIX 4.4 / 5.0 SP2 order entry (QuickFIX/J); ITCH-style SBE market data + replay; WebSocket L1/L2; drop copy; admin REST + OpenAPI; FIX conformance suite | ⏳ |
| 4 | Venue functionality | IOC, FOK, post-only, iceberg, stop; cancel/replace priority rules; self-trade prevention; opening and closing auctions; trading phases; volatility interruptions (RTS 7); tick-size regime (RTS 11); order-to-trade ratio and throttles (RTS 9); kill switch | |
| 5 | Pre-trade risk & members | Member / trader / session hierarchy; max quantity and value, price collars, credit limits, restricted list; exposure monitoring | |
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
