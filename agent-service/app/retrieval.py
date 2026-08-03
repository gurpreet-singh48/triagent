"""Qdrant retrieval, matching the payload shape ingestion/ingest.py wrote:
`doc_content` as the text field, flat top-level metadata otherwise.
"""
from __future__ import annotations

import os

import httpx
from openai import OpenAI
from qdrant_client import QdrantClient
from qdrant_client.http import models as qmodels
from tenacity import retry, retry_if_exception_type, stop_after_attempt, wait_exponential_jitter

from .metrics import record_usage
from .schemas import RetrievedDoc

EMBEDDING_MODEL = os.environ.get("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
QDRANT_HOST = os.environ.get("QDRANT_HOST", "localhost")
QDRANT_HTTP_PORT = int(os.environ.get("QDRANT_HTTP_PORT", "6333"))
COLLECTION_NAME = os.environ.get("QDRANT_COLLECTION", "docs_corpus")
MIN_FILTERED_RESULTS = 3
TOP_K = 5

# The OpenAI SDK already retries transient errors (connection failures,
# timeouts, 429, 5xx) internally with its own exponential backoff+jitter —
# this just makes the attempt limit explicit rather than relying on the
# SDK default.
OPENAI_MAX_RETRIES = 3

_openai_client: OpenAI | None = None
_qdrant_client: QdrantClient | None = None


def _openai() -> OpenAI:
    global _openai_client
    if _openai_client is None:
        _openai_client = OpenAI(api_key=os.environ["OPENAI_API_KEY"], max_retries=OPENAI_MAX_RETRIES)
    return _openai_client


def _qdrant() -> QdrantClient:
    global _qdrant_client
    if _qdrant_client is None:
        _qdrant_client = QdrantClient(host=QDRANT_HOST, port=QDRANT_HTTP_PORT)
    return _qdrant_client


@retry(
    stop=stop_after_attempt(3),
    wait=wait_exponential_jitter(initial=0.25, max=4),
    retry=retry_if_exception_type((httpx.ConnectError, httpx.TimeoutException, ConnectionError, TimeoutError)),
    reraise=True,
)
def _query_qdrant(**kwargs):
    """A Qdrant query is a read with no side effects, so blindly retrying
    it is always safe — unlike the callback, this doesn't depend on any
    idempotency guarantee."""
    return _qdrant().query_points(**kwargs).points


def retrieve(query_text: str, team: str | None, top_k: int = TOP_K) -> list[RetrievedDoc]:
    """Embed query_text and search docs_corpus, filtered by team when given.
    Falls back to an unfiltered search if the filtered results are too thin
    (e.g. a component with sparse doc coverage) — see PLAN.md's retrieval
    design.
    """
    embedding_response = _openai().embeddings.create(model=EMBEDDING_MODEL, input=[query_text])
    if embedding_response.usage:
        record_usage("embedding", embedding_response.usage.total_tokens)
    vector = embedding_response.data[0].embedding

    if team:
        team_filter = qmodels.Filter(
            must=[qmodels.FieldCondition(key="team", match=qmodels.MatchValue(value=team))]
        )
        filtered = _query_qdrant(
            collection_name=COLLECTION_NAME, query=vector, query_filter=team_filter,
            limit=top_k, with_payload=True,
        )
        results = _to_retrieved_docs(filtered)
        if len(results) >= MIN_FILTERED_RESULTS:
            return results

    unfiltered = _query_qdrant(
        collection_name=COLLECTION_NAME, query=vector, limit=top_k, with_payload=True,
    )
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
