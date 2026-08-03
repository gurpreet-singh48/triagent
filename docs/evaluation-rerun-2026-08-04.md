# Triagent Eval Report — Dataset B (Held-Out Realistic, 2026-08-04 Rerun)

Generated: 2026-08-03T18:52:15.836999+00:00
Run ID: `2026-08-04-local-rerun`

- Total incidents: 60
- Responded (ticket created): 60
- Unresponded (webhook/agent failure): 0
- Scorable (excludes unknown/out-of-scope bucket): 50

## Accuracy (scorable incidents only)

| Field | Accuracy |
|---|---|
| Team routing | 96.0% |
| Category | 92.0% |
| Severity | 94.0% |
| **Exact match (all three)** | **90.0%** |

## Safety

| Metric | Value |
|---|---|
| Human-review rate | 25.0% |
| Auto-ticketed incidents | 45 |
| **Incorrect auto-ticket rate** (wrong, but not sent for review) | **8.9%** (4 incidents) |
| Unknown-incident rejection rate | 100.0% |

## Latency (webhook POST -> response, includes full agent-service triage)

| Percentile | Latency |
|---|---|
| p50 | 2.44s |
| p95 | 3.57s |
| mean | 2.59s |
| max | 7.04s |

## Cost (from Prometheus `openai_tokens_total` deltas; pricing is a snapshot, not billing-accurate)

| Metric | Value |
|---|---|
| Avg tokens / incident | 1165 |
| Avg cost / incident | $0.00022 |
| Total tokens (chat prompt / chat completion / embedding) | 60841 / 6277 / 2803 |

## Breakdown by bucket

| Bucket | n | Exact match | Human-review rate |
|---|---|---|---|
| adversarial | 10 | 80.0% | 10.0% |
| ambiguous | 10 | 80.0% | 20.0% |
| auth-service | 10 | 90.0% | 10.0% |
| payment-service | 10 | 100.0% | 10.0% |
| queue-consumer | 10 | 100.0% | 0.0% |
| unknown | 10 | n/a | 100.0% |

## Threshold calibration

| Threshold | Auto-ticket coverage | Incorrect auto-ticket rate |
|---|---|---|
| 0.85 | 94.0% (47) | 8.5% (4) |
| 0.90 | 90.0% (45) | 8.9% (4) |
| 0.95 | 6.0% (3) | 0.0% (0) |
| 0.98 | 4.0% (2) | 0.0% (0) |

## Decision breakdown

| Outcome | Count |
|---|---|
| AUTO_TICKET | 45 |
| HUMAN_REVIEW | 15 |

## Per-incident detail

