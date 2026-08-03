#!/usr/bin/env python3
"""Run either eval dataset against the live stack, score it, and write a
report. Works for both:

  Dataset A (controlled regression) — synthetic, generator-produced,
  designed to catch regressions and measure latency consistently. The
  webhook payload includes `component`/`class`, so retrieval is filtered by
  team — this is the *easy* mode, not a measure of real routing accuracy.

  Dataset B (held-out realistic) — hand-authored, never seen by the
  generator, no `component`/`class` fields in the webhook payload. This is
  the harder, more honest measure: retrieval has to work from free text
  alone, same as it would for a real incoming incident. See
  docs/evaluation-report.md for why this split exists.

Usage:
    python run_eval.py --incidents controlled/incidents.jsonl --out-dir controlled/report
    python run_eval.py --incidents heldout/heldout.jsonl --out-dir heldout/report
"""
from __future__ import annotations

import argparse
import json
import math
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import requests

REPO_ROOT = Path(__file__).resolve().parent.parent

# USD per 1M tokens — a pricing snapshot for the eval report's cost estimate,
# not a billing-accurate source. Mirrors agent-service/app/metrics.py (kept
# duplicated deliberately: eval/ and agent-service/ are separate deployable
# units with separate venvs, not a shared package).
PRICING_PER_MILLION_TOKENS = {
    "chat_prompt": 0.15,
    "chat_completion": 0.60,
    "embedding_prompt": 0.02,
}


def percentile(data: list[float], pct: float) -> float | None:
    if not data:
        return None
    data_sorted = sorted(data)
    k = (len(data_sorted) - 1) * (pct / 100)
    f, c = math.floor(k), math.ceil(k)
    if f == c:
        return data_sorted[int(k)]
    return data_sorted[f] + (data_sorted[c] - data_sorted[f]) * (k - f)


def load_incidents(path: Path) -> list[dict]:
    incidents = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                incidents.append(json.loads(line))
    return incidents


def is_unknown_row(row: dict) -> bool:
    """True for held-out 'unknown/out-of-scope' cases where there is no
    correct team/category — the only right answer is for the system to
    defer to human review rather than confidently guess one."""
    return row["expected"].get("team") is None


def run_one(backend_url: str, incident: dict, timeout: float) -> dict:
    expected = incident["expected"]
    bucket = incident.get("bucket", "controlled")
    start = time.perf_counter()
    try:
        response = requests.post(
            f"{backend_url}/api/webhooks/pagerduty", json=incident["webhook_payload"], timeout=timeout
        )
        response.raise_for_status()
        webhook_result = response.json()
    except requests.RequestException as exc:
        return {
            "id": incident.get("id", incident.get("index")),
            "bucket": bucket,
            "expected": expected,
            "webhook_status": "error",
            "error": str(exc),
            "latency_seconds": time.perf_counter() - start,
            "predicted": None,
            "correct": {"team": False, "category": False, "severity": False, "all": False},
        }

    latency_seconds = time.perf_counter() - start
    ticket_id = webhook_result.get("ticket_id")

    predicted = None
    if ticket_id:
        try:
            ticket_response = requests.get(f"{backend_url}/api/tickets/{ticket_id}", timeout=timeout)
            ticket_response.raise_for_status()
            ticket = ticket_response.json()
            predicted = {
                "team": ticket.get("predicted_team"),
                "category": ticket.get("predicted_category"),
                "severity": ticket.get("predicted_severity"),
                "confidence": ticket.get("confidence_score"),
                "decision": ticket.get("decision"),
                "status": ticket.get("status"),
            }
        except requests.RequestException as exc:
            return {
                "id": incident.get("id", incident.get("index")),
                "bucket": bucket,
                "expected": expected,
                "webhook_status": webhook_result.get("status"),
                "error": f"failed to fetch ticket {ticket_id}: {exc}",
                "latency_seconds": latency_seconds,
                "predicted": None,
                "correct": {"team": False, "category": False, "severity": False, "all": False},
            }

    correct = {
        "team": bool(predicted and predicted["team"] == expected.get("team")),
        "category": bool(predicted and predicted["category"] == expected.get("category")),
        "severity": bool(predicted and predicted["severity"] == expected.get("severity")),
    }
    correct["all"] = correct["team"] and correct["category"] and correct["severity"]

    return {
        "id": incident.get("id", incident.get("index")),
        "bucket": bucket,
        "expected": expected,
        "webhook_status": webhook_result.get("status"),
        "ticket_id": ticket_id,
        "latency_seconds": latency_seconds,
        "predicted": predicted,
        "correct": correct,
    }


