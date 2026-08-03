import sys
from pathlib import Path
from unittest.mock import patch

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app import callback


def _response(status_code, json_body=None):
    request = httpx.Request("POST", "http://backend/api/internal/triage-results")
    resp = httpx.Response(status_code, json=json_body or {}, request=request)
    return resp


@patch("app.callback.httpx.post")
def test_retries_on_connection_error_then_succeeds(mock_post):
    mock_post.side_effect = [
        httpx.ConnectError("connection refused"),
        httpx.ConnectError("connection refused"),
        _response(200, {"ticket_id": "abc", "status": "OPEN"}),
    ]

    result = callback.post_triage_result({"incident_id": "incident-1", "decision": "AUTO_TICKET"})

    assert result == {"ticket_id": "abc", "status": "OPEN"}
    assert mock_post.call_count == 3


@patch("app.callback.httpx.post")
def test_retries_on_5xx_then_succeeds(mock_post):
    mock_post.side_effect = [_response(503), _response(200, {"ticket_id": "abc", "status": "OPEN"})]

    result = callback.post_triage_result({"incident_id": "incident-2", "decision": "AUTO_TICKET"})

    assert result == {"ticket_id": "abc", "status": "OPEN"}
    assert mock_post.call_count == 2


@patch("app.callback.httpx.post")
def test_does_not_retry_on_4xx(mock_post):
    # A 401 (bad internal-service token) or 404 (unknown incident) is
    # permanent for this payload — retrying it is pointless and the call
    # must fail after exactly one attempt.
    mock_post.return_value = _response(401)

    with pytest.raises(httpx.HTTPStatusError):
        callback.post_triage_result({"incident_id": "incident-3", "decision": "AUTO_TICKET"})

    assert mock_post.call_count == 1


@patch("app.callback.httpx.post")
def test_gives_up_after_max_attempts(mock_post):
    mock_post.side_effect = httpx.ConnectError("connection refused")

    with pytest.raises(httpx.ConnectError):
        callback.post_triage_result({"incident_id": "incident-4", "decision": "AUTO_TICKET"})

    assert mock_post.call_count == 4
