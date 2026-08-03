# Triagent — Agentic Incident-Intelligence Platform: Implementation Plan

## Context

This is a from-scratch, local, docker-compose-runnable build of an "agentic incident-intelligence platform" — a portfolio project meant to genuinely earn a resume claim like *"Launched an agentic incident-intelligence platform (LangGraph, Qdrant, Spring AI), cutting manual triage from ~2 hours to under 5 minutes, with a labeled eval measuring triage accuracy, p95 latency, and PII-redaction guardrails."* AWS deployment is explicitly out of scope for now (no license; the $100 trial credit is a possible later stretch, not part of this plan) — everything runs via `docker compose up` on the local machine.

The intended outcome isn't just a working demo — it's one where every number in that resume bullet is backed by something real: an actual labeled eval script producing an accuracy percentage, actual latency instrumentation producing a p95, and an actual redaction module that can be shown masking PII before it hits OpenAI.

**Environment check (done):** Java 25, Python 3.11.5 present. Docker is **not installed** and Node is on **v16.15.1** (too old for modern Vite/React tooling) — user will install Docker Desktop and upgrade Node themselves before Phase 0 / Phase 4 respectively. No existing RFC/Go/PromQL docs were found on disk, so the corpus is synthesized as part of Phase 1 rather than sourced from real files.

