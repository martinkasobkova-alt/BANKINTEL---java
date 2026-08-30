# Search V2 Exact Entity Failure Audit

## Original Failure

The failing behavior for `nasdaq100` was not caused by missing FTS coverage. The sidecar contained direct FRED
Nasdaq-100 rows, including `NASDAQ100` and `NASDAQXNDX`.

The root cause was the planning stage:

- The LLM planner could treat `nasdaq100` as an open market topic.
- Broad variants such as `S&P 500`, `VIX` and generic US equity-market concepts could enter the first retrieval pass.
- Search V2 had no typed query variant roles, so sibling entities were not clearly separated from exact aliases.
- Source selection had source names but no source capability contract.
- Final ranking had no exact-entity protection, so broad or generic candidates could survive ahead of direct entities.

Primary failure owner: query planner and query expansion. Secondary contributors: source routing and final reranking.

## Current Trace

Current query plan for `nasdaq100`:

- `planner_status`: `exact_entity_resolver`
- `resolution_type`: `exact_entity`
- `entity_type`: `market_index`
- `canonical_name`: `NASDAQ-100`
- `symbols`: `NDX`, `NASDAQ100`
- `catalog_family`: `markets_indices`
- `preferred_sources`: `fred`
- `allow_broad_expansion`: `false`
- `semantic_retrieval_enabled`: `false`
- `catalog_index_mode`: `sidecar`

Query variants are role-tagged:

- `nasdaq100`: `original_exact`
- `NASDAQ-100`: `canonical_name`
- `NDX`: `symbol`
- `NASDAQ 100 Index`: `exact_alias`
- `S&P 500`, `Dow Jones`, `NASDAQ Composite`, `VIX`: `related_entity`

Related entities are not first-pass retrieval terms for high-confidence exact entity queries.

## FRED Results

Live post-fix run for `nasdaq100`:

| Rank | Source | Series | Title |
|---:|---|---|---|
| 1 | FRED | `NASDAQ100` | NASDAQ-100 |
| 25 | FRED | `NASDAQXNDX` | Index celkoveho vynosu NASDAQ-100 |

`NASDAQ100` is now top 1. `NASDAQXNDX` remains discoverable and survives retrieval/reranking.

## Remaining Coverage Notes

The new exact-entity eval intentionally includes `DAX` and `USDJPY`.

- `DAX`: the sidecar does not currently contain a direct DAX/DAXIND row. The new strict gate prevents false
  `NASDAQ...` substring results from being shown as DAX.
- `USDJPY`: the resolver and routing are correct, but the sidecar does not expose a direct USD/JPY row through exact
  terms in the current metadata. This should be handled by future dimension-aware FX retrieval, not by query-specific
  hardcode.

## Conclusion

The fix is general:

- exact entity resolution before broad planning,
- source capability routing,
- first-pass exact variant roles,
- deterministic exact result protection,
- source-preference aware final ranking,
- strict false-positive gate for concrete indexes/equities/codes.

No `if query contains nasdaq100` production branch was added.
