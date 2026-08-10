# Search V2 Exact Entity Architecture

## Goal

Search V2 now resolves concrete named entities before broad LLM query expansion. The purpose is to keep
symbols, tickers, market indexes, FX pairs, commodities, ratios, rates, dataset codes and series codes from
being expanded into sibling entities.

The stable baseline remains:

- `SEARCH_ENGINE_VERSION=v2`
- `SEARCH_CATALOG_INDEX=sidecar`
- `SEARCH_SEMANTIC_RETRIEVAL_ENABLED=false`

## Flow

1. User query enters `SearchV2QueryPlanner`.
2. `ExactEntityResolver` checks the versioned exact entity registry.
3. If confidence is high, the planner returns an `exact_entity_resolver` plan and bypasses broad LLM planning.
4. `SearchV2SourceCapabilityRegistry` maps entity type and catalog family to preferred source routing.
5. `SearchV2QueryExpander` uses only first-pass exact roles:
   `original_exact`, `canonical_name`, `exact_alias`, `symbol`, `translated_exact`.
6. FTS retrieval searches the sidecar within routed sources.
7. Semantic validation runs as before, but `SearchV2ExactEntityScorer` protects deterministic exact matches.
8. `SearchV2FinalReranker` uses source preference for high-confidence exact entities before weak fallback score.
9. Strict exact gates drop substring false positives for concrete market indexes/equities/codes.
10. Broad expansion is allowed only as a retry path when exact coverage fails.

## Versioned Data

- `backend-java/src/main/resources/search_v2/exact_entity_registry.json`
- `backend-java/src/main/resources/search_v2/source_capability_registry.json`
- `backend-java/src/main/resources/search_v2/entity_type_schema.json`

These files are data registries, not query-specific production branches. They include broader entity families
such as market indexes, equities, FX pairs, commodities, financial ratios, policy rates and macro indicators.

## Trace Contract

Search V2 responses now expose:

- `entity_resolution`
- `source_routing`
- `query_variants`
- `exact_retrieval_succeeded`
- `broad_expansion_used`

The debug UI renders query variant roles separately, so a symbol or exact alias is no longer shown as if it were
the same thing as a related entity.

## Rollback

1. Revert the files changed in `backend-java/src/main/java/cz/bankintel/search/v2/entity`.
2. Revert Search V2 planner/orchestration/reranker changes.
3. Revert the three `search_v2/*.json` registry resources.
4. Revert the frontend debug-panel changes.
5. Re-run:
   - `cd backend-java && .\gradlew.bat test`
   - `cd frontend && npm run check`

No environment rollback is needed. Semantic retrieval was not enabled.
