# Search V2 Geo Regression Audit

Generated: 2026-07-13

## Scope

This audit covers the Search V2 sidecar configuration:

- `SEARCH_ENGINE_VERSION=v2`
- `SEARCH_CATALOG_INDEX=sidecar`
- `SEARCH_SEMANTIC_RETRIEVAL_ENABLED=false`

The goal was limited to explicit geo-constraint regression. The fix does not redesign retrieval,
query variants, source routing, or semantic retrieval.

## Root Cause

The legacy geo metric fell to `0.84` because explicit geography was not treated as a hard primary
compatibility gate across the whole Search V2 pipeline. In three cases, production ranking allowed
preferred source, FTS, or incomplete metadata to outrank geo compatibility. In one case, production
returned the correct result but the evaluator read only raw stored geo and missed entity/market geo.

The broken priority was effectively:

1. exact/source/FTS evidence
2. preferred source
3. partial or raw geo evidence

The corrected priority is:

1. explicit user source constraint
2. explicit user geo constraint
3. exact entity match
4. measure type, instrument, and catalog family compatibility
5. semantic relevance
6. preferred source
7. FTS score as a tie-breaker

Preferred source can only order candidates after explicit geo compatibility is satisfied.

## Failing Cases

| Query | Requested geo | Old top result | Stage | Type | Reason |
|---|---:|---|---|---|---|
| `urokove sazby Fed` | US | `arad:arad_bank_interest_rates_nfc` | reranking | production bug | Exact entity had US intent, but a Czech ARAD banking-rate row could still rank as primary. |
| `zemni plyn cena Evropa` | EU | `fred:NASDAQFUM` | candidate_normalization | production bug | Commodity market-price query admitted a market/equity proxy with unknown geo while the EU gas-price row was available. |
| `akcie Komercni banka` | CZ | `stocks:KONN.F` | candidate_normalization | production bug | Stock rows lacked normalized market geo; the German exchange suffix could outrank the Prague listing. |
| `S&P 500 index FRED` | US | `fred:SP500` | evaluation_bug | evaluator bug | Production returned the correct SP500 row, but the evaluator did not count entity/market geo as US evidence. |

Machine-readable details:

- `outputs/search_v2_geo_regression_cases.json`
- `outputs/search_v2_geo_regression_cases.csv`

## Pipeline Findings

| Pipeline stage | Finding | Fix |
|---|---|---|
| ExactEntityResolver | Entity fixed geo existed for some entities but was not consistently merged into the plan. | Exact entity attributes now include `geo_mode`, `fixed_geo`, `market`, `return_type`, and the plan merges fixed entity geo when the user did not supply an explicit conflicting geo. |
| SearchQueryPlan | Explicit geo could be absent for English/Czech aliases such as `Czech banks`. | Planner now resolves canonical country aliases and aggregate scopes into normalized geo codes. |
| Source routing | Source preference could pull a source before geo compatibility was enforced. | Hard geo filtering happens before final source balancing and ranking. |
| Candidate normalization | Stock exchange suffixes and aggregate text evidence were not counted as generic geo evidence. | Generic suffix evidence and aggregate geo evidence are centralized in `SearchV2GeoCompatibility`. |
| Hard constraint filter | Dimensionable rows were treated too broadly, while fixed wrong geo was not always dropped. | Fixed geo, stock suffix geo, supported geo lists, aggregate text, entity fixed geo, and dimensionable source capability are evaluated in one place. |
| Reranking | Preferred source and FTS could be stronger than explicit geo. | Geo mismatch is dropped before reranking for primary candidates. |
| Evaluation | The evaluator read raw `geo` too narrowly. | Eval now separates raw geo, stock suffix geo, entity geo, dimensionable geo, aggregate geo, and answer availability. |

## Production vs Evaluator

Production bugs:

- `urokove sazby Fed`
- `zemni plyn cena Evropa`
- `akcie Komercni banka`

Evaluator-only bug:

- `S&P 500 index FRED`

## Verification Snapshot

After the fix:

- `urokove sazby Fed` -> `fred:FEDFUNDS`, geo trace satisfied with `US`
- `zemni plyn cena Evropa` -> `fred:PNGASEUUSDM`, geo trace satisfied with `EU`
- `akcie Komercni banka` -> `stocks:KOMB.PR`, geo trace satisfied with `CZ`
- `S&P 500 index FRED` -> `fred:SP500`, no false production failure
- `Nasdaq-100 total return` -> `fred:NASDAQXNDX`

Semantic retrieval remained disabled.
