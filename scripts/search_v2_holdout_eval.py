#!/usr/bin/env python3
"""Run the frozen Search V2 holdout dataset against the current sidecar baseline.

This is an evaluation tool only. It must not be used to tune ranking or taxonomy before the first
baseline result is recorded.
"""

from __future__ import annotations

import csv
import hashlib
import json
import statistics
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
BACKEND = "http://127.0.0.1:8081"
DATASET_PATH = ROOT / "evaluation" / "search_v2_holdout_queries.json"
OUT_JSON = ROOT / "outputs" / "search_v2_holdout_results.json"
OUT_CSV = ROOT / "outputs" / "search_v2_holdout_results.csv"
DOC_PATH = ROOT / "docs" / "search_v2_holdout_evaluation.md"

DIMENSION_SELECTABLE_SOURCES = {"eurostat", "ecb2", "bis", "imf", "oecd4", "data360", "worldbank"}
GEO_ALIASES = {
    "CZ": {"CZ", "CZECH", "CZECHIA", "CZECH REPUBLIC", "CESKO", "CESKA REPUBLIKA"},
    "SK": {"SK", "SLOVAKIA", "SLOVENSKO"},
    "PL": {"PL", "POLAND", "POLSKO"},
    "DE": {"DE", "GERMANY", "NEMECKO"},
    "HU": {"HU", "HUNGARY", "MADARSKO"},
    "FR": {"FR", "FRANCE", "FRANCIE"},
    "IT": {"IT", "ITALY", "ITALIE"},
    "ES": {"ES", "SPAIN", "SPANELSKO"},
    "US": {"US", "USA", "UNITED STATES", "AMERICA", "AMERIKA"},
    "EU": {"EU", "EU27", "EU27_2020", "EUROPE", "EVROPA", "EUROPEAN UNION"},
    "U2": {"U2", "EA", "EA20", "EURO AREA", "EUROZONE", "EUROZONA"},
}
GEO_NAME_TO_CODE = {alias: code for code, aliases in GEO_ALIASES.items() for alias in aliases}
STOCK_SUFFIX_GEO = {
    "PR": "CZ",
    "F": "DE",
    "DE": "DE",
    "DU": "DE",
    "MU": "DE",
    "SG": "DE",
    "PA": "FR",
    "AS": "NL",
    "MI": "IT",
    "MC": "ES",
    "L": "GB",
    "SW": "CH",
    "VI": "AT",
    "WA": "PL",
    "TO": "CA",
    "NE": "CA",
    "SA": "BR",
    "BK": "TH",
}
SENSITIVE_QUERIES = [
    "jadrova inflace Cesko",
    "mzdy v Cesku",
    "zisk bank v Cesku",
    "sazby CNB",
    "prumyslova vyroba Nemecko",
    "vyroba automobilu Polsko",
    "ceny nemovitosti Slovensko",
    "cena zlata",
    "akcie CEZ",
]


def post_json(path: str, payload: dict[str, Any], timeout: int = 120) -> tuple[dict[str, Any], int]:
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


def geo_code(value: Any) -> str:
    text = str(value or "").strip().upper().replace("-", "_")
    if not text:
        return ""
    return GEO_NAME_TO_CODE.get(text, text if len(text) in {2, 3} else "")


def expected_geos(item: dict[str, Any]) -> set[str]:
    return {code for code in (geo_code(g) for g in item.get("expected_geo") or []) if code}


def text_has_geo(value: Any, expected: set[str]) -> bool:
    hay = " " + norm(value).replace("_", " ") + " "
    for code in expected:
        for alias in GEO_ALIASES.get(code, {code}):
            needle = " " + norm(alias).replace("_", " ") + " "
            if needle.strip() and needle in hay:
                return True
    return False


def stock_geo(row: dict[str, Any]) -> str:
    if norm(row.get("source") or row.get("source_type")) != "stocks":
        return ""
    for value in (row.get("series_id"), row.get("set_id"), row.get("dataset")):
        token = str(value or "").strip().upper()
        if "." in token:
            suffix = token.rsplit(".", 1)[-1]
            if suffix in STOCK_SUFFIX_GEO:
                return STOCK_SUFFIX_GEO[suffix]
    return ""


def plan_entity_geo(plan: dict[str, Any]) -> str:
    entity = plan.get("entity_resolution") if isinstance(plan.get("entity_resolution"), dict) else {}
    attrs = entity.get("attributes") if isinstance(entity.get("attributes"), dict) else {}
    fixed = geo_code(attrs.get("fixed_geo"))
    if fixed:
        return fixed
    return geo_code(attrs.get("market"))


