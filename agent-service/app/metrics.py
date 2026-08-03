"""Shared Prometheus metrics for OpenAI usage.

Token/request counters exist specifically so the eval harness can compute a
real average-cost-per-incident and average-tokens-per-incident by diffing
these counters across an eval run, instead of guessing.
"""
from __future__ import annotations

from prometheus_client import Counter

OPENAI_TOKENS_TOTAL = Counter(
    "openai_tokens_total",
    "OpenAI API tokens consumed, by call type and token kind",
    ["call", "kind"],  # call: "chat" | "embedding"; kind: "prompt" | "completion"
)

OPENAI_REQUESTS_TOTAL = Counter(
    "openai_requests_total",
    "OpenAI API requests made, by call type",
    ["call"],
)

# USD per 1M tokens — a pricing snapshot, not a billing-accurate source. Used
# only to estimate cost/incident for the eval report; see docs/evaluation-report.md
# limitations. Update these if OpenAI's published pricing changes.
PRICING_PER_MILLION_TOKENS = {
    ("chat", "prompt"): 0.15,
    ("chat", "completion"): 0.60,
    ("embedding", "prompt"): 0.02,
}


def record_usage(call: str, prompt_tokens: int, completion_tokens: int = 0) -> None:
    OPENAI_REQUESTS_TOTAL.labels(call=call).inc()
    OPENAI_TOKENS_TOTAL.labels(call=call, kind="prompt").inc(prompt_tokens)
    if completion_tokens:
        OPENAI_TOKENS_TOTAL.labels(call=call, kind="completion").inc(completion_tokens)
