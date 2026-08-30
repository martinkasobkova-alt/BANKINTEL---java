# Search V2 Candidate Pipeline Audit

## Active Configuration

- search_engine: `v2`
- catalog_index_mode: `sidecar`
- semantic_retrieval_enabled: `False`
- fallback_to_legacy: `False`
- deep_search_probe: `{'client_latency_ms': 110, 'search_engine': 'v2', 'catalog_index_mode': 'sidecar', 'semantic_retrieval_enabled': False, 'fallback_to_legacy': False, 'result_count': 5}`

## ROA Bank Funnel

- retrieved_raw: `386`
- deduplicated_unique: `152`
- after_hard_constraints: `152`
- after_source_balancing: `152`
- after_candidate_limit: `152`
- sent_to_deterministic_reranker: `60`
- sent_to_llm_reranker: `0`
- sent_to_preview: `0`
- preview_success: `0`
- preview_failed: `0`
- unique_preview_requests: `0`
- final_results: `10`
- candidate_limit_note: `source_balancing_and_candidate_limit_are_applied_in_one_merge_step`

## FTS Query Variants And Source Counts

- fts_query_variant_count: `4`
- fts_queries: `['roa bank', 'Return on assets', 'ROA', 'rentabilita aktiv']`
- retrieved_duplicates: `234`

| Source | Queries | Retrieved | OK queries | Timeouts/errors |
|---|---:|---:|---:|---:|
| ecb2,imf,bis,data360 | 4 | 386 | 4 | 0 |

## Sidecar Coverage Findings

- ECB ROA geo `AT`: present=`True`, count=`2`, sample=`CBD2/A.AT.W0.11._Z._Z.A.A.I2004._Z._Z._Z._Z._Z._Z.PC, CBD2/A.AT.W0.67._Z._Z.A.A.I2004._Z._Z._Z._Z._Z._Z.PC`
- ECB ROA geo `U2`: present=`True`, count=`6`, sample=`CBD2/A.U2.W0.11._Z._Z.A.A.I2004._Z._Z._Z._Z._Z._Z.PC, CBD2/A.U2.W0.11._Z._Z.L.A.I2004._Z._Z._Z._Z._Z._Z.PC, CBD2/A.U2.W0.11._Z._Z.M.A.I2004._Z._Z._Z._Z._Z._Z.PC`
- ECB ROA geo `PL`: present=`False`, count=`0`, sample=``
- ECB ROA geo `CZ`: present=`False`, count=`0`, sample=``

## Preview Calls

- metadata_total: `0`
- top_preview_total: `90`
