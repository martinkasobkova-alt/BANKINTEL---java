# Catalog search scoring layers

| Třída | Role |
|-------|------|
| `CatalogTextUtils` | Tokenizace dotazu, FTS MATCH (word OR + fráze), needles pro title match |
| `CatalogSearchSynonyms` | CZ→EN expansion, banking/geo synonym groups |
| `CatalogGeoIntent` | Detekce země v dotazu, topic strip, row country code, geo multiplier/hard reject |
| `CatalogLikelySources` | Heuristické likely sources + geo filter + boost pro non-CZ dotazy |
| `CatalogSourceRouter` | Union planner + likely sources (env `CATALOG_DEEP_SEARCH_SOURCE_ROUTER`) |
| `CatalogSearchMetadataSidecar` | **Loader** — JSONL sidecar + retrieval rescue set ids |
| `CatalogMetadataScorer` | **Metadata channel** — intent tags, keyword blob scoring, soft-AND |
| `CatalogCompositeScorer` | **Blend** — BM25 + title + metadata (`W_LEXICAL=0.55`) |
| `CatalogScoringPipeline` | **Orchestrator** — single entry: sidecar → composite → geo → dedup → limit |
| `CatalogSearchVariantDedup` | Near-dup konsolidace titulků v rámci zdroje |
| `CatalogSearchResultCache` | LRU cache FTS výsledků (fold_ascii + ecb set_id key) |

## Typed model (`search/model/`)

| Record | Role |
|--------|------|
| `CatalogRawRow` | Izoluje upstream FTS/JSONL JSON |
| `CatalogHit` | Scored search result (API via `toMap()`) |
| `CatalogCandidate` | Deep-search row + preview lifecycle |
| `SearchPlan` | Planner output (sources, terms, geo) |
| `GeoIntentSnapshot` | Typed geo intent attached to plan/scoring |
| `CatalogKeys` | Canonical JSON field names |

Pipeline: FTS retrieve → sidecar merge → **`CatalogScoringPipeline.scoreAndRank`** → API `toMap()`.
