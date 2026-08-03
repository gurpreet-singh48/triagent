import sys
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app import graph
from app.schemas import Classification

BASE_PAYLOAD = {
    "summary": "payment-service returning elevated 5xx errors",
    "source": "payment-service-prod-1",
    "severity": "critical",
    "component": "payment-service",
    "custom_details": {"error_rate": "0.12"},
}


def _classification(confidence=0.95, category="5xx-spike", team="payment-service", severity="critical"):
    return Classification(
        category=category, predicted_team=team, predicted_severity=severity,
        confidence=confidence, rationale="matches known pattern",
    )


@patch("app.callback.post_triage_result")
@patch("app.classify.classify")
@patch("app.retrieval.retrieve")
def test_high_confidence_routes_to_auto_ticket(mock_retrieve, mock_classify, mock_callback):
    mock_retrieve.return_value = []
    mock_classify.return_value = _classification(confidence=0.95)
    mock_callback.return_value = {"ticket_id": "ticket-123"}

    result = graph.run_triage("incident-1", "R123", "dedup-1", BASE_PAYLOAD)

    assert result["decision"] == "AUTO_TICKET"
    assert result["ticket_id"] == "ticket-123"
    assert mock_callback.call_args.args[0]["decision"] == "AUTO_TICKET"


@patch("app.callback.post_triage_result")
@patch("app.classify.classify")
@patch("app.retrieval.retrieve")
def test_low_confidence_routes_to_human_review(mock_retrieve, mock_classify, mock_callback):
    mock_retrieve.return_value = []
    mock_classify.return_value = _classification(confidence=0.4)
    mock_callback.return_value = {"ticket_id": "ticket-456"}

    result = graph.run_triage("incident-2", "R123", "dedup-2", BASE_PAYLOAD)

    assert result["decision"] == "HUMAN_REVIEW"
    assert mock_callback.call_args.args[0]["decision"] == "HUMAN_REVIEW"


@patch("app.callback.post_triage_result")
@patch("app.classify.classify")
@patch("app.retrieval.retrieve")
def test_retrieval_failure_routes_to_failed_callback(mock_retrieve, mock_classify, mock_callback):
    # Simulates Qdrant being unavailable: retrieve() raises instead of
    # returning docs.
    mock_retrieve.side_effect = ConnectionError("qdrant unavailable")

    result = graph.run_triage("incident-3", "R123", "dedup-3", BASE_PAYLOAD)

    assert result["decision"] == "FAILED"
    assert "error" in result
    mock_classify.assert_not_called()
    assert mock_callback.call_args.args[0] == {"incident_id": "incident-3", "decision": "FAILED"}


@patch("app.callback.post_triage_result")
@patch("app.classify.classify")
@patch("app.retrieval.retrieve")
def test_classification_failure_routes_to_failed_callback(mock_retrieve, mock_classify, mock_callback):
    # Simulates an OpenAI failure during classification.
    mock_retrieve.return_value = []
    mock_classify.side_effect = RuntimeError("openai request failed")

    result = graph.run_triage("incident-4", "R123", "dedup-4", BASE_PAYLOAD)

    assert result["decision"] == "FAILED"
    assert mock_callback.call_args.args[0]["decision"] == "FAILED"


@patch("app.callback.post_triage_result")
@patch("app.classify.classify")
@patch("app.retrieval.retrieve")
def test_callback_failure_on_success_path_does_not_raise(mock_retrieve, mock_classify, mock_callback):
    mock_retrieve.return_value = []
    mock_classify.return_value = _classification(confidence=0.95)
    # Every callback attempt fails — both the success-path callback AND the
    # error_handler's own FAILED callback — proving run_triage never lets
    # an unreachable backend raise out of the graph invocation.
    mock_callback.side_effect = ConnectionError("backend unreachable")

    result = graph.run_triage("incident-5", "R123", "dedup-5", BASE_PAYLOAD)

    assert result["decision"] == "FAILED"
    assert "error" in result


@patch("app.callback.post_triage_result")
@patch("app.classify.classify")
@patch("app.retrieval.retrieve")
def test_pii_is_redacted_before_reaching_retrieval_and_classification(mock_retrieve, mock_classify, mock_callback):
    mock_retrieve.return_value = []
    mock_classify.return_value = _classification()
    mock_callback.return_value = {"ticket_id": "ticket-789"}

    payload = dict(BASE_PAYLOAD)
    payload["summary"] = (
        "Customer alice@example.com reported duplicate charges; token "
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U leaked in logs"
    )

    graph.run_triage("incident-6", "R123", "dedup-6", payload)

    retrieval_query_text = mock_retrieve.call_args.args[0]
    classify_text_arg = mock_classify.call_args.args[0]
    for text in (retrieval_query_text, classify_text_arg):
        assert "alice@example.com" not in text
        assert "eyJhbGciOiJIUzI1NiJ9" not in text
        assert "[REDACTED_EMAIL]" in text
        assert "[REDACTED_TOKEN]" in text
