# Search V2 Relevance Judgment Audit

This audit separates label provenance from Search V2 runtime metrics. The current eval set contains no verified `human_judged` labels; exact-series labels and heuristic labels are reported separately.

## Judgment Counts

| Judgment type | Count |
|---|---:|
| `explicit_gold_series` | 8 |
| `heuristic` | 27 |
| `rule_based` | 5 |

## Metrics By Label Class

| Group | Queries | P@1 | P@5 | MRR | Empty rate | Median ms |
|---|---:|---:|---:|---:|---:|---:|
| `strong_human_or_explicit_gold` | 8 | 1.0 | 0.775 | 1.0 | 0.0 | 1543.0 |
| `provisional_or_rule_based` | 32 | 0.625 | 0.4938 | 0.7135 | 0.0625 | 400.5 |

## Metrics By Judgment Type

| Judgment type | Queries | P@1 | P@5 | MRR | Empty rate | Median ms |
|---|---:|---:|---:|---:|---:|---:|
| `explicit_gold_series` | 8 | 1.0 | 0.775 | 1.0 | 0.0 | 1543.0 |
| `heuristic` | 27 | 0.6296 | 0.4667 | 0.7346 | 0.0 | 432 |
| `rule_based` | 5 | 0.6 | 0.64 | 0.6 | 0.4 | 273 |

## Label Method

- `explicit_gold_series`: a concrete relevant series ID is listed in `gold_queries.json`.
- `rule_based`: source, no-result, clarification, or other explicit constraint label.
- `heuristic`: concept-family/keyword expectation, useful for regression but not human gold.
- `provisional`: weak or exploratory label.

Automatic and heuristic labels must not be presented as human gold metrics.
