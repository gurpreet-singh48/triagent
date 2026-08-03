"""FastAPI entrypoint for the LangGraph triage agent."""
from __future__ import annotations

import logging

from dotenv import load_dotenv

load_dotenv()

from fastapi import FastAPI  # noqa: E402
from prometheus_fastapi_instrumentator import Instrumentator  # noqa: E402

from .graph import run_triage  # noqa: E402
from .schemas import TriageRequest, TriageResponse  # noqa: E402

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("agent-service")

app = FastAPI(title="triagent-agent-service")

# Exposes /metrics with per-endpoint request latency histograms (including
# /triage's overall latency). Per-LangGraph-node timing is a separate,
# finer-grained set of histograms — see graph.py's NODE_DURATION_SECONDS.
Instrumentator().instrument(app).expose(app)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/triage", response_model=TriageResponse)
def triage(request: TriageRequest) -> TriageResponse:
    result_state = run_triage(
        incident_id=request.incident_id,
        routing_key=request.routing_key,
        dedup_key=request.dedup_key,
        payload=request.payload.model_dump(by_alias=True),
    )

    if result_state.get("decision") == "FAILED" or result_state.get("error"):
        logger.error("triage failed for incident %s: %s", request.incident_id, result_state.get("error"))
        return TriageResponse(status="failed", ticket_id=None)

    classification = result_state.get("classification")
    return TriageResponse(
        status="triaged",
        ticket_id=result_state.get("ticket_id"),
        decision=result_state.get("decision"),
        confidence=classification.confidence if classification else None,
        category=classification.category if classification else None,
        predicted_team=classification.predicted_team if classification else None,
    )
