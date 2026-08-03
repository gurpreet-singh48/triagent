# Testing the End-to-End Flow Locally

A manual QA pass through every path in the system: webhook -> idempotency ->
LangGraph agent -> ticket -> (optional human review) -> chat -> observability ->
dead-letter retry -> port lockdown. Run these in order after a fresh
`docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build` — the
`docker-compose.dev.yml` override is needed here since several steps below (ingestion,
curling Qdrant/the agent-service directly) assume host access to services that
`docker-compose.yml` alone keeps internal-only. See [Port exposure](./README.md#port-exposure).

## 0. Prerequisites

```bash
docker compose ps      # all 8 services should show "healthy"
curl -s localhost:6333/collections/docs_corpus | python3 -m json.tool | grep points_count
```

If the doc corpus hasn't been ingested yet (points_count is 0 or the
collection doesn't exist), run the one-time ingestion step first:

```bash
cd ingestion
python3 -m venv .venv && ./.venv/bin/pip install -r requirements.txt
./.venv/bin/python ingest.py --recreate
cd ..
```

## 1. Happy path: auto-ticket

A clear, high-confidence incident should classify correctly and skip human
review (`decision: AUTO_TICKET`, confidence >= 0.9).

```bash
curl -s -X POST localhost:8080/api/webhooks/pagerduty -H "Content-Type: application/json" -d '{
  "routing_key": "R123", "event_action": "trigger", "dedup_key": "test-happy-1",
  "payload": {
    "summary": "payment-service returning elevated 5xx errors, error rate 12%",
    "source": "payment-service-prod-1", "severity": "critical",
    "timestamp": "2026-01-01T00:00:00Z", "component": "payment-service",
    "group": "payments", "class": "5xx-spike", "custom_details": {"error_rate": "0.12"}
  },
  "client": "curl", "client_url": "http://localhost"
}' | python3 -m json.tool
```

Note the `ticket_id` in the response, then fetch it:

```bash
curl -s localhost:8080/api/tickets/<ticket_id> | python3 -m json.tool
```

Expect: `status: "OPEN"`, `decision: "AUTO_TICKET"`, `predicted_team:
"payment-service"`, `predicted_category: "5xx-spike"`, `confidence_score >=
0.9`, a non-empty `retrieved_docs` array, and a `rationale` referencing the
retrieved doc titles.

## 2. Idempotency: duplicate webhook

Re-POST the exact same payload (same `dedup_key`):

```bash
curl -s -X POST localhost:8080/api/webhooks/pagerduty -H "Content-Type: application/json" -d '{
  "routing_key": "R123", "event_action": "trigger", "dedup_key": "test-happy-1",
  "payload": {"summary": "payment-service returning elevated 5xx errors, error rate 12%",
  "source": "payment-service-prod-1", "severity": "critical", "timestamp": "2026-01-01T00:00:00Z",
  "component": "payment-service", "group": "payments", "class": "5xx-spike",
  "custom_details": {"error_rate": "0.12"}}, "client": "curl", "client_url": "http://localhost"
}' | python3 -m json.tool
```

Expect: `status: "duplicate"` and the *same* `incident_id`/`ticket_id` as
step 1 — no new row created, no second call to the agent-service.

## 3. Human-in-the-loop: low-confidence path

Send something genuinely ambiguous — no clean match to a known category —
to drive the classifier's confidence below the 0.9 threshold:

```bash
curl -s -X POST localhost:8080/api/webhooks/pagerduty -H "Content-Type: application/json" -d '{
  "routing_key": "R123", "event_action": "trigger", "dedup_key": "test-review-1",
  "payload": {
    "summary": "something looks off in prod, users reporting intermittent weirdness, not sure which service",
    "source": "unknown-source-1", "severity": "warning",
    "timestamp": "2026-01-01T00:00:00Z", "custom_details": {}
  },
  "client": "curl", "client_url": "http://localhost"
}' | python3 -m json.tool
```

Fetch the ticket and confirm `status: "PENDING_REVIEW"`, `decision:
"HUMAN_REVIEW"`, `confidence_score < 0.9`. Then approve or reject it:

```bash
curl -s -X POST localhost:8080/api/tickets/<ticket_id>/approve \
  -H "Content-Type: application/json" -d '{"reviewed_by": "your-name"}' | python3 -m json.tool
# or: .../reject with the same body
```

Expect: `status` flips to `"APPROVED"` (or `"REJECTED"`), and `reviewed_by`
/ a review timestamp are now populated in the response.

Then confirm the state-transition guard: re-approving/rejecting an already-decided
ticket must be rejected, not silently allowed to flip the outcome:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/tickets/<ticket_id>/reject \
  -H "Content-Type: application/json" -d '{"reviewed_by": "someone-else"}'
```

Expect: `409` (Conflict), and the ticket's status/reviewed_by unchanged.

## 4. PII redaction guardrail

Send an incident whose summary contains PII, and confirm it never reaches
the stored/displayed text:

```bash
curl -s -X POST localhost:8080/api/webhooks/pagerduty -H "Content-Type: application/json" -d '{
  "routing_key": "R123", "event_action": "trigger", "dedup_key": "test-pii-1",
  "payload": {
    "summary": "auth-service token refresh failing for user john.smith@example.com, phone 555-123-4567, from ip 10.0.4.22, reported by John Smith",
    "source": "auth-service-prod-2", "severity": "warning",
    "timestamp": "2026-01-01T00:00:00Z", "component": "auth-service",
    "group": "auth", "class": "token-refresh-failure", "custom_details": {}
  },
  "client": "curl", "client_url": "http://localhost"
}' | python3 -m json.tool
```

```bash
curl -s localhost:8080/api/tickets/<ticket_id> | python3 -m json.tool | grep -A2 redacted_summary
```

Expect `redacted_summary` to show `[REDACTED_EMAIL]`, `[REDACTED_PHONE]`,
`[REDACTED_IP]`, and `[REDACTED_NAME]` in place of the real values — and
confirm none of the raw PII appears anywhere else in the ticket JSON.

## 5. Internal callback authentication

`/api/internal/triage-results` is the endpoint the agent-service calls back into —
nothing else should be able to use it. It requires an `X-Internal-Service-Token`
header matching the backend's `INTERNAL_SERVICE_TOKEN` (default
`dev-local-internal-token`, see `.env`).

No token:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/internal/triage-results \
  -H "Content-Type: application/json" \
  -d '{"incident_id":"00000000-0000-0000-0000-000000000000","decision":"AUTO_TICKET","confidence":0.95}'
```

