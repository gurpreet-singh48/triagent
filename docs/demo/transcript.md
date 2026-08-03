# Triagent demo transcript

**Duration:** 1:44  
**Capture environment:** local Docker Compose stack on Colima  
**Narration:** AI-generated voice

## Transcript

This is Triagent, an agentic incident-intelligence platform running locally on Colima
with eight healthy Docker services.

An incoming PagerDuty-shaped webhook is accepted by the Spring Boot backend,
deduplicated in Redis, and sent to a four-node LangGraph agent. The agent redacts
sensitive data, retrieves relevant operational documents from Qdrant, classifies the
incident, and decides whether it is safe to create a ticket automatically.

The dashboard shows the resulting tickets with team, category, severity, confidence,
and decision. Low-confidence cases remain pending for human review; clear incidents are
auto-ticketed.

Here is an adversarial duplicate-charge incident. Even though its text contains an
auto-approve override, Triagent ignores that instruction and classifies the real failure
correctly. The ticket preserves a redacted summary, concise rationale, and scored
citations to the payment duplicate-charge runbook and the idempotency RFC. That makes
the decision inspectable instead of merely plausible.

The same document corpus powers streaming RAG chat. This answer identifies the race
between a separate select and insert, plus unstable retry keys, then recommends an
atomic insert-on-conflict operation and stable idempotency keys. Every answer includes
its retrieved sources.

Prometheus and Grafana expose triage latency, request outcomes, error rate, auto-ticket
versus human-review decisions, and per-node LangGraph duration.

Finally, the new held-out runner completed all sixty incidents with no request failures.
Exact match improved to ninety percent; team routing reached ninety-six percent;
unknown rejection stayed at one hundred percent. Four of forty-five auto-ticketed
incidents were still incorrect, an eight-point-nine percent safety error rate. Median
latency was two-point-four-four seconds. P ninety-five rose to three-point-five-seven
seconds because the cold first request took seven seconds, so warm-up remains a concrete
optimization target.

That is the full local path: intake, grounded triage, human fallback, cited chat,
observability, and reproducible evaluation.
