#!/usr/bin/env python3
"""Exact-entity regression evaluation for Search V2.

The script calls the running Java backend. It is an evaluation artifact only: it does not tune
retrieval weights, sidecar data or production ranking.
"""

from __future__ import annotations

import csv
import json
import statistics
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
BACKEND = "http://127.0.0.1:8081"
DATASET_PATH = ROOT / "evaluation" / "search_v2_exact_entity_queries.json"
OUT_JSON = ROOT / "outputs" / "search_v2_exact_entity_eval.json"
OUT_CSV = ROOT / "outputs" / "search_v2_exact_entity_eval.csv"
DOC_PATH = ROOT / "docs" / "search_v2_exact_entity_evaluation.md"

DIMENSION_SELECTABLE_SOURCES = {"eurostat", "ecb2", "bis", "imf", "oecd4", "data360", "worldbank"}


def post_json(path: str, payload: dict[str, Any], timeout: int = 180) -> tuple[dict[str, Any], int]:
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
    text = str(value or "").lower()
    for char in "|/_-.(),:;[]{}":
        text = text.replace(char, " ")
    text = text.replace("&", " and ")
    return " ".join(text.split())


def compact(value: Any) -> str:
    return norm(value).replace(" ", "")


def run_search(query: str, *, no_cache: bool, limit: int = 50) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "query": query,
        "q": query,
        "use_ai": True,
        "debug": True,
        "include_retrieval_diagnostics": True,
        "eval_mode": "metadata_only",
        "limit": limit,
    }
    if no_cache:
        payload["no_cache"] = True
    data, elapsed = post_json("/api/catalog/search-v2", payload)
    data["_client_latency_ms"] = elapsed
    return data


def row_haystack(row: dict[str, Any]) -> str:
    raw = row.get("raw") if isinstance(row.get("raw"), dict) else {}
    parts = [
        row.get("source"),
        row.get("series_id"),
        row.get("set_id"),
        row.get("title"),
        row.get("name"),
        row.get("description"),
        row.get("geo"),
        row.get("frequency"),
        row.get("unit"),
        raw.get("canonical_title_en"),
        raw.get("canonical_title_cs"),
        raw.get("original_title"),
        raw.get("primary_concept"),
        raw.get("catalog_family"),
        raw.get("measure_type"),
        raw.get("instrument"),
        raw.get("aliases_en"),
        raw.get("aliases_cs"),
        raw.get("abbreviations"),
        " ".join(str(x) for x in row.get("concepts") or []),
        " ".join(str(x) for x in row.get("tags") or []),
    ]
    joined = " ".join(json.dumps(p, ensure_ascii=False) if isinstance(p, (list, dict)) else str(p or "") for p in parts)
    return norm(joined)


def row_relevant(row: dict[str, Any], item: dict[str, Any]) -> bool:
    if not row:
        return False
    source = norm(row.get("source") or row.get("source_type"))
    acceptable_sources = {norm(x) for x in item.get("acceptable_sources") or [] if norm(x)}
    if acceptable_sources and source not in acceptable_sources:
        return False
    series_id = str(row.get("series_id") or row.get("set_id") or "").strip().lower()
    relevant_ids = {str(x).strip().lower() for x in item.get("relevant_series_ids") or [] if str(x).strip()}
    if relevant_ids and series_id in relevant_ids:
        return True
    haystack = row_haystack(row)
    expected_terms = [norm(x) for x in item.get("expected_terms") or [] if norm(x)]
    return any(term and (term in haystack or compact(term) in compact(haystack)) for term in expected_terms)


def hit_rank(rows: list[dict[str, Any]], item: dict[str, Any]) -> int | None:
    for idx, row in enumerate(rows, start=1):
        if row_relevant(row, item):
            return idx
    return None


def pool_hit(rows: list[dict[str, Any]], item: dict[str, Any], k: int) -> bool:
    return any(row_relevant(row, item) for row in rows[:k])


