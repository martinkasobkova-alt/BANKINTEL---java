#!/usr/bin/env python3
"""Search V2 remaining-error audit and before/after regression report.

The expected targets below are audit labels only. Production ranking must stay
query-agnostic and metadata-driven.
"""

from __future__ import annotations

import argparse
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
OUT = ROOT / "outputs"
DOCS = ROOT / "docs"
GOLD_PATH = ROOT / "backend-java" / "src" / "main" / "resources" / "search_v2" / "gold_queries.json"
SIDECAR_DB = ROOT / "data" / "search_v2_sidecar" / "search_v2_sidecar.sqlite"
DIMENSION_SELECTABLE_SOURCES = {"eurostat", "ecb2", "bis", "imf", "oecd4", "data360", "worldbank"}


PROBLEM_TARGETS: list[dict[str, Any]] = [
    {
        "query": "jadrova inflace Cesko",
        "primary_concept": "core_inflation",
        "measure_type": "core_inflation",
        "geo": "CZ",
        "catalog_family": "macro",
        "keywords": ["core inflation", "jadrova inflace", "excluding energy", "excluding food"],
        "semantic_conflict": "core_vs_headline_inflation",
    },
    {
        "query": "realne mzdy CR",
        "primary_concept": "real_wages",
        "measure_type": "real_level",
        "geo": "CZ",
        "catalog_family": "macro",
        "keywords": ["real wages", "realne mzdy", "inflation adjusted wages"],
        "semantic_conflict": "real_vs_nominal",
    },
    {
        "query": "mzdy v Cesku",
        "primary_concept": "average_wages",
        "measure_type": "level",
        "geo": "CZ",
        "catalog_family": "macro",
        "keywords": ["wages", "earnings", "mzdy", "total economy"],
        "semantic_conflict": "total_economy_vs_government_sector",
    },
    {
        "query": "zisk bank v Cesku",
        "primary_concept": "bank_net_profit",
        "measure_type": "net_profit",
        "geo": "CZ",
        "catalog_family": "banking",
        "keywords": ["net profit", "profit of banks", "zisk bank"],
        "semantic_conflict": "net_profit_vs_profitability_ratio",
    },
    {
        "query": "sazby CNB",
        "primary_concept": "central_bank_policy_rate",
        "measure_type": "central_bank_policy_rate",
        "geo": "CZ",
        "catalog_family": "macro",
        "keywords": ["policy rate", "repo rate", "discount rate", "lombard rate", "sazby cnb"],
        "semantic_conflict": "policy_rate_vs_retail_lending_rate",
    },
    {
        "query": "prumyslova vyroba Nemecko",
        "primary_concept": "industrial_production",
        "measure_type": "industrial_production_index",
        "geo": "DE",
        "catalog_family": "sectoral",
        "keywords": ["industrial production", "prumyslova vyroba", "production in industry"],
        "semantic_conflict": "industrial_production_vs_unrelated_series",
    },
    {
        "query": "vyroba automobilu Polsko",
        "primary_concept": "automotive_production",
        "measure_type": "industrial_production_index",
        "geo": "PL",
        "catalog_family": "sectoral",
        "industry_sector": "automotive_manufacturing",
        "accepted_primary_concepts": ["industrial_production"],
        "keywords": ["motor vehicle", "automotive", "automobile", "nace c29", "cars production"],
        "semantic_conflict": "automotive_vs_unrelated_industry",
    },
    {
        "query": "ceny nemovitosti Slovensko",
        "primary_concept": "house_price_index",
        "measure_type": "house_price_index",
        "geo": "SK",
        "catalog_family": "real_estate",
        "keywords": ["house price", "residential property prices", "property price", "ceny nemovitosti"],
        "semantic_conflict": "house_price_vs_housing_quantity",
    },
    {
        "query": "cena zlata",
        "primary_concept": "commodity_spot_price",
        "measure_type": "market_price",
        "catalog_family": "commodities",
        "price_type": "commodity_market_price",
        "keywords": ["gold price", "spot price", "commodity market price"],
        "semantic_conflict": "market_price_vs_reserve_asset",
    },
    {
        "query": "akcie CEZ",
        "primary_concept": "equity_market_price",
        "measure_type": "market_price",
        "geo": "CZ",
        "catalog_family": "markets_equities",
        "instrument": "equity",
        "keywords": ["stock price", "share price", "equity", "CEZ"],
        "semantic_conflict": "equity_market_price_vs_unrelated_series",
        "separate_catalog": "stocks",
        "accepted_sources": ["stocks", "yahoo_finance"],
    },
]


