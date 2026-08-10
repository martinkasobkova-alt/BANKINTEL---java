# Search V2 Geo Regression Evaluation

Generated: 2026-07-13

## Configuration

- `SEARCH_ENGINE_VERSION=v2`
- `SEARCH_CATALOG_INDEX=sidecar`
- `SEARCH_SEMANTIC_RETRIEVAL_ENABLED=false`

## Commands Run

```powershell
cd C:\Bankoapp-main\BankIntel-v2\backend-java
.\gradlew.bat test

cd C:\Bankoapp-main\BankIntel-v2\frontend
npm run check

cd C:\Bankoapp-main\BankIntel-v2
python scripts\search_v2_holdout_eval.py
python scripts\search_v2_exact_entity_eval.py
python scripts\search_v2_judgment_audit.py
```

All commands completed successfully.

## Holdout Before/After

| Metric | Before | After |
|---|---:|---:|
| P@1 | 0.6786 | 0.7857 |
| P@5 | 0.7857 | 0.8929 |
| MRR | 0.7191 | 0.8304 |
| Candidate Recall@20 | 0.5714 | 0.6786 |
| Candidate Recall@50 | 0.6786 | 0.7500 |
| Source constraint accuracy | 1.0000 | 1.0000 |
| Legacy geo constraint accuracy | 0.8400 | 1.0000 |
| Explicit geo primary accuracy | n/a | 1.0000 |
| Explicit geo any-result accuracy | n/a | 1.0000 |
| Dimensioned geo resolution accuracy | n/a | 1.0000 |
| Aggregate geo accuracy | n/a | 1.0000 |
| Geo answer availability | n/a | 0.9643 |
| Warm median latency | 71 ms | 93 ms |
| Warm P95 latency | 124 ms | 174 ms |
| Cold median latency | 345 ms | 322.5 ms |

The old eval did not split explicit/dimensioned/aggregate geo metrics, so those fields are reported
as `n/a` before the fix.

## Exact Entity Before/After

| Metric | Before | After |
|---|---:|---:|
| P@1 | 0.9286 | 0.9286 |
| P@5 | 0.9286 | 0.9286 |
| MRR | 0.9286 | 0.9286 |
| Candidate Recall@20 | n/a | 0.8929 |
| Candidate Recall@50 | n/a | 0.9643 |
| Exact entity resolution accuracy | 1.0000 | 1.0000 |
| Source routing accuracy | 1.0000 | 1.0000 |
| Sibling contamination top3 | 0.0000 | 0.0000 |
| Warm median latency | 78 ms | 116.5 ms |
| Warm P95 latency | 180 ms | 215 ms |

## Geo-Focused Metrics

| Metric | After |
|---|---:|
| Geo query count | 25 |
| Explicit geo query count | 21 |
| Dimensioned geo query count | 18 |
| Aggregate geo query count | 1 |
| Explicit geo primary accuracy | 1.0000 |
| Explicit geo any-result accuracy | 1.0000 |
| Dimensioned geo resolution accuracy | 1.0000 |
| Aggregate geo accuracy | 1.0000 |
| Geo answer availability | 0.9643 |

## Nasdaq Regression Check

| Query | Expected | Observed |
|---|---|---|
| `nasdaq100` | `NASDAQ100` rank 1 | `NASDAQ100` rank 1 |
| `Nasdaq-100` | `NASDAQ100` rank 1 | `NASDAQ100` rank 1 |
| `NDX` | `NASDAQ100` rank 1 | `NASDAQ100` rank 1 |
| `Nasdaq-100 total return` | total-return series before price index | `NASDAQXNDX` rank 1 |

Return-type behavior is handled through generic `return_type` and `requested_return_type` metadata,
not by a query-specific code branch.

## Acceptance

| Check | Result |
|---|---|
| Explicit geo primary accuracy = 1.0 | pass |
| Source constraint accuracy = 1.0 | pass |
| Exact resolution accuracy = 1.0 | pass |
| Sibling contamination = 0 | pass |
| Exact P@1 >= 0.90 | pass |
| Holdout P@1 not regressed beyond 0.02 | pass |
| Holdout P@5 not regressed beyond 0.02 | pass |
| Warm P95 <= 220 ms | pass |
| Semantic retrieval disabled | pass |

Machine-readable metrics:

- `outputs/search_v2_geo_eval_before_after.json`
- `outputs/search_v2_geo_eval_before_after.csv`