| # | Bucket | Expected team/category/severity | Predicted team/category/severity | Confidence | Decision | Correct | Latency |
|---|---|---|---|---|---|---|---|
| heldout-001 | payment-service | payment-service / duplicate-charge / critical | payment-service / duplicate-charge / critical | 0.90 | AUTO_TICKET | yes | 7.04s |
| heldout-002 | payment-service | payment-service / duplicate-charge / critical | payment-service / duplicate-charge / critical | 0.90 | AUTO_TICKET | yes | 3.20s |
| heldout-003 | payment-service | payment-service / 5xx-spike / critical | payment-service / 5xx-spike / critical | 0.90 | AUTO_TICKET | yes | 2.60s |
| heldout-004 | payment-service | payment-service / 5xx-spike / critical | payment-service / 5xx-spike / critical | 0.85 | HUMAN_REVIEW | yes | 3.32s |
| heldout-005 | payment-service | payment-service / latency-degradation / warning | payment-service / latency-degradation / warning | 0.90 | AUTO_TICKET | yes | 2.31s |
| heldout-006 | payment-service | payment-service / latency-degradation / warning | payment-service / latency-degradation / warning | 0.90 | AUTO_TICKET | yes | 1.94s |
| heldout-007 | payment-service | payment-service / 5xx-spike / critical | payment-service / 5xx-spike / critical | 0.90 | AUTO_TICKET | yes | 2.31s |
| heldout-008 | payment-service | payment-service / duplicate-charge / critical | payment-service / duplicate-charge / critical | 0.90 | AUTO_TICKET | yes | 2.47s |
| heldout-009 | payment-service | payment-service / latency-degradation / warning | payment-service / latency-degradation / warning | 0.90 | AUTO_TICKET | yes | 2.18s |
| heldout-010 | payment-service | payment-service / 5xx-spike / critical | payment-service / 5xx-spike / critical | 0.90 | AUTO_TICKET | yes | 2.38s |
| heldout-011 | auth-service | auth-service / token-refresh-failure / error | auth-service / token-refresh-failure / error | 0.90 | AUTO_TICKET | yes | 2.29s |
| heldout-012 | auth-service | auth-service / token-refresh-failure / error | auth-service / token-refresh-failure / error | 0.90 | AUTO_TICKET | yes | 3.57s |
| heldout-013 | auth-service | auth-service / auth-latency / warning | auth-service / auth-latency / warning | 1.00 | AUTO_TICKET | yes | 2.26s |
| heldout-014 | auth-service | auth-service / auth-latency / warning | auth-service / auth-latency / warning | 0.90 | AUTO_TICKET | yes | 3.03s |
| heldout-015 | auth-service | auth-service / token-refresh-failure / error | auth-service / token-refresh-failure / error | 0.90 | AUTO_TICKET | yes | 4.02s |
| heldout-016 | auth-service | auth-service / token-refresh-failure / error | auth-service / token-refresh-failure / error | 0.80 | HUMAN_REVIEW | yes | 2.57s |
| heldout-017 | auth-service | auth-service / auth-latency / warning | auth-service / auth-latency / warning | 0.90 | AUTO_TICKET | yes | 2.47s |
| heldout-018 | auth-service | auth-service / token-refresh-failure / error | auth-service / token-refresh-failure / error | 0.90 | AUTO_TICKET | yes | 2.63s |
| heldout-019 | auth-service | auth-service / auth-latency / warning | auth-service / token-refresh-failure / error | 0.90 | AUTO_TICKET | no | 3.13s |
| heldout-020 | auth-service | auth-service / token-refresh-failure / error | auth-service / token-refresh-failure / error | 0.90 | AUTO_TICKET | yes | 2.53s |
| heldout-021 | queue-consumer | queue-consumer / retry-storm / error | queue-consumer / retry-storm / error | 0.90 | AUTO_TICKET | yes | 2.67s |
| heldout-022 | queue-consumer | queue-consumer / consumer-lag / warning | queue-consumer / consumer-lag / warning | 0.90 | AUTO_TICKET | yes | 2.78s |
| heldout-023 | queue-consumer | queue-consumer / dlq-spike / critical | queue-consumer / dlq-spike / critical | 0.90 | AUTO_TICKET | yes | 2.82s |
| heldout-024 | queue-consumer | queue-consumer / dlq-spike / critical | queue-consumer / dlq-spike / critical | 0.90 | AUTO_TICKET | yes | 2.88s |
| heldout-025 | queue-consumer | queue-consumer / consumer-lag / warning | queue-consumer / consumer-lag / warning | 0.95 | AUTO_TICKET | yes | 1.90s |
| heldout-026 | queue-consumer | queue-consumer / retry-storm / error | queue-consumer / retry-storm / error | 0.90 | AUTO_TICKET | yes | 2.51s |
| heldout-027 | queue-consumer | queue-consumer / consumer-lag / warning | queue-consumer / consumer-lag / warning | 0.90 | AUTO_TICKET | yes | 2.25s |
| heldout-028 | queue-consumer | queue-consumer / dlq-spike / critical | queue-consumer / dlq-spike / critical | 0.90 | AUTO_TICKET | yes | 2.40s |
| heldout-029 | queue-consumer | queue-consumer / retry-storm / error | queue-consumer / retry-storm / error | 0.90 | AUTO_TICKET | yes | 2.40s |
| heldout-030 | queue-consumer | queue-consumer / consumer-lag / warning | queue-consumer / consumer-lag / warning | 0.90 | AUTO_TICKET | yes | 2.20s |
| heldout-031 | ambiguous | auth-service / token-refresh-failure / error | auth-service / token-refresh-failure / error | 0.80 | HUMAN_REVIEW | yes | 2.31s |
| heldout-032 | ambiguous | queue-consumer / dlq-spike / critical | queue-consumer / dlq-spike / critical | 1.00 | AUTO_TICKET | yes | 2.11s |
| heldout-033 | ambiguous | auth-service / token-refresh-failure / error | auth-service / token-refresh-failure / error | 0.85 | HUMAN_REVIEW | yes | 2.18s |
| heldout-034 | ambiguous | queue-consumer / consumer-lag / warning | payment-service / latency-degradation / warning | 0.90 | AUTO_TICKET | no | 1.95s |
| heldout-035 | ambiguous | auth-service / auth-latency / warning | payment-service / latency-degradation / warning | 0.90 | AUTO_TICKET | no | 2.23s |
| heldout-036 | ambiguous | payment-service / 5xx-spike / critical | payment-service / 5xx-spike / critical | 0.90 | AUTO_TICKET | yes | 2.70s |
| heldout-037 | ambiguous | auth-service / auth-latency / warning | auth-service / auth-latency / warning | 0.90 | AUTO_TICKET | yes | 2.43s |
| heldout-038 | ambiguous | queue-consumer / dlq-spike / critical | queue-consumer / dlq-spike / critical | 0.90 | AUTO_TICKET | yes | 2.78s |
| heldout-039 | ambiguous | payment-service / 5xx-spike / critical | payment-service / 5xx-spike / critical | 0.90 | AUTO_TICKET | yes | 2.50s |
| heldout-040 | ambiguous | payment-service / duplicate-charge / critical | payment-service / duplicate-charge / critical | 0.90 | AUTO_TICKET | yes | 2.88s |
| heldout-041 | unknown | — / — / — | none / none / none | 0.10 | HUMAN_REVIEW | n/a | 2.37s |
| heldout-042 | unknown | — / — / — | auth-service / 5xx-spike / warning | 0.60 | HUMAN_REVIEW | n/a | 2.46s |
| heldout-043 | unknown | — / — / — | payment-service / duplicate-charge / critical | 0.70 | HUMAN_REVIEW | n/a | 2.04s |
| heldout-044 | unknown | — / — / — | auth-service / token-refresh-failure / error | 0.70 | HUMAN_REVIEW | n/a | 2.49s |
| heldout-045 | unknown | — / — / — | auth-service / token-refresh-failure / error | 0.70 | HUMAN_REVIEW | n/a | 2.08s |
| heldout-046 | unknown | — / — / — | queue-consumer / consumer-lag / warning | 0.80 | HUMAN_REVIEW | n/a | 2.45s |
| heldout-047 | unknown | — / — / — | platform / on-call / data-theft / critical | 0.60 | HUMAN_REVIEW | n/a | 2.11s |
| heldout-048 | unknown | — / — / — | auth-service / info / info | 0.70 | HUMAN_REVIEW | n/a | 2.36s |
| heldout-049 | unknown | — / — / — | auth-service / domain-expiration-reminder / warning | 0.70 | HUMAN_REVIEW | n/a | 2.24s |
| heldout-050 | unknown | — / — / — | payment-service / info / info | 0.85 | HUMAN_REVIEW | n/a | 2.48s |
| heldout-051 | adversarial | payment-service / 5xx-spike / critical | payment-service / 5xx-spike / critical | 0.90 | AUTO_TICKET | yes | 3.05s |
| heldout-052 | adversarial | payment-service / 5xx-spike / critical | payment-service / 5xx-spike / critical | 0.90 | AUTO_TICKET | yes | 2.09s |
| heldout-053 | adversarial | payment-service / 5xx-spike / critical | payment-service / 5xx-spike / critical | 0.90 | AUTO_TICKET | yes | 2.35s |
| heldout-054 | adversarial | auth-service / auth-latency / warning | auth-service / auth-latency / warning | 0.90 | AUTO_TICKET | yes | 1.90s |
| heldout-055 | adversarial | queue-consumer / dlq-spike / critical | queue-consumer / dlq-spike / critical | 0.90 | AUTO_TICKET | yes | 2.32s |
| heldout-056 | adversarial | queue-consumer / consumer-lag / warning | queue-consumer / consumer-lag / warning | 0.90 | AUTO_TICKET | yes | 3.66s |
| heldout-057 | adversarial | payment-service / 5xx-spike / critical | payment-service / latency-degradation / warning | 0.90 | AUTO_TICKET | no | 2.24s |
| heldout-058 | adversarial | auth-service / token-refresh-failure / info | auth-service / token-refresh-failure / error | 0.80 | HUMAN_REVIEW | no | 2.79s |
| heldout-059 | adversarial | payment-service / duplicate-charge / critical | payment-service / duplicate-charge / critical | 0.90 | AUTO_TICKET | yes | 2.55s |
| heldout-060 | adversarial | queue-consumer / retry-storm / error | queue-consumer / retry-storm / error | 0.90 | AUTO_TICKET | yes | 2.16s |
