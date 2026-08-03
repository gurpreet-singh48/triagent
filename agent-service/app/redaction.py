"""Regex/heuristic PII redaction guardrail.

This is intentionally not real NER — it's regex + a small hardcoded name
gazetteer, documented as such (see PLAN.md's assumptions). It's the first
thing that runs on any incident text before that text is embedded, sent to
an LLM, or persisted as `redacted_summary`.
"""
from __future__ import annotations

import re
from typing import Any

# Order matters: more specific/structured patterns (tokens, emails) must be
# redacted before looser ones (phone numbers) that could otherwise partially
# match leftover fragments.

_JWT_RE = re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b")
_GENERIC_TOKEN_RE = re.compile(r"\b(?:sk|pk|ghp|gho|xox[baprs])-?[A-Za-z0-9]{16,}\b", re.IGNORECASE)
_EMAIL_RE = re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.-]+\b")
_IPV4_RE = re.compile(r"\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b")
_PHONE_RE = re.compile(r"\b(?:\+?1[\s.-]?)?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}\b")

# Small, explicitly non-exhaustive gazetteer — a real deployment would use
# Presidio/Comprehend NER instead of a hardcoded name list.
_NAME_GAZETTEER = [
    "John Smith", "Jane Smith", "John Doe", "Jane Doe", "Michael Brown",
    "Sarah Johnson", "David Wilson", "Emily Davis", "Robert Miller", "Jennifer Garcia",
]
_NAME_RE = re.compile(r"\b(" + "|".join(re.escape(n) for n in _NAME_GAZETTEER) + r")\b", re.IGNORECASE)


def redact(text: str) -> str:
    """Redact emails, phone numbers, IPv4 addresses, JWT/API-key-looking
    tokens, and gazetteer names from free text."""
    if not text:
        return text
    text = _JWT_RE.sub("[REDACTED_TOKEN]", text)
    text = _GENERIC_TOKEN_RE.sub("[REDACTED_TOKEN]", text)
    text = _EMAIL_RE.sub("[REDACTED_EMAIL]", text)
    text = _IPV4_RE.sub("[REDACTED_IP]", text)
    text = _PHONE_RE.sub("[REDACTED_PHONE]", text)
    text = _NAME_RE.sub("[REDACTED_NAME]", text)
    return text


def build_incident_text(payload: dict[str, Any]) -> str:
    """Flatten an incident payload's summary + custom_details into a single
    text blob suitable for redaction, embedding, and classification."""
    parts = [payload.get("summary") or ""]
    custom_details = payload.get("custom_details") or {}
    for key, value in custom_details.items():
        parts.append(f"{key}: {value}")
    return "\n".join(p for p in parts if p)