def row_matches_entity(row: dict[str, Any], plan: dict[str, Any]) -> bool:
    entity = plan.get("entity_resolution") if isinstance(plan.get("entity_resolution"), dict) else {}
    if entity.get("resolution_type") not in {"exact_entity", "probable_entity"}:
        return False
    hay = result_haystack(row)
    series_id = norm(row.get("series_id") or row.get("set_id"))
    for value in [entity.get("canonical_name"), *(entity.get("symbols") or []), *(entity.get("exact_terms") or [])]:
        token = norm(value)
        if token and (token == series_id or token in hay):
            return True
    return False


def canonical_sha256(queries: list[dict[str, Any]]) -> str:
    canonical = json.dumps(queries, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def verify_dataset(dataset: dict[str, Any]) -> None:
    expected = dataset.get("queries_sha256")
    actual = canonical_sha256(dataset.get("queries") or [])
    if expected != actual:
        raise SystemExit(f"Holdout dataset checksum mismatch: expected {expected}, actual {actual}")


def run_search(query: str, *, no_cache: bool, limit: int = 20) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "query": query,
        "q": query,
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


def result_haystack(row: dict[str, Any]) -> str:
    raw = row.get("raw") if isinstance(row.get("raw"), dict) else {}
    parts = [
        row.get("source"),
        row.get("series_id"),
        row.get("title"),
        row.get("description"),
        row.get("geo"),
        row.get("frequency"),
        row.get("unit"),
        raw.get("primary_concept"),
        raw.get("measure_type"),
        raw.get("economic_object"),
        raw.get("institutional_sector"),
        raw.get("scope"),
        raw.get("nominal_real"),
        raw.get("catalog_family"),
        " ".join(str(x) for x in row.get("concepts") or []),
        " ".join(str(x) for x in row.get("tags") or []),
    ]
    return norm(" ".join(str(p or "") for p in parts))


def source_ok(row: dict[str, Any], item: dict[str, Any]) -> bool:
    source = norm(row.get("source") or row.get("source_type"))
    required = norm(item.get("required_source"))
    if required:
        return source == required
    acceptable = {norm(s) for s in item.get("acceptable_sources") or [] if norm(s)}
    return not acceptable or source in acceptable


def geo_ok(row: dict[str, Any], item: dict[str, Any], plan: dict[str, Any] | None = None) -> bool:
    expected = expected_geos(item)
    if not expected:
        return True
    raw = row.get("raw") if isinstance(row.get("raw"), dict) else {}
    geo = geo_code(row.get("geo") or raw.get("geo") or raw.get("country") or raw.get("ref_area"))
    if geo:
        return geo in expected
    suffix_geo = stock_geo(row)
    if suffix_geo:
        return suffix_geo in expected
    if text_has_geo(
        " ".join(str(x or "") for x in [
            row.get("series_id"),
            row.get("set_id"),
            row.get("dataset"),
            row.get("title"),
            row.get("description"),
            raw.get("canonical_title_en"),
            raw.get("canonical_title_cs"),
            raw.get("original_title"),
        ]),
        expected,
    ):
        return True
    if plan and row_matches_entity(row, plan):
        entity_geo = plan_entity_geo(plan)
        if entity_geo and entity_geo in expected:
            return True
    if not plan:
        return norm(row.get("source")) in DIMENSION_SELECTABLE_SOURCES
    plan_geos = {geo_code(g) for g in (plan or {}).get("geographies", []) if geo_code(g)}
    return bool(plan_geos & expected) and norm(row.get("source")) in DIMENSION_SELECTABLE_SOURCES


def row_relevant(row: dict[str, Any], item: dict[str, Any]) -> bool:
    if item.get("expect_no_available_series"):
        return False
    series_id = str(row.get("series_id") or row.get("set_id") or "").strip()
    relevant_ids = {str(s).strip().lower() for s in item.get("relevant_series_ids") or [] if str(s).strip()}
    if relevant_ids and series_id.lower() in relevant_ids:
        return True
    if not source_ok(row, item) or not geo_ok(row, item):
        return False
    hay = result_haystack(row)
    expected = [norm(x) for x in item.get("expected_concepts") or [] if norm(x)]
    return not expected or any(term in hay for term in expected)


def hit_rank(rows: list[dict[str, Any]], item: dict[str, Any]) -> int | None:
    for idx, row in enumerate(rows, start=1):
        if row_relevant(row, item):
            return idx
    return None


def pool_hit(rows: list[dict[str, Any]], item: dict[str, Any], k: int) -> bool:
    return any(row_relevant(row, item) for row in rows[:k])


def top(row_list: list[dict[str, Any]]) -> dict[str, Any]:
    return row_list[0] if row_list else {}


def evaluate_one(item: dict[str, Any]) -> dict[str, Any]:
    cold = run_search(item["query"], no_cache=True, limit=20)
    run_search(item["query"], no_cache=False, limit=20)
    warm = run_search(item["query"], no_cache=False, limit=20)
    results = [r for r in cold.get("results") or [] if isinstance(r, dict)]
    plan = cold.get("query_plan") if isinstance(cold.get("query_plan"), dict) else {}
    diagnostics = cold.get("retrieval_diagnostics") if isinstance(cold.get("retrieval_diagnostics"), dict) else {}
    merged = [r for r in diagnostics.get("merged_top_200") or [] if isinstance(r, dict)]
    rank = hit_rank(results, item)
    top_row = top(results)
    no_available_ok = bool(item.get("expect_no_available_series")) and not results
    if item.get("expect_no_available_series"):
        p1 = p5 = mrr = 1.0 if no_available_ok else 0.0
    else:
        p1 = 1.0 if rank == 1 else 0.0
        p5 = 1.0 if rank is not None and rank <= 5 else 0.0
        mrr = 0.0 if rank is None else 1.0 / rank
    source_constraint_applies = bool(item.get("required_source"))
    geo_constraint_applies = bool(item.get("expected_geo"))
    plan_geos = {geo_code(g) for g in plan.get("geographies", []) if geo_code(g)}
    expected = expected_geos(item)
    explicit_geo_primary_applies = bool(expected and plan_geos)
    top_geo_ok = bool(results and geo_ok(top_row, item, plan))
    any_geo_ok = bool(results and any(geo_ok(row, item, plan) for row in results[:5]))
    geo_answer_available = True if item.get("expect_no_available_series") else rank is not None
    top_source = norm(top_row.get("source") or top_row.get("source_type"))
    dimensioned_geo_applies = bool(explicit_geo_primary_applies and top_source in DIMENSION_SELECTABLE_SOURCES)
    aggregate_geo_applies = bool(expected & {"EU", "U2"})
    return {
        "query_id": item["query_id"],
        "query": item["query"],
        "category": item.get("category"),
        "judgment_type": item.get("judgment_type"),
        "hit_rank": rank,
        "precision_at_1": p1,
        "precision_at_5": p5,
        "mrr": mrr,
        "candidate_recall_at_20": 1.0 if pool_hit(merged, item, 20) else 0.0,
        "candidate_recall_at_50": 1.0 if pool_hit(merged, item, 50) else 0.0,
        "empty_result": not results,
        "source_constraint_applies": source_constraint_applies,
        "source_constraint_ok": None if not source_constraint_applies else bool(results and source_ok(top_row, item)),
        "geo_constraint_applies": geo_constraint_applies,
        "geo_constraint_ok": None if not geo_constraint_applies else top_geo_ok,
        "explicit_geo_primary_applies": explicit_geo_primary_applies,
        "explicit_geo_primary_ok": None
        if not explicit_geo_primary_applies
        else bool(no_available_ok or top_geo_ok),
        "explicit_geo_any_result_ok": None
        if not explicit_geo_primary_applies
        else bool(no_available_ok or any_geo_ok),
        "dimensioned_geo_resolution_applies": dimensioned_geo_applies,
        "dimensioned_geo_resolution_ok": None if not dimensioned_geo_applies else top_geo_ok,
        "aggregate_geo_applies": aggregate_geo_applies,
        "aggregate_geo_ok": None if not aggregate_geo_applies else top_geo_ok,
        "geo_answer_available": geo_answer_available,
        "query_plan_geographies": sorted(plan_geos),
        "entity_geo": plan_entity_geo(plan),
        "cold_latency_ms": int(cold.get("_client_latency_ms") or 0),
        "warm_latency_ms": int(warm.get("_client_latency_ms") or 0),
        "top_source": top_row.get("source"),
        "top_series": top_row.get("series_id"),
        "top_title": top_row.get("title"),
        "top_geo": top_row.get("geo"),
        "top_inferred_geo": stock_geo(top_row) or geo_code(top_row.get("geo")),
        "catalog_index_mode": cold.get("catalog_index_mode"),
        "semantic_retrieval_enabled": cold.get("semantic_retrieval_enabled"),
        "fallback_to_legacy": cold.get("fallback_to_legacy"),
        "candidate_counts": cold.get("candidate_counts"),
    }


def summary(rows: list[dict[str, Any]]) -> dict[str, Any]:
    n = max(1, len(rows))
    constrained_source = [r for r in rows if r["source_constraint_applies"]]
    constrained_geo = [r for r in rows if r["geo_constraint_applies"]]
    explicit_geo = [r for r in rows if r["explicit_geo_primary_applies"]]
    dimensioned_geo = [r for r in rows if r["dimensioned_geo_resolution_applies"]]
    aggregate_geo = [r for r in rows if r["aggregate_geo_applies"]]
    warm = [r["warm_latency_ms"] for r in rows]
    return {
        "query_count": len(rows),
        "precision_at_1": round(sum(r["precision_at_1"] for r in rows) / n, 4),
        "precision_at_5": round(sum(r["precision_at_5"] for r in rows) / n, 4),
        "mrr": round(sum(r["mrr"] for r in rows) / n, 4),
        "candidate_recall_at_20": round(sum(r["candidate_recall_at_20"] for r in rows) / n, 4),
        "candidate_recall_at_50": round(sum(r["candidate_recall_at_50"] for r in rows) / n, 4),
        "empty_result_rate": round(sum(1 for r in rows if r["empty_result"]) / n, 4),
        "source_constraint_accuracy": None
        if not constrained_source
        else round(sum(1 for r in constrained_source if r["source_constraint_ok"]) / len(constrained_source), 4),
        "geo_constraint_accuracy": None
        if not constrained_geo
        else round(sum(1 for r in constrained_geo if r["geo_constraint_ok"]) / len(constrained_geo), 4),
        "explicit_geo_primary_accuracy": None
        if not explicit_geo
        else round(sum(1 for r in explicit_geo if r["explicit_geo_primary_ok"]) / len(explicit_geo), 4),
        "explicit_geo_any_result_accuracy": None
        if not explicit_geo
        else round(sum(1 for r in explicit_geo if r["explicit_geo_any_result_ok"]) / len(explicit_geo), 4),
        "dimensioned_geo_resolution_accuracy": None
        if not dimensioned_geo
        else round(sum(1 for r in dimensioned_geo if r["dimensioned_geo_resolution_ok"]) / len(dimensioned_geo), 4),
        "aggregate_geo_accuracy": None
        if not aggregate_geo
        else round(sum(1 for r in aggregate_geo if r["aggregate_geo_ok"]) / len(aggregate_geo), 4),
        "geo_answer_availability": round(sum(1 for r in rows if r["geo_answer_available"]) / n, 4),
        "warm_median_latency_ms": statistics.median(warm) if warm else 0,
        "warm_p95_latency_ms": percentile(warm, 0.95),
        "cold_median_latency_ms": statistics.median([r["cold_latency_ms"] for r in rows]) if rows else 0,
    }


def percentile(values: list[int], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round((len(ordered) - 1) * pct)))
    return float(ordered[idx])


