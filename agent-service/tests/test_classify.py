import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app import classify
from app.schemas import Classification


def _make_completion(category="5xx-spike", team="payment-service", severity="critical",
                      confidence=0.95, prompt_tokens=100, completion_tokens=20):
    parsed = Classification(
        category=category, predicted_team=team, predicted_severity=severity,
        confidence=confidence, rationale="matches known 5xx-spike pattern",
    )
    completion = MagicMock()
    completion.choices = [MagicMock(message=MagicMock(parsed=parsed))]
    completion.usage = MagicMock(prompt_tokens=prompt_tokens, completion_tokens=completion_tokens)
    return completion


@patch("app.classify.record_usage")
@patch("app.classify._openai")
def test_classify_returns_parsed_classification(mock_openai, mock_record_usage):
    mock_openai.return_value.chat.completions.parse.return_value = _make_completion()

    result = classify.classify("payment-service 5xx spike", retrieved_docs=[])

    assert isinstance(result, Classification)
    assert result.category == "5xx-spike"
    assert result.predicted_team == "payment-service"
    assert result.confidence == 0.95


@patch("app.classify.record_usage")
@patch("app.classify._openai")
def test_classify_records_token_usage(mock_openai, mock_record_usage):
    mock_openai.return_value.chat.completions.parse.return_value = _make_completion(
        prompt_tokens=321, completion_tokens=45
    )

    classify.classify("some incident text", retrieved_docs=[])

    mock_record_usage.assert_called_once_with("chat", 321, 45)


@patch("app.classify.record_usage")
@patch("app.classify._openai")
def test_classify_includes_redacted_text_and_doc_context_in_prompt(mock_openai, mock_record_usage):
    mock_openai.return_value.chat.completions.parse.return_value = _make_completion()
    retrieved_docs = [
        {"rank": 1, "source_type": "alert", "title": "PaymentService5xxSpike",
         "snippet": "Fires when 5xx rate exceeds 5%", "doc_id": "5xx-spike", "score": 0.9},
    ]

    classify.classify("[REDACTED_EMAIL] reported payment-service 5xx errors", retrieved_docs)

    call_kwargs = mock_openai.return_value.chat.completions.parse.call_args.kwargs
    user_message = next(m["content"] for m in call_kwargs["messages"] if m["role"] == "user")
    assert "[REDACTED_EMAIL] reported payment-service 5xx errors" in user_message
    assert "PaymentService5xxSpike" in user_message


@patch("app.classify.record_usage")
@patch("app.classify._openai")
def test_classify_with_no_retrieved_docs_notes_it_in_prompt(mock_openai, mock_record_usage):
    mock_openai.return_value.chat.completions.parse.return_value = _make_completion()

    classify.classify("an incident with nothing relevant retrieved", retrieved_docs=[])

    call_kwargs = mock_openai.return_value.chat.completions.parse.call_args.kwargs
    user_message = next(m["content"] for m in call_kwargs["messages"] if m["role"] == "user")
    assert "no relevant documents retrieved" in user_message
