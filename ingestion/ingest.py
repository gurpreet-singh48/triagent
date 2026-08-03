#!/usr/bin/env python3
"""Ingest the docs corpus (RFCs, Go services, alert docs) into Qdrant.

Usage:
    python ingest.py [--corpus-dir docs-corpus] [--recreate] [--batch-size 64]

Embeds every chunk with OPENAI_EMBEDDING_MODEL (must match the embedding
model configured on the Spring AI QdrantVectorStore used by the chat
endpoint — see PLAN.md's "Cross-Service Design" section) and upserts into
the `docs_corpus` Qdrant collection with a flat, top-level metadata payload
shape and a `doc_content` text field — the exact shape Spring AI's
QdrantVectorStore expects when `initializeSchema(false)`.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import uuid
from pathlib import Path
from typing import Any, Iterable

from dotenv import load_dotenv
from openai import OpenAI
from qdrant_client import QdrantClient
from qdrant_client.http import models as qmodels
from tqdm import tqdm

from chunking import Chunk, chunk_alert, chunk_go, chunk_rfc

REPO_ROOT = Path(__file__).resolve().parent.parent
load_dotenv(REPO_ROOT / ".env")

EMBEDDING_MODEL = os.environ.get("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
EMBEDDING_DIM = 1536
QDRANT_HOST = os.environ.get("QDRANT_HOST", "localhost")
QDRANT_HTTP_PORT = int(os.environ.get("QDRANT_HTTP_PORT", "6333"))
COLLECTION_NAME = os.environ.get("QDRANT_COLLECTION", "docs_corpus")

# Deterministic point IDs are derived via uuid5 under this fixed namespace,
# so re-running ingest.py upserts existing points instead of duplicating
# them (same doc_id + chunk_index -> same point ID every time).
POINT_ID_NAMESPACE = uuid.UUID("6f9d7f1a-6b0e-4c2a-9b8e-6b6f2f1a9c11")


class ParsedDoc:
    def __init__(self, doc_id: str, source_type: str, title: str, team: str | None,
                 file_path: str, tags: list[str], text: str):
        self.doc_id = doc_id
        self.source_type = source_type
        self.title = title
        self.team = team
        self.file_path = file_path
        self.tags = tags
        self.text = text


def _field(text: str, label: str) -> str | None:
    m = re.search(rf"-\s*\*\*{re.escape(label)}:\*\*\s*`?([^`\n]+)`?", text)
    return m.group(1).strip() if m else None


def parse_alert(path: Path) -> ParsedDoc:
    text = path.read_text()
    title_match = re.search(r"^#\s*Alert:\s*(.+)$", text, flags=re.MULTILINE)
    title = title_match.group(1).strip() if title_match else path.stem
    category = _field(text, "Category") or path.stem
    team = _field(text, "Team")
    severity = _field(text, "Severity")
    tags = [t for t in (category, severity, "alert") if t]
    return ParsedDoc(
        doc_id=path.stem,
        source_type="alert",
        title=title,
        team=team,
        file_path=str(path.relative_to(REPO_ROOT)),
        tags=tags,
        text=text,
    )


def parse_rfc(path: Path) -> ParsedDoc:
    text = path.read_text()
    title_match = re.search(r"^#\s*(RFC-\d+:.+)$", text, flags=re.MULTILINE)
    title = title_match.group(1).strip() if title_match else path.stem
    owner = _field(text, "Owner")
    related = _field(text, "Related alerts")
    tags = ["rfc"]
    if related:
        tags.extend(a.strip().strip("`") for a in related.split(","))
    return ParsedDoc(
        doc_id=path.stem,
        source_type="rfc",
        title=title,
        team=owner,
        file_path=str(path.relative_to(REPO_ROOT)),
        tags=tags,
        text=text,
    )


def parse_go(path: Path) -> ParsedDoc:
    text = path.read_text()
    team = path.parent.name  # payment-service, auth-service, queue-consumer
    return ParsedDoc(
        doc_id=f"{team}-{path.stem}",
        source_type="go_code",
        title=f"{team}/{path.name}",
        team=team,
        file_path=str(path.relative_to(REPO_ROOT)),
        tags=["go_code", team],
        text=text,
    )


def discover(corpus_dir: Path) -> list[ParsedDoc]:
    corpus_dir = corpus_dir.resolve()
    docs: list[ParsedDoc] = []
    for path in sorted((corpus_dir / "alerts").glob("*.md")):
        docs.append(parse_alert(path))
    for path in sorted((corpus_dir / "rfcs").glob("*.md")):
        docs.append(parse_rfc(path))
    for path in sorted((corpus_dir / "go-services").rglob("*.go")):
        docs.append(parse_go(path))
    return docs


def chunk_doc(doc: ParsedDoc) -> list[Chunk]:
    if doc.source_type == "alert":
        return chunk_alert(doc.text)
    if doc.source_type == "rfc":
        return chunk_rfc(doc.text)
    if doc.source_type == "go_code":
        return chunk_go(doc.text)
    raise ValueError(f"unknown source_type {doc.source_type!r}")


def batched(items: list[Any], size: int) -> Iterable[list[Any]]:
    for i in range(0, len(items), size):
        yield items[i : i + size]


def ensure_collection(client: QdrantClient, recreate: bool) -> None:
    exists = client.collection_exists(COLLECTION_NAME)
    if exists and not recreate:
        return
    if exists and recreate:
        client.delete_collection(COLLECTION_NAME)
    client.create_collection(
        collection_name=COLLECTION_NAME,
        vectors_config=qmodels.VectorParams(size=EMBEDDING_DIM, distance=qmodels.Distance.COSINE),
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus-dir", default=str(REPO_ROOT / "docs-corpus"))
    parser.add_argument("--recreate", action="store_true", help="drop and recreate the collection first")
    parser.add_argument("--batch-size", type=int, default=64, help="embedding batch size")
    args = parser.parse_args()

    corpus_dir = Path(args.corpus_dir)
    if not corpus_dir.is_dir():
        sys.exit(f"corpus dir not found: {corpus_dir}")

    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key or api_key.startswith("sk-replace-me"):
        sys.exit("OPENAI_API_KEY is not set (check .env) — required to embed the corpus")

    openai_client = OpenAI(api_key=api_key)
    qdrant = QdrantClient(host=QDRANT_HOST, port=QDRANT_HTTP_PORT)

    print(f"Discovering docs under {corpus_dir} ...")
    docs = discover(corpus_dir)
    print(f"Found {len(docs)} source documents.")

    ensure_collection(qdrant, args.recreate)

    # Flatten every doc's chunks into (doc, chunk) pairs so embeddings can be
    # requested in batches across document boundaries, not one doc at a time.
    flat: list[tuple[ParsedDoc, Chunk]] = []
    for doc in docs:
        for chunk in chunk_doc(doc):
            flat.append((doc, chunk))
    print(f"Chunked into {len(flat)} total chunks.")

    points: list[qmodels.PointStruct] = []
    for batch in tqdm(list(batched(flat, args.batch_size)), desc="embedding"):
        texts = [chunk.text for _, chunk in batch]
        response = openai_client.embeddings.create(model=EMBEDDING_MODEL, input=texts)
        for (doc, chunk), embedding_obj in zip(batch, response.data):
            point_id = str(uuid.uuid5(POINT_ID_NAMESPACE, f"{doc.doc_id}:{chunk.chunk_index}"))
            payload = {
                "doc_content": chunk.text,
                "doc_id": doc.doc_id,
                "source_type": doc.source_type,
                "title": doc.title,
                "team": doc.team,
                "file_path": doc.file_path,
                "chunk_index": chunk.chunk_index,
                "tags": doc.tags,
            }
            points.append(qmodels.PointStruct(id=point_id, vector=embedding_obj.embedding, payload=payload))

    print(f"Upserting {len(points)} points into '{COLLECTION_NAME}' ...")
    for batch in tqdm(list(batched(points, 128)), desc="upserting"):
        qdrant.upsert(collection_name=COLLECTION_NAME, points=batch)

    count = qdrant.count(collection_name=COLLECTION_NAME, exact=True).count
    print(f"Done. Collection '{COLLECTION_NAME}' now has {count} points.")


if __name__ == "__main__":
    main()