def sensitive_check() -> list[dict[str, Any]]:
    checks: list[dict[str, Any]] = []
    for query in SENSITIVE_QUERIES:
        data = run_search(query, no_cache=True, limit=10)
        results = [r for r in data.get("results") or [] if isinstance(r, dict)]
        rows = []
        for idx, row in enumerate(results[:5], start=1):
            raw = row.get("raw") if isinstance(row.get("raw"), dict) else {}
            rows.append(
                {
                    "rank": idx,
                    "source": row.get("source"),
                    "series_id": row.get("series_id"),
                    "title": row.get("title"),
                    "geo": row.get("geo"),
                    "frequency": row.get("frequency"),
                    "unit": row.get("unit"),
                    "primary_concept": raw.get("primary_concept"),
                    "measure_type": raw.get("measure_type"),
                    "institutional_sector": raw.get("institutional_sector"),
                    "scope": raw.get("scope"),
                    "nominal_real": raw.get("nominal_real"),
                }
            )
        checks.append(
            {
                "query": query,
                "top5": rows,
                "note": sensitive_note(query, rows),
            }
        )
    return checks


def sensitive_note(query: str, rows: list[dict[str, Any]]) -> str:
    first = rows[0] if rows else {}
    if query == "jadrova inflace Cesko":
        return (
            "Eurostat tipscp10 means 'Core inflation differential vis-a-vis EA' in the Macroeconomic Imbalance "
            "Procedure table. It is related to core inflation but is a differential against the euro area, not a "
            "direct Czech core-inflation level. ECB/OECD core-inflation rows are closer direct primary answers."
        )
    if query == "mzdy v Cesku":
        sector = first.get("institutional_sector") or ""
        scope = first.get("scope") or ""
        measure = first.get("measure_type") or ""
        nominal = first.get("nominal_real") or ""
        return (
            f"Top result metadata: institutional_sector={sector or 'blank'}, scope={scope or 'blank'}, "
            f"measure_type={measure or 'blank'}, nominal_real={nominal or 'blank'}, "
            f"unit={first.get('unit') or 'blank'}, frequency={first.get('frequency') or 'blank'}."
        )
    return ""


