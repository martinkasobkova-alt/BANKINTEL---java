# Search V2 Retrieval Diagnosis

- Mode: `metadata_only`
- Queries: 10
- Retrieval failure share: 0.6666666666666666
- Reranking failure share: 0.3333333333333333

## Variant Summary

| Variant | Candidate R@20 | Candidate R@50 | Candidate R@100 | P@5 | MRR | nDCG@10 | Empty | Median ms | P95 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A_legacy_fallback | 0.5 | 0.6 | 0.7 | 0.5 | 0.62 | 0.6472859834964251 | 0 | 4029.0 | 6129.0 |
| C_sidecar_fallback | 0.36666666666666664 | 0.4666666666666666 | 0.5 | 0.68 | 0.85 | 0.868632950258409 | 0 | 2133.0 | 2573.0 |
| B_legacy_llm | 0.8 | 0.9 | 0.9 | 0.58 | 0.7333333333333333 | 0.7509332663849216 | 0 | 29408.0 | 41220.0 |
| D_sidecar_llm | 0.4666666666666666 | 0.6 | 0.6 | 0.6 | 0.6533333333333333 | 0.7382290183004319 | 1 | 28141.0 | 53097.0 |

## Failure Buckets (A legacy fallback)

`{relevant_series_retrieved_but_reranked_low=1, relevant_series_retrieved_but_truncated=1, relevant_series_not_retrieved=1}`

## Per Query Buckets

| ID | Query | A bucket | C bucket | A top | C top |
|---|---|---|---|---|---|
| cz_bank_profit | zisk bank v Cesku | ok | ok | Bilance obchodních bank - pasiva:Měsíční, Zisk(+) / ztráta (-) běžného období, Nerezidenti, Všechny měny celkem | Čistý zisk bank |
| cz_bank_roa | ROA ceskych bank | relevant_series_retrieved_but_reranked_low | ok | Balance Sheets and Income Statements, Other financial corporations, Life Insurance Corporations, Data series for compiling FSIs: ROA and ROE, Average capital and reserves | ROA eurozóny |
| ea_bank_roe | ROE bank v eurozone | ok | ok | Return on equity of banks | Rentabilita bank (ROE) |
| eu_bank_capital_ratio | kapitalova primerenost bank v EU | relevant_series_retrieved_but_truncated | ok | Backward looking three months · Credit standards · Loan supply · Diffusion index · All banks · Austria | Tier-1 kapitálová přiměřenost bank |
| bank_net_interest_income | cisty urokovy vynos bank | ok | ok | Backward looking three months · Credit standards · Loan supply · Diffusion index · All banks · Austria | 10Y Bund výnos — Bankovnictví a finance |
| cz_inflation | inflace CR | ok | ok | Inflation, consumer prices (annual % growth) | HICP inflace — celkový index, meziroční tempo změny (měsíčně) |
| cz_core_inflation | jadrova inflace Cesko | ok | ok | Harmonizovaný index spotřebitelských cen (HICP) - měsíční | Jádrová inflace HICP — bez energie a potravin (ECB ICP) |
| es_inflation_eurostat | inflace Spanelsko Eurostat | ok | ok | HICP - monthly data (index) (1996-2025) | HICP inflace — celkový index, meziroční tempo změny (měsíčně) |
| pl_food_prices | ceny potravin v Polsku | relevant_series_not_retrieved | ok | West Bank and Gaza · Potraviny a nealkoholické nápoje — Váha | Ceny potravin a tropických nápojů |
| pl_gdp | HDP Polska | ok | ok | Gross domestic product (GDP) at market prices - annual data | Růst HDP |