**Confirmed decisions:**
- **Dual-stack architecture**: Spring Boot owns webhook intake, Redis idempotency, ticket DB, ticket/team-status API, human-in-the-loop approve/reject, and the RAG chat endpoint (via Spring AI). A separate Python FastAPI + **LangGraph** service owns the actual agentic reasoning (retrieve → classify → decide) and calls back into Spring Boot to create tickets. This is deliberately closer to the real resume stack than a single-service shortcut would be — it's what gives genuine, defensible LangGraph + Spring AI experience.
- **Seed corpus is generated**, not sourced — a small hand-authored set of RFCs, Go service code, and Grafana PromQL alert docs, designed so the alert docs define a taxonomy that later drives synthetic incident generation and eval ground truth (closing the loop cleanly).
- **All four recommended enhancements are in scope**: PII redaction guardrail, labeled eval harness, Prometheus+Grafana observability (p95 latency), and a synthetic incident generator (no real PagerDuty account needed).
- **Idempotency**: Redis SETNX on a dedupe key (PagerDuty's `dedup_key`, or a hash fallback), TTL 24h — duplicate/retriggered incidents short-circuit before ever reaching the LLM.
- **Postgres** for ticket/incident storage; **Qdrant** single collection for the doc corpus; **communication between Spring Boot and the agent service is synchronous HTTP** (no broker — appropriate for a local demo, not a production HA claim).

**Key assumptions (stated openly, not hidden):**
- "Confident more than 9" → **confidence ≥ 0.9** (0–1 scale). Numerically equivalent either way this is read.
- The "~2 hours → <5 minutes" baseline is an *estimate*, not something the system can measure directly (no real historical human-triage data exists to compare against). Recommend qualifying the resume bullet accordingly, or optionally timing a human doing the same eval batch by hand for a defensible number.
- PII redaction is regex/heuristic-based (emails, phones, IPs, tokens/JWT-looking strings, a small name gazetteer) — not real NER. This is a fine, honest scope for a demo and a good interview talking point ("regex+gazetteer here; production would use Presidio/Comprehend").
- No auth/login system — `reviewed_by` is a free-text demo field.
- **Cross-service embedding consistency is load-bearing**: the Python ingestion pipeline and Spring AI's `QdrantVectorStore` (used by the chat endpoint) must use the identical embedding model (`text-embedding-3-small`, 1536-dim, Cosine) and the ingestion pipeline must write Qdrant payloads in the exact shape Spring AI expects — text under a `doc_content` key, metadata as flat top-level fields (not nested) — with the backend's `QdrantVectorStore` configured `initializeSchema(false)`. Getting this wrong breaks the chat feature silently later.

---

## Repository Layout

```
triagent/
├── docker-compose.yml
├── .env.example
├── README.md
├── docs-corpus/
│   ├── rfcs/                  # RFC-001..004 markdown
│   ├── go-services/           # payment-service/, auth-service/, queue-consumer/
│   └── alerts/                # one markdown per PromQL alert definition
├── ingestion/
│   ├── ingest.py
│   ├── chunking.py
│   ├── smoke_query.py
│   └── requirements.txt
├── backend/                    # Spring Boot (Maven)
│   ├── src/main/java/com/incidentintel/...
│   ├── src/main/resources/db/migration/   # Flyway
│   ├── pom.xml
│   └── Dockerfile
├── agent-service/               # FastAPI + LangGraph
│   ├── app/{main,graph,redaction,retrieval,classify,callback,schemas}.py
│   ├── tests/test_redaction.py
│   ├── requirements.txt
│   └── Dockerfile
├── frontend/                    # React + Vite + TS
│   ├── src/{pages,components,api}/
│   ├── package.json
│   ├── nginx.conf
│   └── Dockerfile
├── synthetic-generator/
│   ├── templates/*.json         # single source of truth, shared into backend + eval at build time
│   └── generate.py
├── eval/
│   ├── incidents.jsonl
│   ├── run_eval.py
│   └── report/
└── observability/
    ├── prometheus/prometheus.yml
    └── grafana/provisioning/{datasources,dashboards}/
```

`docker-compose.yml`'s `backend` and `agent-service` build with `context: .` (repo root) so both Dockerfiles can `COPY synthetic-generator/templates/` into their images — template definitions authored once, used by Java, Python, and the eval harness alike.

---

## Cross-Service Design

### End-to-end flow
```
Client (curl / synthetic-generator / "Trigger Sample Incident" button)
  → POST backend /api/webhooks/pagerduty
      [Redis SETNX idempotency check — short-circuits duplicates here]
      [persist Incident row, status=RECEIVED]
      → POST agent-service /triage  (blocking, ~30s timeout)
            [redact PII]
            [embed + retrieve from Qdrant]
            [classify via OpenAI structured output, get confidence]
            [decide: confidence >= 0.9 ? AUTO_TICKET : HUMAN_REVIEW]
            → POST backend /api/internal/triage-results
                  [create Ticket row, update Incident.status=TRIAGED]
      ← 202 {incident_id, ticket_id, status}
```

### Webhook payload (PagerDuty Events API v2 shaped)
```json
POST /api/webhooks/pagerduty
{
  "routing_key": "string",
  "event_action": "trigger",
  "dedup_key": "optional-string",
  "payload": {
    "summary": "string", "source": "payment-service-prod-1",
    "severity": "critical|error|warning|info", "timestamp": "ISO8601",
    "component": "payment-service", "group": "payments", "class": "5xx-spike",
    "custom_details": { "arbitrary log/metric context" }
  },
  "client": "Synthetic Incident Generator", "client_url": "http://localhost:..."
}
```
Response: `202 {status: "processing"|"duplicate", incident_id, ticket_id|null}`

**Idempotency key** = `dedup_key` if present, else `sha256(routing_key+source+component+class+summary)`. Redis `idem:{key}` → `incident_id`, TTL 24h, `SETNX`. On collision: fetch the existing incident/ticket from Postgres and return — no call to the agent service, proving dedupe works without burning an OpenAI call.

### `POST agent-service /triage`
Request: `{incident_id, routing_key, dedup_key, payload: {...}}`
Response: `{status: "triaged"|"failed", ticket_id, decision, confidence, category, predicted_team}`

### `POST backend /api/internal/triage-results` (called by agent-service)
```json
{
  "incident_id": "uuid", "decision": "AUTO_TICKET|HUMAN_REVIEW",
  "confidence": 0.93, "predicted_team": "payment-service",
  "predicted_category": "5xx-spike", "predicted_severity": "critical",
  "rationale": "string", "redacted_summary": "string — what was actually sent to the LLM",
  "retrieved_docs": [{"doc_id":"...","title":"...","source_type":"rfc|go_code|alert_rule","score":0.81,"snippet":"...","rank":1}]
}
```
Backend re-checks `confidence >= threshold` itself (defense-in-depth, not just trusting the agent's label) before setting ticket status to `OPEN` vs `PENDING_REVIEW`.

### Public/frontend-facing backend API
- `GET /api/tickets?team=&status=&page=&size=`
- `GET /api/tickets/{id}` — incident, confidence, decision, retrieved-doc citations
- `POST /api/tickets/{id}/approve` / `POST /api/tickets/{id}/reject`
- `POST /api/chat` → `{message, conversationId?}` → `{answer, sources:[{docId,title,snippet,score}]}`
- `GET /actuator/health`, `GET /actuator/prometheus`

### Postgres schema
```sql
incidents(
  id UUID PK, dedup_key VARCHAR, idempotency_key VARCHAR UNIQUE,
  routing_key VARCHAR, source VARCHAR, summary TEXT, severity VARCHAR,
  component VARCHAR, group_name VARCHAR, class VARCHAR, custom_details JSONB,
  status VARCHAR,  -- RECEIVED, TRIAGING, TRIAGED, FAILED
  received_at TIMESTAMPTZ, created_at, updated_at TIMESTAMPTZ
)
tickets(
  id UUID PK, incident_id UUID FK,
  status VARCHAR,  -- OPEN, PENDING_REVIEW, APPROVED, REJECTED, RESOLVED, DISCARDED
  predicted_team VARCHAR, predicted_category VARCHAR, predicted_severity VARCHAR,
  confidence_score NUMERIC(4,3), decision VARCHAR, rationale TEXT,
  redacted_summary TEXT, reviewed_by VARCHAR, reviewed_at TIMESTAMPTZ,
  created_at, updated_at TIMESTAMPTZ
)
ticket_retrieved_docs(
  id UUID PK, ticket_id UUID FK, doc_id VARCHAR, doc_title VARCHAR,
  doc_source_type VARCHAR, score NUMERIC, snippet TEXT, rank INT
)
```
Indexes: `tickets.status`, `tickets.predicted_team`, unique `incidents.idempotency_key`. Managed via Flyway migrations (`backend/src/main/resources/db/migration/V1__init.sql`).

### Qdrant collection: `docs_corpus`
- Vector size 1536 (`text-embedding-3-small`), Cosine distance.
- Payload: `doc_content` (**exact key name required** for Spring AI compatibility), `doc_id`, `source_type`, `title`, `team`, `file_path`, `chunk_index`, `tags[]`.
- Point IDs deterministic (`hash(doc_id + chunk_index)`) so re-ingestion upserts rather than duplicates.
- Chunking by type: RFCs split on `##` headers (further split sections >~800 tokens, ~50-token overlap); Go code chunked per-function; alert docs are one chunk each (already short, natural retrieval unit).
- Retrieval-time: filter by `team == incident.component`, fall back to unfiltered top-k if filtered results < 3.

### LangGraph graph (`agent-service/app/graph.py`)
State: `{incident_id, raw_payload, redacted_text, retrieved_docs, classification, decision, ticket_id, error}`
```
START → redact_pii → retrieve_docs → classify → decide (conditional edge)
                                                    ├─ confidence >= 0.9 → auto_ticket → END
                                                    └─ confidence <  0.9 → human_review → END
(any node failure) → error_handler → callback(status=FAILED) → END
```
`classify` uses OpenAI structured output (pydantic schema `Classification{category, predicted_team, predicted_severity, confidence, rationale}`) over `redacted_text` + retrieved doc context. `error_handler` guarantees a callback always fires so incidents never get stuck in `TRIAGING`.

---

## Phases

### Phase 0 — Repo scaffolding + docker-compose infra skeleton
Directory layout above; `docker-compose.yml` with `postgres`, `redis`, `qdrant` + named volumes, shared network, healthchecks (`pg_isready`, `redis-cli ping`, `curl qdrant/healthz`); `.env.example`; root `README.md`.
**Verify:** `docker compose up -d postgres redis qdrant` → `docker compose ps` all healthy; `curl localhost:6333/collections`; `redis-cli -h localhost ping` → `PONG`.

### Phase 1 — Seed docs corpus + Qdrant ingestion pipeline
Hand-author: 4 RFCs (Payment Idempotency, Auth Token Refresh, Queue Consumer Retry/Backoff, Incident Severity/Escalation Policy — each with a "Failure Modes" section foreshadowing an eval category), 3 fictional Go services (payment-service, auth-service, queue-consumer) with realistic subtle bugs baked in, 6–8 PromQL alert docs (name/expr/for/severity/team/runbook/description) that become the ground-truth taxonomy for Phase 6. `ingestion/ingest.py` (type-specific chunking, batched embeddings, Qdrant upsert with `doc_content`-keyed flat-metadata payload shape, `--recreate` flag), `ingestion/smoke_query.py` for eyeballing retrieval quality.
**Verify:** `python ingestion/ingest.py`; `curl localhost:6333/collections/docs_corpus` shows expected point count; smoke query for "why would payment service duplicate a charge" surfaces RFC-001 + the payment-service Go file near the top.

### Phase 2 — Spring Boot webhook intake + Redis idempotency + ticket DB
Maven project, Flyway migration for the schema above, `RedisIdempotencyService`, webhook controller, ticket CRUD/filter + approve/reject endpoints, and a **temporary hand-curlable stub** of `POST /api/internal/triage-results` (the real caller doesn't exist until Phase 3). Add `backend` to compose.
**Verify:** duplicate `dedup_key` webhook calls return the same `incident_id`, only one Postgres row; manual curl to the stub creates a ticket row visible via `GET /api/tickets`; approve/reject flips status; `/actuator/health` OK.

### Phase 3 — Python LangGraph agent service, wired end-to-end
FastAPI app implementing the graph; `redaction.py` (+ `tests/test_redaction.py` covering emails/phones/IPv4/tokens/gazetteer names); `retrieval.py`; `classify.py`; `callback.py`. Backend's webhook handler now calls the real agent service instead of Phase 2's stub. Add `agent-service` to compose.
**Verify:** `pytest agent-service/tests`; realistic "payment-service 5xx spike" payload → ticket shows correct `predicted_team`, confidence, and citations to RFC-001 + matching alert doc; a vague payload lands in `PENDING_REVIEW`; a payload with a fake email/IP/token in `custom_details` shows masked in `redacted_summary` (concrete PII-guardrail proof); resending the same `dedup_key` shows no new agent-service call in logs.

### Phase 4 — React frontend (dashboard, human-in-the-loop, chat)
(Upgrade Node to 18+/20+ first.) Vite+React+TS: filterable ticket dashboard → detail view (confidence, decision, doc citations), approve/reject on `PENDING_REVIEW`, chat page hitting `POST /api/chat`. Backend gains the RAG chat endpoint here (Spring AI `ChatClient` + `QuestionAnswerAdvisor` + `QdrantVectorStore`, `initializeSchema(false)`, `contentFieldName("doc_content")`, same embedding model as ingestion). Multi-stage Dockerfile (Node build → nginx proxying `/api`). Add `frontend` to compose.
**Verify:** trigger an incident via curl, see it on the dashboard, approve a pending ticket, ask the chat "what causes payment service duplicate charges" and get an answer citing RFC-001.

### Phase 5 — Observability (Prometheus + Grafana, p95 latency)
Backend: `micrometer-registry-prometheus`, `/actuator/prometheus`, custom `triage_latency_seconds` timer tagged by outcome. Agent-service: `prometheus-fastapi-instrumentator` + per-node LangGraph timing histograms. `observability/prometheus/prometheus.yml` scrapes both; Grafana auto-provisioned datasource + dashboard (p50/p95 latency, request volume, error rate, auto-vs-human breakdown). Add `prometheus`+`grafana` to compose.
**Verify:** burst of synthetic incidents via curl loop; p95 query works directly in Prometheus; Grafana dashboard renders real panels.

### Phase 6 — Eval harness + synthetic incident generator
`synthetic-generator/templates/*.json` — one per Phase-1 alert doc, each carrying `expected_team/category/severity` (so ground truth derives directly from the alert taxonomy, not hand-labeling) plus randomizable fields and a unique `dedup_key` per generation. `generate.py --count 30 --out eval/incidents.jsonl` (labels kept separate from the payload sent to the LLM). `eval/run_eval.py`: posts each incident, polls until triaged, records latency, scores predicted-vs-expected, writes `eval/report/eval_report.{json,md}` — **this file is what backs the resume's accuracy/p95 numbers.** Backend gets a dev-only `SampleIncidentController` wiring the frontend's "Trigger Sample Incident" button to the same shared templates.
**Verify:** generate 30 labeled incidents; run the eval against the full stack; inspect the markdown report; click "Trigger Sample Incident" in the UI and watch a ticket appear; re-run eval to confirm idempotency doesn't swallow the new run (unique dedup_keys per generation); watch the Grafana p95 panel populate live during the run.

---

## Critical Files
- `docker-compose.yml` — wires all 8 services, healthchecks, shared build context for template sharing
- `ingestion/ingest.py` — defines the Qdrant payload shape that both agent-service retrieval and Spring AI's `QdrantVectorStore` depend on
- `agent-service/app/graph.py` — the LangGraph retrieve→classify→decide structure, the core "agentic" deliverable
- `agent-service/app/redaction.py` — the testable PII-redaction guardrail
- `backend/src/main/resources/db/migration/V1__init.sql` — schema anchoring every later phase
- `eval/run_eval.py` — produces the actual accuracy/p95 numbers behind the resume bullet
