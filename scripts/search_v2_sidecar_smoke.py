#!/usr/bin/env python3
"""Repeatable Search V2 sidecar smoke and candidate-pipeline audit.

This script calls the running Java backend. It does not change production ranking
and does not add query-specific behavior; expected labels are used only for audit
reporting.
"""

from __future__ import annotations

import csv
import json
import sqlite3
import statistics
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
BACKEND = "http://127.0.0.1:8081"
OUT_DIR = ROOT / "outputs"
DOCS_DIR = ROOT / "docs"
GOLD_PATH = ROOT / "backend-java" / "src" / "main" / "resources" / "search_v2" / "gold_queries.json"
SIDECAR_DB = ROOT / "data" / "search_v2_sidecar" / "search_v2_sidecar.sqlite"
DIMENSION_SELECTABLE_SOURCES = {"eurostat", "ecb2", "bis", "imf", "oecd4", "data360", "worldbank"}


GEO_TESTS = [
    ("roa bank", None),
    ("roa bank Cesko", "CZ"),
    ("ROA bank v eurozone", "U2"),
    ("ROA bank Rakousko", "AT"),
    ("ROA bank Polsko", "PL"),
]

SOURCE_TESTS = [
    ("ROA bank pouze ECB", "ecb2"),
    ("ROA bank pouze ARAD", "arad"),
    ("inflace Spanelsko pouze Eurostat", "eurostat"),
    ("HDP Polska pouze IMF", "imf"),
    ("EUR/USD pouze ECB", "ecb2"),
]

SMOKE_QUERIES = [
    "inflace CR",
    "jadrova inflace Cesko",
    "realne mzdy CR",
    "mzdy v Cesku",
    "cisty urokovy vynos bank",
    "zisk bank v Cesku",
    "nove hypoteky Cesko",
    "sazby CNB",
    "sazby CNB a hypoteky",
    "prumyslova vyroba Nemecko",
    "vyroba automobilu Polsko",
    "ceny nemovitosti Slovensko",
    "cena zlata",
    "cena ropy Brent",
    "akcie CEZ",
    "vynos desetileteho ceskeho dluhopisu",
    "EUR/USD ECB",
    "CZK/EUR",
    "HDP Polska",
    "HDP Polska pouze IMF",
]


def post_json(path: str, payload: dict[str, Any], timeout: int = 90) -> tuple[dict[str, Any], int]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        BACKEND + path,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            elapsed_ms = int((time.perf_counter() - start) * 1000)
            return json.loads(response.read().decode("utf-8")), elapsed_ms
    except urllib.error.HTTPError as exc:
        elapsed_ms = int((time.perf_counter() - start) * 1000)
        try:
            detail = json.loads(exc.read().decode("utf-8"))
        except Exception:
            detail = {"error": str(exc)}
        return {"ok": False, "status": "http_error", "detail": detail}, elapsed_ms
    except Exception as exc:
        elapsed_ms = int((time.perf_counter() - start) * 1000)
        return {"ok": False, "status": "request_error", "error": str(exc)}, elapsed_ms


def run_search(query: str, *, mode: str = "metadata_only", no_cache: bool = True, limit: int = 10) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "q": query,
        "query": query,
        "use_ai": False,
        "debug": True,
        "include_retrieval_diagnostics": True,
        "limit": limit,
    }
    if no_cache:
        payload["no_cache"] = True
    if mode == "metadata_only":
        payload["eval_mode"] = "metadata_only"
    else:
        payload["preview_mode"] = mode
        payload["preview_top_n"] = 5
    data, elapsed_ms = post_json("/api/catalog/search-v2", payload)
    data["_client_latency_ms"] = elapsed_ms
    data["_query"] = query
    data["_mode"] = mode
    return data


def run_deep_search_probe() -> dict[str, Any]:
    data, elapsed_ms = post_json(
        "/api/catalog/deep-search",
        {
            "q": "roa bank",
            "query": "roa bank",
            "sources": ["ecb2"],
            "use_ai": False,
            "eval_mode": "metadata_only",
            "limit": 5,
            "no_cache": True,
        },
    )
    return {
        "client_latency_ms": elapsed_ms,
        "search_engine": data.get("search_engine"),
        "catalog_index_mode": data.get("catalog_index_mode"),
        "semantic_retrieval_enabled": data.get("semantic_retrieval_enabled"),
        "fallback_to_legacy": data.get("fallback_to_legacy"),
        "result_count": len(data.get("results") or []),
    }


