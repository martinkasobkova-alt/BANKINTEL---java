#!/usr/bin/env python3
"""Audit legacy Search V2 FTS inputs and metadata coverage.

The 9GB legacy SQLite index is intentionally inspected lightly for schema/counts.
Field-quality checks are sampled from JSONL/metadata sidecars so the audit can run
on a developer machine without locking the app for minutes.
"""

from __future__ import annotations

import csv
import json
import re
import sqlite3
import time
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
INDEX_DIR = ROOT / "data" / "catalog_search_indexes"
METADATA_DIR = ROOT / "data" / "catalog_search_metadata"
OUTPUT_DIR = ROOT / "outputs"
DOCS_DIR = ROOT / "docs"
FTS_DB = INDEX_DIR / "classic_catalog_search.sqlite"
SAMPLE_LIMIT = 20_000


def main() -> None:
    started = time.time()
    OUTPUT_DIR.mkdir(exist_ok=True)
    DOCS_DIR.mkdir(exist_ok=True)
    schema = sqlite_schema()
    source_counts = sqlite_source_counts()
    stats = []
    for source in sorted(metadata_sources() | index_sources() | set(source_counts)):
        stats.append(source_stats(source, source_counts.get(source)))
    report = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "fts_db": str(FTS_DB),
        "fts_db_exists": FTS_DB.is_file(),
        "fts_db_mb": round(FTS_DB.stat().st_size / 1024 / 1024, 2) if FTS_DB.is_file() else 0,
        "schema": schema,
        "field_weighting_observation": (
            "Legacy raw Search V2 uses bm25(catalog_fts) without per-field weights; "
            "Search V1 applies an extra scoring pipeline after retrieval. "
            "Sidecar FTS uses separate canonical fields and explicit bm25 weights."
        ),
        "sources": stats,
        "duration_seconds": round(time.time() - started, 2),
        "sampling": {
            "jsonl_sample_limit_per_source": SAMPLE_LIMIT,
            "quality_stats_are_sampled": True,
            "sqlite_counts_are_exact_when_available": bool(source_counts),
        },
    }
    (OUTPUT_DIR / "search_v2_fts_index_audit.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    write_csv(stats)
    write_markdown(report)
    print(json.dumps({"ok": True, "sources": len(stats), "duration_seconds": report["duration_seconds"]}, indent=2))


def sqlite_schema() -> dict[str, Any]:
    if not FTS_DB.is_file():
        return {"available": False}
    try:
        con = sqlite3.connect(f"file:{FTS_DB}?mode=ro", uri=True, timeout=10)
        con.execute("PRAGMA query_only=ON")
        rows = con.execute(
            "SELECT name, type, sql FROM sqlite_master WHERE name LIKE '%catalog%' ORDER BY type, name"
        ).fetchall()
        table_info = []
        try:
            table_info = con.execute("PRAGMA table_info(catalog_fts)").fetchall()
        except sqlite3.Error:
            pass
        con.close()
        return {
            "available": True,
            "objects": [{"name": r[0], "type": r[1], "sql": r[2]} for r in rows],
            "catalog_fts_columns": [
                {"cid": r[0], "name": r[1], "type": r[2], "notnull": r[3], "pk": r[5]} for r in table_info
            ],
        }
    except Exception as exc:
        return {"available": False, "error": str(exc)}


def sqlite_source_counts() -> dict[str, int]:
    if not FTS_DB.is_file():
        return {}
    try:
        con = sqlite3.connect(f"file:{FTS_DB}?mode=ro", uri=True, timeout=15)
        con.execute("PRAGMA query_only=ON")
        rows = con.execute("SELECT source, COUNT(*) FROM catalog_fts GROUP BY source").fetchall()
        con.close()
        return {str(source): int(count) for source, count in rows}
    except Exception:
        return {}


def metadata_sources() -> set[str]:
    return {p.stem for p in METADATA_DIR.glob("*.jsonl") if p.is_file()}


def index_sources() -> set[str]:
    return {p.stem for p in INDEX_DIR.glob("*.jsonl") if p.is_file() and not p.name.endswith(".meta.json")}


def source_stats(source: str, sqlite_count: int | None) -> dict[str, Any]:
    metadata_path = METADATA_DIR / f"{source}.jsonl"
    index_path = INDEX_DIR / f"{source}.jsonl"
    path = metadata_path if metadata_path.is_file() else index_path
    counters = Counter()
    duplicates = Counter()
    sample_rows = 0
    if path.is_file():
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if not line.strip():
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    counters["bad_json"] += 1
                    continue
                sample_rows += 1
                title = first(row, "human_label_cs", "human_label_en", "title_original", "title", "name", "dataset_name")
                description = first(row, "description", "description_cs", "description_en", "full_path", "tree_path")
                geo = first(row, "geo", "geo_code", "REF_AREA", "country", "territory", "geo_tags")
                unit = first(row, "unit", "unit_label", "UNIT_MEASURE", "measure")
                freq = first(row, "frequency", "freq", "FREQ", "period")
                series_id = first(row, "series_id", "set_id", "id", "key", "dataset_id")
                if not description:
                    counters["without_description"] += 1
                if not geo:
                    counters["without_geo"] += 1
                if not unit:
                    counters["without_unit"] += 1
                if not freq:
                    counters["without_frequency"] += 1
                if len(title) < 8:
                    counters["short_titles"] += 1
                if looks_code_only(title):
                    counters["code_only_titles"] += 1
                if not first(row, "intent_tags", "concepts", "tags", "primary_concept"):
                    counters["without_canonical_concepts"] += 1
                if not first(row, "institutional_sector"):
                    counters["without_institutional_sector"] += 1
                if not first(row, "measure_type"):
                    counters["without_measure_type"] += 1
                if not first(row, "flow_stock"):
                    counters["without_flow_stock"] += 1
                if not first(row, "price_type"):
                    counters["without_price_type"] += 1
                if not first(row, "seasonal_adjustment", "adjustment", "s_adj", "S_ADJ"):
                    counters["without_adjustment"] += 1
                if not first(row, "search_keywords_cs", "aliases_cs"):
                    counters["without_czech_synonyms"] += 1
                if not first(row, "search_keywords_en", "aliases_en"):
                    counters["without_english_synonyms"] += 1
                duplicates[(series_id.lower(), normalize(title))] += 1
                if sample_rows >= SAMPLE_LIMIT:
                    break
    duplicate_count = sum(v - 1 for v in duplicates.values() if v > 1)
    return {
        "source": source,
        "sqlite_rows": sqlite_count,
        "sample_rows": sample_rows,
        "quality_source": "metadata" if metadata_path.is_file() else "raw_index",
        "without_description": counters["without_description"],
        "without_geo": counters["without_geo"],
        "without_unit": counters["without_unit"],
        "without_frequency": counters["without_frequency"],
        "duplicates_in_sample": duplicate_count,
        "short_titles": counters["short_titles"],
        "code_only_titles": counters["code_only_titles"],
        "without_canonical_concepts": counters["without_canonical_concepts"],
        "without_institutional_sector": counters["without_institutional_sector"],
        "without_measure_type": counters["without_measure_type"],
        "without_flow_stock": counters["without_flow_stock"],
        "without_price_type": counters["without_price_type"],
        "without_adjustment": counters["without_adjustment"],
        "without_czech_synonyms": counters["without_czech_synonyms"],
        "without_english_synonyms": counters["without_english_synonyms"],
    }


def first(row: dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = row.get(key)
        if isinstance(value, list):
            text = " ".join(str(v).strip() for v in value if str(v).strip())
        else:
            text = "" if value is None else str(value).strip()
        if text:
            return text
    return ""


def normalize(value: str) -> str:
    return re.sub(r"\s+", " ", value.lower()).strip()


def looks_code_only(value: str) -> bool:
    return bool(value and re.fullmatch(r"[A-Z0-9_.\-/]{3,}", value.strip()))


def write_csv(stats: list[dict[str, Any]]) -> None:
    fields = [
        "source",
        "sqlite_rows",
        "sample_rows",
        "quality_source",
        "without_description",
        "without_geo",
        "without_unit",
        "without_frequency",
        "duplicates_in_sample",
        "short_titles",
        "code_only_titles",
        "without_canonical_concepts",
        "without_institutional_sector",
        "without_measure_type",
        "without_flow_stock",
        "without_price_type",
        "without_adjustment",
        "without_czech_synonyms",
        "without_english_synonyms",
    ]
    with (OUTPUT_DIR / "search_v2_fts_index_audit.csv").open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        writer.writerows(stats)


def write_markdown(report: dict[str, Any]) -> None:
    lines = [
        "# Search V2 FTS Index Audit",
        "",
        f"- FTS DB: `{report['fts_db']}`",
        f"- FTS DB size MB: `{report['fts_db_mb']}`",
        f"- Duration seconds: `{report['duration_seconds']}`",
        f"- Sampling: `{report['sampling']}`",
        "",
        "## Current Legacy FTS Observations",
        "",
        f"- {report['field_weighting_observation']}",
        "- The legacy index stores a serialized row JSON next to FTS fields; Search V2 raw retrieval does not expose canonical title/concept fields.",
        "- Metadata coverage varies by source; missing geo/unit/frequency makes hard constraints and chart-ready defaults less reliable.",
        "",
        "## Source Coverage",
        "",
        "| Source | SQLite rows | Sample rows | No desc | No geo | No unit | No freq | No concepts | Code titles | Duplicates sample |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in report["sources"]:
        lines.append(
            "| {source} | {sqlite_rows} | {sample_rows} | {without_description} | {without_geo} | {without_unit} | {without_frequency} | {without_canonical_concepts} | {code_only_titles} | {duplicates_in_sample} |".format(
                **row
            )
        )
    lines.append("")
    lines.append("## Generated Files")
    lines.append("")
    lines.append("- `outputs/search_v2_fts_index_audit.json`")
    lines.append("- `outputs/search_v2_fts_index_audit.csv`")
    (DOCS_DIR / "search_v2_fts_index_audit.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
