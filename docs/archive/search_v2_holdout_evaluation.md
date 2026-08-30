# Search V2 Holdout Evaluation

- Dataset: `evaluation/search_v2_holdout_queries.json`
- Dataset checksum: `25dc5b0513b745d1b6fa14d233ab9569483a9e249c049f05d888804e906a32e2`
- Baseline: `SEARCH_ENGINE_VERSION=v2`, `SEARCH_CATALOG_INDEX=sidecar`, `SEARCH_SEMANTIC_RETRIEVAL_ENABLED=false`

## Summary

| Metric | Value |
|---|---:|
| `query_count` | 28 |
| `precision_at_1` | 0.5714 |
| `precision_at_5` | 0.75 |
| `mrr` | 0.6513 |
| `candidate_recall_at_20` | 0.6071 |
| `candidate_recall_at_50` | 0.6786 |
| `empty_result_rate` | 0.0357 |
| `source_constraint_accuracy` | 1.0 |
| `geo_constraint_accuracy` | 0.88 |
| `explicit_geo_primary_accuracy` | 1.0 |
| `explicit_geo_any_result_accuracy` | 1.0 |
| `dimensioned_geo_resolution_accuracy` | 1.0 |
| `aggregate_geo_accuracy` | 1.0 |
| `geo_answer_availability` | 0.8571 |
| `warm_median_latency_ms` | 101.5 |
| `warm_p95_latency_ms` | 711.0 |

## Per Query

| Query | Hit rank | Top source | Top series | Top title |
|---|---:|---|---|---|
| inflace Madarsko | 1 | data360 | WEF_GCIHH\|WEF_GCIHH_INFLAYRAVG | Inflace, roční % změna |
| core inflation Czech Republic | 5 | oecd4 | economic_outlook_118/CZE/PCORE/_/A | Jádrový index inflace |
| bank ROE Slovakia ECB | 1 | ecb2 | CBD2/A.SK.W0.11._Z._Z.A.A.I2003._Z._Z._Z._Z._Z._Z.PC | ROE - Slovensko |
| urokove sazby Fed | 1 | fred | RIFSPFFNA | Úrokové sazby Fed |
| kurz libra euro ECB | 1 | ecb2 | RAS/M.N.GB.1C.S121.S121.LE.A.FA.R.FK._Z.GBP.XDR.M.N.ALL | Annual growth rate · 1C · GB · LE · Britská libra |
| USD CZK exchange rate |  | imf | IMF\|IMF.STA\|EER\|6.0.0\|HUN.NEER_IX_RY2010_ACW.A | Nominální efektivní směnný kurz Maďarska |
| spot price copper |  | data360 | WB_WDI\|WB_WDI_NY_ADJ_DMIN_CD | Upravené úspory, vyčerpání minerálů (běžné USD) |
| zemni plyn cena Evropa | 1 | commodities | NATURAL_GAS_EUROPE | Natural gas, Europe |
| akcie Komercni banka | 1 | stocks | KOMB.PR | KOMERCNI BANKA |
| S&P 500 index FRED | 1 | fred | SP500 | Index S&P 500 |
| unemployment rate France |  | imf | IMF\|IMF.RES\|WEO\|9.0.0\|FRA.LUR | Míra nezaměstnanosti Francie |
| average wages Slovakia | 1 | data360 | IMF_FSI\|IMF_FSI_FS_HH_IDGW | Mzdy domácností |
| real wages Czech Republic | 1 | oecd4 | economic_outlook_117/CZE/WAGE/_/A | Mzdy v ekonomice |
| industrial production Italy | 2 | data360 | IMF_IFS\|IMF_IFS_AIPMA | Průmyslová výroba v manufaktuře |
| car production Germany | 13 | ecb2 | E11/A.N.DE._Z.S13._Z.N.D.D632._Z._Z.GF02.XDC_R_B1GQ._Z.S.V.N._T | General government · Current prices · Debit (Uses) · Defence · Non transformed data · Social transfers in kind - purchased market production |
| construction output Poland | 1 | eurostat | sts_copr_m_f_total | Stavební produkce — celkem (NACE F, měsíčně) |
| house price index Hungary Eurostat | 1 | eurostat | tipsho40 | Index cen nemovitostí čtvrtletně |
| ceny bytu Praha CSU |  | csu | PRUMCENEMOT1 | Průměrné ceny nemovitostí |
| mortgage interest rate Czech Republic | 2 | ecb2 | ecb_bsi_mortgage_loans_households | Hypoteční úvěry domácnostem (BSI housing loans, mil. EUR) |
| non-performing loans Czech banks | 3 | eurostat | tipsbd10 | Nefunkční úvěry v bankovnictví |
| bank capital ratio Poland | 1 | eurostat | tipsbd30 | Tier-1 kapitálová přiměřenost bank |
| food prices Germany | 2 | imf | IMF\|IMF.STA\|CPI\|5.0.0\|DEU.CPI.CP01.WGT.M | Váha potravin a nápojů Německo |
| retail sales Slovakia | 1 | eurostat | sts_trtu_m_retail_turnover_cultural_goods_retail | Turnover and volume of sales in wholesale and retail trade - monthly data |
| government debt Greece | 8 | ecb2 | GFS/A.N.GR.W0.S13.S1.C.L.F.F3.T._Z.XDC_R_B1GQ._T.S.V.N._T | General government · All original maturities · Consolidated · Debt securities · Greece |
| population on Mars |  |  |  |  |
| bitcoin consumer price index |  | eurostat | enpe_cpi | Index spotřebitelských cen v ENP-Východ |
| vyvoz Cesko do Nemecka | 1 | oecd4 | economic_outlook_117/CZE/XPERF_ANNPCT/_/A | Vývozní výkonnost ČR |
| OECD GDP Japan | 1 | oecd4 | composite_leading_cli/G4E/RS/_Z/M | Referenční série (HDP) čtyř zemí EU |

