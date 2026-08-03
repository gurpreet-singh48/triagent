# Alert: AuthServiceTokenRefreshFailure

- **Category:** `token-refresh-failure`
- **Team:** `auth-service`
- **Severity:** `error`
- **Expr:** `sum(rate(auth_token_refresh_total{service="auth-service",result="failure"}[5m])) / sum(rate(auth_token_refresh_total{service="auth-service"}[5m])) > 0.1`
- **For:** `5m`
- **Runbook:** `https://runbooks.internal/auth-service/token-refresh-failure`

## Description
Fires when more than 10% of refresh-token exchanges fail over a 5-minute
window. A refresh failure forces the client back to a full login, so a
sustained spike here shows up downstream as a login-rate spike and user
complaints about being "randomly logged out."

Common root causes: clock skew between `auth-service` instances and the token
issuer causing valid tokens to be rejected as "not yet valid" or "expired"
prematurely, refresh-token reuse-detection false-positives (see RFC-002,
Auth Token Refresh, Failure Modes), or an expired signing-key rotation that
wasn't propagated to all instances before old tokens stopped verifying.

## Runbook Steps
1. Check whether the failure spike is uniform across `auth-service` instances
   or isolated to a subset — isolated failures point to clock skew or a
   signing-key rotation that hasn't fully propagated.
2. Check `auth-service` instance clock drift against NTP.
3. Check the signing-key rotation timeline against the deploy timeline.
4. Check whether reuse-detection is firing on legitimate concurrent refreshes
   from the same client (e.g. multiple tabs) rather than actual token theft —
   this is a known false-positive mode, see RFC-002.
5. If reuse-detection false-positives are confirmed, this is not a security
   incident; downgrade urgency but still file a bug against the refresh-token
   rotation logic.
