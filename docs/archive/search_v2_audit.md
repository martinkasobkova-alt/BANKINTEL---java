# Search V2 Audit

Date: 2026-07-11

## Current Search V1 Call Graph

`POST /api/catalog/deep-search` enters `CatalogController.deepSearch`, then calls `CatalogDeepSearchService.deepSearch`.

Main V1 flow:

1. `CatalogController.deepSearch`
2. `CatalogDeepSearchService.deepSearchWithLanes`
3. `CatalogQueryPlanner.planTyped`
4. `CatalogSourceRouter` and `CatalogLikelySources`
5. per-source lane execution through `CatalogIndexStore.searchHits/searchSource`
6. SQLite FTS5 or JSONL fallback in `CatalogIndexStore`
7. sidecar metadata rescue in `CatalogSearchMetadataSidecar`
8. row scoring in `CatalogScoringPipeline`, `CatalogCompositeScorer`, `CatalogMetadataScorer`
9. source/geo reranking and candidate dedupe in `CatalogDeepSearchService`
10. optional candidate ranking in `CatalogAiDataResolver`
11. live preview validation in `CatalogDeepSearchPreviewService`
12. promotion and final sorting in `CatalogDeepSearchPromotion` and `CatalogDeepSearchFinalRanker`
13. answer generation in `CatalogSearchAnswerService`
14. follow-up bootstrap in `CatalogFollowupService`

Classic search (`POST /api/catalog/search`) is thinner:

1. `CatalogController.search`
2. `CatalogClassicSearchService`
3. `CatalogIndexStore.searchSource`
4. SQLite FTS5 or JSONL fallback
5. sidecar rescue and scoring pipeline

Source route endpoint:

1. `CatalogController.deepSearchSourceRoute`
2. `CatalogSourceRouteService`
3. `CatalogQueryPlanner`
4. `CatalogSourceRouter`

## Current Index And FTS

The catalog index is local disk backed:

- SQLite FTS5 database: `CLASSIC_CATALOG_FTS_DB`, defaulting under `CATALOG_SEARCH_INDEX_DIR`.
- JSONL fallback files under `CATALOG_SEARCH_INDEX_DIR`.
- Metadata sidecar files under `CATALOG_SEARCH_METADATA_DIR`.

Core FTS tables used by V1:

- `catalog_fts` with `source`, `set_id`, `title`, `full_path`, `row_json` and FTS columns.
- `catalog_rows_lookup` for direct lookup by `source` and `set_id`.

The public V1 adapter is `CatalogIndexStore.searchSource`, but it is not a pure FTS adapter: it also performs sidecar rescue and scoring. Search V2 must therefore use a raw FTS adapter that returns candidate rows before V1 scoring.

## LLM Calls In V1

Potential LLM calls per deep search request:

- `CatalogQueryPlanner` planner call, short timeout, JSON mode.
- `CatalogAiDataResolver` candidate ranking call, when AI path is enabled.
- `CatalogSearchAnswerService` answer/summarization call, when there are candidate rows.
- follow-up/result chat paths are separate post-search calls.

Because planner, candidate ranking and answer generation can each call LLM independently, V1 latency and failure mode are hard to reason about. V1 also has deterministic fallback branches that can silently replace LLM reasoning.

## Database Calls In V1

For one deep search request:

- one FTS operation per source lane and per term/probe branch,
- optional count queries for large sources before ordered BM25 retrieval,
- optional lookup queries for sidecar and direct id rescue,
- preview API calls after candidate ranking.

The exact count depends on selected sources, query variants, sidecar seeds and timeout fallbacks. This makes latency data-dependent.

## Main Problems

- Search planning, source routing, FTS retrieval, semantic scoring, preview validation and answer synthesis are interleaved.
- Query-specific behavior is spread across several classes rather than being expressed as a single explainable semantic decision.
- Source routing happens before global semantic validation, so relevant candidates may never enter the pool.
- Several fallback branches can change candidate pools without a unified trace.
- Candidate validity and semantic relevance are mixed: a row can be preview-valid but semantically wrong.
- FTS score, local boosts and preview success all influence final order before a global semantic pass.
- Debugging a bad result requires reading multiple service logs and inferred fields in rows.

## Performance Bottlenecks

- FTS count + ordered BM25 scans for very broad queries on large sources.
- Many per-source/per-term lane tasks with source-specific fallback logic.
- Live preview validation before final user-facing selection can call external APIs for many candidates.
- Multiple possible LLM calls per request.
- Sidecar rescue and lookup expansion can add more DB calls after the first FTS retrieval.

## Hardcoded And Rule-Like Areas In V1

These are not all bad individually, but together they make relevance behavior difficult to reason about:

