# Alert: QueueConsumerLagHigh

- **Category:** `consumer-lag`
- **Team:** `queue-consumer`
- **Severity:** `warning`
- **Expr:** `max(queue_consumer_lag_messages{service="queue-consumer"}) > 10000`
- **For:** `10m`
- **Runbook:** `https://runbooks.internal/queue-consumer/lag-high`

## Description
Fires when the number of unacknowledged messages behind the consumer group
exceeds 10,000 for 10 consecutive minutes. Lag itself isn't an outage — it
means downstream processing (order fulfillment events, notification dispatch,
webhook delivery) is delayed, not lost — but sustained lag growth indicates
the consumer can't keep up with producer throughput.

Typical causes: a slow downstream dependency the consumer calls per-message,
insufficient consumer parallelism for current traffic, or — most often in
this system — a `retry-storm` where a subset of poison messages are
repeatedly retried with insufficient backoff, starving throughput for the
rest of the queue (see RFC-003, Queue Consumer Retry/Backoff).

## Runbook Steps
1. Check whether lag growth is uniform (throughput problem) or driven by a
   small number of messages being retried repeatedly (poison-message /
   retry-storm problem) — check `queue_consumer_retry_total` by message key.
2. If throughput-bound, check downstream dependency latency and consider
   scaling consumer replicas.
3. If retry-storm-bound, see `retry-storm` and RFC-003 — the fix is
   dead-lettering after a bounded retry count, not scaling.
4. Escalate to `dlq-spike` territory if poison messages are being
   dead-lettered rather than retried forever (that alert firing is actually
   the *healthy* outcome of this one being fixed).
