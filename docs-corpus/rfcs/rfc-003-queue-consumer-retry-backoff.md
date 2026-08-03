# RFC-003: Queue Consumer Retry/Backoff

- **Status:** Accepted
- **Owner:** queue-consumer
- **Related alerts:** `retry-storm`, `consumer-lag`, `dlq-spike`

## Summary
Defines the retry and dead-lettering policy for `queue-consumer`, which
processes order-fulfillment, notification, and webhook-delivery events from
the main event queue. Covers when to retry, how long to wait between
retries, and when to give up and dead-letter.

## Motivation
Not every processing failure is the same: a transient downstream 503 should
be retried, because it will likely succeed shortly; a malformed payload will
never succeed no matter how many times it's retried, and retrying it forever
just wastes consumer throughput that the rest of the queue needs. Getting
retry policy wrong in either direction has a real cost — too aggressive, and
transient failures escalate to permanent data loss; too permissive with no
backoff or attempt limit, and a small number of poison messages can starve
the whole consumer group.

## Design
On processing failure, a message should be retried with **exponential
backoff and jitter**: base delay doubling each attempt, with randomized
jitter added so that many consumer instances retrying failures from the same
incident don't all retry in lockstep against an already-struggling
downstream dependency. After a bounded number of attempts (5), the message
is routed to the dead-letter queue for manual inspection rather than retried
indefinitely.

```
delay = min(max_delay, base_delay * 2^attempt) * (0.5 + random(0, 0.5))
```

## Failure Modes
- **`retry-storm`** — retrying with a **fixed** delay and no jitter means
  that when a downstream dependency fails, every consumer instance holding a
  failed message retries at the same fixed interval, so all instances
  hammer the recovering dependency in lockstep on every retry cycle — a
  classic thundering-herd pattern that can prevent the dependency from ever
  fully recovering. Jitter is not a nice-to-have here; without it, backoff
  provides no real protection under concurrent retries.
- **`consumer-lag`** — with no bounded attempt count, a single poison message
  (malformed payload, a downstream 4xx that will never succeed) retries
  forever, consuming a processing slot on every cycle and starving
  throughput for every other message behind it in the partition, causing lag
  to grow even though the "real" traffic is otherwise processing fine.
- **`dlq-spike`** — the *intended*, healthy outcome once bounded retries are
  correctly implemented: messages that exhaust their retry budget are
  dead-lettered rather than retried forever. A DLQ spike immediately
  following a `retry-storm` is very likely this working-as-designed case and
  should be cross-checked against retry-storm timing before being treated as
  a new, separate incident.

## Non-goals
This RFC does not define DLQ replay tooling or alerting on DLQ *contents*
(e.g. schema validation of dead-lettered payloads) — only the retry/backoff
policy that determines what ends up there.
