import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app import retrieval


def _make_point(doc_id, title, score):
    point = MagicMock()
    point.score = score
    point.payload = {
        "doc_id": doc_id,
        "title": title,
        "source_type": "alert",
        "doc_content": f"content for {doc_id}",
    }
    return point


def _make_embedding_response(total_tokens=12):
    response = MagicMock()
    response.data = [MagicMock(embedding=[0.0] * 1536)]
    response.usage = MagicMock(total_tokens=total_tokens)
    return response


@patch("app.retrieval.record_usage")
@patch("app.retrieval._qdrant")
@patch("app.retrieval._openai")
def test_retrieve_filters_by_team_when_enough_results(mock_openai, mock_qdrant, mock_record_usage):
    mock_openai.return_value.embeddings.create.return_value = _make_embedding_response()
    filtered_points = [_make_point(f"doc-{i}", f"Doc {i}", 0.9 - i * 0.05) for i in range(3)]
    mock_qdrant.return_value.query_points.return_value = MagicMock(points=filtered_points)

    docs = retrieval.retrieve("payment-service is down", team="payment-service")

    assert len(docs) == 3
    # Only ONE Qdrant call: the filtered search returned enough results
    # (>= MIN_FILTERED_RESULTS), so no unfiltered fallback call is made.
    assert mock_qdrant.return_value.query_points.call_count == 1
    call_kwargs = mock_qdrant.return_value.query_points.call_args.kwargs
    assert call_kwargs["query_filter"] is not None


@patch("app.retrieval.record_usage")
@patch("app.retrieval._qdrant")
@patch("app.retrieval._openai")
def test_retrieve_falls_back_to_unfiltered_when_filtered_results_too_thin(mock_openai, mock_qdrant, mock_record_usage):
    mock_openai.return_value.embeddings.create.return_value = _make_embedding_response()
    thin_filtered = [_make_point("doc-1", "Doc 1", 0.8)]  # below MIN_FILTERED_RESULTS (3)
    unfiltered = [_make_point(f"doc-{i}", f"Doc {i}", 0.7 - i * 0.05) for i in range(5)]
    mock_qdrant.return_value.query_points.side_effect = [
        MagicMock(points=thin_filtered),
        MagicMock(points=unfiltered),
    ]

    docs = retrieval.retrieve("some vague incident with sparse doc coverage", team="payment-service")

    assert len(docs) == 5
    assert mock_qdrant.return_value.query_points.call_count == 2
    first_call, second_call = mock_qdrant.return_value.query_points.call_args_list
    assert first_call.kwargs["query_filter"] is not None
    assert second_call.kwargs.get("query_filter") is None


@patch("app.retrieval.record_usage")
@patch("app.retrieval._qdrant")
@patch("app.retrieval._openai")
def test_retrieve_with_no_team_skips_filtered_search_entirely(mock_openai, mock_qdrant, mock_record_usage):
    mock_openai.return_value.embeddings.create.return_value = _make_embedding_response()
    mock_qdrant.return_value.query_points.return_value = MagicMock(points=[_make_point("doc-1", "Doc 1", 0.6)])

    docs = retrieval.retrieve("an incident with no known component", team=None)

    assert len(docs) == 1
    assert mock_qdrant.return_value.query_points.call_count == 1
    call_kwargs = mock_qdrant.return_value.query_points.call_args.kwargs
    assert call_kwargs.get("query_filter") is None


@patch("app.retrieval.record_usage")
@patch("app.retrieval._qdrant")
@patch("app.retrieval._openai")
def test_retrieve_records_embedding_token_usage(mock_openai, mock_qdrant, mock_record_usage):
    mock_openai.return_value.embeddings.create.return_value = _make_embedding_response(total_tokens=42)
    mock_qdrant.return_value.query_points.return_value = MagicMock(points=[])

    retrieval.retrieve("some incident", team=None)

    mock_record_usage.assert_called_once_with("embedding", 42)


@patch("app.retrieval.record_usage")
@patch("app.retrieval._qdrant")
@patch("app.retrieval._openai")
def test_qdrant_query_retries_on_connection_error_then_succeeds(mock_openai, mock_qdrant, mock_record_usage):
    mock_openai.return_value.embeddings.create.return_value = _make_embedding_response()
    mock_qdrant.return_value.query_points.side_effect = [
        httpx.ConnectError("connection refused"),
        MagicMock(points=[_make_point("doc-1", "Doc 1", 0.6)]),
    ]

    docs = retrieval.retrieve("some incident", team=None)

    assert len(docs) == 1
    assert mock_qdrant.return_value.query_points.call_count == 2


@patch("app.retrieval.record_usage")
@patch("app.retrieval._qdrant")
@patch("app.retrieval._openai")
def test_qdrant_query_gives_up_after_max_attempts(mock_openai, mock_qdrant, mock_record_usage):
    mock_openai.return_value.embeddings.create.return_value = _make_embedding_response()
    mock_qdrant.return_value.query_points.side_effect = httpx.ConnectError("connection refused")

    with pytest.raises(httpx.ConnectError):
        retrieval.retrieve("some incident", team=None)

    assert mock_qdrant.return_value.query_points.call_count == 3
