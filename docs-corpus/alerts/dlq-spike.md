# Alert: QueueConsumerDeadLetterSpike

- **Category:** `dlq-spike`
- **Team:** `queue-consumer`
- **Severity:** `critical`
- **Expr:** `sum(increase(queue_consumer_dead_lettered_total{service="queue-consumer"}[15m])) > 100`
- **For:** `1m`
- **Runbook:** `https://runbooks.internal/queue-consumer/dlq-spike`

## Description
Fires when more than 100 messages are routed to the dead-letter queue within
a 15-minute window. Every dead-lettered message represents an event that was
**not** successfully processed (an order-fulfillment update, a notification,
a webhook delivery) and now requires manual inspection or replay — this is a
data-completeness incident, not just a performance one.

A spike here typically follows one of two patterns: (1) a genuine batch of
malformed/invalid messages from an upstream producer bug, which should be
fixed at the source and the batch replayed after the fix, or (2) fallout from
a `retry-storm` where messages finally exhaust their retry budget and get
dead-lettered — in that case this alert firing is a *sign the retry-storm fix
is working as intended* (bounded retries instead of infinite retries), not a
new problem, and should be cross-checked against `retry-storm` timing before
paging anyone.

## Runbook Steps
1. Sample a handful of dead-lettered messages and check whether they're
   malformed (producer bug) or valid-but-failing (downstream dependency
   issue).
2. Cross-check timing against `retry-storm` — if a retry storm was active in
   the preceding 15 minutes, this is likely the expected/bounded outcome, not
   a new incident.
3. If malformed messages from a producer bug, notify the producing team and
   hold the DLQ contents for replay after the fix ships.
4. If downstream-dependency-caused, treat as an extension of that
   dependency's incident, not a separate `queue-consumer` incident.
5. Never auto-replay a DLQ without confirming root cause — replaying malformed
   messages just re-triggers the same failure.
