# Rulebook: access control

Until now every caller who reached a port could act as the operator or as any member: the REST API was
bound to the loopback interface for that reason. This chapter says who may do what once the venue is
exposed. It applies to the REST API and the FIX gateway; the engine stays unaware of credentials (ADR-0017).
Every rule ID is cited by tests through `@Rulebook("AC-xxx")`.

Terms used below:
- **Principal**: a configured user (REST) or session (FIX), with a name, a secret and, for a member, the
  member it acts for.
- **Role**: `READ` (queries), `MEMBER` (order entry for its own member), `ADMIN` (the operator).

## Authentication

**AC-001: Authentication.** With security enabled (the default), every REST request must carry valid HTTP
Basic credentials, and every FIX Logon must carry a `Password` (tag 554) that matches its CompID. A request
without or with wrong credentials is answered `401`, and a Logon is rejected. Nothing is processed for an
unauthenticated caller.

**AC-002: Credentials are stored as hashes.** A user's or session's secret is configured as a salted hash with
an algorithm prefix (`{bcrypt}…`). A secret without a prefix, which would be plain text, makes the venue refuse
to start.

**AC-003: Failing closed.** With security enabled, the venue refuses to start if no user is configured, or if a
FIX session has no password. Disabling security is an explicit setting (`lob.security.enabled=false`), logged
as a warning at start-up.

## Authorisation

**AC-004: Roles.** A `READ` principal may query instruments, books, order status and the market-data feed. A
`MEMBER` may also enter, replace and cancel orders, for its own member only. An `ADMIN` may do everything: set
the trading phase, set a member's limits, suspend and resume a member (the kill switch), and act for any
member. A request above the caller's role is answered `403`.

**AC-005: Member binding.** A `MEMBER` principal's orders are entered for the member configured for it. The
`X-Member-Id` header may be omitted, or must name that member; another member is answered `403`. An `ADMIN`
that names a member acts for it, and without the header acts as the operator (member `0`).

**AC-006: Failures are logged.** A failed authentication is logged with the user name and the source address,
never with a secret.

**AC-007: FIX logon.** A FIX session logs on only if its Logon `Password` is accepted for its CompID; the
reason of a refusal is not revealed to the peer. Drop-copy sessions authenticate the same way.

**AC-009: Repeated failures lock the name out.** After `maxFailures` failed authentications in a row for
one user name (REST) or CompID (FIX) from one source address, further attempts for that name from that address
are refused for `lockout`, without checking the secret: REST answers `429` with `Retry-After`, a FIX Logon is
rejected as any failed Logon (AC-007). A success clears the count; an attempt counts as a failure from the
moment it starts until it is known to have succeeded, so attempts in parallel do not add guesses: at most
`maxFailures` minus the failures so far are checked at once for a name and address, and another waits up to
2 seconds for one of them to finish, then is refused with `429`, reason `AUTHENTICATION_BUSY`. REST user
names are counted without regard to case, as they are looked up; an IPv6 address counts for its /64 network.
The lockout is per name and address, so a caller cannot lock a member out from another address. This holds with security enabled; the defaults are 5
failures and 1 minute, and `maxFailures` `0` switches it off. Lockouts are kept in memory and logged.

**AC-012: Verified credentials are remembered briefly.** HTTP Basic sends the credentials with every request,
and bcrypt makes each check cost tens of milliseconds by design. Once bcrypt has accepted a user's credentials,
the same credentials are accepted without bcrypt for `credentialCache` (10 minutes by default; `0` checks every
request). The venue keeps only an HMAC-SHA256 of the password under a random key that exists in its memory alone,
compares it in constant time, and remembers only successes, at most one per configured user. A wrong password is
always checked by bcrypt and counted (AC-009), and a locked-out name is refused before the cache is consulted;
while failures are counted for a name from an address, its credentials are checked by bcrypt again, whose success
clears them. A negative `credentialCache` stops the venue from starting.

## Encryption in transit

**AC-010: TLS on every exposed port.** With security enabled, the REST API, a management port of its own (the
actuator, `management.server.port`) and the FIX acceptor accept only TLS 1.2 or 1.3 on an address other than
loopback: the venue refuses to start if any of them would listen beyond loopback in clear, and checks it before
it opens any of them.
Credentials (HTTP Basic, the FIX Logon password) and orders never cross a network unencrypted. A venue whose TLS is
terminated in front of it (an ingress, a load balancer) says so explicitly (`lob.security.tls-terminated-in-front`),
and is logged as a warning at start-up. The market data feeds carry public data and no credentials, and are not
covered.

**AC-011: FIX over TLS, optionally mutual.** The FIX acceptor uses the venue's own key store, never a default one.
With `need-client-auth`, a session's connection must also present a certificate issued by a CA in the venue's trust
store, or the handshake fails before any FIX message; the Logon password is still checked (AC-007).

**AC-013: A member's certificate names its CompID.** With mutual TLS, a session logs on only if the certificate its
connection presented has exactly one common name, and that name is the CompID it logs on as: a member's trusted
certificate proves which member it is, not just that it is one, so one member cannot log on as another with its own
certificate. A Logon from any other certificate is rejected after its password is checked, and logged.
`lob.fix.tls.bind-certificate-to-comp-id=false` switches this off, for certificates that cannot name their member.

## Browsers

**AC-008: No writes from another site.** A browser that has answered a Basic challenge resends the credentials
on its own, so a page on another site could make it enter or cancel orders for its user (cross-site request
forgery). A request that changes something (any method but `GET`, `HEAD`, `OPTIONS` and `TRACE`) is refused
with `403` when the browser marks it as coming from elsewhere: a `Sec-Fetch-Site` other than `same-origin` or
`none`, or, from a browser too old for Fetch Metadata, an `Origin` header without `Sec-Fetch-Site`. A request
that changes something with a body an HTML form can send (`application/x-www-form-urlencoded`,
`multipart/form-data`, `text/plain`) is refused whatever its headers: the API reads only JSON, and some old
browsers send neither header. Refusing creates no session. This holds with security on or off. Clients that
are not browsers send neither header and a JSON body, and are not affected.

