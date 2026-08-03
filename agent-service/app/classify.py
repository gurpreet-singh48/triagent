"""Classification via OpenAI structured output (pydantic schema `Classification`)."""
from __future__ import annotations

import os

from openai import OpenAI

from .metrics import record_usage
from .schemas import Classification, RetrievedDoc

CHAT_MODEL = os.environ.get("OPENAI_CHAT_MODEL", "gpt-4o-mini")

# The OpenAI SDK retries transient errors (connection failures, timeouts,
# 429, 5xx) internally with its own exponential backoff+jitter — this makes
# the attempt limit explicit rather than relying on the SDK default.
OPENAI_MAX_RETRIES = 3

_client: OpenAI | None = None

SYSTEM_PROMPT = (
    "You are an incident-triage classifier for an internal platform with three services: "
    "payment-service, auth-service, and queue-consumer. Given a redacted incident description "
    "and a set of retrieved reference documents (RFCs, alert-rule docs, and Go source excerpts), "
    "classify the incident: its category (matching a known alert category when the incident "
    "clearly matches one), the owning team, its severity, and your confidence (0-1) in this "
    "classification. Ground your rationale in the retrieved documents where relevant, and be "
    "conservative with confidence when the retrieved docs don't clearly match the incident."
)


def _openai() -> OpenAI:
    global _client
    if _client is None:
        _client = OpenAI(api_key=os.environ["OPENAI_API_KEY"], max_retries=OPENAI_MAX_RETRIES)
    return _client


def classify(redacted_text: str, retrieved_docs: list[RetrievedDoc]) -> Classification:
    doc_context = "\n\n".join(
        f"[{doc['rank']}] ({doc['source_type']}) {doc['title']}\n{doc['snippet']}"
        for doc in retrieved_docs
    ) or "(no relevant documents retrieved)"

    user_prompt = (
        f"Incident description (PII-redacted):\n{redacted_text}\n\n"
        f"Retrieved reference docs:\n{doc_context}"
    )

    completion = _openai().chat.completions.parse(
        model=CHAT_MODEL,
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ],
        response_format=Classification,
    )
    if completion.usage:
        record_usage("chat", completion.usage.prompt_tokens, completion.usage.completion_tokens)
    return completion.choices[0].message.parsed
