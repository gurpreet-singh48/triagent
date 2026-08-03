"""Pydantic models and the LangGraph state shape shared across app modules."""
from __future__ import annotations

from typing import Any, Literal, TypedDict

from pydantic import BaseModel, Field


class IncidentPayload(BaseModel):
    summary: str
    source: str | None = None
    severity: str | None = None
    timestamp: str | None = None
    component: str | None = None
    group: str | None = None
    class_: str | None = Field(default=None, alias="class")
    custom_details: dict[str, Any] = Field(default_factory=dict)

    model_config = {"populate_by_name": True}


class TriageRequest(BaseModel):
    incident_id: str
    routing_key: str | None = None
    dedup_key: str | None = None
    payload: IncidentPayload


class TriageResponse(BaseModel):
    status: Literal["triaged", "failed"]
    ticket_id: str | None = None
    decision: str | None = None
    confidence: float | None = None
    category: str | None = None
    predicted_team: str | None = None


class Classification(BaseModel):
    """OpenAI structured-output schema for the classify node."""
    category: str = Field(description="one of the alert-doc categories, e.g. '5xx-spike', 'duplicate-charge'")
    predicted_team: str = Field(description="owning team, e.g. 'payment-service', 'auth-service', 'queue-consumer'")
    predicted_severity: str = Field(description="one of: critical, error, warning, info")
    confidence: float = Field(description="0-1 confidence that category/team/severity are correct", ge=0, le=1)
    rationale: str = Field(
        description="one to three sentences explaining the classification, citing retrieved doc titles where relevant"
    )


class RetrievedDoc(TypedDict):
    doc_id: str
    title: str
    source_type: str
    score: float
    snippet: str
    rank: int


class GraphState(TypedDict, total=False):
    incident_id: str
    routing_key: str | None
    dedup_key: str | None
    payload: dict[str, Any]
    redacted_text: str
    retrieved_docs: list[RetrievedDoc]
    classification: Classification | None
    decision: str
    ticket_id: str | None
    error: str | None
