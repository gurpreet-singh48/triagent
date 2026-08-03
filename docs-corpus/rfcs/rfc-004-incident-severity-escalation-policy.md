# RFC-004: Incident Severity & Escalation Policy

- **Status:** Accepted
- **Owner:** platform / on-call
- **Related alerts:** all

## Summary
Defines the four severity levels used across every alert in this system
(`critical`, `error`, `warning`, `info`), what each obligates the receiving
team to do, and how incidents escalate between them. This RFC is
cross-cutting — every alert doc in `docs-corpus/alerts/` declares one of
these four severities, and every alert's runbook references the escalation
paths defined here.

## Motivation
Without a shared severity definition, teams calibrate "critical" differently,
which either causes alert fatigue (everything is critical, so critical stops
meaning anything) or under-paging (a genuinely severe issue is labeled
"warning" because that team's bar for critical is unusually high). A single
shared rubric, applied consistently across `payment-service`, `auth-service`,
and `queue-consumer`, is what makes cross-team on-call rotations and
automated triage (this very system) possible at all.

## Design

| Severity | Definition | Response obligation |
|---|---|---|
| `critical` | User-facing failure or data-integrity issue in progress (money moved incorrectly, requests failing outright, data being lost or corrupted) | Page on-call immediately, regardless of time of day. Acknowledge within 5 minutes. |
| `error` | Degraded but not yet failing outright — a meaningful fraction of requests/messages are failing or being retried, and left unaddressed will likely become `critical` | Page on-call during business hours; queue for next-business-day if off-hours and not accelerating. |
| `warning` | Early-warning signal — a leading indicator (latency creeping up, lag growing) that something is trending toward a problem but nothing is failing yet | Ticket, no page. Investigate within 1 business day. |
| `info` | Informational — noteworthy but not actionable on its own | No ticket required; log for trend analysis. |

Escalation between levels is expected and normal, not a sign of
misclassification: a `warning`-level `consumer-lag` alert can escalate into
an `error`-level `retry-storm` if the underlying cause is a downstream
dependency degrading, which can in turn produce `critical`-level
`dlq-spike` once retries exhaust — and each of those is a *correct*
classification of that alert *at that point in the incident's evolution*,
not three inconsistent judgments of the same problem.

## Failure Modes
This RFC's own "failure mode" is severity drift: alerts get created ad hoc
over time by whichever engineer is closest to the pain, without checking
this rubric, and severities become inconsistent across teams again. The
mitigation is process, not code: every new alert doc must declare a severity
from this table with a one-line justification, and alert docs should be
reviewed alongside the runbook they ship with.

## Non-goals
This RFC does not define the specific PromQL expressions or thresholds for
any individual alert — those are owned by each alert doc in
`docs-corpus/alerts/` and are expected to be tuned per-service based on
observed traffic patterns, while still mapping onto one of the four severity
levels defined here.