def norm(value: Any) -> str:
    return " ".join(str(value or "").lower().replace("_", " ").split())


def canonical(value: Any) -> str:
    return str(value or "").strip().lower()


def post_json(path: str, payload: dict[str, Any], timeout: int = 90) -> tuple[dict[str, Any], int]:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        BACKEND + path,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            elapsed = int((time.perf_counter() - started) * 1000)
            return json.loads(response.read().decode("utf-8")), elapsed
    except urllib.error.HTTPError as exc:
        elapsed = int((time.perf_counter() - started) * 1000)
        return {"ok": False, "status": "http_error", "error": exc.read().decode("utf-8", errors="ignore")}, elapsed
    except Exception as exc:
        elapsed = int((time.perf_counter() - started) * 1000)
        return {"ok": False, "status": "request_error", "error": str(exc)}, elapsed


def run_search(query: str, *, no_cache: bool, limit: int = 10) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "q": query,
        "query": query,
        "use_ai": False,
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


def candidate_haystack(candidate: dict[str, Any]) -> str:
    raw = candidate.get("raw") if isinstance(candidate.get("raw"), dict) else {}
    parts = [
        candidate.get("title"),
        candidate.get("name"),
        candidate.get("description"),
        candidate.get("series_id"),
        candidate.get("source"),
        candidate.get("geo"),
        raw.get("primary_concept"),
        raw.get("measure_type"),
        raw.get("economic_object"),
        raw.get("instrument"),
        raw.get("industry_sector"),
        raw.get("nominal_real"),
        raw.get("institutional_sector"),
        raw.get("price_type"),
        raw.get("catalog_family"),
        " ".join(str(x) for x in candidate.get("concepts") or []),
        " ".join(str(x) for x in candidate.get("tags") or []),
    ]
    return norm(" ".join(str(p or "") for p in parts))


def metadata_value(candidate: dict[str, Any], key: str) -> str:
    raw = candidate.get("raw") if isinstance(candidate.get("raw"), dict) else {}
    value = raw.get(key)
    if value in (None, ""):
        value = candidate.get(key)
    return canonical(value)


def accepted_values(target: dict[str, Any], key: str) -> set[str]:
    values = {canonical(target.get(key))}
    values.update(canonical(item) for item in target.get(f"accepted_{key}s", []) or [])
    values.discard("")
    return values


def measure_compatible(expected: Any, actual: Any, primary: Any) -> bool:
    exp = canonical(expected)
    act = canonical(actual)
    concept = canonical(primary)
    if not exp:
        return True
    if exp == act:
        return True
    if exp == "house_price_index" and concept == "house_price_index" and act in {"price_index", "index"}:
        return True
    if exp == "industrial_production_index" and concept == "industrial_production" and act in {"production_index", "index"}:
        return True
    return False


def geo_compatible(candidate: dict[str, Any], target: dict[str, Any]) -> bool:
    expected = canonical(target.get("geo")).upper()
    if not expected:
        return True
    raw = candidate.get("raw") if isinstance(candidate.get("raw"), dict) else {}
    geo = str(candidate.get("geo") or raw.get("geo") or "").upper()
    if geo:
        return geo == expected
    source = canonical(candidate.get("source"))
    accepted_sources = {canonical(item) for item in target.get("accepted_sources", []) or []}
    if source in accepted_sources:
        return True
    return source in DIMENSION_SELECTABLE_SOURCES