def geo_ok(row: dict[str, Any], item: dict[str, Any]) -> bool | None:
    expected = {str(x).strip().upper() for x in item.get("expected_geo") or [] if str(x).strip()}
    if not expected:
        return None
    raw = row.get("raw") if isinstance(row.get("raw"), dict) else {}
    geo = str(row.get("geo") or raw.get("geo") or "").strip().upper()
    if geo:
        return geo in expected
    return norm(row.get("source")) in DIMENSION_SELECTABLE_SOURCES


def source_ok(row: dict[str, Any], item: dict[str, Any]) -> bool:
    acceptable = {norm(x) for x in item.get("acceptable_sources") or [] if norm(x)}
    if not acceptable:
        return True
    return norm(row.get("source") or row.get("source_type")) in acceptable


def entity_resolution_ok(plan: dict[str, Any], item: dict[str, Any]) -> bool:
    resolution = plan.get("entity_resolution") if isinstance(plan.get("entity_resolution"), dict) else {}
    if resolution.get("resolution_type") not in {"exact_entity", "probable_entity"}:
        return False
    return (
        norm(resolution.get("entity_type")) == norm(item.get("entity_type"))
        and norm(resolution.get("canonical_name")) == norm(item.get("canonical_entity"))
    )


def source_routing_ok(plan: dict[str, Any], item: dict[str, Any]) -> bool:
    routing = plan.get("source_routing") if isinstance(plan.get("source_routing"), dict) else {}
    preferred = {norm(x) for x in routing.get("preferred_sources") or [] if norm(x)}
    acceptable = {norm(x) for x in item.get("acceptable_sources") or [] if norm(x)}
    return not acceptable or bool(preferred & acceptable)


def sibling_contaminated(rows: list[dict[str, Any]], item: dict[str, Any]) -> bool:
    expected = [norm(x) for x in item.get("expected_terms") or [] if norm(x)]
    siblings = [norm(x) for x in item.get("sibling_terms") or [] if norm(x)]
    if not siblings:
        return False
    for row in rows[:3]:
        if row_relevant(row, item):
            continue
        haystack = row_haystack(row)
        if any(term and (term in haystack or compact(term) in compact(haystack)) for term in siblings):
            if not any(term and (term in haystack or compact(term) in compact(haystack)) for term in expected):
                return True
    return False


def retrieval_pool(data: dict[str, Any]) -> list[dict[str, Any]]:
    diagnostics = data.get("retrieval_diagnostics") if isinstance(data.get("retrieval_diagnostics"), dict) else {}
    for key in ("merged_top_200", "final_rank_input", "fts_candidates", "candidates"):
        rows = diagnostics.get(key)
        if isinstance(rows, list):
            return [x for x in rows if isinstance(x, dict)]
    return []


def top_summary(row: dict[str, Any]) -> dict[str, Any]:
    if not row:
        return {"source": "", "series_id": "", "title": "", "geo": ""}
    return {
        "source": row.get("source") or row.get("source_type") or "",
        "series_id": row.get("series_id") or row.get("set_id") or "",
        "title": row.get("title") or row.get("name") or "",
        "geo": row.get("geo") or "",
    }


