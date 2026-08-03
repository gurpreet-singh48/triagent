"""Calls back into the Spring Boot backend with the triage result."""
from __future__ import annotations

import os
from typing import Any

import httpx

BACKEND_CALLBACK_URL = os.environ.get("BACKEND_CALLBACK_URL", "http://localhost:8080")
TIMEOUT_SECONDS = 10.0


def post_triage_result(payload: dict[str, Any]) -> dict[str, Any]:
    url = f"{BACKEND_CALLBACK_URL}/api/internal/triage-results"
    response = httpx.post(url, json=payload, timeout=TIMEOUT_SECONDS)
    response.raise_for_status()
    return response.json()