def structured_target_match(candidate: dict[str, Any], target: dict[str, Any]) -> bool:
    if not geo_compatible(candidate, target):
        return False
    source = canonical(candidate.get("source"))
    accepted_sources = {canonical(item) for item in target.get("accepted_sources", []) or []}
    if accepted_sources and source not in accepted_sources:
        return False

    primary = metadata_value(candidate, "primary_concept")
    measure = metadata_value(candidate, "measure_type")
    primary_ok = not accepted_values(target, "primary_concept") or primary in accepted_values(target, "primary_concept")
    measure_ok = measure_compatible(target.get("measure_type"), measure, primary)

    if target.get("industry_sector") and metadata_value(candidate, "industry_sector") != canonical(target.get("industry_sector")):
        return False
    if target.get("instrument") and metadata_value(candidate, "instrument") != canonical(target.get("instrument")):
        return False
    if target.get("price_type") and metadata_value(candidate, "price_type") != canonical(target.get("price_type")):
        return False

    if primary and measure:
        return primary_ok and measure_ok
    if primary:
        return primary_ok
    return False


def target_match(candidate: dict[str, Any], target: dict[str, Any]) -> bool:
    if not isinstance(candidate, dict):
        return False
    if structured_target_match(candidate, target):
        return True
    if not geo_compatible(candidate, target):
        return False
    source = canonical(candidate.get("source"))
    accepted_sources = {canonical(item) for item in target.get("accepted_sources", []) or []}
    if accepted_sources and source not in accepted_sources:
        return False
    hay = candidate_haystack(candidate)
    keywords = [norm(x) for x in target.get("keywords", [])]
    return bool(keywords and any(k and k in hay for k in keywords))


def rank_in_pool(pool: list[Any], target: dict[str, Any]) -> tuple[int | None, dict[str, Any] | None]:
    for idx, row in enumerate(pool or [], start=1):
        if isinstance(row, dict) and target_match(row, target):
            return idx, row
    return None, None


def classify(target: dict[str, Any], data: dict[str, Any], raw_exists: bool, sidecar_exists: bool) -> str:
    diagnostics = data.get("retrieval_diagnostics") if isinstance(data.get("retrieval_diagnostics"), dict) else {}
    pre_rank, _ = rank_in_pool(diagnostics.get("pre_merge_top_200") or [], target)
    merged_rank, _ = rank_in_pool(diagnostics.get("merged_top_200") or [], target)
    result_rank, _ = rank_in_pool(data.get("results") or [], target)
    top = (data.get("results") or [None])[0]
    top_matches = isinstance(top, dict) and target_match(top, target)
    if target.get("separate_catalog"):
        return "ok" if top_matches else "B"
    if top_matches:
        return "ok"
    if not raw_exists and not sidecar_exists:
        return "A"
    if raw_exists and not sidecar_exists:
        return "D"
    if pre_rank is None and sidecar_exists:
        return "G"
    if merged_rank is None and pre_rank is not None:
        return "I"
    if result_rank is None and merged_rank is not None:
        return "J"
    if not top_matches:
        return "J"
    return "ok"


def scan_catalog_metadata(target: dict[str, Any]) -> tuple[bool, str]:
    if target.get("separate_catalog"):
        return True, f"separate_catalog:{target['separate_catalog']}"
    keywords = [norm(k) for k in target.get("keywords", []) if norm(k)]
    if not keywords:
        return False, ""
    metadata_dir = ROOT / "data" / "catalog_search_metadata"
    if not metadata_dir.exists():
        return False, "metadata_dir_missing"
    for path in metadata_dir.glob("*.jsonl"):
        try:
            with path.open("r", encoding="utf-8") as fh:
                for line in fh:
                    text = norm(line)
                    if any(k in text for k in keywords):
                        return True, path.name
        except Exception:
            continue
    return False, ""


def scan_sidecar(target: dict[str, Any]) -> tuple[bool, str]:
    if target.get("separate_catalog"):
        return False, "separate_catalog_not_sidecar"
    if not SIDECAR_DB.exists():
        return False, "sidecar_db_missing"
    import sqlite3

    try:
        with sqlite3.connect(SIDECAR_DB) as conn:
            primary_values = [target.get("primary_concept"), *(target.get("accepted_primary_concepts") or [])]
            placeholders = ",".join("?" for _ in primary_values if _)
            if placeholders:
                rows = conn.execute(
                    f"""
                    SELECT source, series_id, doc_json
                    FROM sidecar_doc
                    WHERE json_extract(doc_json, '$.primary_concept') IN ({placeholders})
                    LIMIT 500
                    """,
                    [canonical(value) for value in primary_values if value],
                ).fetchall()
            else:
                rows = conn.execute("SELECT source, series_id, doc_json FROM sidecar_doc LIMIT 500").fetchall()
            for source, series_id, doc_json in rows:
                try:
                    doc = json.loads(doc_json)
                except Exception:
                    continue
                candidate = {
                    "source": source,
                    "series_id": series_id,
                    "geo": doc.get("geo", ""),
                    "title": doc.get("canonical_title_cs") or doc.get("canonical_title_en") or doc.get("original_title") or "",
                    "raw": doc,
                }
                if structured_target_match(candidate, target):
                    return True, f"{source}:{series_id}"
    except Exception as exc:
        return False, f"sidecar_scan_error:{exc}"
    return False, ""


