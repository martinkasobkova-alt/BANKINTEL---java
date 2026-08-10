#!/usr/bin/env python3
"""Audit Search V2 relevance labels separately from production ranking.

This script deliberately does not modify retrieval, ranking, taxonomy, or sidecar data. It only
classifies the existing eval labels and, when the backend is available, runs the frozen baseline
to report metrics by judgment type.
"""

from __future__ import annotations

import json
import statistics
import time
import urllib.error
import urllib.request
from collections import defaultdict
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
BACKEND = "http://127.0.0.1:8081"
GOLD_PATH = ROOT / "backend-java" / "src" / "main" / "resources" / "search_v2" / "gold_queries.json"
OUT_PATH = ROOT / "outputs" / "search_v2_human_vs_provisional_metrics.json"
DOC_PATH = ROOT / "docs" / "search_v2_relevance_judgment_audit.md"


def post_json(path: str, payload: dict[str, Any], timeout: int = 240) -> tuple[dict[str, Any], int]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        BACKEND + path,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            elapsed_ms = int((time.perf_counter() - started) * 1000)
            return json.loads(response.read().decode("utf-8")), elapsed_ms
    except urllib.error.HTTPError as exc:
        elapsed_ms = int((time.perf_counter() - started) * 1000)
        return {"ok": False, "status": "http_error", "error": exc.read().decode("utf-8", errors="ignore")}, elapsed_ms
    except Exception as exc:
        elapsed_ms = int((time.perf_counter() - started) * 1000)
        return {"ok": False, "status": "request_error", "error": str(exc)}, elapsed_ms


def norm(value: Any) -> str:
    return " ".join(str(value or "").lower().replace("_", " ").split())


def classify(item: dict[str, Any]) -> str:
    if item.get("judgment_type"):
        return str(item["judgment_type"])
    if item.get("judged_by") == "human":
        return "human_judged"
    if item.get("relevant_series_ids") or item.get("gold_series"):
        return "explicit_gold_series"
    if item.get("expected_clarification") is True or item.get("clarification_required") is True:
        return "rule_based"
    if item.get("required_source") or item.get("forbidden_sources"):
        return "rule_based"
    if item.get("relevant_concept_families") or item.get("expected_concepts"):
        return "heuristic"
    return "provisional"


def judgment_record(item: dict[str, Any]) -> dict[str, Any]:
    relevant = list(dict.fromkeys((item.get("relevant_series_ids") or []) + (item.get("gold_series") or [])))
    graded = {series_id: 3 for series_id in relevant}
    return {
        "query_id": item.get("id"),
        "query": item.get("query"),
        "judgment_type": classify(item),
        "judged_by": "search_v2_gold_queries.json + codex_label_audit_2026_07_12",
        "relevant_series_ids": relevant,
        "graded_judgments": graded,
        "label_limitations": limitation(item, relevant),
    }


def limitation(item: dict[str, Any], relevant: list[str]) -> str:
    if relevant:
        return "Exact series IDs are available; this is the strongest non-human label class."
    if item.get("required_source") or item.get("forbidden_sources"):
        return "Rule-based source/constraint label, not human semantic judgment."
    if item.get("relevant_concept_families") or item.get("expected_concepts"):
        return "Heuristic concept-family label; useful for regression, not a human gold judgment."
    return "Provisional label only."


def run_eval(query_count: int) -> dict[str, Any]:
    payload = {
        "max": query_count,
        "skip_v1": True,
        "include_llm_variants": False,
        "use_ai": False,
        "mode": "metadata_only",
        "diagnose_retrieval": False,
        "write_artifacts": False,
    }
    data, elapsed_ms = post_json("/api/catalog/search-v2/evaluate", payload)
    data["_client_latency_ms"] = elapsed_ms
    return data


def p1_from_mrr(metrics: dict[str, Any]) -> float:
    try:
        return 1.0 if float(metrics.get("mrr") or 0.0) == 1.0 else 0.0
    except Exception:
        return 0.0


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    if not rows:
        return {
            "query_count": 0,
            "precision_at_1": None,
            "precision_at_5": None,
            "mrr": None,
            "empty_result_rate": None,
            "median_latency_ms": None,
        }
    return {
        "query_count": len(rows),
        "precision_at_1": round(sum(row["precision_at_1"] for row in rows) / len(rows), 4),
        "precision_at_5": round(sum(row["precision_at_5"] for row in rows) / len(rows), 4),
        "mrr": round(sum(row["mrr"] for row in rows) / len(rows), 4),
        "empty_result_rate": round(sum(1 for row in rows if row["empty_result"]) / len(rows), 4),
        "median_latency_ms": statistics.median([row["latency_ms"] for row in rows]),
    }


