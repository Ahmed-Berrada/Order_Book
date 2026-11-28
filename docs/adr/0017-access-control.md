# ADR-0017: Authentication and authorisation at the edges

- **Status:** Accepted
- **Date:** 2026-11-27

## Context

Phase 8 closes the gap that every earlier phase documented: any caller who reaches a port is trusted. Rules:
[`access-control.md`](../rulebook/access-control.md) (AC-001 to AC-008).

## Decisions

### 1. Credentials stop at the edge

The engine and the journal know *members*, not credentials (ADR-0015). REST and FIX authenticate the caller,
decide which member it acts for, and pass that number down. A flaw in authentication can therefore let a
caller act as the wrong member, but cannot make the engine do anything a member may not do.

### 2. HTTP Basic over TLS, stateless

Basic authentication is the simplest scheme that works for machine clients, has no session to steal and
needs no token service. It is only as private as the channel: TLS is required for anything but loopback
(deployment, phase 9). OAuth 2 / OIDC for people (an operator console) is a different problem and not needed
for machine members; the roadmap keeps it for when a console exists.

**Cross-site request forgery.** A first version of this ADR said CSRF protection was unnecessary because no
cookie carries authority. That was wrong, and CodeQL flagged it: a browser that has answered a Basic challenge
caches the credentials and sends them again, on its own, to the same origin, so another site can make it act.
The token-based defence does not fit a stateless API for machine clients, so the CSRF filter is kept but only
for writes that the browser itself marks as cross-site (Fetch Metadata `Sec-Fetch-Site`, or `Origin` from older
browsers, AC-008), and for any write with a body an HTML form can send (`application/x-www-form-urlencoded`,
`multipart/form-data`, `text/plain`), since the API reads only JSON and some old browsers send a cross-site form
with neither header. A cross-site write with a JSON body needs a CORS preflight, which the venue never grants.
Such a request cannot carry a token and is refused: the token repository never stores or hands one out, so
the refusal creates no session and the chain stays stateless. Machine clients send neither header and a JSON
body, and are not affected.

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

### 6. Lockout after repeated failures (added in phase 8b)

Bcrypt already makes each guess cost about a tenth of a second, but nothing stopped a caller from guessing
for ever. After a few failures in a row for one name from one source address, that name is locked out from
that address for a minute, and the secret is not even checked (AC-009). The check wraps the provider that
verifies the secret, so it sees the name exactly as Spring Security authenticates it, whatever the spelling of
the `Authorization` header; REST names are counted without regard to case, as they are looked up. An attempt
reserves its place before the secret is checked, so guesses sent in parallel count as failures until they are
known to have succeeded. The price is that a client with the right secret, sending many requests at once over
REST, has at most five checked at a time (HTTP Basic checks the secret on every request): the others wait for
a slot rather than being refused, for up to 2 seconds. FIX checks the secret once per session and is not
affected.

A flood of new names cannot undo a lockout: when the table is full, the entry forgotten is the least
recently used that is neither locked out nor being checked. It can forget a partial count, at the price of a
guess on another name for each entry in the table. Callers that share an address (NAT, a proxy) share its
counts: a success of the member clears an attacker's failures on that name from there. A FIX Logon whose
address the transport does not give is counted under one shared address for its CompID. Keying on the address as well as the
name means an attacker cannot lock a member out by failing on purpose from elsewhere; it also means an
attacker with many addresses gets a few guesses from each, which the bcrypt cost and the network controls in
front of the venue must cover. REST answers `429` with `Retry-After`, which a client can act on and which
reveals nothing more than a `401` (a name that does not exist is locked out the same way). FIX rejects the
Logon as any failed one. The counts are in memory, for at most 10,000 names and addresses, the least recently
used forgotten first; a restart forgets them. An IPv6 address counts for its /64 network, since one host can
usually choose any address in it. Behind a proxy that terminates TLS, the venue sees the proxy's address for
every caller, so one caller could lock a member out for everyone: the proxy must pass the caller's address
(`X-Forwarded-For`), and Spring Boot be told to trust it (`server.forward-headers-strategy=native` with the
proxy's addresses as internal proxies), before the venue is exposed this way.

### 7. Remember verified credentials, briefly (added in phase 8d)

HTTP Basic sends the credentials with every request, and bcrypt is slow by design: at its default cost each check
takes 70 to 100 ms of CPU, and the lockout lets five run at once per name and address. The end-to-end load test
measured the result: one REST user could send about 50 requests a second. Once bcrypt has accepted a user's
credentials, they are now accepted without bcrypt for ten minutes (AC-012); the same user then sends 2,000 a
second, as without authentication.

What is kept is an HMAC-SHA256 of the password under a key drawn at start and never written anywhere, compared in
constant time. That protects nothing against someone who reads the process's memory: the key is in the same heap,
and with it each remembered password is a fast hash that an offline guess tests millions of times faster than
bcrypt. The cache makes a heap dump worse than the bcrypt hashes alone. It does not make it much worse than it
already was: the same heap holds the `Authorization` headers of recent requests, which carry the passwords in
plain Base64. Heap dumps and memory are therefore treated as secret (no `heapdump` actuator endpoint is exposed).

Only successes are kept, so the cache cannot grow beyond the configured users, and a wrong password always goes to
bcrypt and is counted. The lockout is checked first, and while failures are counted for a name from an address,
bcrypt checks again and only its success clears them: a hit, cheap and frequent, must not reset a guesser's count
behind an address it shares with the member faster than bcrypt would. A cached principal is a fresh user object
per request, with no password. Users only change at a restart, which empties the cache.

Spring Security stamps a password authentication with the time bcrypt checked it (`FACTOR_PASSWORD`); a remembered
authentication carries that first time for up to ten minutes. Nothing in the venue reads it; a rule on the
freshness of the password factor would have to set the cache to `0`.

### 8. Not here

Mutual TLS for FIX (needs certificates provisioned,
phase 9), secret rotation and an external identity provider.

## Consequences

- ➕ The loopback-only restriction is no longer the only protection.
- ➕ One set of users and one hash format for both channels.
- ➖ Basic credentials are static: anyone who obtains one can use it until it is changed.
- ➖ Brute force from many addresses is slowed only by the cost of bcrypt (AC-009 locks out per address).
