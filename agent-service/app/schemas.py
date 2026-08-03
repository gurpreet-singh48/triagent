"""Pydantic models and the LangGraph state shape shared across app modules."""
from __future__ import annotations

from typing import Any, Literal, Optional, TypedDict

from pydantic import BaseModel, Field


class IncidentPayload(BaseModel):
    summary: str
    source: Optional[str] = None
    severity: Optional[str] = None
    timestamp: Optional[str] = None
    component: Optional[str] = None
    group: Optional[str] = None
    class_: Optional[str] = Field(default=None, alias="class")
    custom_details: dict[str, Any] = Field(default_factory=dict)

    model_config = {"populate_by_name": True}


class TriageRequest(BaseModel):
    incident_id: str
    routing_key: Optional[str] = None
    dedup_key: Optional[str] = None
    payload: IncidentPayload


class TriageResponse(BaseModel):
    status: Literal["triaged", "failed"]
    ticket_id: Optional[str] = None
    decision: Optional[str] = None
    confidence: Optional[float] = None
    category: Optional[str] = None
    predicted_team: Optional[str] = None


class Classification(BaseModel):
    """OpenAI structured-output schema for the classify node."""
    category: str = Field(description="one of the alert-doc categories, e.g. '5xx-spike', 'duplicate-charge'")
    predicted_team: str = Field(description="owning team, e.g. 'payment-service', 'auth-service', 'queue-consumer'")
    predicted_severity: str = Field(description="one of: critical, error, warning, info")
    confidence: float = Field(description="0-1 confidence that category/team/severity are correct", ge=0, le=1)
    rationale: str = Field(description="one to three sentences explaining the classification, citing retrieved docs by title where relevant")


class RetrievedDoc(TypedDict):
    doc_id: str
    title: str
    source_type: str
    score: float
    snippet: str
    rank: int


class GraphState(TypedDict, total=False):
    incident_id: str
    routing_key: Optional[str]
    dedup_key: Optional[str]
    payload: dict[str, Any]
    redacted_text: str
    retrieved_docs: list[RetrievedDoc]
    classification: Optional[Classification]
    decision: str
    ticket_id: Optional[str]
    error: Optional[str]