def write_csv(rows: list[dict[str, Any]]) -> None:
    OUT_CSV.parent.mkdir(exist_ok=True)
    fields = [
        "query_id",
        "query",
        "category",
        "judgment_type",
        "hit_rank",
        "precision_at_1",
        "precision_at_5",
        "mrr",
        "candidate_recall_at_20",
        "candidate_recall_at_50",
        "empty_result",
        "source_constraint_ok",
        "geo_constraint_ok",
        "explicit_geo_primary_applies",
        "explicit_geo_primary_ok",
        "explicit_geo_any_result_ok",
        "dimensioned_geo_resolution_applies",
        "dimensioned_geo_resolution_ok",
        "aggregate_geo_applies",
        "aggregate_geo_ok",
        "geo_answer_available",
        "query_plan_geographies",
        "entity_geo",
        "cold_latency_ms",
        "warm_latency_ms",
        "top_source",
        "top_series",
        "top_title",
        "top_geo",
        "top_inferred_geo",
        "catalog_index_mode",
        "semantic_retrieval_enabled",
        "fallback_to_legacy",
    ]
    with OUT_CSV.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({key: row.get(key) for key in fields})


def markdown(report: dict[str, Any]) -> str:
    s = report["summary"]
    lines = [
        "# Search V2 Holdout Evaluation",
        "",
        "- Dataset: `evaluation/search_v2_holdout_queries.json`",
        f"- Dataset checksum: `{report['dataset']['queries_sha256']}`",
        "- Baseline: `SEARCH_ENGINE_VERSION=v2`, `SEARCH_CATALOG_INDEX=sidecar`, `SEARCH_SEMANTIC_RETRIEVAL_ENABLED=false`",
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
        "explicit_geo_primary_accuracy",
        "explicit_geo_any_result_accuracy",
        "dimensioned_geo_resolution_accuracy",
        "aggregate_geo_accuracy",
        "geo_answer_availability",
        "warm_median_latency_ms",
        "warm_p95_latency_ms",
    ]:
        lines.append(f"| `{key}` | {s.get(key)} |")
    lines.extend([
        "",
        "## Per Query",
        "",
        "| Query | Hit rank | Top source | Top series | Top title |",
        "|---|---:|---|---|---|",
    ])
    for row in report["rows"]:
        lines.append(
            f"| {escape(row['query'])} | {row.get('hit_rank') or ''} | {escape(row.get('top_source'))} | "
            f"{escape(row.get('top_series'))} | {escape(row.get('top_title'))} |"
        )
    lines.extend([
        "",
        "## Sensitive Query Check",
        "",
    ])
    for check in report["sensitive_checks"]:
        lines.append(f"### {escape(check['query'])}")
        lines.append("")
        lines.append("| Rank | Source | Series | Title | Geo | Concept | Measure | Sector | Scope | Nominal/real |")
        lines.append("|---:|---|---|---|---|---|---|---|---|---|")
        for row in check["top5"]:
            lines.append(
                f"| {row['rank']} | {escape(row.get('source'))} | {escape(row.get('series_id'))} | "
                f"{escape(row.get('title'))} | {escape(row.get('geo'))} | {escape(row.get('primary_concept'))} | "
                f"{escape(row.get('measure_type'))} | {escape(row.get('institutional_sector'))} | "
                f"{escape(row.get('scope'))} | {escape(row.get('nominal_real'))} |"
            )
        if check["note"]:
            lines.append("")
            lines.append(check["note"])
        lines.append("")
    return "\n".join(lines)


def escape(value: Any) -> str:
    return str(value or "").replace("|", "\\|").replace("\n", " ")


def main() -> int:
    dataset = json.loads(DATASET_PATH.read_text(encoding="utf-8"))
    verify_dataset(dataset)
    rows = [evaluate_one(item) for item in dataset["queries"]]
    report = {
        "generated_at": "2026-07-12",
        "dataset": {
            "dataset_id": dataset.get("dataset_id"),
            "version": dataset.get("version"),
            "queries_sha256": dataset.get("queries_sha256"),
            "query_count": len(dataset.get("queries") or []),
        },
        "summary": summary(rows),
        "rows": rows,
        "sensitive_checks": sensitive_check(),
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