- `CatalogLikelySources` source inference.
- `CatalogSourceRouter` intent tag routing and planner/source union logic.
- `CatalogSearchSynonyms`, `CatalogSearchLexicon`, `CatalogQueryIntent`, `CatalogQuerySemanticProfile`.
- `CatalogCompositeScorer`, `CatalogMetadataScorer`, `CatalogResultSpecificityScorer`, `CatalogRequiredTokenScorer`.
- `CatalogDeepSearchPromotion` and `CatalogDeepSearchFinalRanker`.
- source-specific rescue paths in `CatalogIndexStore` and deep-search timeout fallbacks.
- commodity fallback in `CatalogDeepSearchService`.
- preview-safe promotion/demotion rules in deep search.

## Safe To Reuse In V2

- `CatalogIndexStore` as a low-level FTS/lookup access point, with a new raw FTS method.
- `CatalogSourceRegistry` for source normalization and labels.
- `CatalogTextUtils` for safe FTS escaping and row title extraction.
- `CatalogPreviewOrchestrator` only after final selection, when data preview verification is desired.
- `OpenAiClient` for JSON-mode model calls.
- `BankIntelEnvVars` and Spring configuration.

## Must Not Be Used As V2 Decision Layers

- `CatalogDeepSearchService`
- `CatalogQueryPlanner`
- `CatalogSourceRouter`
- `CatalogLikelySources`
- `CatalogScoringPipeline`
- `CatalogCompositeScorer`
- `CatalogDeepSearchFinalRanker`
- `CatalogDeepSearchPromotion`
- query/concept-specific rescue branches

## Search V2 Architecture

Search V2 is a parallel package under `cz.bankintel.search.v2`.

Pipeline:

1. `SearchV2QueryPlanner` creates a structured `SearchQueryPlan`.
2. `SearchV2QueryExpander` turns the plan into compact FTS variants.
3. `SearchV2FtsRetriever` runs source/query variants in parallel through the raw FTS adapter.
4. `SearchV2CandidateNormalizer` builds canonical candidates.
5. `SearchV2Deduplicator` removes exact duplicates only.
6. `SearchV2SemanticValidator` performs global LLM keep/supporting/drop decisions in batches.
7. `SearchV2FinalReranker` globally orders kept/supporting rows.
8. `SearchV2CoverageChecker` detects missing coverage and ambiguity.
9. `SearchV2RetryPlanner` can perform one targeted retry.
10. `SearchV2TraceStore` stores trace/debug data.
11. `SearchV2Service` orchestrates and returns V2 plus compatibility fields.

Feature flag:

- `SEARCH_ENGINE_VERSION=v2` makes `/api/catalog/deep-search` use V2.
- `POST /api/catalog/search-v2` always runs V2 explicitly.
- `POST /api/catalog/search-v2/evaluate` runs the bundled evaluation dataset.
- `GET /api/catalog/search-v2/trace/{traceId}` returns the latest trace for debugging.

## New Files

- `search/v2/schema/SearchQueryPlan.java`
- `search/v2/schema/SearchCandidate.java`
- `search/v2/schema/SearchResult.java`
- `search/v2/schema/SemanticDecision.java`
- `search/v2/planner/SearchV2QueryPlanner.java`
- `search/v2/retrieval/SearchV2QueryExpander.java`
- `search/v2/retrieval/SearchV2FtsRetriever.java`
- `search/v2/retrieval/SearchV2CandidateMerger.java`
- `search/v2/normalization/SearchV2CandidateNormalizer.java`
- `search/v2/normalization/SearchV2Deduplicator.java`
- `search/v2/reranking/SearchV2SemanticValidator.java`
- `search/v2/reranking/SearchV2BatchReranker.java`
- `search/v2/reranking/SearchV2FinalReranker.java`
- `search/v2/coverage/SearchV2CoverageChecker.java`
- `search/v2/coverage/SearchV2RetryPlanner.java`
- `search/v2/observability/SearchV2Trace.java`
- `search/v2/observability/SearchV2TraceStore.java`
- `search/v2/orchestration/SearchV2FeatureFlags.java`
- `search/v2/orchestration/SearchV2Service.java`
- `search/v2/evaluation/SearchV2GoldQueries.java`
- `search/v2/evaluation/SearchV2Evaluator.java`
- `resources/search_v2/planner_prompt.md`
- `resources/search_v2/reranker_prompt.md`
- `resources/search_v2/gold_queries.json`
- `tools/search-v2-eval.mjs`

## Existing Files Minimally Modified

- `CatalogController` adds Search V2 endpoints and feature flag switch for `/deep-search`.
- `CatalogIndexStore` exposes a raw FTS retrieval method for V2.
- `application.yml` adds `bankintel.search.engine-version`.

## Metadata Findings

- Some rows carry strong human titles and full paths.
- Some rows still expose technical ids as labels, especially when source metadata lacks enriched names.
- Geo, frequency and unit fields are not uniform across sources.
- Category paths vary by source and sometimes need ingest-time enrichment.

Search V2 therefore normalizes the candidate shape at query time, but deeper semantic tags should be enriched offline at ingest/build time and versioned separately from source metadata.