def load_gold() -> dict[str, dict[str, Any]]:
    items = json.loads(GOLD_PATH.read_text(encoding="utf-8"))
    return {str(item.get("query", "")).strip().lower(): item for item in items}


def norm(value: Any) -> str:
    return str(value or "").strip().lower()


def geo_matches_result(row: dict[str, Any], expected_geo: str | None) -> bool:
    if expected_geo is None:
        return True
    geo = norm(row.get("geo"))
    if geo == norm(expected_geo):
        return True
    source = norm(row.get("source") or row.get("source_type"))
    return not geo and source in DIMENSION_SELECTABLE_SOURCES


def result_rows(data: dict[str, Any], query: str, suite: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for idx, row in enumerate((data.get("results") or [])[:10], start=1):
        raw = row.get("raw") if isinstance(row.get("raw"), dict) else {}
        concepts = row.get("concepts") if isinstance(row.get("concepts"), list) else []
        rows.append(
            {
                "suite": suite,
                "query": query,
                "mode": data.get("_mode", ""),
                "rank": row.get("rank", idx),
                "series_id": row.get("series_id") or row.get("set_id") or "",
                "title": row.get("title") or row.get("name") or "",
                "source": row.get("source") or row.get("source_type") or "",
                "geo": row.get("geo") or "",
                "frequency": row.get("frequency") or "",
                "unit": row.get("unit") or "",
                "primary_concept": raw.get("primary_concept") or (concepts[0] if concepts else ""),
                "relevance_score": row.get("relevance_score", ""),
                "role": row.get("role") or row.get("result_role") or "",
                "why_selected": row.get("why_selected") or "",
                "preview_status": row.get("preview_status") or row.get("status") or "",
            }
        )
    return rows


def expected_ok(data: dict[str, Any], gold: dict[str, Any] | None) -> tuple[bool | None, str]:
    results = data.get("results") or []
    if not gold or not results:
        return (None if results else False), "no_gold_or_empty" if not gold else "empty"
    return candidate_matches_gold(results[0], gold)


def candidate_matches_gold(candidate: dict[str, Any], gold: dict[str, Any] | None) -> tuple[bool | None, str]:
    if not gold:
        return None, "no_gold"
    source = norm(candidate.get("source") or candidate.get("source_type"))
    title = norm((candidate.get("title") or "") + " " + (candidate.get("description") or ""))
    raw = candidate.get("raw") if isinstance(candidate.get("raw"), dict) else {}
    haystack = " ".join(
        [
            source,
            norm(candidate.get("geo")),
            title,
            norm(raw.get("primary_concept")),
            norm(candidate.get("primary_concept")),
            " ".join(norm(x) for x in (candidate.get("concepts") or [])),
            norm(candidate.get("series_id")),
        ]
    )
    acceptable_sources = {norm(x) for x in gold.get("acceptable_sources", []) if norm(x)}
    if acceptable_sources and source not in acceptable_sources:
        return False, "wrong_source"
    forbidden = [norm(x) for x in gold.get("forbidden_concept_families", []) if norm(x)]
    if any(term and term in haystack for term in forbidden):
        return False, "forbidden_concept"
    expected = [norm(x) for x in gold.get("expected_concepts", []) if norm(x)]
    if expected and not any(term in haystack for term in expected):
        return False, "concept_miss"
    return True, "ok"


def pool_has_gold_candidate(data: dict[str, Any], gold: dict[str, Any] | None) -> bool | None:
    if not gold:
        return None
    diagnostics = data.get("retrieval_diagnostics") if isinstance(data.get("retrieval_diagnostics"), dict) else {}
    pool = diagnostics.get("merged_top_200") or []
    return any(candidate_matches_gold(candidate, gold)[0] is True for candidate in pool if isinstance(candidate, dict))


def sidecar_roa_geo_coverage() -> list[dict[str, Any]]:
    """Audit-only probe: is the ECB ROA series present in the sidecar for requested geos?"""
    geos = ["AT", "U2", "PL", "CZ"]
    if not SIDECAR_DB.exists():
        return [{"geo": geo, "present": False, "count": 0, "sample": [], "note": "sidecar_db_missing"} for geo in geos]
    rows: list[dict[str, Any]] = []
    with sqlite3.connect(SIDECAR_DB) as conn:
        for geo in geos:
            pattern = f"CBD2/A.{geo}.%I2004%"
            found = conn.execute(
                """
                SELECT series_id, json_extract(doc_json, '$.canonical_title_cs')
                FROM sidecar_doc
                WHERE source = 'ecb2' AND series_id LIKE ?
                ORDER BY series_id
                LIMIT 8
                """,
                (pattern,),
            ).fetchall()
            rows.append(
                {
                    "geo": geo,
                    "present": bool(found),
                    "count": len(found),
                    "sample": [{"series_id": sid, "title": title} for sid, title in found],
                }
            )
    return rows


def percentile(values: list[int], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round((len(ordered) - 1) * pct)))
    return float(ordered[idx])


