# Search V2 Generalization Audit

Date: 2026-07-11

Scope: newly added Search V2 retrieval, fallback reranking, source balancing, commodity adapter, preview/eval modes, and regression data. Classification:

- A: general structural normalization
- B: metadata-driven rule
- C: versioned concept ontology
- D: query-specific or concept-specific hardcode

## Summary

Search V2 no longer keeps ROA/ROE/HICP/CPI/GDP/HDP aliases directly inside the deterministic fallback reranker or query expander. Those signals now live in `backend-java/src/main/resources/search_v2/concept_ontology.json` and are loaded by `SearchV2ConceptOntology`. The fallback itself is generic: it scores title/metadata overlap, checks explicit short professional signals only when the user typed them, penalizes context-only series, and applies geo/source constraints outside the fallback.

No production rule was added that says `if query contains inflation require CPI`, `if query contains bank require ROA/ROE`, or `if query contains GDP require GDP/HDP`.

## Rule Audit

| Rule | File / function | What it does | Class | Keep? | Move? | Risk |
|---|---|---:|---|---|---|---|
| Deterministic fallback lexical scoring | `SearchV2SemanticValidator.fallbackDecision` | Scores overlap between normalized query/search terms and canonical candidate metadata. | B | Yes | No | Low. Generic metadata evidence. |
| Explicit short signal requirement | `SearchV2SemanticValidator.requiredSignals` + `SearchV2ConceptOntology` | If the original query explicitly contains a known short signal such as `roa`, candidate metadata must contain that signal or an ontology alias. | C | Yes | Already moved to ontology | Medium. Ontology must stay curated and audited; it must not become query examples. |
| Context-only series penalty | `SearchV2SemanticValidator.containsContextOnlyTerm` + ontology | Demotes rows such as weights/contributions when user did not ask for that context. | C | Yes | Already moved to ontology | Medium. Needs future metadata tags for stronger source-native detection. |
| Fallback stop terms | `SearchV2SemanticValidator.fallbackTerms` + ontology | Removes generic words before metadata overlap. | A/C | Yes | Already moved to ontology | Low. Stop terms are not domain decisions. |
| Short meaningful terms in query expansion | `SearchV2QueryExpander.meaningful` + ontology | Preserves short economic abbreviations that would otherwise be dropped. | C | Yes | Already moved to ontology | Medium. Add terms only when they are stable abbreviations, not query examples. |
| FX compact pair expansion | `SearchV2QueryExpander.currencyPairVariants` | Converts `eurusd` to `eur usd`, `exchange rate eur usd`, `fx eur usd` using ISO-like currency codes from ontology. | A/C | Yes | Currency list is in ontology | Low. Structural normalization; not tied to one query. |
| Geo alias expansion | `SearchV2QueryExpander.withPreferredGeoAliases`, `SearchV2QueryPlanner.geographiesFromQuery` | Adds source-searchable aliases for requested territories and removes already requested geo tokens from base query terms. | A | Yes | No | Low. Based on `CatalogCountryAliasRegistry`. |
| Commodity adapter | `SearchV2FtsRetriever.retrieveOne` and `CatalogCommoditySearch.searchHits` | Routes source `commodities` to the curated Pink Sheet commodity catalog instead of SQLite FTS rows. | B | Yes | Keep as source adapter | Medium. Commodity lexicon should remain source metadata/lexicon backed. |
| Source-balanced candidate pool | `SearchV2CandidateMerger.merge` | Round-robin merges deduped candidates by source before final semantic validation. | B | Yes | No | Low. Affects only recall pool. |
| Final source diversity | `SearchV2FinalReranker.finalRank` | No source quota. Sorts by role, relevance, confidence, then FTS score. | B | Yes | No | Low. Regression test proves top 5 can all be one source. |
| Preview gating | `SearchV2Service.verifyPreview` | Normal app search defaults to `full` preview gate. Eval can use `metadata_only` or `top_preview`. | B | Yes | No | Low. Internal eval mode does not weaken normal production search. |
| Duplicate preview reuse | `SearchV2PreviewVerifier.verify` | Reuses the same in-request future for duplicate `source + series_id + geo`. | A/B | Yes | No | Low. Prevents duplicate calls and stabilizes latency. |

## Hardcode Found And Resolved

Found in Java before this cleanup:

- `SearchV2SemanticValidator`: fixed lists for fallback stop terms, short professional signals, required signal aliases, and context-only terms.
- `SearchV2QueryExpander`: fixed short meaningful terms and currency code list.

Resolution:

- Added `search_v2/concept_ontology.json`.
- Added `SearchV2ConceptOntology`.
- Refactored fallback and expander to read ontology data.
- Restricted hard required signals to explicit original-query abbreviations only. Planner expansions can improve scoring, but they do not become hard rejection rules.

Remaining acceptable source/domain lexicons:

- Commodity lexicon and analytics/forecast playbooks remain in resource files. They are source/domain metadata, not query-specific Search V2 ranking branches.

## Source Diversity Verification

Candidate pool diversity remains in `SearchV2CandidateMerger.merge`. Final ranking remains relevance-first in `SearchV2FinalReranker.finalRank`; there is no quota such as "one result from every source".

Regression:

- `SearchV2CandidateMergerTest.balancesCandidatesAcrossSourcesBeforeApplyingLimit`
- `SearchV2FinalRerankerTest.finalRankingDoesNotForceSourceDiversity`

## Eval / Latency Changes

Eval modes:

- `metadata_only`: no live preview, no series values, default for eval.
- `top_preview`: verifies only top N candidates with the preview timeout; results are not blocked by preview failures.
- `full`: normal production-like preview gate.

Artifacts:

- `outputs/search_eval_v1_vs_v2.json`
- `outputs/search_eval_v1_vs_v2.csv`
- `docs/search_v2_evaluation_report.md`

Latency instrumentation included in report:

- total latency
- planner latency
- FTS/retrieval latency
- reranker latency
- preview latency
- LLM call estimate and prompt-token estimate

## Recommendations

1. Keep adding stable abbreviations to `concept_ontology.json`, not to Java fallback branches.
2. Move future source-specific topic knowledge into source metadata, not final ranking code.
3. Treat `metadata_only` eval as the nightly regression gate and run `top_preview` on a smaller sample for live connector health.
4. Add human judgments to `gold_queries.json` over time, but never generate production rules from individual gold queries.
