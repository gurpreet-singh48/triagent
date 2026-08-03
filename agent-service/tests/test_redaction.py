import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.redaction import build_incident_text, redact


def test_redacts_email():
    text = "Customer alice@example.com reported a failed charge."
    result = redact(text)
    assert "alice@example.com" not in result
    assert "[REDACTED_EMAIL]" in result


def test_redacts_phone_number():
    text = "Call the customer back at 415-555-0132 to confirm."
    result = redact(text)
    assert "415-555-0132" not in result
    assert "[REDACTED_PHONE]" in result


def test_redacts_ipv4():
    text = "Request originated from 10.42.7.19 during the spike."
    result = redact(text)
    assert "10.42.7.19" not in result
    assert "[REDACTED_IP]" in result


def test_redacts_jwt_looking_token():
    text = "auth header: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dQw4w9WgXcQ_abc123"
    result = redact(text)
    assert "eyJhbGciOiJIUzI1NiJ9" not in result
    assert "[REDACTED_TOKEN]" in result


def test_redacts_generic_api_key_token():
    # Deliberately not an "sk-" prefix: that shape trips gitleaks' OpenAI-key
    # rule even though this is fake fixture data, not a real credential.
    text = "customer support pasted ghp-abcdefghijklmnopqrstuvwx into the ticket by mistake."  # gitleaks:allow
    result = redact(text)
    assert "ghp-abcdefghijklmnopqrstuvwx" not in result
    assert "[REDACTED_TOKEN]" in result


def test_redacts_gazetteer_name():
    text = "Escalated by John Smith on the payments team."
    result = redact(text)
    assert "John Smith" not in result
    assert "[REDACTED_NAME]" in result


def test_redacts_multiple_pii_types_in_one_pass():
    text = "Contact John Doe at john.doe@example.com or 212-555-0100 from 192.168.1.5."
    result = redact(text)
    assert "John Doe" not in result
    assert "john.doe@example.com" not in result
    assert "212-555-0100" not in result
    assert "192.168.1.5" not in result
    assert result.count("[REDACTED_") == 4


def test_empty_text_is_noop():
    assert redact("") == ""


def test_build_incident_text_includes_summary_and_custom_details():
    payload = {
        "summary": "payment-service 5xx spike",
        "custom_details": {"error_rate": "0.12", "trace_id": "abc123"},
    }
    text = build_incident_text(payload)
    assert "payment-service 5xx spike" in text
    assert "error_rate: 0.12" in text
    assert "trace_id: abc123" in text


def test_build_incident_text_handles_missing_custom_details():
    assert build_incident_text({"summary": "auth-service latency"}) == "auth-service latency"