def query_prometheus_scalar(prometheus_url: str, promql: str) -> float | None:
    try:
        resp = requests.get(f"{prometheus_url}/api/v1/query", params={"query": promql}, timeout=10)
        resp.raise_for_status()
        result = resp.json()["data"]["result"]
        return sum(float(r["value"][1]) for r in result) if result else 0.0
    except (requests.RequestException, KeyError, ValueError):
        return None


def collect_openai_usage(prometheus_url: str) -> dict[str, float | None]:
    return {
        "chat_prompt": query_prometheus_scalar(prometheus_url, 'sum(openai_tokens_total{call="chat",kind="prompt"})'),
        "chat_completion": query_prometheus_scalar(prometheus_url, 'sum(openai_tokens_total{call="chat",kind="completion"})'),
        "embedding_prompt": query_prometheus_scalar(prometheus_url, 'sum(openai_tokens_total{call="embedding",kind="prompt"})'),
    }


def usage_delta(before: dict, after: dict) -> dict[str, float | None]:
    delta = {}
    for key in before:
        b, a = before.get(key), after.get(key)
        delta[key] = (a - b) if (a is not None and b is not None) else None
    return delta


def build_report(rows: list[dict], usage: dict[str, float | None] | None, n_incidents_for_cost: int) -> dict:
    total = len(rows)
    responded = [r for r in rows if r["predicted"] is not None]
    n_responded = len(responded)

    scorable = [r for r in responded if not is_unknown_row(r)]
    n_scorable = len(scorable)

    def accuracy(field: str) -> float | None:
        return (sum(1 for r in scorable if r["correct"][field]) / n_scorable) if n_scorable else None

    latencies = [r["latency_seconds"] for r in rows]

    auto_ticket_rows = [r for r in responded if r["predicted"]["decision"] == "AUTO_TICKET"]
    human_review_rows = [r for r in responded if r["predicted"]["decision"] == "HUMAN_REVIEW"]
    unknown_rows = [r for r in responded if is_unknown_row(r)]

    # The safety metric: of incidents that were auto-accepted with NO human
    # review, and that had a known-correct answer, how many were wrong?
    auto_ticket_scorable = [r for r in auto_ticket_rows if not is_unknown_row(r)]
    incorrect_auto_ticket_count = sum(1 for r in auto_ticket_scorable if not r["correct"]["all"])
    incorrect_auto_ticket_rate = (
        incorrect_auto_ticket_count / len(auto_ticket_scorable) if auto_ticket_scorable else None
    )

    unknown_rejection_rate = (
        sum(1 for r in unknown_rows if r["predicted"]["decision"] == "HUMAN_REVIEW") / len(unknown_rows)
        if unknown_rows else None
    )

    bucket_breakdown = {}
    for bucket in sorted({r.get("bucket", "controlled") for r in rows}):
        rows_in_bucket = [r for r in rows if r.get("bucket", "controlled") == bucket]
        scorable_in_bucket = [r for r in rows_in_bucket if r in scorable]
        bucket_breakdown[bucket] = {
            "n": len(rows_in_bucket),
            "exact_match": (
                sum(1 for r in scorable_in_bucket if r["correct"]["all"]) / len(scorable_in_bucket)
                if scorable_in_bucket else None
            ),
            "human_review_rate": (
                sum(1 for r in rows_in_bucket if r["predicted"] and r["predicted"]["decision"] == "HUMAN_REVIEW")
                / len(rows_in_bucket)
                if rows_in_bucket else None
            ),
        }

    decision_breakdown = Counter(
        (r["predicted"]["decision"] if r["predicted"] else None) or r["webhook_status"] or "unknown"
        for r in rows
    )

    cost = None
    if usage is not None:
        chat_prompt = usage.get("chat_prompt") or 0.0
        chat_completion = usage.get("chat_completion") or 0.0
        embedding_prompt = usage.get("embedding_prompt") or 0.0
        total_tokens = chat_prompt + chat_completion + embedding_prompt
        cost_usd = (
            chat_prompt / 1_000_000 * PRICING_PER_MILLION_TOKENS["chat_prompt"]
            + chat_completion / 1_000_000 * PRICING_PER_MILLION_TOKENS["chat_completion"]
            + embedding_prompt / 1_000_000 * PRICING_PER_MILLION_TOKENS["embedding_prompt"]
        )
        cost = {
            "chat_prompt_tokens": chat_prompt,
            "chat_completion_tokens": chat_completion,
            "embedding_prompt_tokens": embedding_prompt,
            "total_tokens": total_tokens,
            "estimated_cost_usd": cost_usd,
            "avg_tokens_per_incident": total_tokens / n_incidents_for_cost if n_incidents_for_cost else None,
            "avg_cost_usd_per_incident": cost_usd / n_incidents_for_cost if n_incidents_for_cost else None,
        }

    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "total_incidents": total,
        "responded_incidents": n_responded,
        "unresponded_incidents": total - n_responded,
        "scorable_incidents": n_scorable,
        "unknown_bucket_incidents": len(unknown_rows),
        "accuracy": {
            "team": accuracy("team"),
            "category": accuracy("category"),
            "severity": accuracy("severity"),
            "exact_match": accuracy("all"),
        },
        "safety": {
            "human_review_rate": (len(human_review_rows) / n_responded) if n_responded else None,
            "auto_ticket_count": len(auto_ticket_rows),
            "incorrect_auto_ticket_count": incorrect_auto_ticket_count,
            "incorrect_auto_ticket_rate": incorrect_auto_ticket_rate,
            "unknown_rejection_rate": unknown_rejection_rate,
        },
        "latency_seconds": {
            "p50": percentile(latencies, 50),
            "p95": percentile(latencies, 95),
            "mean": sum(latencies) / len(latencies) if latencies else None,
            "max": max(latencies) if latencies else None,
        },
        "cost": cost,
        "decision_breakdown": dict(decision_breakdown),
        "bucket_breakdown": bucket_breakdown,
        "rows": rows,
    }