def evaluate_one(item: dict[str, Any]) -> dict[str, Any]:
    cold = run_search(item["query"], no_cache=True)
    run_search(item["query"], no_cache=False)
    warm = run_search(item["query"], no_cache=False)
    results = [x for x in cold.get("results") or [] if isinstance(x, dict)]
    pool = retrieval_pool(cold)
    rank = hit_rank(results, item)
    top = results[0] if results else {}
    plan = cold.get("query_plan") if isinstance(cold.get("query_plan"), dict) else {}
    p1 = 1.0 if rank == 1 else 0.0
    p5 = 1.0 if rank is not None and rank <= 5 else 0.0
    mrr = 0.0 if rank is None else 1.0 / rank
    geo_result = geo_ok(top, item)
    return {
        "query_id": item["query_id"],
        "query": item["query"],
        "expected_entity_type": item.get("entity_type"),
        "expected_canonical_entity": item.get("canonical_entity"),
        "hit_rank": rank,
        "precision_at_1": p1,
        "precision_at_5": p5,
        "mrr": mrr,
        "candidate_recall_at_20": 1.0 if pool_hit(pool, item, 20) else 0.0,
        "candidate_recall_at_50": 1.0 if pool_hit(pool, item, 50) else 0.0,
        "empty_result": not results,
        "source_constraint_ok": source_ok(top, item) if results else False,
        "geo_constraint_applies": geo_result is not None,
        "geo_constraint_ok": geo_result,
        "exact_entity_resolution_ok": entity_resolution_ok(plan, item),
        "source_routing_ok": source_routing_ok(plan, item),
        "sibling_entity_contamination_top3": sibling_contaminated(results, item),
        "cold_latency_ms": int(cold.get("_client_latency_ms") or 0),
        "warm_latency_ms": int(warm.get("_client_latency_ms") or 0),
        "catalog_index_mode": cold.get("catalog_index_mode"),
        "semantic_retrieval_enabled": cold.get("semantic_retrieval_enabled"),
        "fallback_to_legacy": cold.get("fallback_to_legacy"),
        "planner_status": plan.get("planner_status"),
        "resolution_type": (plan.get("entity_resolution") or {}).get("resolution_type") if isinstance(plan.get("entity_resolution"), dict) else "",
        "resolved_entity_type": (plan.get("entity_resolution") or {}).get("entity_type") if isinstance(plan.get("entity_resolution"), dict) else "",
        "resolved_canonical_entity": (plan.get("entity_resolution") or {}).get("canonical_name") if isinstance(plan.get("entity_resolution"), dict) else "",
        "preferred_sources": (plan.get("source_routing") or {}).get("preferred_sources") if isinstance(plan.get("source_routing"), dict) else [],
        "broad_expansion_used": cold.get("broad_expansion_used"),
        "exact_retrieval_succeeded": cold.get("exact_retrieval_succeeded"),
        **{f"top_{k}": v for k, v in top_summary(top).items()},
    }


def percentile(values: list[int], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round((len(ordered) - 1) * pct)))
    return float(ordered[idx])


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    n = max(1, len(rows))
    geo_rows = [r for r in rows if r["geo_constraint_applies"]]
    warm = [int(r["warm_latency_ms"] or 0) for r in rows]
    return {
        "query_count": len(rows),
        "precision_at_1": round(sum(r["precision_at_1"] for r in rows) / n, 4),
        "precision_at_5": round(sum(r["precision_at_5"] for r in rows) / n, 4),
        "mrr": round(sum(r["mrr"] for r in rows) / n, 4),
        "candidate_recall_at_20": round(sum(r["candidate_recall_at_20"] for r in rows) / n, 4),
        "candidate_recall_at_50": round(sum(r["candidate_recall_at_50"] for r in rows) / n, 4),
        "empty_result_rate": round(sum(1 for r in rows if r["empty_result"]) / n, 4),
        "source_constraint_accuracy": round(sum(1 for r in rows if r["source_constraint_ok"]) / n, 4),
        "geo_constraint_accuracy": None if not geo_rows else round(sum(1 for r in geo_rows if r["geo_constraint_ok"]) / len(geo_rows), 4),
        "exact_entity_resolution_accuracy": round(sum(1 for r in rows if r["exact_entity_resolution_ok"]) / n, 4),
        "source_routing_accuracy": round(sum(1 for r in rows if r["source_routing_ok"]) / n, 4),
        "sibling_entity_contamination_rate_top3": round(sum(1 for r in rows if r["sibling_entity_contamination_top3"]) / n, 4),
        "warm_median_latency_ms": statistics.median(warm) if warm else 0,
        "warm_p95_latency_ms": percentile(warm, 0.95),
    }