def metrics_by_type(judgments: list[dict[str, Any]], eval_report: dict[str, Any]) -> dict[str, Any]:
    rows = eval_report.get("rows") if isinstance(eval_report.get("rows"), list) else []
    by_id = {str(row.get("id")): row for row in rows if isinstance(row, dict)}
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    per_query: list[dict[str, Any]] = []
    for judgment in judgments:
        row = by_id.get(str(judgment["query_id"]), {})
        v2 = row.get("v2") if isinstance(row.get("v2"), dict) else {}
        item = {
            "query_id": judgment["query_id"],
            "query": judgment["query"],
            "judgment_type": judgment["judgment_type"],
            "precision_at_1": p1_from_mrr(v2),
            "precision_at_5": float(v2.get("precision_at_5") or 0.0),
            "mrr": float(v2.get("mrr") or 0.0),
            "empty_result": bool(v2.get("empty_result")),
            "latency_ms": int(v2.get("latency_ms") or 0),
            "top_source": v2.get("top_source"),
            "top_series": v2.get("top_series"),
            "top_title": v2.get("top_title"),
        }
        grouped[judgment["judgment_type"]].append(item)
        per_query.append(item)
    strong_types = {"human_judged", "explicit_gold_series"}
    strong = [row for row in per_query if row["judgment_type"] in strong_types]
    provisional = [row for row in per_query if row["judgment_type"] not in strong_types]
    return {
        "strong_human_or_explicit_gold": summarize(strong),
        "provisional_or_rule_based": summarize(provisional),
        "by_judgment_type": {key: summarize(value) for key, value in sorted(grouped.items())},
        "per_query_metrics": per_query,
    }


def markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Search V2 Relevance Judgment Audit",
        "",
        "This audit separates label provenance from Search V2 runtime metrics. The current eval set contains no verified `human_judged` labels; exact-series labels and heuristic labels are reported separately.",
        "",
        "## Judgment Counts",
        "",
        "| Judgment type | Count |",
        "|---|---:|",
    ]
    for key, value in sorted(report["judgment_counts"].items()):
        lines.append(f"| `{key}` | {value} |")
    lines.extend([
        "",
        "## Metrics By Label Class",
        "",
        "| Group | Queries | P@1 | P@5 | MRR | Empty rate | Median ms |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ])
    metrics = report["metrics"]
    for key in ["strong_human_or_explicit_gold", "provisional_or_rule_based"]:
        row = metrics[key]
        lines.append(
            f"| `{key}` | {row['query_count']} | {row['precision_at_1']} | {row['precision_at_5']} | "
            f"{row['mrr']} | {row['empty_result_rate']} | {row['median_latency_ms']} |"
        )
    lines.extend([
        "",
        "## Metrics By Judgment Type",
        "",
        "| Judgment type | Queries | P@1 | P@5 | MRR | Empty rate | Median ms |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ])
    for key, row in sorted(metrics["by_judgment_type"].items()):
        lines.append(
            f"| `{key}` | {row['query_count']} | {row['precision_at_1']} | {row['precision_at_5']} | "
            f"{row['mrr']} | {row['empty_result_rate']} | {row['median_latency_ms']} |"
        )
    lines.extend([
        "",
        "## Label Method",
        "",
        "- `explicit_gold_series`: a concrete relevant series ID is listed in `gold_queries.json`.",
        "- `rule_based`: source, no-result, clarification, or other explicit constraint label.",
        "- `heuristic`: concept-family/keyword expectation, useful for regression but not human gold.",
        "- `provisional`: weak or exploratory label.",
        "",
        "Automatic and heuristic labels must not be presented as human gold metrics.",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    ROOT.joinpath("outputs").mkdir(exist_ok=True)
    ROOT.joinpath("docs").mkdir(exist_ok=True)
    gold = json.loads(GOLD_PATH.read_text(encoding="utf-8"))
    judgments = [judgment_record(item) for item in gold]
    counts: dict[str, int] = defaultdict(int)
    for item in judgments:
        counts[item["judgment_type"]] += 1
    eval_report = run_eval(len(gold))
    if eval_report.get("status") in {"http_error", "request_error"}:
        existing = ROOT / "outputs" / "search_eval_v1_vs_v2.json"
        if existing.is_file():
            eval_report = json.loads(existing.read_text(encoding="utf-8"))
            eval_report["_source"] = str(existing)
        else:
            eval_report["_error"] = "Backend eval unavailable and no previous report found."
    report = {
        "generated_at": "2026-07-12",
        "baseline_config": {
            "SEARCH_ENGINE_VERSION": "v2",
            "SEARCH_CATALOG_INDEX": "sidecar",
            "SEARCH_SEMANTIC_RETRIEVAL_ENABLED": "false",
        },
        "query_count": len(gold),
        "judgment_counts": dict(sorted(counts.items())),
        "judgments": judgments,
        "metrics": metrics_by_type(judgments, eval_report),
        "eval_report_status": {
            "source": eval_report.get("_source", "live_backend"),
            "status": eval_report.get("status", eval_report.get("search_engine")),
            "latency_ms": eval_report.get("_client_latency_ms", eval_report.get("latency_ms")),
        },
    }
    OUT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    DOC_PATH.write_text(markdown(report), encoding="utf-8")
    print(json.dumps(report["metrics"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