## Sensitive Query Check

### jadrova inflace Cesko

| Rank | Source | Series | Title | Geo | Concept | Measure | Sector | Scope | Nominal/real |
|---:|---|---|---|---|---|---|---|---|---|
| 1 | oecd4 | economic_outlook_118/CZE/PCORE_YTYPCT/_/A | Jádrová inflace | CZ | core_inflation | core_inflation |  |  |  |
| 2 | oecd4 | economic_outlook_117/CZE/PCORE_YTYPCT/_/A | Jádrová inflace | CZ | core_inflation | core_inflation |  |  |  |
| 3 | ecb2 | ecb_icp_inflace_jadrova | Jádrová inflace HICP — bez energie a potravin (ECB ICP) |  | core_inflation | core_inflation |  |  |  |
| 4 | oecd4 | economic_outlook_117/CZE/PCORE/_/A | Jádrový index inflace | CZ | core_inflation | core_inflation |  |  |  |
| 5 | oecd4 | economic_outlook_118/CZE/PCORE/_/A | Jádrový index inflace | CZ | core_inflation | core_inflation |  |  |  |

Eurostat tipscp10 means 'Core inflation differential vis-a-vis EA' in the Macroeconomic Imbalance Procedure table. It is related to core inflation but is a differential against the euro area, not a direct Czech core-inflation level. ECB/OECD core-inflation rows are closer direct primary answers.

### mzdy v Cesku

| Rank | Source | Series | Title | Geo | Concept | Measure | Sector | Scope | Nominal/real |
|---:|---|---|---|---|---|---|---|---|---|
| 1 | oecd4 | economic_outlook_118/CZE/WAGE/_/A | Mzdy v ekonomice | CZ | average_wages | level | total_economy |  | nominal |
| 2 | arad | 1117:SGFSDBY008 | mzdy a platy | CZ | average_wages | level | total_economy |  | nominal |
| 3 | oecd4 | economic_outlook_117/CZE/WAGE/_/A | Mzdy v ekonomice | CZ | average_wages | level | total_economy |  | nominal |
| 4 | oecd4 | kei_short_term/CZE/H_EARN/C/A | Hodinové mzdy | CZ | average_wages | level | total_economy |  | nominal |
| 5 | csu | MZDKQT1 | Průměrné mzdy - časová řada | Stát | average_wages | level | total_economy |  | nominal |

Top result metadata: institutional_sector=total_economy, scope=blank, measure_type=level, nominal_real=nominal, unit=SAVG, frequency=blank.

### zisk bank v Cesku

| Rank | Source | Series | Title | Geo | Concept | Measure | Sector | Scope | Nominal/real |
|---:|---|---|---|---|---|---|---|---|---|
| 1 | data360 | IMF_FSI\|IMF_FSI_FS_ODX_INAET | Čistý zisk bank | GLOBAL | bank_net_profit | net_profit | banks |  |  |
| 2 | data360 | IMF_FSI\|IMF_FSI_FS_ODX_INBT | Zisk bank před daněmi | GLOBAL | bank_net_profit | net_profit | banks |  |  |
| 3 | data360 | IMF_BOP\|IMF_BOP_BFPEONF_BP6 | BOP, nefinanční korporace | GLOBAL | net_interest_income | income | banks |  |  |
| 4 | data360 | IMF_FSI\|IMF_FSI_FS_ODX_INIP | Prorated earnings bank | GLOBAL | bank_net_profit | net_profit | banks |  |  |
| 5 | data360 | WEF_GCIHH\|WEF_GCIHH_EOSQ087 | Zdravotní stav bank | GLOBAL | net_interest_income | income | banks |  |  |

### sazby CNB

