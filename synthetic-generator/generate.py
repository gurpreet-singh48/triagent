#!/usr/bin/env python3
"""Generate synthetic PagerDuty-shaped incidents from the shared templates.

Each template (one per Phase-1 alert doc) carries its own ground-truth
expected_team/category/severity, so eval labels derive directly from the
alert taxonomy rather than hand-labeling. Output is JSONL where each line
keeps the webhook payload actually sent to the LLM separate from the
expected-label fields used for scoring — see PLAN.md.

Usage:
    python generate.py --count 30 --out ../eval/incidents.jsonl
"""
from __future__ import annotations

import argparse
import json
import random
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_TEMPLATES_DIR = Path(__file__).resolve().parent / "templates"


def load_templates(templates_dir: Path) -> list[dict]:
    templates = []
    for path in sorted(templates_dir.glob("*.json")):
        with open(path) as f:
            templates.append(json.load(f))
    return templates


def generate_field_value(field: dict) -> int | float:
    if field["type"] == "int":
        return random.randint(field["min"], field["max"])
    if field["type"] == "float":
        value = random.uniform(field["min"], field["max"])
        return round(value, field.get("decimals", 2))
    raise ValueError(f"unknown field type: {field['type']!r}")


def build_incident(template: dict, index: int) -> dict:
    field_values = {f["name"]: generate_field_value(f) for f in template["fields"]}
    summary = template["summary_template"].format(**field_values)
    instance = random.randint(1, 9)
    dedup_key = f"synthetic-{uuid.uuid4()}"

    webhook_payload = {
        "routing_key": "Rsynthetic",
        "event_action": "trigger",
        "dedup_key": dedup_key,
        "payload": {
            "summary": summary,
            "source": f"{template['source_prefix']}-{instance}",
            "severity": template["expected_severity"],
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "component": template["component"],
            "group": template["group"],
            "class": template["category"],
            "custom_details": field_values,
        },
        "client": "Synthetic Incident Generator",
        "client_url": "http://localhost:5173",
    }

    return {
        "index": index,
        "dedup_key": dedup_key,
        "webhook_payload": webhook_payload,
        "expected": {
            "team": template["expected_team"],
            "category": template["category"],
            "severity": template["expected_severity"],
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=30)
    parser.add_argument("--out", default=str(REPO_ROOT / "eval" / "controlled" / "incidents.jsonl"))
    parser.add_argument("--templates-dir", default=str(DEFAULT_TEMPLATES_DIR))
    parser.add_argument("--seed", type=int, default=None, help="random seed, for reproducible batches")
    args = parser.parse_args()

    if args.seed is not None:
        random.seed(args.seed)

    templates_dir = Path(args.templates_dir)
    templates = load_templates(templates_dir)
    if not templates:
        sys.exit(f"no templates found under {templates_dir}")

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    with open(out_path, "w") as f:
        for i in range(args.count):
            template = random.choice(templates)
            incident = build_incident(template, i)
            f.write(json.dumps(incident) + "\n")

    print(f"Wrote {args.count} synthetic incidents to {out_path}")
    print(f"Templates used: {len(templates)} ({', '.join(t['category'] for t in templates)})")


if __name__ == "__main__":
    main()
