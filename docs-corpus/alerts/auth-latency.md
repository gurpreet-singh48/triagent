# Alert: AuthServiceHighLatency

- **Category:** `auth-latency`
- **Team:** `auth-service`
- **Severity:** `warning`
- **Expr:** `histogram_quantile(0.95, sum(rate(http_request_duration_seconds_bucket{service="auth-service"}[5m])) by (le)) > 0.8`
- **For:** `10m`
- **Runbook:** `https://runbooks.internal/auth-service/high-latency`

## Description
Fires when `auth-service`'s p95 latency exceeds 800ms for 10 consecutive
minutes. Because every authenticated request across the platform depends on
`auth-service` (directly for login/refresh, indirectly via cached tokens for
everything else), latency here has an outsized blast radius even before it
becomes outright failures.

Typical causes: a surge in concurrent refresh requests overwhelming the
token-signing path (see `token-refresh-failure` and the "thundering herd"
failure mode in RFC-002), slow lookups against the session store, or
CPU-bound signature verification during a burst of traffic.

## Runbook Steps
1. Check whether the latency correlates with a spike in refresh-token volume
   — if so, this may be an early symptom of the thundering-herd failure mode
   described in RFC-002, and `token-refresh-failure` may follow shortly.
2. Check session-store (Redis) latency and connection count.
3. Check CPU utilization on `auth-service` instances — signature verification
   is CPU-bound and can dominate under load.
4. Consider horizontal scale-out if CPU-bound and traffic is legitimately
   elevated (not a retry storm).
5. Escalate to `token-refresh-failure` territory if refresh failure rate
   begins rising alongside latency.
