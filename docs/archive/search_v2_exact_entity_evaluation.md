# Search V2 Exact Entity Evaluation

- Dataset: `evaluation/search_v2_exact_entity_queries.json`
- Baseline: `SEARCH_ENGINE_VERSION=v2`, `SEARCH_CATALOG_INDEX=sidecar`, `SEARCH_SEMANTIC_RETRIEVAL_ENABLED=false`
- Mode: live backend, `use_ai=true`, `eval_mode=metadata_only`; exact entities bypass broad LLM planning when confidence is high.

## Summary

| Metric | Value |
|---|---:|
| `query_count` | 28 |
| `precision_at_1` | 0.9286 |
| `precision_at_5` | 0.9286 |
| `mrr` | 0.9286 |
| `candidate_recall_at_20` | 0.8929 |
| `candidate_recall_at_50` | 0.9643 |
| `empty_result_rate` | 0.0714 |
| `source_constraint_accuracy` | 0.9286 |
| `geo_constraint_accuracy` | 1.0 |
| `exact_entity_resolution_accuracy` | 1.0 |
| `source_routing_accuracy` | 1.0 |
| `sibling_entity_contamination_rate_top3` | 0.0 |
| `warm_median_latency_ms` | 127.0 |
| `warm_p95_latency_ms` | 260.0 |

## Per Query

| Query | Entity OK | Routing OK | Hit rank | Top source | Top series | Top title | Sibling contamination |
|---|---:|---:|---:|---|---|---|---:|
| nasdaq100 | True | True | 1 | fred | NASDAQ100 | NASDAQ-100 | False |
| Nasdaq 100 | True | True | 1 | fred | NASDAQ100 | NASDAQ-100 | False |
| Nasdaq-100 | True | True | 1 | fred | NASDAQ100 | NASDAQ-100 | False |
| NDX | True | True | 1 | fred | NASDAQ100 | NASDAQ-100 | False |
| NASDAQ100 | True | True | 1 | fred | NASDAQ100 | NASDAQ-100 | False |
| Nasdaq-100 index | True | True | 1 | fred | NASDAQ100 | NASDAQ-100 | False |
| vyvoj Nasdaq 100 | True | True | 1 | fred | NASDAQ100 | NASDAQ-100 | False |
| hodnota Nasdaq-100 | True | True | 1 | fred | NASDAQ100 | NASDAQ-100 | False |
| Nasdaq-100 total return | True | True | 1 | fred | NASDAQXNDX | Index celkového výnosu NASDAQ-100 | False |
| S&P 500 | True | True | 1 | fred | SP500 | Index S&P 500 | False |
| Dow Jones | True | True | 1 | fred | DJIA | Dow Jones Industrial Average | False |
| DAX | True | True |  |  |  |  | False |
| VIX | True | True | 1 | fred | VIXCLS | Index volatility VIX | False |
| CEZ.PR | True | True | 1 | stocks | CEZ.PR | CEZ | False |
| AAPL | True | True | 1 | stocks | AAPL | Apple Inc. | False |
| MSFT | True | True | 1 | stocks | MSFT | Microsoft Corporation | False |
| EUR/USD | True | True | 1 | ecb2 | ecb_exr_eur_spot | Referenční devizový kurz EUR — měsíční průměr (ECB EXR) | False |
| CZK/EUR | True | True | 1 | ecb2 | EXR/M.CZK.EUR.SP00.A | EUR/CZK (ECB EXR) | False |
| USDJPY | True | True |  |  |  |  | False |
| ROA bank | True | True | 1 | ecb2 | CBD2/A.AT.W0.11._Z._Z.A.A.I2004._Z._Z._Z._Z._Z._Z.PC | ROA Rakousko | False |
| ROE bank | True | True | 1 | ecb2 | ecb_cbd2_roe | Návratnost vlastního kapitálu bank (CBD2 ROE, %) | False |
| HICP Spain | True | True | 1 | eurostat | ei_cphi_m | Harmonizovaný index cen | False |
| CPI Spain | True | True | 1 | eurostat | prc_hicp_midx | HICP - měsíční index | False |
| GDP Germany | True | True | 1 | eurostat | namq_10_gdp_b1gq | HDP a hlavní složky — hrubý domácí produkt (B1GQ), čtvrtletně | False |
| 2T repo sazba | True | True | 1 | arad | arad_repo_rate | 2T repo sazba ČNB | False |
| Brent | True | True | 1 | fred | DCOILBRENTEU | Cena ropy Brent | False |
| gold price | True | True | 1 | fred | NASDAQNQUSB55103025 | Index těžby zlata | False |
| natural gas price | True | True | 1 | fred | PNGASEUUSDM | Global price of Natural gas, EU | False |

## Notes

- `sibling_entity_contamination_rate_top3` is the share of exact-entity queries where a sibling entity appears in top 3 instead of the requested entity.
- The metric is intentionally conservative: direct matches from accepted sources are counted before sibling checks.
- This report is a regression guard; it must not be used as production ranking logic.