Expect: `401`.

Wrong token:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/internal/triage-results \
  -H "Content-Type: application/json" -H "X-Internal-Service-Token: wrong-token" \
  -d '{"incident_id":"00000000-0000-0000-0000-000000000000","decision":"AUTO_TICKET","confidence":0.95}'
```

Expect: `401`.

Correct token, unknown incident (proves auth passed and only the lookup failed):

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/internal/triage-results \
  -H "Content-Type: application/json" -H "X-Internal-Service-Token: dev-local-internal-token" \
  -d '{"incident_id":"00000000-0000-0000-0000-000000000000","decision":"AUTO_TICKET","confidence":0.95}'
```

Expect: `404`.

## 6. Dead-letter retry: agent-service outage

Stop the agent-service, then trigger an incident — the backend's call to it fails
with a connection error instead of a callback ever arriving:

```bash
docker compose stop agent-service

curl -s -X POST localhost:8080/api/webhooks/pagerduty -H "Content-Type: application/json" -d '{
  "routing_key": "R123", "event_action": "trigger", "dedup_key": "test-retry-1",
  "payload": {"summary": "payment-service returning elevated 5xx errors, error rate 12%",
  "source": "payment-service-prod-1", "severity": "critical", "timestamp": "2026-01-01T00:00:00Z",
  "component": "payment-service", "group": "payments", "class": "5xx-spike",
  "custom_details": {"error_rate": "0.12"}}, "client": "curl", "client_url": "http://localhost"
}' | python3 -m json.tool
```

`ticket_id` will be `null`. Check the incident's retry bookkeeping directly:

```bash
docker compose exec -T postgres psql -U triagent -d triagent -c "
SELECT status, attempt_count, failure_stage, error_category, next_retry_at
FROM incidents WHERE idempotency_key = 'test-retry-1';
"
```

Expect: `status: RETRYING`, `attempt_count: 1`, `error_category:
timeout_or_connection_error`, `failure_stage: agent_call`, and `next_retry_at` set
~30-40s in the future (exponential backoff with jitter — see
`WebhookService.backoff()`).

Bring the agent-service back and let `IncidentRetryScheduler` pick it up on its next
poll (every 30s by default, `triagent.retry.poll-interval-ms`):

```bash
docker compose start agent-service

for i in $(seq 1 20); do
  st=$(docker compose exec -T postgres psql -U triagent -d triagent -t -c \
    "SELECT status FROM incidents WHERE idempotency_key = 'test-retry-1';" | tr -d ' ')
  echo "poll $i: $st"
  [ "$st" = "TRIAGED" ] || [ "$st" = "FAILED" ] && break
  sleep 5
done
```

Expect `status` to move `RETRYING` -> `TRIAGING` -> `TRIAGED` within about a minute,
with a real ticket now attached — no manual intervention, no incident silently lost.

To see the *exhausted* path (3 failed attempts -> terminal `FAILED`, not retried
forever) without waiting through real backoff delays, see the equivalent automated
test: `WebhookRetryTest.scheduler_exhaustsMaxAttempts_marksFailed`.

## 7. Port exposure lockdown

Confirm the internal-only services really are unreachable from the host when running
`docker-compose.yml` alone (no dev override):