def write_csv(rows: list[dict[str, Any]]) -> None:
    OUT_CSV.parent.mkdir(exist_ok=True)
    fields = [
        "query_id",
        "query",
        "expected_entity_type",
        "expected_canonical_entity",
        "hit_rank",
        "precision_at_1",
        "precision_at_5",
        "mrr",
        "candidate_recall_at_20",
        "candidate_recall_at_50",
        "empty_result",
        "source_constraint_ok",
        "geo_constraint_ok",
        "exact_entity_resolution_ok",
        "source_routing_ok",
        "sibling_entity_contamination_top3",
        "cold_latency_ms",
        "warm_latency_ms",
        "planner_status",
        "resolution_type",
        "resolved_entity_type",
        "resolved_canonical_entity",
        "preferred_sources",
        "exact_retrieval_succeeded",
        "broad_expansion_used",
        "top_source",
        "top_series_id",
        "top_title",
        "top_geo",
        "catalog_index_mode",
        "semantic_retrieval_enabled",
        "fallback_to_legacy",
    ]
    with OUT_CSV.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            out = {key: row.get(key) for key in fields}
            out["preferred_sources"] = ",".join(str(x) for x in row.get("preferred_sources") or [])
            writer.writerow(out)


def escape(value: Any) -> str:
    return str(value or "").replace("|", "\\|").replace("\n", " ")


def markdown(report: dict[str, Any]) -> str:
    s = report["summary"]
    lines = [
        "# Search V2 Exact Entity Evaluation",
        "",
        "- Dataset: `evaluation/search_v2_exact_entity_queries.json`",
        "- Baseline: `SEARCH_ENGINE_VERSION=v2`, `SEARCH_CATALOG_INDEX=sidecar`, `SEARCH_SEMANTIC_RETRIEVAL_ENABLED=false`",
        "- Mode: live backend, `use_ai=true`, `eval_mode=metadata_only`; exact entities bypass broad LLM planning when confidence is high.",
        "",
        "## Summary",
        "",
        "| Metric | Value |",
        "|---|---:|",
    ]
    for key in [
        "query_count",
        "precision_at_1",
        "precision_at_5",
        "mrr",
        "candidate_recall_at_20",
        "candidate_recall_at_50",
        "empty_result_rate",
        "source_constraint_accuracy",
        "geo_constraint_accuracy",
        "exact_entity_resolution_accuracy",
        "source_routing_accuracy",
        "sibling_entity_contamination_rate_top3",
        "warm_median_latency_ms",
        "warm_p95_latency_ms",
    ]:
        lines.append(f"| `{key}` | {s.get(key)} |")
    lines.extend([
        "",
        "## Per Query",
        "",
        "| Query | Entity OK | Routing OK | Hit rank | Top source | Top series | Top title | Sibling contamination |",
        "|---|---:|---:|---:|---|---|---|---:|",
    ])
    for row in report["rows"]:
        lines.append(
            f"| {escape(row['query'])} | {row['exact_entity_resolution_ok']} | {row['source_routing_ok']} | "
            f"{row.get('hit_rank') or ''} | {escape(row.get('top_source'))} | {escape(row.get('top_series_id'))} | "
            f"{escape(row.get('top_title'))} | {row['sibling_entity_contamination_top3']} |"
        )
    lines.extend([
        "",
        "## Notes",
        "",
        "- `sibling_entity_contamination_rate_top3` is the share of exact-entity queries where a sibling entity appears in top 3 instead of the requested entity.",
        "- The metric is intentionally conservative: direct matches from accepted sources are counted before sibling checks.",
        "- This report is a regression guard; it must not be used as production ranking logic.",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    dataset = json.loads(DATASET_PATH.read_text(encoding="utf-8"))
    rows = [evaluate_one(item) for item in dataset["queries"]]
    report = {
        "generated_at": "2026-07-12",
        "backend": BACKEND,
        "dataset": {
            "dataset_id": dataset.get("dataset_id"),
            "version": dataset.get("version"),
            "query_count": len(dataset.get("queries") or []),
        },
        "summary": summarize(rows),
        "rows": rows,
    }
    OUT_JSON.parent.mkdir(exist_ok=True)
    DOC_PATH.parent.mkdir(exist_ok=True)
    OUT_JSON.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    write_csv(rows)
    DOC_PATH.write_text(markdown(report), encoding="utf-8")
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
