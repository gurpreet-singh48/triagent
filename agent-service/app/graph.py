"""LangGraph: redact -> retrieve -> classify -> decide, wired end-to-end
with the backend callback. Every path — success or failure — ends by
posting a result to the backend so incidents never get stuck in TRIAGING.
"""
from __future__ import annotations

import logging
import os
import time
import traceback
from typing import Callable, Optional

from langgraph.graph import END, START, StateGraph
from prometheus_client import Histogram

from . import callback
from . import classify as classify_module
from . import retrieval
from .redaction import build_incident_text, redact
from .schemas import GraphState

logger = logging.getLogger("agent-service.graph")

CONFIDENCE_THRESHOLD = float(os.environ.get("TRIAGE_CONFIDENCE_THRESHOLD", "0.9"))

NODE_DURATION_SECONDS = Histogram(
    "langgraph_node_duration_seconds",
    "Duration of each LangGraph node execution, by node name",
    ["node"],
)


def _timed_node(name: str, fn: Callable[[GraphState], dict]) -> Callable[[GraphState], dict]:
    def wrapper(state: GraphState) -> dict:
        start = time.perf_counter()
        try:
            return fn(state)
        finally:
            NODE_DURATION_SECONDS.labels(node=name).observe(time.perf_counter() - start)

    return wrapper


def redact_pii(state: GraphState) -> dict:
    raw_text = build_incident_text(state["payload"])
    return {"redacted_text": redact(raw_text)}


def retrieve_docs(state: GraphState) -> dict:
    team = state["payload"].get("component")
    docs = retrieval.retrieve(state["redacted_text"], team)
    return {"retrieved_docs": docs}


def classify_node(state: GraphState) -> dict:
    classification = classify_module.classify(state["redacted_text"], state["retrieved_docs"])
    return {"classification": classification}


def decide(state: GraphState) -> dict:
    confidence = state["classification"].confidence
    decision = "AUTO_TICKET" if confidence >= CONFIDENCE_THRESHOLD else "HUMAN_REVIEW"
    return {"decision": decision}


def route_after_decide(state: GraphState) -> str:
    return "auto_ticket" if state["decision"] == "AUTO_TICKET" else "human_review"


def _post_success(state: GraphState) -> dict:
    classification = state["classification"]
    result = callback.post_triage_result({
        "incident_id": state["incident_id"],
        "decision": state["decision"],
        "confidence": classification.confidence,
        "predicted_team": classification.predicted_team,
        "predicted_category": classification.category,
        "predicted_severity": classification.predicted_severity,
        "rationale": classification.rationale,
        "redacted_summary": state["redacted_text"],
        "retrieved_docs": [dict(d) for d in state["retrieved_docs"]],
    })
    return {"ticket_id": result.get("ticket_id")}


def auto_ticket(state: GraphState) -> dict:
    return _post_success(state)


def human_review(state: GraphState) -> dict:
    return _post_success(state)


def error_handler(incident_id: str) -> None:
    """Guarantees a callback always fires on failure, so the incident never
    gets stuck in TRIAGING on the backend."""
    try:
        callback.post_triage_result({"incident_id": incident_id, "decision": "FAILED"})
    except Exception:
        logger.exception("FAILED callback itself failed for incident %s", incident_id)


def build_graph():
    graph = StateGraph(GraphState)
    graph.add_node("redact_pii", _timed_node("redact_pii", redact_pii))
    graph.add_node("retrieve_docs", _timed_node("retrieve_docs", retrieve_docs))
    graph.add_node("classify", _timed_node("classify", classify_node))
    graph.add_node("decide", _timed_node("decide", decide))
    graph.add_node("auto_ticket", _timed_node("auto_ticket", auto_ticket))
    graph.add_node("human_review", _timed_node("human_review", human_review))

    graph.add_edge(START, "redact_pii")
    graph.add_edge("redact_pii", "retrieve_docs")
    graph.add_edge("retrieve_docs", "classify")
    graph.add_edge("classify", "decide")
    graph.add_conditional_edges("decide", route_after_decide, {
        "auto_ticket": "auto_ticket",
        "human_review": "human_review",
    })
    graph.add_edge("auto_ticket", END)
    graph.add_edge("human_review", END)

    return graph.compile()


_compiled_graph = None


def get_graph():
    global _compiled_graph
    if _compiled_graph is None:
        _compiled_graph = build_graph()
    return _compiled_graph


def run_triage(incident_id: str, routing_key: Optional[str], dedup_key: Optional[str], payload: dict) -> GraphState:
    """Runs the graph for one incident. A node failure anywhere in the graph
    (retrieval error, OpenAI error, etc.) is caught here and always routed
    through error_handler, which fires the FAILED callback — this is the
    "(any node failure) -> error_handler -> callback(FAILED) -> END" path
    from PLAN.md's graph design.
    """
    initial_state: GraphState = {
        "incident_id": incident_id,
        "routing_key": routing_key,
        "dedup_key": dedup_key,
        "payload": payload,
    }
    try:
        return get_graph().invoke(initial_state)
    except Exception as exc:
        logger.exception("triage graph failed for incident %s", incident_id)
        error_handler(incident_id)
        return {**initial_state, "decision": "FAILED", "error": f"{exc}\n{traceback.format_exc()}"}
