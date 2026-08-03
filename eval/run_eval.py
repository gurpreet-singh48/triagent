#!/usr/bin/env python3
"""Post each synthetic incident to the running stack, score predicted vs
expected labels, and write eval/report/eval_report.{json,md} — the file
that backs the resume's accuracy/p95 numbers.

Usage:
    python run_eval.py --incidents incidents.jsonl --backend-url http://localhost:8080
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


def run_one(backend_url: str, incident: dict, timeout: float) -> dict:
    expected = incident["expected"]
    start = time.perf_counter()
    try:
        response = requests.post(
            f"{backend_url}/api/webhooks/pagerduty", json=incident["webhook_payload"], timeout=timeout
        )
        response.raise_for_status()
        webhook_result = response.json()
    except requests.RequestException as exc:
        return {
            "index": incident["index"],
            "dedup_key": incident["dedup_key"],
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
                "index": incident["index"],
                "dedup_key": incident["dedup_key"],
                "expected": expected,
                "webhook_status": webhook_result.get("status"),
                "error": f"failed to fetch ticket {ticket_id}: {exc}",
                "latency_seconds": latency_seconds,
                "predicted": None,
                "correct": {"team": False, "category": False, "severity": False, "all": False},
            }

    correct = {
        "team": bool(predicted and predicted["team"] == expected["team"]),
        "category": bool(predicted and predicted["category"] == expected["category"]),
        "severity": bool(predicted and predicted["severity"] == expected["severity"]),
    }
    correct["all"] = correct["team"] and correct["category"] and correct["severity"]

    return {
        "index": incident["index"],
        "dedup_key": incident["dedup_key"],
        "expected": expected,
        "webhook_status": webhook_result.get("status"),
        "ticket_id": ticket_id,
        "latency_seconds": latency_seconds,
        "predicted": predicted,
        "correct": correct,
    }


def build_report(rows: list[dict]) -> dict:
    total = len(rows)
    scored = [r for r in rows if r["predicted"] is not None]
    n_scored = len(scored)

    def accuracy(field: str) -> float | None:
        return (sum(1 for r in scored if r["correct"][field]) / n_scored) if n_scored else None

    latencies = [r["latency_seconds"] for r in rows]
    decision_breakdown = Counter(
        (r["predicted"]["decision"] if r["predicted"] else None) or r["webhook_status"] or "unknown"
        for r in rows
    )

    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "total_incidents": total,
        "scored_incidents": n_scored,
        "unscored_incidents": total - n_scored,
        "accuracy": {
            "team": accuracy("team"),
            "category": accuracy("category"),
            "severity": accuracy("severity"),
            "exact_match": accuracy("all"),
        },
        "latency_seconds": {
            "p50": percentile(latencies, 50),
            "p95": percentile(latencies, 95),
            "mean": sum(latencies) / len(latencies) if latencies else None,
            "max": max(latencies) if latencies else None,
        },
        "decision_breakdown": dict(decision_breakdown),
        "rows": rows,
    }


def write_markdown(report: dict, path: Path) -> None:
    acc = report["accuracy"]
    lat = report["latency_seconds"]

    def pct(x: float | None) -> str:
        return f"{x * 100:.1f}%" if x is not None else "n/a"

    def secs(x: float | None) -> str:
        return f"{x:.2f}s" if x is not None else "n/a"

    lines = [
        "# Triagent Eval Report",
        "",
        f"Generated: {report['generated_at']}",
        "",
        f"- Total incidents: {report['total_incidents']}",
        f"- Scored (ticket created): {report['scored_incidents']}",
        f"- Unscored (webhook/agent failure): {report['unscored_incidents']}",
        "",
        "## Accuracy",
        "",
        "| Field | Accuracy |",
        "|---|---|",
        f"| Team | {pct(acc['team'])} |",
        f"| Category | {pct(acc['category'])} |",
        f"| Severity | {pct(acc['severity'])} |",
        f"| **Exact match (all three)** | **{pct(acc['exact_match'])}** |",
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
        "## Decision breakdown",
        "",
        "| Outcome | Count |",
        "|---|---|",
    ]
    for outcome, count in sorted(report["decision_breakdown"].items()):
        lines.append(f"| {outcome} | {count} |")

    lines += [
        "",
        "## Per-incident detail",
        "",
        "| # | Expected team/category/severity | Predicted team/category/severity | Confidence | Correct | Latency |",
        "|---|---|---|---|---|---|",
    ]
    for row in report["rows"]:
        exp = row["expected"]
        pred = row["predicted"]
        exp_str = f"{exp['team']} / {exp['category']} / {exp['severity']}"
        if pred:
            pred_str = f"{pred['team']} / {pred['category']} / {pred['severity']}"
            conf_str = f"{pred['confidence']:.2f}" if pred["confidence"] is not None else "n/a"
        else:
            pred_str = "(failed)"
            conf_str = "n/a"
        correct_str = "yes" if row["correct"]["all"] else "no"
        lines.append(
            f"| {row['index']} | {exp_str} | {pred_str} | {conf_str} | {correct_str} | {row['latency_seconds']:.2f}s |"
        )

    path.write_text("\n".join(lines) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--incidents", default=str(REPO_ROOT / "eval" / "incidents.jsonl"))
    parser.add_argument("--backend-url", default="http://localhost:8080")
    parser.add_argument("--out-dir", default=str(REPO_ROOT / "eval" / "report"))
    parser.add_argument("--timeout", type=float, default=60.0, help="per-request timeout in seconds")
    args = parser.parse_args()

    incidents = load_incidents(Path(args.incidents))
    print(f"Loaded {len(incidents)} incidents from {args.incidents}")

    rows = []
    for i, incident in enumerate(incidents, start=1):
        print(f"[{i}/{len(incidents)}] posting incident {incident['index']} ({incident['expected']['category']}) ...")
        row = run_one(args.backend_url, incident, args.timeout)
        status = "OK" if row["correct"]["all"] else ("FAILED" if row["predicted"] is None else "MISMATCH")
        print(f"  -> {status} in {row['latency_seconds']:.2f}s")
        rows.append(row)

    report = build_report(rows)

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / "eval_report.json"
    md_path = out_dir / "eval_report.md"

    json_path.write_text(json.dumps(report, indent=2))
    write_markdown(report, md_path)

    print()
    print(f"Wrote {json_path}")
    print(f"Wrote {md_path}")
    print()
    print(f"Exact-match accuracy: {report['accuracy']['exact_match']}")
    print(f"p95 latency: {report['latency_seconds']['p95']}")


if __name__ == "__main__":
    main()
