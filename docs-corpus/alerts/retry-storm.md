# Alert: QueueConsumerRetryStorm

- **Category:** `retry-storm`
- **Team:** `queue-consumer`
- **Severity:** `error`
- **Expr:** `sum(rate(queue_consumer_retry_total{service="queue-consumer"}[5m])) > 50`
- **For:** `5m`
- **Runbook:** `https://runbooks.internal/queue-consumer/retry-storm`

## Description
Fires when the consumer is retrying messages at a sustained rate above 50/sec.
This indicates a subset of messages are failing processing and being retried
without success — usually because the current retry logic re-enqueues
failed messages with a **fixed** delay and no jitter, so many consumer
instances end up retrying in lockstep, amplifying load on whatever downstream
dependency is already failing (a classic thundering-herd pattern).

Because retries currently have no maximum attempt count in the affected code
path, a single poison message (malformed payload, a downstream 4xx that will
never succeed) can retry indefinitely, which is both the direct cause of this
alert and the root cause of unbounded `consumer-lag` growth. See RFC-003
(Queue Consumer Retry/Backoff), Failure Modes, and `queue-consumer`'s
`retry.go` for the current implementation.

## Runbook Steps
1. Identify the message(s) driving the retry rate via
   `queue_consumer_retry_total` grouped by message type/key.
2. Determine if the message is a poison message (will never succeed) or a
   transient failure (downstream dependency is down).
3. If poison, manually route it to the dead-letter queue to stop the storm
   immediately (see `dlq-spike` for what happens once this fix is deployed
   properly).
4. If transient, check whether the downstream dependency itself is degraded —
   the retry storm may be amplifying an unrelated outage.
5. File a bug: retry logic needs exponential backoff with jitter and a bounded
   max-attempts count before dead-lettering, per RFC-003.