def result_summary(data: dict[str, Any]) -> dict[str, Any]:
    results = data.get("results") or []
    top = results[0] if results else {}
    raw = top.get("raw") if isinstance(top.get("raw"), dict) else {}
    counts = data.get("candidate_counts") or {}
    return {
        "top_source": top.get("source", ""),
        "top_series_id": top.get("series_id") or top.get("set_id") or "",
        "top_title": top.get("title") or top.get("name") or "",
        "top_geo": top.get("geo", ""),
        "top_measure_type": raw.get("measure_type", ""),
        "top_primary_concept": raw.get("primary_concept", ""),
        "top_catalog_family": raw.get("catalog_family", ""),
        "candidate_counts": counts,
        "latency_ms": data.get("_client_latency_ms"),
        "semantic_retrieval_enabled": data.get("semantic_retrieval_enabled"),
        "catalog_index_mode": data.get("catalog_index_mode"),
    }


def audit_problem_query(target: dict[str, Any], phase: str) -> dict[str, Any]:
    data = run_search(target["query"], no_cache=True, limit=10)
    diagnostics = data.get("retrieval_diagnostics") if isinstance(data.get("retrieval_diagnostics"), dict) else {}
    pre_rank, pre_candidate = rank_in_pool(diagnostics.get("pre_merge_top_200") or [], target)
    merged_rank, merged_candidate = rank_in_pool(diagnostics.get("merged_top_200") or [], target)
    result_rank, result_candidate = rank_in_pool(data.get("results") or [], target)
    raw_exists, raw_evidence = scan_catalog_metadata(target)
    sidecar_exists, sidecar_evidence = scan_sidecar(target)
    category = classify(target, data, raw_exists, sidecar_exists)
    top = result_summary(data)
    return {
        "phase": phase,
        "query": target["query"],
        "expected_target": {k: target.get(k, "") for k in [
            "primary_concept", "measure_type", "geo", "catalog_family", "industry_sector", "instrument", "price_type"
        ]},
        "correct_series": {
            "exists_in_raw_catalog": raw_exists,
            "raw_catalog_evidence": raw_evidence,
            "exists_in_sidecar": sidecar_exists,
            "sidecar_evidence": sidecar_evidence,
            "series_id": (result_candidate or merged_candidate or pre_candidate or {}).get("series_id", ""),
            "raw_retrieval_rank_top200": pre_rank,
            "merged_pool_rank_top200": merged_rank,
            "rerank_rank_top10": result_rank,
        },
        "failure_category": category,
        "failure_stage": failure_stage(category),
        "semantic_conflict_expected": target.get("semantic_conflict", ""),
        "top": top,
        "source_statuses": data.get("source_statuses", {}),
    }


def failure_stage(category: str) -> str:
    return {
        "A": "catalog_coverage",
        "B": "separate_catalog_routing",
        "C": "raw_catalog_ingest",
        "D": "sidecar_coverage",
        "E": "canonical_metadata_incomplete",
        "F": "canonical_metadata_incorrect",
        "G": "query_expansion_or_fts_recall",
        "H": "sidecar_fts_weighting",
        "I": "candidate_pool_truncation",
        "J": "deterministic_reranking",
        "K": "target_resolution",
        "L": "ambiguous_query",
        "ok": "ok",
    }.get(category, "unknown")


