# RFC-002: Auth Token Refresh

- **Status:** Accepted
- **Owner:** auth-service
- **Related alerts:** `token-refresh-failure`, `auth-latency`

## Summary
Defines the refresh-token exchange flow used by `auth-service`: short-lived
access tokens (15 minutes) backed by longer-lived refresh tokens (30 days),
with refresh-token rotation and reuse detection on every exchange.

## Motivation
Short-lived access tokens limit the blast radius of a leaked token, but only
if refresh is reliable — if refresh fails spuriously, users get logged out
constantly, which trains them to distrust session persistence and generates
support load. Refresh-token rotation (issuing a new refresh token on every
use and invalidating the old one) limits the blast radius of a leaked refresh
token, but reuse detection has to distinguish "an attacker replayed a stolen
token" from "the same legitimate client retried a request or has multiple
tabs open" — getting this wrong in either direction is a real cost: too
strict, and legitimate users get logged out; too loose, and token theft goes
undetected.

## Design
1. Client presents `refresh_token`.
2. `auth-service` validates signature and expiry against its current signing
   key set.
3. On success, the refresh token is marked used (rotated) and a new
   access/refresh token pair is issued.
4. If a refresh token that was already marked "used" is presented again,
   this is treated as potential reuse — the entire token family is revoked
   and the user is forced to re-authenticate.

Signing-key rotation happens on a schedule; all `auth-service` instances must
have the new key available *before* the old key stops verifying, or in-flight
tokens signed with the old key will fail validation on whichever instance
hasn't picked up rotation yet.

## Failure Modes
- **`token-refresh-failure`** — clock skew between an `auth-service` instance
  and the token issuer's clock source causes valid, unexpired tokens to be
  rejected as "not yet valid" (`nbf` claim in the future from that instance's
  perspective) or prematurely "expired." This is an infrastructure/NTP issue,
  not a logic bug, but it presents identically to a real refresh failure from
  the alert's perspective — check instance clock drift first.
- **`token-refresh-failure`** — reuse-detection false positives: a legitimate
  client with multiple concurrent sessions (two browser tabs, a mobile app
  plus a web session) can trigger two near-simultaneous refresh calls with
  the same refresh token before either has completed rotation, causing the
  second to be flagged as "reuse of an already-used token" and the whole
  family revoked, logging out a legitimate user. This is the most common
  real-world cause of `token-refresh-failure` and should be checked before
  escalating as a security incident.
- **`auth-latency`** — a "thundering herd" refresh pattern: if many clients'
  access tokens happen to expire in a narrow time window (e.g. all issued
  around the same deploy/login event), their refresh calls all land at once,
  creating a CPU-bound spike in signature verification and signing that
  degrades latency platform-wide before any individual refresh actually
  fails. Staggering token expiry (adding jitter to the 15-minute TTL) is the
  long-term fix; horizontal scale-out is the short-term mitigation.

## Non-goals
This RFC does not cover initial login/authentication (username+password, SSO)
— only the refresh-token exchange for an already-established session.
