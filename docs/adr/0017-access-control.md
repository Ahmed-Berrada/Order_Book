# ADR-0017: Authentication and authorisation at the edges

- **Status:** Accepted
- **Date:** 2026-11-27

## Context

Phase 8 closes the gap that every earlier phase documented: any caller who reaches a port is trusted. Rules:
[`access-control.md`](../rulebook/access-control.md) (AC-001 to AC-007).

## Decisions

### 1. Credentials stop at the edge

The engine and the journal know *members*, not credentials (ADR-0015). REST and FIX authenticate the caller,
decide which member it acts for, and pass that number down. A flaw in authentication can therefore let a
caller act as the wrong member, but cannot make the engine do anything a member may not do.

### 2. HTTP Basic over TLS, stateless

Basic authentication is the simplest scheme that works for machine clients, has no session to steal and
needs no token service. It is only as private as the channel: TLS is required for anything but loopback
(deployment, phase 9). OAuth 2 / OIDC for people (an operator console) is a different problem and not needed
for machine members; the roadmap keeps it for when a console exists. CSRF protection is off because no
cookie or session carries authority: a browser cannot attach the Basic header on its own.

### 3. Roles are few and coarse

`READ`, `MEMBER`, `ADMIN`. Finer permissions (a trader who may enter but not cancel, per-instrument rights)
need the member / trader / session hierarchy of the roadmap, which needs a place to store it.

### 4. Secure by default, fail closed

Security is on unless switched off by name; an enabled venue without users, with a plain-text secret, or with
a FIX session without a password does not start. A secure system that silently runs open is worse than one
that refuses.

### 5. FIX: the gateway asks, the application answers

`fix-gateway` stays free of any framework (ADR-0006). It calls a `LogonAuthenticator` on every Logon; the API
module implements it with Spring Security's password encoders, so REST and FIX verify secrets the same way.

### 6. Not here

Rate limiting of failed attempts (ingress, phase 9), mutual TLS for FIX (needs certificates provisioned,
phase 9), secret rotation and an external identity provider.

## Consequences

- ➕ The loopback-only restriction is no longer the only protection.
- ➕ One set of users and one hash format for both channels.
- ➖ Basic credentials are static: anyone who obtains one can use it until it is changed.
- ➖ Brute force is not throttled yet.