def gold_match(candidate: dict[str, Any], gold: dict[str, Any]) -> bool:
    hay = candidate_haystack(candidate)
    source = norm(candidate.get("source"))
    acceptable = {norm(s) for s in gold.get("acceptable_sources", []) if norm(s)}
    if acceptable and source not in acceptable:
        return False
    for forbidden in gold.get("forbidden_concept_families", []):
        if norm(forbidden) and norm(forbidden) in hay:
            return False
    expected = [norm(x) for x in gold.get("expected_concepts", []) if norm(x)]
    return not expected or any(exp in hay for exp in expected)


def eval_metrics(phase: str) -> dict[str, Any]:
    gold_items = json.loads(GOLD_PATH.read_text(encoding="utf-8"))
    rows = []
    latencies = []
    warm_latencies = []
    recall20 = recall50 = p1 = p5 = reciprocal = empty = 0.0
    source_ok = source_total = geo_ok = geo_total = 0
    for item in gold_items:
        query = item["query"]
        data = run_search(query, no_cache=True, limit=10)
        run_search(query, no_cache=False, limit=10)
        warm = run_search(query, no_cache=False, limit=10)
        results = [r for r in data.get("results") or [] if isinstance(r, dict)]
        diagnostics = data.get("retrieval_diagnostics") if isinstance(data.get("retrieval_diagnostics"), dict) else {}
        merged = [r for r in diagnostics.get("merged_top_200") or [] if isinstance(r, dict)]
        latencies.append(int(data.get("_client_latency_ms") or 0))
        warm_latencies.append(int(warm.get("_client_latency_ms") or 0))
        if not results:
            empty += 1
        hit_rank = next((idx for idx, row in enumerate(results, start=1) if gold_match(row, item)), None)
        pool20 = any(gold_match(row, item) for row in merged[:20])
        pool50 = any(gold_match(row, item) for row in merged[:50])
        recall20 += 1 if pool20 else 0
        recall50 += 1 if pool50 else 0
        p1 += 1 if hit_rank == 1 else 0
        p5 += 1 if hit_rank is not None and hit_rank <= 5 else 0
        reciprocal += 0 if hit_rank is None else 1 / hit_rank
        if item.get("required_source"):
            source_total += 1
            source_ok += 1 if results and norm(results[0].get("source")) == norm(item["required_source"]) else 0
        if item.get("expected_geo"):
            geo_total += 1
            top_geo = str(results[0].get("geo") if results else "").upper()
            geos = {str(g).upper() for g in item.get("expected_geo", [])}
            geo_ok += 1 if not top_geo or top_geo in geos else 0
        rows.append({
            "phase": phase,
            "query": query,
            "hit_rank": hit_rank or "",
            "top_source": results[0].get("source", "") if results else "",
            "top_title": results[0].get("title", "") if results else "",
            "latency_ms": data.get("_client_latency_ms"),
        })
    n = max(1, len(gold_items))
    return {
        "phase": phase,
        "query_count": len(gold_items),
        "candidate_recall_at_20": recall20 / n,
        "candidate_recall_at_50": recall50 / n,
        "precision_at_1": p1 / n,
        "precision_at_5": p5 / n,
        "mrr": reciprocal / n,
        "empty_result_rate": empty / n,
        "source_constraint_accuracy": None if source_total == 0 else source_ok / source_total,
        "geo_constraint_accuracy": None if geo_total == 0 else geo_ok / geo_total,
        "warm_median_latency_ms": statistics.median(warm_latencies) if warm_latencies else 0,
        "warm_p95_latency_ms": percentile(warm_latencies, 0.95),
        "cold_median_latency_ms": statistics.median(latencies) if latencies else 0,
        "rows": rows,
    }


def percentile(values: list[int], pct: float) -> float:
    if not values:
        return 0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round((len(ordered) - 1) * pct)))
    return float(ordered[idx])