def latency_summary(items: list[dict[str, Any]]) -> dict[str, float]:
    values = [int(item.get("_client_latency_ms") or 0) for item in items if item.get("_client_latency_ms") is not None]
    if not values:
        return {"min": 0, "median": 0, "p95": 0, "max": 0}
    return {
        "min": float(min(values)),
        "median": float(statistics.median(values)),
        "p95": percentile(values, 0.95),
        "max": float(max(values)),
    }


def timing_summary(items: list[dict[str, Any]], key: str) -> dict[str, float]:
    values: list[int] = []
    for item in items:
        timings = item.get("timings") if isinstance(item.get("timings"), dict) else {}
        if key in timings:
            try:
                values.append(int(timings[key] or 0))
            except Exception:
                pass
    if not values:
        return {"min": 0, "median": 0, "p95": 0, "max": 0}
    return {
        "min": float(min(values)),
        "median": float(statistics.median(values)),
        "p95": percentile(values, 0.95),
        "max": float(max(values)),
    }


def source_candidate_counts(data: dict[str, Any]) -> list[dict[str, Any]]:
    diagnostics = data.get("retrieval_diagnostics") if isinstance(data.get("retrieval_diagnostics"), dict) else {}
    stats = diagnostics.get("query_stats") if isinstance(diagnostics.get("query_stats"), list) else []
    buckets: dict[str, dict[str, Any]] = {}
    for stat in stats:
        if not isinstance(stat, dict):
            continue
        source = str(stat.get("source") or "")
        if not source:
            continue
        bucket = buckets.setdefault(source, {"source": source, "queries": 0, "retrieved": 0, "ok_queries": 0, "timeouts": 0})
        bucket["queries"] += 1
        bucket["retrieved"] += int(stat.get("count") or 0)
        if stat.get("ok") is True:
            bucket["ok_queries"] += 1
        if stat.get("error"):
            bucket["timeouts"] += 1
    return sorted(buckets.values(), key=lambda item: (-int(item["retrieved"]), str(item["source"])))


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    fields = list(rows[0].keys())
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    OUT_DIR.mkdir(exist_ok=True)
    DOCS_DIR.mkdir(exist_ok=True)
    gold = load_gold()

    active = run_search("roa bank", mode="metadata_only", no_cache=True, limit=10)
    deep_probe = run_deep_search_probe()

    geo_results = []
    for query, expected_geo in GEO_TESTS:
        data = run_search(query, mode="metadata_only", no_cache=True, limit=10)
        results = data.get("results") or []
        primary = [r for r in results if (r.get("role") or "primary") == "primary"]
        checked = primary or results[:1]
        primary_geo_ok = all(geo_matches_result(r, expected_geo) for r in checked)
        answer_available = bool(checked)
        ok = primary_geo_ok and (True if expected_geo is None else answer_available)
        counts = data.get("candidate_counts") or {}
        failure_bucket = ""
        if expected_geo and not answer_available:
            failure_bucket = (
                "candidate_pool_survived_but_metadata_validation_rejected"
                if int(counts.get("after_hard_constraints") or 0) > 0
                else "missing_after_hard_geo_constraint"
            )
        elif expected_geo and not primary_geo_ok:
            failure_bucket = "geo_hard_constraint_violation"
        geo_results.append(
            {
                "query": query,
                "expected_geo": expected_geo or "",
                "ok": ok,
                "primary_geo_ok": primary_geo_ok,
                "answer_available": answer_available,
                "failure_bucket": failure_bucket,
                "top_geo": (results[0].get("geo") if results else ""),
                "top_source": (results[0].get("source") if results else ""),
                "top_title": (results[0].get("title") if results else ""),
                "candidate_counts": counts,
                "latency_ms": data.get("_client_latency_ms"),
                "response": data,
            }
        )

    source_results = []
    for query, expected_source in SOURCE_TESTS:
        data = run_search(query, mode="metadata_only", no_cache=True, limit=10)
        results = data.get("results") or []
        offending = [r for r in results if norm(r.get("source")) != norm(expected_source)]
        source_results.append(
            {
                "query": query,
                "expected_source": expected_source,
                "ok": not offending,
                "result_count": len(results),
                "top_source": (results[0].get("source") if results else ""),
                "top_title": (results[0].get("title") if results else ""),
                "candidate_counts": data.get("candidate_counts") or {},
                "latency_ms": data.get("_client_latency_ms"),
                "response": data,
            }
        )

    metadata_runs = []
    warm_runs = []
    preview_runs = []
    top_rows = []
    wrong_top1 = []
    empty = []
    correct_series_not_in_candidate_pool = []
    correct_series_reranked_low = []
    for query in SMOKE_QUERIES:
        cold = run_search(query, mode="metadata_only", no_cache=True, limit=10)
        warm = run_search(query, mode="metadata_only", no_cache=False, limit=10)
        preview = run_search(query, mode="top_preview", no_cache=True, limit=10)
        metadata_runs.append(cold)
        warm_runs.append(warm)
        preview_runs.append(preview)
        top_rows.extend(result_rows(cold, query, "smoke_metadata"))
        ok, reason = expected_ok(cold, gold.get(query.lower()))
        pool_match = pool_has_gold_candidate(cold, gold.get(query.lower()))
        if not (cold.get("results") or []):
            empty.append({"query": query, "reason": cold.get("status", "empty")})
        elif ok is False:
            pool_record = {
                "query": query,
                "reason": reason,
                "top_source": cold["results"][0].get("source"),
                "top_title": cold["results"][0].get("title"),
                "top_geo": cold["results"][0].get("geo"),
            }
            wrong_top1.append(
                pool_record
            )
            if pool_match is False:
                correct_series_not_in_candidate_pool.append(pool_record)
            elif pool_match is True:
                correct_series_reranked_low.append(pool_record)

    candidate_funnel = active.get("candidate_counts") or {}
    active_diagnostics = active.get("retrieval_diagnostics") if isinstance(active.get("retrieval_diagnostics"), dict) else {}
    duplicate_count = max(0, int(candidate_funnel.get("retrieved_raw") or 0) - int(candidate_funnel.get("deduplicated_unique") or 0))
    summary = {
        "active_configuration": {
            "search_engine": active.get("search_engine"),
            "catalog_index_mode": active.get("catalog_index_mode"),
            "semantic_retrieval_enabled": active.get("semantic_retrieval_enabled"),
            "fallback_to_legacy": active.get("fallback_to_legacy"),
            "candidate_limits": active.get("candidate_limits"),
            "deep_search_probe": deep_probe,
        },
        "candidate_funnel_roa_bank": candidate_funnel,
        "candidate_pipeline_details_roa_bank": {
            "fts_query_variant_count": len(active_diagnostics.get("queries") or []),
            "fts_queries": active_diagnostics.get("queries") or [],
            "retrieved_duplicates": duplicate_count,
            "source_candidate_counts": source_candidate_counts(active),
        },
        "geo_tests": [{k: v for k, v in item.items() if k != "response"} for item in geo_results],
        "geo_hard_constraint_ok": all(item["primary_geo_ok"] for item in geo_results),
        "geo_answer_available_ok": all(item["answer_available"] for item in geo_results if item["expected_geo"]),
        "sidecar_coverage_findings": {
            "ecb_roa_geo_coverage": sidecar_roa_geo_coverage(),
        },
        "source_tests": [{k: v for k, v in item.items() if k != "response"} for item in source_results],
        "latency": {
            "metadata_cold": latency_summary(metadata_runs),
            "metadata_warm": latency_summary(warm_runs),
            "top_preview": latency_summary(preview_runs),
        },
        "phase_latency": {
            "metadata_cold": {
                "planner_ms": timing_summary(metadata_runs, "planner_ms"),
                "fts_ms": timing_summary(metadata_runs, "fts_ms"),
                "retrieval_cache_wrapper_ms": timing_summary(metadata_runs, "retrieval_cache_wrapper_ms"),
                "reranker_ms": timing_summary(metadata_runs, "reranker_ms"),
                "preview_verification_ms": timing_summary(metadata_runs, "preview_verification_ms"),
            },
            "top_preview": {
                "planner_ms": timing_summary(preview_runs, "planner_ms"),
                "fts_ms": timing_summary(preview_runs, "fts_ms"),
                "retrieval_cache_wrapper_ms": timing_summary(preview_runs, "retrieval_cache_wrapper_ms"),
                "reranker_ms": timing_summary(preview_runs, "reranker_ms"),
                "preview_verification_ms": timing_summary(preview_runs, "preview_verification_ms"),
            },
        },
        "preview_calls": {
            "metadata_total": sum(int((r.get("candidate_counts") or {}).get("sent_to_preview") or 0) for r in metadata_runs),
            "top_preview_total": sum(int((r.get("candidate_counts") or {}).get("sent_to_preview") or 0) for r in preview_runs),
            "top_preview_per_request": [
                {
                    "query": r.get("_query"),
                    "sent_to_preview": (r.get("candidate_counts") or {}).get("sent_to_preview", 0),
                    "unique_preview_requests": (r.get("candidate_counts") or {}).get("unique_preview_requests", 0),
                }
                for r in preview_runs
            ],
        },
        "wrong_top1": wrong_top1,
        "empty_results": empty,
        "correct_series_not_in_candidate_pool": correct_series_not_in_candidate_pool,
        "correct_series_reranked_low": correct_series_reranked_low,
        "smoke_top10_rows": top_rows,
    }

    (OUT_DIR / "search_v2_sidecar_smoke_results.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    write_csv(OUT_DIR / "search_v2_sidecar_smoke_results.csv", top_rows)

    candidate_md = [
        "# Search V2 Candidate Pipeline Audit",
        "",
        "## Active Configuration",
        "",
        f"- search_engine: `{summary['active_configuration']['search_engine']}`",
        f"- catalog_index_mode: `{summary['active_configuration']['catalog_index_mode']}`",
        f"- semantic_retrieval_enabled: `{summary['active_configuration']['semantic_retrieval_enabled']}`",
        f"- fallback_to_legacy: `{summary['active_configuration']['fallback_to_legacy']}`",
        f"- deep_search_probe: `{summary['active_configuration']['deep_search_probe']}`",
        "",
        "## ROA Bank Funnel",
        "",
    ]
    for key, value in candidate_funnel.items():
        candidate_md.append(f"- {key}: `{value}`")
    details = summary["candidate_pipeline_details_roa_bank"]
    candidate_md.extend([
        "",
        "## FTS Query Variants And Source Counts",
        "",
        f"- fts_query_variant_count: `{details['fts_query_variant_count']}`",
        f"- fts_queries: `{details['fts_queries']}`",
        f"- retrieved_duplicates: `{details['retrieved_duplicates']}`",
        "",
        "| Source | Queries | Retrieved | OK queries | Timeouts/errors |",
        "|---|---:|---:|---:|---:|",
    ])
    for item in details["source_candidate_counts"]:
        candidate_md.append(
            f"| {item['source']} | {item['queries']} | {item['retrieved']} | {item['ok_queries']} | {item['timeouts']} |"
        )
    candidate_md.extend(
        [
        "",
        "## Sidecar Coverage Findings",
        "",
    ])
    for item in summary["sidecar_coverage_findings"]["ecb_roa_geo_coverage"]:
        sample = ", ".join(row["series_id"] for row in item["sample"][:3])
        candidate_md.append(f"- ECB ROA geo `{item['geo']}`: present=`{item['present']}`, count=`{item['count']}`, sample=`{sample}`")
    candidate_md.extend([
        "",
        "## Preview Calls",
        "",
            f"- metadata_total: `{summary['preview_calls']['metadata_total']}`",
            f"- top_preview_total: `{summary['preview_calls']['top_preview_total']}`",
            "",
        ]
    )
    (DOCS_DIR / "search_v2_candidate_pipeline_audit.md").write_text("\n".join(candidate_md), encoding="utf-8")

    smoke_md = [
        "# Search V2 Sidecar Smoke Report",
        "",
        "## Latency",
        "",
        f"- metadata cold: `{summary['latency']['metadata_cold']}`",
        f"- metadata warm: `{summary['latency']['metadata_warm']}`",
        f"- top preview: `{summary['latency']['top_preview']}`",
        "",
        "## Phase Latency",
        "",
        f"- metadata cold: `{summary['phase_latency']['metadata_cold']}`",
        f"- top preview: `{summary['phase_latency']['top_preview']}`",
        "",
        "## Geo Tests",
        "",
        "| Query | Expected | OK | Top geo | Top source | Top title |",
        "|---|---|---:|---|---|---|",
    ]
    for item in summary["geo_tests"]:
        smoke_md.append(
            f"| {item['query']} | {item['expected_geo']} | {item['ok']} | {item['top_geo']} | {item['top_source']} | {str(item['top_title']).replace('|', '/')} |"
        )
    smoke_md.extend(["", f"- geo_hard_constraint_ok: `{summary['geo_hard_constraint_ok']}`"])
    smoke_md.extend(["", f"- geo_answer_available_ok: `{summary['geo_answer_available_ok']}`"])
    failed_geo = [item for item in summary["geo_tests"] if item.get("failure_bucket")]
    if failed_geo:
        smoke_md.extend(["", "### Geo Failure Buckets", ""])
        for item in failed_geo:
            smoke_md.append(f"- `{item['query']}`: {item['failure_bucket']} counts={item['candidate_counts']}")
    smoke_md.extend(["", "## Source Tests", "", "| Query | Expected | OK | Count | Top source | Top title |", "|---|---|---:|---:|---|---|"])
    for item in summary["source_tests"]:
        smoke_md.append(
            f"| {item['query']} | {item['expected_source']} | {item['ok']} | {item['result_count']} | {item['top_source']} | {str(item['top_title']).replace('|', '/')} |"
        )
    smoke_md.extend(["", "## Wrong Top 1 Candidates", ""])
    if wrong_top1:
        for item in wrong_top1:
            smoke_md.append(f"- `{item['query']}`: {item['reason']} -> {item['top_source']} / {item['top_title']}")
    else:
        smoke_md.append("- None by provisional gold metadata checks.")
    smoke_md.extend(["", "## Empty Results", ""])
    if empty:
        for item in empty:
            smoke_md.append(f"- `{item['query']}`: {item['reason']}")
    else:
        smoke_md.append("- None.")
    smoke_md.extend(["", "## Candidate Pool Diagnostics", ""])
    if correct_series_not_in_candidate_pool:
        smoke_md.append("### Correct candidate not found in merged pool")
        for item in correct_series_not_in_candidate_pool:
            smoke_md.append(f"- `{item['query']}`: {item['reason']} -> {item['top_source']} / {item['top_title']}")
    else:
        smoke_md.append("- No provisional gold miss was proven absent from the merged top-200 pool.")
    if correct_series_reranked_low:
        smoke_md.extend(["", "### Candidate in pool but ranked below top 1"])
        for item in correct_series_reranked_low:
            smoke_md.append(f"- `{item['query']}`: {item['reason']} -> {item['top_source']} / {item['top_title']}")
    smoke_md.extend(["", "## Smoke Top 10", ""])
    current_query = None
    for row in top_rows:
        if row["query"] != current_query:
            current_query = row["query"]
            smoke_md.extend(["", f"### {current_query}", "", "| Rank | Source | Geo | Title | Series | Role | Preview |", "|---:|---|---|---|---|---|---|"])
        smoke_md.append(
            "| {rank} | {source} | {geo} | {title} | {series_id} | {role} | {preview_status} |".format(
                rank=row["rank"],
                source=str(row["source"]).replace("|", "/"),
                geo=str(row["geo"]).replace("|", "/"),
                title=str(row["title"]).replace("|", "/"),
                series_id=str(row["series_id"]).replace("|", "/"),
                role=str(row["role"]).replace("|", "/"),
                preview_status=str(row["preview_status"]).replace("|", "/"),
            )
        )
    (DOCS_DIR / "search_v2_sidecar_smoke_report.md").write_text("\n".join(smoke_md), encoding="utf-8")

    print(json.dumps({
        "active_configuration": summary["active_configuration"],
        "candidate_funnel_roa_bank": candidate_funnel,
        "geo_ok": all(item["ok"] for item in summary["geo_tests"]),
        "geo_hard_constraint_ok": summary["geo_hard_constraint_ok"],
        "geo_answer_available_ok": summary["geo_answer_available_ok"],
        "source_ok": all(item["ok"] for item in summary["source_tests"]),
        "latency": summary["latency"],
        "wrong_top1_count": len(wrong_top1),
        "empty_count": len(empty),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
