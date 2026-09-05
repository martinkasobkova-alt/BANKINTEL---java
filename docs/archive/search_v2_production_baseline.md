# Search V2 Production Baseline

Frozen on: 2026-07-12

This document freezes the current Search V2 production baseline. It is a baseline, not a claim that relevance is finished. Future Search V2 ranking, taxonomy, or FTS changes must be compared against this state on both the original eval set and the holdout dataset.

## Configuration

Recommended production configuration:

```env
SEARCH_ENGINE_VERSION=v2
SEARCH_CATALOG_INDEX=sidecar
SEARCH_SEMANTIC_RETRIEVAL_ENABLED=false
```

Additional sidecar paths used by deployment:

```env
SEARCH_CATALOG_SIDECAR_DIR=/data/search_v2_sidecar
SEARCH_CATALOG_SIDECAR_FTS_DB=/data/search_v2_sidecar/search_v2_sidecar.sqlite
```

`render.yaml`, `backend-java/.env.example`, and `start-dev.ps1` now make this baseline explicit. The frontend global catalog path calls `/api/catalog/deep-search`; the backend routes that endpoint to Search V2 when `SEARCH_ENGINE_VERSION=v2`.

## Sidecar

- Sidecar database: `data/search_v2_sidecar/search_v2_sidecar.sqlite`
- Sidecar document count: 60,402
- Source counts:
  - arad: 894
  - bis: 34
  - commodities: 46
  - csu: 1,648
  - data360: 10,310
  - ecb2: 180
  - eurostat: 7,797
  - fred: 10,859
  - imf: 27,643
  - oecd4: 991

The legacy classic FTS index remains present under `data/catalog_search_indexes/` and was not rebuilt or removed in this phase.

## Baseline Metrics

Original 40-query Search V2 baseline before this freeze:

| Metric | Value |
|---|---:|
| Source hard constraints | 100% |
| Geo hard constraints | 100% |
| Precision@1 | 0.675 |
| Precision@5 | 0.900 |
| MRR | 0.775 |
| Candidate Recall@20 | 0.700 |
| Candidate Recall@50 | 0.800 |
| Warm median latency | 78.5 ms |
| Warm P95 latency | 109 ms |

Runtime smoke after sidecar rebuild:

| Metric | Value |
|---|---:|
| Search engine | v2 |
| Catalog index mode | sidecar |
| Semantic retrieval enabled | false |
| Fallback to legacy | false |
| Warm median latency | 73.5 ms |
| Warm P95 latency | 121 ms |

## Candidate Limits

| Limit | Value |
|---|---:|
| Retrieval per variant | 25 |
| Retrieval pool after merge | 240 |
| Reranker max candidates | 60 |
| Preview top N default | 5 |
| Preview max verify | 8 |
| Final result default | 12 |
| Final result max | 30 |

Representative funnel for `roa bank`:

| Stage | Count |
|---|---:|
| retrieved_raw | 1,397 |
| deduplicated_unique | 933 |
| after_hard_constraints | 933 |
| after_source_balancing | 240 |
| after_candidate_limit | 240 |
| sent_to_deterministic_reranker | 60 |
| sent_to_llm_reranker | 0 |
| sent_to_preview | 0 |
| final_results | 10 |

Preview is skipped in `metadata_only` eval mode. Live preview remains available in normal UI flows, but metadata-only baseline intentionally measures retrieval/ranking without API-preview noise.

## Label Audit

The current 40-query eval set is not fully human-gold:

- `explicit_gold_series`: 8 queries
- `heuristic`: 27 queries
- `rule_based`: 5 queries
- `human_judged`: 0 queries

See `docs/search_v2_relevance_judgment_audit.md` and `outputs/search_v2_human_vs_provisional_metrics.json`. Future reporting must keep exact/human labels separate from heuristic/provisional labels.

## Holdout Baseline

Frozen holdout dataset: `evaluation/search_v2_holdout_queries.json`

| Metric | Value |
|---|---:|
| Queries | 28 |
| Precision@1 | 0.6786 |
| Precision@5 | 0.7857 |
| MRR | 0.7191 |
| Candidate Recall@20 | 0.5714 |
| Candidate Recall@50 | 0.6786 |
| Empty result rate | 0.0 |
| Source constraint accuracy | 1.0 |
| Geo constraint accuracy | 0.88 |
| Warm median latency | 82.0 ms |
| Warm P95 latency | 177.0 ms |

The holdout includes provisional/rule labels, so it is a regression screen and failure-attribution tool, not a final human-gold score.

## Known Limitations

- `tipscp10` is a Eurostat "Core inflation differential vis-a-vis EA" table, not a direct Czech core-inflation level. It is related, but ECB/OECD core inflation rows are better direct answers for `jadrova inflace Cesko`.
- Some broad source catalogs still expose dimension-selectable rows with blank fixed geo. This is acceptable only when the detail view can select the requested geography.
- No-available-series queries currently may return plausible-looking but wrong catalog rows; these need explicit coverage/no-answer handling before being treated as solved.
- Provisional eval labels can over-credit broad concept matches. Human review is still needed for a true gold set.
- Direct real wages are absent from raw catalog/sidecar and are handled as a derived-series requirement, not as a ranking tweak.

## Rollback

Explicit rollback to legacy search:

```env
SEARCH_ENGINE_VERSION=v1
SEARCH_CATALOG_INDEX=legacy
SEARCH_SEMANTIC_RETRIEVAL_ENABLED=false
```

Per-request rollback for diagnostics:

```json
{
  "search_engine_version": "v1",
  "search_catalog_index": "legacy"
}
```

Rollback checks:

1. Restart backend after changing env.
2. Call `POST /api/catalog/deep-search` and confirm `search_engine` is not `v2`.
3. Call `POST /api/catalog/search-v2` only for diagnostic comparison if needed.
4. Confirm traces/reporting do not silently claim sidecar when legacy is requested.

## Git Baseline

Requested tag: `search-v2-sidecar-baseline`

Local status: `C:\Bankoapp-main\BankIntel-v2` is not a Git repository (`fatal: not a git repository`). A real commit/tag cannot be created here without initializing or attaching the repository metadata. If this folder is later put under Git, commit this baseline state first and tag it as `search-v2-sidecar-baseline`.