def flatten_rows(audit: dict[str, Any]) -> list[dict[str, Any]]:
    rows = []
    for phase, payload in audit.get("phases", {}).items():
        for item in payload.get("problem_queries", []):
            top = item.get("top", {})
            correct = item.get("correct_series", {})
            target = item.get("expected_target", {})
            rows.append({
                "phase": phase,
                "query": item["query"],
                "failure_category": item.get("failure_category"),
                "failure_stage": item.get("failure_stage"),
                "expected_primary_concept": target.get("primary_concept"),
                "expected_measure_type": target.get("measure_type"),
                "expected_geo": target.get("geo"),
                "expected_catalog_family": target.get("catalog_family"),
                "exists_in_raw_catalog": correct.get("exists_in_raw_catalog"),
                "exists_in_sidecar": correct.get("exists_in_sidecar"),
                "raw_retrieval_rank_top200": correct.get("raw_retrieval_rank_top200"),
                "merged_pool_rank_top200": correct.get("merged_pool_rank_top200"),
                "rerank_rank_top10": correct.get("rerank_rank_top10"),
                "top_source": top.get("top_source"),
                "top_series_id": top.get("top_series_id"),
                "top_title": top.get("top_title"),
                "top_geo": top.get("top_geo"),
                "top_primary_concept": top.get("top_primary_concept"),
                "top_measure_type": top.get("top_measure_type"),
                "top_catalog_family": top.get("top_catalog_family"),
                "latency_ms": top.get("latency_ms"),
            })
    return rows


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def write_docs(audit: dict[str, Any]) -> None:
    phases = audit.get("phases", {})
    after = phases.get("after") or phases.get("before") or {}
    before = phases.get("before") or {}
    metrics = after.get("eval_metrics", {})
    problems = after.get("problem_queries", [])
    before_by_query = {row.get("query"): row for row in before.get("problem_queries", [])}
    counts: dict[str, int] = {}
    for row in problems:
        category = row.get("failure_category", "unknown")
        counts[category] = counts.get(category, 0) + 1

    def metric(name: str) -> str:
        value = metrics.get(name)
        return "" if value is None else str(round(value, 4) if isinstance(value, float) else value)

    lines = [
        "# Search V2 Remaining Errors Analysis",
        "",
        "Baseline: `SEARCH_CATALOG_INDEX=sidecar`, `SEARCH_SEMANTIC_RETRIEVAL_ENABLED=false`.",
        "Audit běží pouze nad sidecar + FTS + deterministickým rerankingem; LLM reranking není zapnutý.",
        "",
        "## Candidate Funnel",
        "",
        "`retrieved_raw -> deduplicated_unique -> after_hard_constraints -> after_source_balancing -> after_candidate_limit -> sent_to_deterministic_reranker -> sent_to_preview -> final_results`",
        "",
        "`after_source_balancing` a `after_candidate_limit` mají u současné implementace stejnou hodnotu, protože source balancing a limit 240 jsou aplikované v jednom merge kroku.",
        "",
        "## Metrics",
        "",
        f"- Candidate Recall@20: `{metric('candidate_recall_at_20')}`",
        f"- Candidate Recall@50: `{metric('candidate_recall_at_50')}`",
        f"- Precision@1: `{metric('precision_at_1')}`",
        f"- Precision@5: `{metric('precision_at_5')}`",
        f"- MRR: `{metric('mrr')}`",
        f"- Empty result rate: `{metric('empty_result_rate')}`",
        f"- Source constraint accuracy: `{metric('source_constraint_accuracy')}`",
        f"- Geo constraint accuracy: `{metric('geo_constraint_accuracy')}`",
        f"- Warm median ms: `{metric('warm_median_latency_ms')}`",
        f"- Warm P95 ms: `{metric('warm_p95_latency_ms')}`",
        "",
        "## Current Classification",
        "",
    ]
    for category, count in sorted(counts.items()):
        lines.append(f"- `{category}` / `{failure_stage(category)}`: {count}")

    lines.extend([
        "",
        "## Before / After Top 1",
        "",
        "| Query | Before top 1 | After top 1 | Current category | Evidence |",
        "|---|---|---|---|---|",
    ])
    for row in problems:
        query = row["query"]
        before_top = before_by_query.get(query, {}).get("top", {})
        after_top = row.get("top", {})
        correct = row.get("correct_series", {})
        evidence = (
            f"raw={correct.get('exists_in_raw_catalog')}, "
            f"sidecar={correct.get('exists_in_sidecar')}, "
            f"rank={correct.get('rerank_rank_top10') or ''}"
        )
        lines.append(
            "| {query} | {before} | {after} | {cat} | {evidence} |".format(
                query=query,
                before=f"{before_top.get('top_source', '')}:{before_top.get('top_series_id', '')} {before_top.get('top_title', '')}".replace("|", "/").strip(),
                after=f"{after_top.get('top_source', '')}:{after_top.get('top_series_id', '')} {after_top.get('top_title', '')}".replace("|", "/").strip(),
                cat=row.get("failure_category"),
                evidence=evidence,
            )
        )

    lines.extend([
        "",
        "## Ten Query Detail",
        "",
        "| Query | Category | Raw | Sidecar | Raw rank | Pool rank | Rerank rank | Top metadata |",
        "|---|---|---:|---:|---:|---:|---:|---|",
    ])
    for row in problems:
        correct = row.get("correct_series", {})
        top = row.get("top", {})
        top_meta = " / ".join(str(top.get(key, "")) for key in ["top_primary_concept", "top_measure_type", "top_catalog_family"] if top.get(key))
        lines.append(
            "| {query} | {cat} | {raw} | {sidecar} | {pre} | {pool} | {rr} | {top_meta} |".format(
                query=row["query"],
                cat=row.get("failure_category"),
                raw=correct.get("exists_in_raw_catalog"),
                sidecar=correct.get("exists_in_sidecar"),
                pre=correct.get("raw_retrieval_rank_top200") or "",
                pool=correct.get("merged_pool_rank_top200") or "",
                rr=correct.get("rerank_rank_top10") or "",
                top_meta=top_meta.replace("|", "/"),
            )
        )
    (DOCS / "search_v2_remaining_errors_analysis.md").write_text("\n".join(lines), encoding="utf-8")

    changes = [
        "# Search V2 Sidecar Enrichment Changes",
        "",
        "- Candidate funnel renamed to `retrieved_raw -> deduplicated_unique -> after_hard_constraints -> after_source_balancing -> after_candidate_limit`.",
        "- Geo hard constraints are applied to raw candidates before re-running dedupe/source balancing.",
        "- Blank-geo dimension-selectable sources now include Eurostat, ECB, BIS, IMF, OECD, Data360/World Bank; local ARAD/CSU remain fixed-geo protected.",
        "- Sidecar documents now carry `industry_sector`, `nominal_real`, `dataset_family`, and `catalog_family`.",
        "- ARAD policy-rate titles prefer the official indicator title over contradictory generated labels such as bond-yield labels.",
        "- Taxonomy now contains core inflation, policy rates, industrial production, automotive production, and equity market price concepts.",
        "- Bank-profit query expansion now targets net income / income-statement evidence instead of expanding to ROE.",
        "- Deterministic fallback reranker now penalizes canonical semantic conflicts such as core/headline, real/nominal, policy/lending, market price/reserves, and equity/macro mismatches.",
        "- Search V2 can route stock/equity intents to the separate `stocks` adapter without enabling semantic retrieval.",
    ]
    (DOCS / "search_v2_sidecar_enrichment_changes.md").write_text("\n".join(changes), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", choices=["before", "after"], required=True)
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True)
    DOCS.mkdir(exist_ok=True)

    path = OUT / "search_v2_remaining_errors_before_after.json"
    audit = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"phases": {}}
    problem_rows = [audit_problem_query(target, args.phase) for target in PROBLEM_TARGETS]
    metrics = eval_metrics(args.phase)
    audit["phases"][args.phase] = {
        "problem_queries": problem_rows,
        "eval_metrics": {k: v for k, v in metrics.items() if k != "rows"},
        "eval_rows": metrics["rows"],
        "semantic_retrieval_enabled": any(
            row.get("top", {}).get("semantic_retrieval_enabled") for row in problem_rows
        ),
    }
    path.write_text(json.dumps(audit, ensure_ascii=False, indent=2), encoding="utf-8")
    rows = flatten_rows(audit)
    write_csv(OUT / "search_v2_remaining_errors_before_after.csv", rows)
    write_csv(OUT / "search_v2_remaining_errors.csv", [row for row in rows if row["phase"] == args.phase])
    write_docs(audit)
    print(json.dumps({
        "phase": args.phase,
        "metrics": audit["phases"][args.phase]["eval_metrics"],
        "problem_categories": {
            row["query"]: row["failure_category"] for row in problem_rows
        },
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
