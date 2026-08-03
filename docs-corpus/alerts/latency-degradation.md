# Alert: PaymentServiceLatencyDegradation

- **Category:** `latency-degradation`
- **Team:** `payment-service`
- **Severity:** `warning`
- **Expr:** `histogram_quantile(0.95, sum(rate(http_request_duration_seconds_bucket{service="payment-service"}[5m])) by (le)) > 1.5`
- **For:** `10m`
- **Runbook:** `https://runbooks.internal/payment-service/latency-degradation`

## Description
Fires when `payment-service`'s p95 request latency exceeds 1.5s for 10
consecutive minutes. Unlike `5xx-spike`, requests are still succeeding — this
is an early-warning signal, not an outage, but sustained latency degradation
on the payment path directly increases checkout abandonment.

Typical causes: Postgres query plan regressions (often after a migration or a
statistics change), connection-pool queueing under elevated load, downstream
card-gateway latency creeping up without yet failing outright, or GC pressure
from a memory leak.

## Runbook Steps
1. Compare p50 vs p95 vs p99 — a p95-only regression usually points to
   connection-pool queueing; a uniform shift across all percentiles points to
   a downstream dependency or a code-level regression.
2. Check for recent schema migrations or query plan changes in
   `payment-service`'s Postgres usage.
3. Check downstream card-gateway latency dashboards.
4. If latency correlates with rising memory/GC time, treat as a possible leak
   and consider a rolling restart while investigating.
5. Escalate to `critical` (`5xx-spike` territory) if p95 exceeds 5s or errors
   begin appearing.