| Rank | Source | Series | Title | Geo | Concept | Measure | Sector | Scope | Nominal/real |
|---:|---|---|---|---|---|---|---|---|---|
| 1 | arad | 1119:SFTP02M11 | Diskontní sazba | CZ | central_bank_policy_rate | central_bank_policy_rate | central_bank |  |  |
| 2 | arad | 1119:SFTP03M11 | Lombardní sazba | CZ | central_bank_policy_rate | central_bank_policy_rate | central_bank |  |  |
| 3 | arad | 1169:SFTP02M11 | Diskontní sazba | CZ | central_bank_policy_rate | central_bank_policy_rate | central_bank |  |  |
| 4 | arad | 1169:SFTP03M11 | Lombardní sazba | CZ | central_bank_policy_rate | central_bank_policy_rate | central_bank |  |  |
| 5 | arad | 1119:SFTP01M11 | 2T repo sazba | CZ | central_bank_policy_rate | central_bank_policy_rate | central_bank |  |  |

### prumyslova vyroba Nemecko

| Rank | Source | Series | Title | Geo | Concept | Measure | Sector | Scope | Nominal/real |
|---:|---|---|---|---|---|---|---|---|---|
| 1 | data360 | IMF_IFS\|IMF_IFS_AIP | Průmyslová výroba | GLOBAL | industrial_production | industrial_production_index |  |  |  |
| 2 | data360 | IMF_IFS\|IMF_IFS_AIPEE | Průmyslová výroba energie | GLOBAL | industrial_production | industrial_production_index |  |  |  |
| 3 | data360 | IMF_IFS\|IMF_IFS_AIPCO | Průmyslová výroba ve stavebnictví | GLOBAL | industrial_production | industrial_production_index |  |  |  |
| 4 | data360 | IMF_IFS\|IMF_IFS_AIPMI | Průmyslová výroba v těžbě | GLOBAL | industrial_production | industrial_production_index |  |  |  |
| 5 | eurostat | ei_is_m_vtgfix | Průmyslová výroba - měsíční data |  | industrial_production | industrial_production_index |  |  |  |

### vyroba automobilu Polsko

| Rank | Source | Series | Title | Geo | Concept | Measure | Sector | Scope | Nominal/real |
|---:|---|---|---|---|---|---|---|---|---|
| 1 | eurostat | sts_inpr_m_c29 | Průmyslová produkce — C29 (měsíčně, index) |  | industrial_production | industrial_production_index |  |  |  |

### ceny nemovitosti Slovensko

| Rank | Source | Series | Title | Geo | Concept | Measure | Sector | Scope | Nominal/real |
|---:|---|---|---|---|---|---|---|---|---|
| 1 | data360 | IMF_FSI\|IMF_FSI_FSREPCR | Ceny komerčních nemovitostí | GLOBAL | house_price_index | price_index |  |  | real |
| 2 | csu | PRUMCENEMOT1 | Průměrné ceny nemovitostí | Stát, Kraj | house_price_index | house_price_index |  |  |  |
| 3 | csu | WCEN04T01 | Kupní ceny nemovitostí | Stát, Kraj | house_price_index | house_price_index |  |  |  |
| 4 | data360 | IMF_FSI\|IMF_FSI_FSREPRR | Ceny rezidenčních nemovitostí | GLOBAL | house_price_index | price_index |  |  | real |
| 5 | eurostat | teicp270 | Index cen nemovitostí (2015 = 100) - čtvrtletní data |  | house_price_index | house_price_index |  |  |  |

### cena zlata

| Rank | Source | Series | Title | Geo | Concept | Measure | Sector | Scope | Nominal/real |
|---:|---|---|---|---|---|---|---|---|---|
| 1 | fred | NASDAQQGLDI | Index zlata | US | commodity_spot_price | market_price |  | market |  |
| 2 | commodities | GOLD | Gold |  |  |  |  |  |  |
| 3 | data360 | IMF_IFS\|IMF_IFS_EDG_XDR_OZT | Cena zlata v SDR za unci | GLOBAL | commodity_spot_price | market_price |  | market |  |
| 4 | data360 | IMF_IFS\|IMF_IFS_EDG_USD_OZT | Cena zlata v USD za unci | GLOBAL | commodity_spot_price | market_price |  | market |  |
| 5 | data360 | WB_WDI\|WB_WDI_NY_ADJ_DMIN_CD | Upravené úspory, vyčerpání minerálů (běžné USD) | GLOBAL | commodity_spot_price | market_price |  | market | nominal |

### akcie CEZ

| Rank | Source | Series | Title | Geo | Concept | Measure | Sector | Scope | Nominal/real |
|---:|---|---|---|---|---|---|---|---|---|
| 1 | stocks | CEZ.PR | CEZ |  | equity_market_price | market_price |  |  |  |
| 2 | stocks | CEZK.XD | CEZ AS |  | equity_market_price | market_price |  |  |  |
