# Triagent — Agentic Incident-Intelligence Platform

A local, `docker compose up`-runnable platform that turns a PagerDuty-shaped
webhook into a triaged, cited ticket in seconds: a Spring Boot service
handles intake, idempotency, ticket storage, human-in-the-loop review, and
a streaming RAG chat; a Python **LangGraph** agent does the actual
reasoning (redact → retrieve → classify → decide) against a **Qdrant**-indexed
corpus of RFCs, alert-rule docs, and Go service source. Built from scratch,
including a labeled eval harness and a Prometheus/Grafana observability
stack.

![Triagent architecture](docs/architecture.svg)

## Results

On a 100-incident synthetic labeled eval (see [Running the eval
harness](#running-the-eval-harness)):

| Metric | Value |
|---|---|
| Exact-match accuracy (team + category + severity) | 100% |
| p50 latency (webhook → ticket) | 2.14s |
| p95 latency (webhook → ticket) | 2.75s |

All incidents are PII-redacted (`[REDACTED_EMAIL]`, `[REDACTED_PHONE]`,
`[REDACTED_IP]`, `[REDACTED_NAME]`, token/JWT patterns) before the text is
ever embedded, sent to an LLM, or persisted — see
[redaction.py](agent-service/app/redaction.py).

## How it works

1. A webhook (PagerDuty-shaped JSON, or a curl/synthetic incident) hits the
   Spring Boot backend, which reserves an idempotency key in Redis and
   persists the incident.
2. The backend calls the agent-service synchronously, which runs a
   4-node LangGraph: **redact PII → retrieve** (Qdrant similarity search
   over the doc corpus) **→ classify** (OpenAI structured output: team,
   category, severity, confidence, rationale) **→ decide** (confidence
   ≥ 0.9 auto-creates a ticket, otherwise it's queued for human review).
3. The agent-service calls back into the backend, which creates the
   ticket with its predicted fields, rationale, redacted summary, and the
   retrieved-doc citations.
4. The React frontend shows a ticket dashboard, a detail view with
   citations and an approve/reject flow for `PENDING_REVIEW` tickets, and
   a streaming RAG chat page over the same doc corpus.
5. Both services export Prometheus metrics (`triage_latency_seconds`,
   `langgraph_node_duration_seconds`), visualized on a pre-provisioned
   Grafana dashboard.

See [PLAN.md](./PLAN.md) for the full design and phased build plan.

## Tech stack

**Backend:** Spring Boot 3 (Java 21), Spring Data JPA, Spring AI (`ChatClient`,
`QdrantVectorStore`, `QuestionAnswerAdvisor`), Postgres, Redis, Flyway,
Micrometer/Prometheus.
**Agent service:** Python, FastAPI, LangGraph, OpenAI (structured outputs +
streaming), Qdrant client.
**Frontend:** React 19, Vite, TypeScript, react-router-dom.
**Infra:** Docker Compose, Prometheus, Grafana.

## Prerequisites
- Docker + Docker Compose v2 (`docker compose version`)
- An OpenAI API key
- Node.js 18+ (only needed if building the frontend outside Docker)
- Python 3.11+ (only needed for the ingestion pipeline / eval harness outside Docker)

## Quickstart

```bash
cp .env.example .env   # then fill in OPENAI_API_KEY
docker compose up -d --build
docker compose ps      # all 8 services should report healthy
```

Services: `postgres`, `redis`, `qdrant`, `backend` (Spring Boot, :8080), `agent-service`
(FastAPI + LangGraph, :8000), `frontend` (React via nginx, :5173), `prometheus` (:9090),
`grafana` (:3000, login `admin`/`admin` unless overridden).

## One-time setup: ingest the doc corpus

The chat feature and agent retrieval depend on the `docs_corpus` Qdrant collection being
populated. Run this once after first bringing the stack up (and again any time
`docs-corpus/` changes):

```bash
cd ingestion
python3 -m venv .venv && ./.venv/bin/pip install -r requirements.txt
./.venv/bin/python ingest.py --recreate
./.venv/bin/python smoke_query.py "why would payment service duplicate a charge"
```

## Using it

- Open http://localhost:5173 — dashboard of tickets, click "Trigger Sample Incident" to
  generate a realistic synthetic incident end-to-end, or approve/reject a `PENDING_REVIEW`
  ticket.
- Ask the chat page (http://localhost:5173/chat) something like *"what causes payment
  service duplicate charges"* — it's a real, token-streamed RAG pipeline citing the doc corpus.
- Or trigger an incident directly via curl:
  ```bash
  curl -X POST localhost:8080/api/webhooks/pagerduty -H "Content-Type: application/json" -d '{
    "routing_key": "R123", "event_action": "trigger", "dedup_key": "demo-1",
    "payload": {
      "summary": "payment-service returning elevated 5xx errors, error rate 12%",
      "source": "payment-service-prod-1", "severity": "critical",
      "timestamp": "2026-01-01T00:00:00Z", "component": "payment-service",
      "group": "payments", "class": "5xx-spike", "custom_details": {"error_rate": "0.12"}
    },
    "client": "curl", "client_url": "http://localhost"
  }'
  ```

For a full manual walkthrough of every path (idempotency, human-in-the-loop
review, PII redaction, observability), see [TESTING.md](./TESTING.md).

## Running the eval harness

Produces the labeled accuracy + p95 latency numbers behind the results table above:

```bash
cd eval
python3 -m venv .venv && ./.venv/bin/pip install -r requirements.txt
python3 ../synthetic-generator/generate.py --count 100 --out incidents.jsonl
./.venv/bin/python run_eval.py --incidents incidents.jsonl --backend-url http://localhost:8080
cat report/eval_report.md
```

Watch p50/p95 latency populate live in Grafana (http://localhost:3000, dashboard
"Triagent Overview") while the eval runs.

## Observability

- Prometheus: http://localhost:9090 — `triage_latency_seconds` (backend, tagged by
  `outcome`: `auto_ticket`/`human_review`/`failed`) and `langgraph_node_duration_seconds`
  (agent-service, tagged by `node`).
- Grafana: http://localhost:3000 — auto-provisioned "Triagent Overview" dashboard
  (p50/p95 latency, request volume, error rate, auto-vs-human breakdown).

## Deploying to AWS

[AWS_DEPLOYMENT.md](./AWS_DEPLOYMENT.md) walks through running this live on
a single EC2 instance — security group, Docker install, bringing the stack
up, and (critically) a full teardown checklist so nothing keeps billing
afterward.

## Repo layout

See [PLAN.md](./PLAN.md) for the full repository layout, cross-service design (webhook →
idempotency → agent-service → callback → ticket), Postgres schema, Qdrant payload shape,
and the LangGraph graph structure.
