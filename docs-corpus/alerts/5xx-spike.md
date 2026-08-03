# Alert: PaymentService5xxSpike

- **Category:** `5xx-spike`
- **Team:** `payment-service`
- **Severity:** `critical`
- **Expr:** `sum(rate(http_requests_total{service="payment-service",code=~"5.."}[5m])) / sum(rate(http_requests_total{service="payment-service"}[5m])) > 0.05`
- **For:** `5m`
- **Runbook:** `https://runbooks.internal/payment-service/5xx-spike`

## Description
Fires when more than 5% of requests to `payment-service` over a 5-minute window
return a 5xx status code. This is the primary signal for user-facing payment
outages: checkout failures, stuck authorizations, and capture errors all surface
here first, usually before customer support tickets arrive.

Common root causes: an unhealthy downstream dependency (card network gateway,
fraud-scoring service), a bad deploy, connection-pool exhaustion against
Postgres, or cascading failures triggered by a retry storm from an upstream
caller (see `retry-storm`).

## Runbook Steps
1. Check `payment-service` deploy history — roll back if a release went out in
   the last 30 minutes.
2. Check the gateway/fraud-service dependency dashboards for correlated error
   spikes.
3. Check Postgres connection pool saturation (`pg_stat_activity` count vs pool
   max).
4. If the spike correlates with a burst of retried requests from a single
   `client_id`, treat as a retry storm and see RFC-003 (Queue Consumer
   Retry/Backoff) even though this is the HTTP path, not the queue path — the
   backoff/jitter guidance still applies to any client-side retry logic.
5. Escalate to `payment-service` on-call if error rate exceeds 20% or persists
   past 15 minutes.
