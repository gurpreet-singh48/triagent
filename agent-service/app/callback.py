"""Calls back into the Spring Boot backend with the triage result."""
from __future__ import annotations

import logging
import os
from typing import Any

import httpx
from tenacity import before_sleep_log, retry, retry_if_exception, stop_after_attempt, wait_exponential_jitter

logger = logging.getLogger("agent-service.callback")

BACKEND_CALLBACK_URL = os.environ.get("BACKEND_CALLBACK_URL", "http://localhost:8080")
INTERNAL_SERVICE_TOKEN = os.environ.get("INTERNAL_SERVICE_TOKEN", "dev-local-internal-token")
TIMEOUT_SECONDS = 10.0


def _is_retryable(exc: BaseException) -> bool:
    """Retry connection failures/timeouts and 5xx responses; never 4xx.
    A 400/401/404/409 is permanent for a given payload — retrying wastes
    attempts and, for 409-adjacent cases, could even be misread as the
    server being flaky when it's actually rejecting the request correctly.

    Retrying here at all is only safe because the callback endpoint is
    idempotent (see TriageResultService.record on the backend): a retried
    callback for the same incident returns the existing ticket instead of
    creating a duplicate.
    """
    if isinstance(exc, httpx.HTTPStatusError):
        return exc.response.status_code >= 500
    return isinstance(exc, (httpx.ConnectError, httpx.TimeoutException, httpx.RemoteProtocolError))


@retry(
    stop=stop_after_attempt(4),
    wait=wait_exponential_jitter(initial=0.5, max=8),
    retry=retry_if_exception(_is_retryable),
    before_sleep=before_sleep_log(logger, logging.WARNING),
    reraise=True,
)
def post_triage_result(payload: dict[str, Any]) -> dict[str, Any]:
    url = f"{BACKEND_CALLBACK_URL}/api/internal/triage-results"
    headers = {"X-Internal-Service-Token": INTERNAL_SERVICE_TOKEN}
    response = httpx.post(url, json=payload, headers=headers, timeout=TIMEOUT_SECONDS)
    response.raise_for_status()
    return response.json()