```bash
docker compose up -d   # base file only — no docker-compose.dev.yml this time
sleep 5                # switching away from the dev override recreates postgres/redis/
                        # qdrant, which briefly interrupts the backend's connection pool

curl -s --max-time 3 localhost:6333/collections; echo " exit:$?"   # expect a connection failure
curl -s --max-time 3 localhost:8000/health; echo " exit:$?"        # expect a connection failure
curl -s --max-time 8 -w "\nHTTP:%{http_code}\n" localhost:8080/actuator/health   # still reachable
```

Expect the first two curls to fail to connect (`exit:7`), and the backend to still
respond `200` — Postgres/Redis/Qdrant/agent-service aren't published to the host, but
the backend still reaches all of them fine over `triagent-net`.

Bring the dev override back for the rest of this guide (and for ingestion above):

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
curl -s --max-time 3 -o /dev/null -w "%{http_code}\n" localhost:6333/collections   # now 200
```

## 8. Frontend (browser)

```bash
open http://localhost:5173
```

- **Dashboard** (`/`): table of tickets from steps 1-4 should be visible;
  filter by team/status; click "Trigger Sample Incident" and confirm it
  navigates to a freshly created ticket.
- **Ticket detail** (`/tickets/<id>`): team/category/severity, confidence
  pill, rationale, redacted summary, and citation list (doc titles + source
  type + snippet) should all render. For the `PENDING_REVIEW` ticket from
  step 3, type a reviewer name and click Approve/Reject — status badge and
  "Reviewed by" section should update without a page reload.
- **Chat** (`/chat`): ask *"why would payment service duplicate a charge"*
  — expect the answer to stream in token-by-token (not appear all at once)
  with source citations below it (e.g. RFC-001, the duplicate-charge alert doc).

Open the browser console and confirm there are no errors on any of the
three pages.

## 9. Observability

```bash
open http://localhost:9090   # Prometheus
open http://localhost:3000   # Grafana (admin/admin) -> "Triagent Overview" dashboard
```

Query directly to sanity-check the metrics exist and have data:

```bash
curl -s 'localhost:9090/api/v1/query?query=triage_latency_seconds_count' | python3 -m json.tool
curl -s 'localhost:9090/api/v1/query?query=langgraph_node_duration_seconds_count' | python3 -m json.tool
curl -s 'localhost:9090/api/v1/query?query=openai_tokens_total' | python3 -m json.tool
```

Expect non-empty `result` arrays. In Grafana, the "Triage latency
p50/p95" and "LangGraph node duration p95" panels should show real values
after a few incidents have been posted (a single event won't populate
`rate()`-based panels until Prometheus has scraped at least twice across a
value change).

## 10. Eval harness (accuracy, safety metrics, p95 latency, cost)

Regenerates the numbers behind the README's results table, against the live stack —
see [docs/evaluation-report.md](docs/evaluation-report.md) for what these mean:

```bash
cd eval
python3 -m venv .venv && ./.venv/bin/pip install -r requirements.txt

# Dataset A — controlled regression (generated)
python3 ../synthetic-generator/generate.py --count 100 --seed 42 --out controlled/incidents.jsonl
./.venv/bin/python run_eval.py --incidents controlled/incidents.jsonl --out-dir controlled/report
cat controlled/report/eval_report.md

# Dataset B — held-out realistic (hand-authored, committed at eval/heldout/heldout.jsonl)
./.venv/bin/python run_eval.py --incidents heldout/heldout.jsonl --out-dir heldout/report
cat heldout/report/eval_report.md
cd ..
```

## 11. Cleanup

All of the above writes real rows to Postgres and the eval harness alone
adds 160 (100 + 60). Clean up test data by `idempotency_key` prefix so it doesn't
pollute the dashboard or Grafana panels for the next person testing:

```bash
docker compose exec -T postgres psql -U triagent -d triagent -c "
DELETE FROM ticket_retrieved_docs WHERE ticket_id IN (SELECT id FROM tickets WHERE incident_id IN (SELECT id FROM incidents WHERE idempotency_key LIKE 'test-%' OR idempotency_key LIKE 'synthetic-%' OR idempotency_key LIKE 'heldout-%'));
DELETE FROM tickets WHERE incident_id IN (SELECT id FROM incidents WHERE idempotency_key LIKE 'test-%' OR idempotency_key LIKE 'synthetic-%' OR idempotency_key LIKE 'heldout-%');
DELETE FROM incidents WHERE idempotency_key LIKE 'test-%' OR idempotency_key LIKE 'synthetic-%' OR idempotency_key LIKE 'heldout-%';
"
```

Also clear the matching Redis idempotency keys so re-running the same
`dedup_key`s later doesn't get rejected as a stale duplicate:

```bash
docker compose exec redis redis-cli --scan --pattern 'idem:test-*' | xargs -r docker compose exec -T redis redis-cli DEL
docker compose exec redis redis-cli --scan --pattern 'idem:synthetic-*' | xargs -r docker compose exec -T redis redis-cli DEL
docker compose exec redis redis-cli --scan --pattern 'idem:heldout-*' | xargs -r docker compose exec -T redis redis-cli DEL
```
