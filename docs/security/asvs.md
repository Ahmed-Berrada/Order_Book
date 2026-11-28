# OWASP ASVS 4.0.3: where the project stands

A chapter-level self-assessment against the Application Security Verification Standard, aiming at level 2.
It is an honest map, not an audit: **Meets** means the control exists and is tested here; **Partial** that part
does; **Not met** that it does not; **n/a** that it does not apply to this kind of application.

| Chapter | Status | Notes |
|---|---|---|
| V1 Architecture, design and threat modelling | Partial | [Threat model](threat-model.md) and ADRs exist; no formal security architecture review |
| V2 Authentication | Partial | Hashed secrets, no plain text, failures logged and locked out per name and address after 5 in a row (AC-001 to AC-003, AC-006, AC-009). Not met: multi-factor, credential rotation |
| V3 Session management | Partial | Stateless: no sessions or cookies, refusals included. Cached Basic credentials are covered by refusing cross-site writes and form bodies (AC-008) |
| V4 Access control | Meets | Deny by default; roles; member binding; ownership of cancels (AC-004, AC-005, MB-003) |
| V5 Validation, sanitisation and encoding | Meets | Strict JSON binding refuses unknown fields; bean validation; exact decimal handling; engine validation of every value (CT-009) |
| V6 Stored cryptography | Partial | Bcrypt for secrets. Journals are not encrypted; there is no key management |
| V7 Error handling and logging | Partial | Problem-detail errors without stack traces; authentication failures logged. No central log protection or audit-log integrity |
| V8 Data protection | Partial | No personal data is held; order data is not encrypted at rest |
| V9 Communications | Not met | No TLS in the application; terminate it in front (phase 9) |
| V10 Malicious code | Meets | CodeQL, dependency review, SBOM, signed provenance ([supply chain](supply-chain.md)) |
| V11 Business logic | Meets | The rulebook; limits, phases, kill switch; property-tested against an independent model |
| V12 Files and resources | n/a | No uploads. Journal paths come from configuration only, and symbols are restricted to `[A-Z0-9._-]` |
| V13 API and web service | Partial | Role-checked REST; a documented OpenAPI contract; members' orders rate-limited (OE-008) and failed logins locked out (AC-009). No limit on reads or on connections |
| V14 Configuration | Partial | Secure defaults, fail-closed start-up (AC-003); dependencies pinned. No hardened container or deployment profile yet |
