"""Qdrant retrieval, matching the payload shape ingestion/ingest.py wrote:
`doc_content` as the text field, flat top-level metadata otherwise.
"""
from __future__ import annotations

import os
from typing import Optional

from openai import OpenAI
from qdrant_client import QdrantClient
from qdrant_client.http import models as qmodels

from .schemas import RetrievedDoc

EMBEDDING_MODEL = os.environ.get("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
QDRANT_HOST = os.environ.get("QDRANT_HOST", "localhost")
QDRANT_HTTP_PORT = int(os.environ.get("QDRANT_HTTP_PORT", "6333"))
COLLECTION_NAME = os.environ.get("QDRANT_COLLECTION", "docs_corpus")
MIN_FILTERED_RESULTS = 3
TOP_K = 5

_openai_client: Optional[OpenAI] = None
_qdrant_client: Optional[QdrantClient] = None


def _openai() -> OpenAI:
    global _openai_client
    if _openai_client is None:
        _openai_client = OpenAI(api_key=os.environ["OPENAI_API_KEY"])
    return _openai_client


def _qdrant() -> QdrantClient:
    global _qdrant_client
    if _qdrant_client is None:
        _qdrant_client = QdrantClient(host=QDRANT_HOST, port=QDRANT_HTTP_PORT)
    return _qdrant_client


def retrieve(query_text: str, team: Optional[str], top_k: int = TOP_K) -> list[RetrievedDoc]:
    """Embed query_text and search docs_corpus, filtered by team when given.
    Falls back to an unfiltered search if the filtered results are too thin
    (e.g. a component with sparse doc coverage) — see PLAN.md's retrieval
    design.
    """
    vector = _openai().embeddings.create(model=EMBEDDING_MODEL, input=[query_text]).data[0].embedding

    if team:
        team_filter = qmodels.Filter(
            must=[qmodels.FieldCondition(key="team", match=qmodels.MatchValue(value=team))]
        )
        filtered = _qdrant().query_points(
            collection_name=COLLECTION_NAME, query=vector, query_filter=team_filter,
            limit=top_k, with_payload=True,
        ).points
        results = _to_retrieved_docs(filtered)
        if len(results) >= MIN_FILTERED_RESULTS:
            return results

    unfiltered = _qdrant().query_points(
        collection_name=COLLECTION_NAME, query=vector, limit=top_k, with_payload=True,
    ).points
    return _to_retrieved_docs(unfiltered)


def _to_retrieved_docs(points) -> list[RetrievedDoc]:
    docs: list[RetrievedDoc] = []
    for rank, point in enumerate(points, start=1):
        payload = point.payload or {}
        docs.append(RetrievedDoc(
            doc_id=payload.get("doc_id", ""),
            title=payload.get("title", ""),
            source_type=payload.get("source_type", ""),
            score=point.score,
            snippet=(payload.get("doc_content") or "")[:500],
            rank=rank,
        ))
    return docs
