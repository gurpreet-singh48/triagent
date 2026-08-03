#!/usr/bin/env python3
"""Eyeball retrieval quality against the ingested docs_corpus collection.

Usage:
    python smoke_query.py "why would payment service duplicate a charge"
    python smoke_query.py "auth service logging users out randomly" --team auth-service
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from dotenv import load_dotenv
from openai import OpenAI
from qdrant_client import QdrantClient
from qdrant_client.http import models as qmodels

REPO_ROOT = Path(__file__).resolve().parent.parent
load_dotenv(REPO_ROOT / ".env")

EMBEDDING_MODEL = os.environ.get("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
QDRANT_HOST = os.environ.get("QDRANT_HOST", "localhost")
QDRANT_HTTP_PORT = int(os.environ.get("QDRANT_HTTP_PORT", "6333"))
COLLECTION_NAME = os.environ.get("QDRANT_COLLECTION", "docs_corpus")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("query", help="natural-language query to embed and search")
    parser.add_argument("--team", default=None, help="filter by team (e.g. payment-service)")
    parser.add_argument("--top-k", type=int, default=5)
    args = parser.parse_args()

    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key or api_key.startswith("sk-replace-me"):
        sys.exit("OPENAI_API_KEY is not set (check .env)")

    openai_client = OpenAI(api_key=api_key)
    qdrant = QdrantClient(host=QDRANT_HOST, port=QDRANT_HTTP_PORT)

    vector = openai_client.embeddings.create(model=EMBEDDING_MODEL, input=[args.query]).data[0].embedding

    query_filter = None
    if args.team:
        query_filter = qmodels.Filter(
            must=[qmodels.FieldCondition(key="team", match=qmodels.MatchValue(value=args.team))]
        )

    results = qdrant.query_points(
        collection_name=COLLECTION_NAME,
        query=vector,
        query_filter=query_filter,
        limit=args.top_k,
        with_payload=True,
    ).points

    print(f'Query: "{args.query}"' + (f" (team={args.team})" if args.team else ""))
    print(f"Top {len(results)} results:\n")
    for rank, point in enumerate(results, start=1):
        payload = point.payload or {}
        snippet = (payload.get("doc_content") or "").strip().replace("\n", " ")
        if len(snippet) > 200:
            snippet = snippet[:200] + "..."
        print(f"#{rank}  score={point.score:.4f}  [{payload.get('source_type')}] {payload.get('title')}")
        print(f"      doc_id={payload.get('doc_id')} team={payload.get('team')} chunk={payload.get('chunk_index')}")
        print(f"      {snippet}\n")


if __name__ == "__main__":
    main()
