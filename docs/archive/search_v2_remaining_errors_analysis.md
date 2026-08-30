# Search V2 Remaining Errors Analysis

Baseline: `SEARCH_CATALOG_INDEX=sidecar`, `SEARCH_SEMANTIC_RETRIEVAL_ENABLED=false`.
Audit běží pouze nad sidecar + FTS + deterministickým rerankingem; LLM reranking není zapnutý.

## Candidate Funnel

`retrieved_raw -> deduplicated_unique -> after_hard_constraints -> after_source_balancing -> after_candidate_limit -> sent_to_deterministic_reranker -> sent_to_preview -> final_results`

`after_source_balancing` a `after_candidate_limit` mají u současné implementace stejnou hodnotu, protože source balancing a limit 240 jsou aplikované v jednom merge kroku.

## Metrics

- Candidate Recall@20: `0.7`
- Candidate Recall@50: `0.8`
- Precision@1: `0.675`
- Precision@5: `0.9`
- MRR: `0.775`
- Empty result rate: `0.05`
- Source constraint accuracy: `0.8`
- Geo constraint accuracy: `1.0`
- Warm median ms: `78.5`
- Warm P95 ms: `109.0`

## Current Classification

- `A` / `catalog_coverage`: 1
- `ok` / `ok`: 9

## Before / After Top 1

| Query | Before top 1 | After top 1 | Current category | Evidence |
|---|---|---|---|---|
| jadrova inflace Cesko | eurostat:rail_tf_ns20_cz Železniční doprava Česko | eurostat:tipscp10 Jádrová inflace - rozdíl vůči EA | ok | raw=True, sidecar=True, rank=1 |
| realne mzdy CR | csu:MZDKQT1 Průměrné mzdy - časová řada | csu:MZDKQT1 Průměrné mzdy - časová řada | A | raw=False, sidecar=False, rank= |
| mzdy v Cesku | arad:1117:SGFSDBY008 mzdy a platy | arad:1117:SGFSDBY008 mzdy a platy | ok | raw=True, sidecar=True, rank=1 |
| zisk bank v Cesku | arad:1022:DVYBAQ602 čisté úrokové výnosy | data360:IMF_FSI/IMF_FSI_FS_ODX_INAET Čistý zisk bank | ok | raw=True, sidecar=True, rank=1 |
| sazby CNB | arad:1146 Úrokové sazby ostatních nových úvěrů | arad:1119:SFTP02M11 Diskontní sazba | ok | raw=True, sidecar=True, rank=1 |
| prumyslova vyroba Nemecko | imf:IMF/IMF.STA/RE/2.0.1/DEU.EG_GWH.BIOENERGY.A Výroba elektřiny Německo | data360:IMF_IFS/IMF_IFS_AIP Průmyslová výroba | ok | raw=True, sidecar=True, rank=1 |
| vyroba automobilu Polsko | imf:IMF/IMF.STA/RE/2.0.1/POL.EG_GWH.BIOENERGY.A Výroba elektřiny Polsko | eurostat:sts_inpr_m_c29 Průmyslová produkce — C29 (měsíčně, index) | ok | raw=True, sidecar=True, rank=1 |
| ceny nemovitosti Slovensko | eurostat:avia_par_sk Letecká doprava Slovensko | data360:IMF_FSI/IMF_FSI_FSREPCR Ceny komerčních nemovitostí | ok | raw=True, sidecar=True, rank=1 |
| cena zlata | data360:IMF_IFS/IMF_IFS_EDG_XDR_OZT Cena zlata v SDR za unci | data360:IMF_IFS/IMF_IFS_EDG_XDR_OZT Cena zlata v SDR za unci | ok | raw=True, sidecar=True, rank=1 |
| akcie CEZ | stocks:CEZ.PR CEZ | stocks:CEZ.PR CEZ | ok | raw=True, sidecar=False, rank=1 |

## Ten Query Detail

| Query | Category | Raw | Sidecar | Raw rank | Pool rank | Rerank rank | Top metadata |
|---|---|---:|---:|---:|---:|---:|---|
| jadrova inflace Cesko | ok | True | True |  | 3 | 1 | core_inflation / core_inflation / macro |
| realne mzdy CR | A | False | False |  |  |  | average_wages / level / other |
| mzdy v Cesku | ok | True | True | 1 | 1 | 1 | average_wages / level / other |
| zisk bank v Cesku | ok | True | True |  | 6 | 1 | bank_net_profit / net_profit / banking |
| sazby CNB | ok | True | True | 15 | 15 | 1 | central_bank_policy_rate / central_bank_policy_rate / macro |
| prumyslova vyroba Nemecko | ok | True | True | 1 | 1 | 1 | industrial_production / industrial_production_index / sectoral |
| vyroba automobilu Polsko | ok | True | True | 1 | 1 | 1 | industrial_production / industrial_production_index / sectoral |
| ceny nemovitosti Slovensko | ok | True | True | 1 | 1 | 1 | house_price_index / price_index / real_estate |
| cena zlata | ok | True | True |  |  | 1 | commodity_spot_price / market_price / other |
| akcie CEZ | ok | True | False | 1 | 1 | 1 | equity_market_price / market_price / markets_equities |