def write_markdown(report: dict, path: Path, title: str) -> None:
    acc = report["accuracy"]
    safety = report["safety"]
    lat = report["latency_seconds"]
    cost = report["cost"]

    def pct(x: float | None) -> str:
        return f"{x * 100:.1f}%" if x is not None else "n/a"

    def secs(x: float | None) -> str:
        return f"{x:.2f}s" if x is not None else "n/a"

    lines = [
        f"# {title}",
        "",
        f"Generated: {report['generated_at']}",
        "",
        f"- Total incidents: {report['total_incidents']}",
        f"- Responded (ticket created): {report['responded_incidents']}",
        f"- Unresponded (webhook/agent failure): {report['unresponded_incidents']}",
        f"- Scorable (excludes unknown/out-of-scope bucket): {report['scorable_incidents']}",
        "",
        "## Accuracy (scorable incidents only)",
        "",
        "| Field | Accuracy |",
        "|---|---|",
        f"| Team routing | {pct(acc['team'])} |",
        f"| Category | {pct(acc['category'])} |",
        f"| Severity | {pct(acc['severity'])} |",
        f"| **Exact match (all three)** | **{pct(acc['exact_match'])}** |",
        "",
        "## Safety",
        "",
        "| Metric | Value |",
        "|---|---|",
        f"| Human-review rate | {pct(safety['human_review_rate'])} |",
        f"| Auto-ticketed incidents | {safety['auto_ticket_count']} |",
        f"| **Incorrect auto-ticket rate** (wrong, but not sent for review) | **{pct(safety['incorrect_auto_ticket_rate'])}** ({safety['incorrect_auto_ticket_count']} incidents) |",
        f"| Unknown-incident rejection rate | {pct(safety['unknown_rejection_rate'])} |",
        "",
        "## Latency (webhook POST -> response, includes full agent-service triage)",
        "",
        "| Percentile | Latency |",
        "|---|---|",
        f"| p50 | {secs(lat['p50'])} |",
        f"| p95 | {secs(lat['p95'])} |",
        f"| mean | {secs(lat['mean'])} |",
        f"| max | {secs(lat['max'])} |",
        "",
    ]

    if cost:
        lines += [
            "## Cost (from Prometheus `openai_tokens_total` deltas; pricing is a snapshot, not billing-accurate)",
            "",
            "| Metric | Value |",
            "|---|---|",
            f"| Avg tokens / incident | {cost['avg_tokens_per_incident']:.0f} |" if cost['avg_tokens_per_incident'] is not None else "| Avg tokens / incident | n/a |",
            f"| Avg cost / incident | ${cost['avg_cost_usd_per_incident']:.5f} |" if cost['avg_cost_usd_per_incident'] is not None else "| Avg cost / incident | n/a |",
            f"| Total tokens (chat prompt / chat completion / embedding) | {cost['chat_prompt_tokens']:.0f} / {cost['chat_completion_tokens']:.0f} / {cost['embedding_prompt_tokens']:.0f} |",
            "",
        ]

    if report["bucket_breakdown"]:
        lines += ["## Breakdown by bucket", "", "| Bucket | n | Exact match | Human-review rate |", "|---|---|---|---|"]
        for bucket, stats in report["bucket_breakdown"].items():
            lines.append(f"| {bucket} | {stats['n']} | {pct(stats['exact_match'])} | {pct(stats['human_review_rate'])} |")
        lines.append("")

    lines += ["## Decision breakdown", "", "| Outcome | Count |", "|---|---|"]
    for outcome, count in sorted(report["decision_breakdown"].items()):
        lines.append(f"| {outcome} | {count} |")

    lines += [
        "",
        "## Per-incident detail",
        "",
        "| # | Bucket | Expected team/category/severity | Predicted team/category/severity | Confidence | Decision | Correct | Latency |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for row in report["rows"]:
        exp = row["expected"]
        pred = row["predicted"]
        exp_str = f"{exp.get('team') or '—'} / {exp.get('category') or '—'} / {exp.get('severity') or '—'}"
        if pred:
            pred_str = f"{pred['team']} / {pred['category']} / {pred['severity']}"
            conf_str = f"{pred['confidence']:.2f}" if pred["confidence"] is not None else "n/a"
            decision_str = pred["decision"] or "n/a"
        else:
            pred_str, conf_str, decision_str = "(failed)", "n/a", "n/a"
        correct_str = "n/a" if is_unknown_row(row) else ("yes" if row["correct"]["all"] else "no")
        lines.append(
            f"| {row['id']} | {row.get('bucket', 'controlled')} | {exp_str} | {pred_str} | {conf_str} | "
            f"{decision_str} | {correct_str} | {row['latency_seconds']:.2f}s |"
        )

    path.write_text("\n".join(lines) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--incidents", required=True)
    parser.add_argument("--backend-url", default="http://localhost:8080")
    parser.add_argument("--prometheus-url", default="http://localhost:9090")
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--title", default="Triagent Eval Report")
    parser.add_argument("--timeout", type=float, default=60.0, help="per-request timeout in seconds")
    parser.add_argument("--no-cost", action="store_true", help="skip Prometheus cost/token collection")
    args = parser.parse_args()

    incidents = load_incidents(Path(args.incidents))
    print(f"Loaded {len(incidents)} incidents from {args.incidents}")

    usage_before = None if args.no_cost else collect_openai_usage(args.prometheus_url)

    rows = []
    for i, incident in enumerate(incidents, start=1):
        label = incident.get("id", incident.get("index"))
        print(f"[{i}/{len(incidents)}] posting {label} ({incident['expected'].get('category', '?')}) ...")
        row = run_one(args.backend_url, incident, args.timeout)
        status = "OK" if row["correct"]["all"] else ("FAILED" if row["predicted"] is None else "MISMATCH")
        print(f"  -> {status} in {row['latency_seconds']:.2f}s")
        rows.append(row)

    usage = None
    if usage_before is not None:
        print("Waiting for Prometheus to scrape final metrics...")
        time.sleep(8)
        usage_after = collect_openai_usage(args.prometheus_url)
        usage = usage_delta(usage_before, usage_after)

    report = build_report(rows, usage, len(incidents))

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / "eval_report.json"
    md_path = out_dir / "eval_report.md"

    json_path.write_text(json.dumps(report, indent=2))
    write_markdown(report, md_path, args.title)

    print()
    print(f"Wrote {json_path}")
    print(f"Wrote {md_path}")
    print()
    print(f"Exact-match accuracy: {report['accuracy']['exact_match']}")
    print(f"Incorrect auto-ticket rate: {report['safety']['incorrect_auto_ticket_rate']}")
    print(f"p95 latency: {report['latency_seconds']['p95']}")


if __name__ == "__main__":
    main()
