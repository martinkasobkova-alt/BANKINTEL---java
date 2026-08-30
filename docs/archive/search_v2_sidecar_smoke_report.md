# Search V2 Sidecar Smoke Report

## Latency

- metadata cold: `{'min': 120.0, 'median': 412.0, 'p95': 1368.0, 'max': 1404.0}`
- metadata warm: `{'min': 90.0, 'median': 345.0, 'p95': 1379.0, 'max': 1402.0}`
- top preview: `{'min': 757.0, 'median': 8102.5, 'p95': 17378.0, 'max': 17409.0}`

## Phase Latency

- metadata cold: `{'planner_ms': {'min': 17.0, 'median': 31.5, 'p95': 64.0, 'max': 73.0}, 'fts_ms': {'min': 19.0, 'median': 270.5, 'p95': 1260.0, 'max': 1262.0}, 'retrieval_cache_wrapper_ms': {'min': 19.0, 'median': 270.5, 'p95': 1260.0, 'max': 1262.0}, 'reranker_ms': {'min': 2.0, 'median': 66.5, 'p95': 99.0, 'max': 114.0}, 'preview_verification_ms': {'min': 0.0, 'median': 0.0, 'p95': 0.0, 'max': 0.0}}`
- top preview: `{'planner_ms': {'min': 19.0, 'median': 31.5, 'p95': 49.0, 'max': 59.0}, 'fts_ms': {'min': 18.0, 'median': 203.0, 'p95': 1253.0, 'max': 1258.0}, 'retrieval_cache_wrapper_ms': {'min': 18.0, 'median': 203.0, 'p95': 1253.0, 'max': 1258.0}, 'reranker_ms': {'min': 2.0, 'median': 73.0, 'p95': 108.0, 'max': 114.0}, 'preview_verification_ms': {'min': 398.0, 'median': 8006.5, 'p95': 16028.0, 'max': 16032.0}}`

## Geo Tests

| Query | Expected | OK | Top geo | Top source | Top title |
|---|---|---:|---|---|---|
| roa bank |  | True | AT | ecb2 | ROA Rakousko |
| roa bank Cesko | CZ | False | CZ | ecb2 | Return on assets (ROA) · Czech Republic · Domestic banking groups and stand-alone banks · Q (%) |
| ROA bank v eurozone | U2 | True | U2 | ecb2 | ROA eurozóny |
| ROA bank Rakousko | AT | False | AT | ecb2 | ROA Rakousko |
| ROA bank Polsko | PL | False | GLOBAL | data360 | ROA penzijních fondů |

- geo_hard_constraint_ok: `False`

- geo_answer_available_ok: `True`

### Geo Failure Buckets

- `roa bank Cesko`: geo_hard_constraint_violation counts={'retrieved_raw': 388, 'deduplicated_unique': 153, 'after_hard_constraints': 153, 'after_source_balancing': 153, 'after_candidate_limit': 153, 'sent_to_deterministic_reranker': 60, 'sent_to_llm_reranker': 0, 'sent_to_preview': 0, 'preview_success': 0, 'preview_failed': 0, 'unique_preview_requests': 0, 'final_results': 10, 'candidate_limit_note': 'source_balancing_and_candidate_limit_are_applied_in_one_merge_step'}
- `ROA bank Rakousko`: geo_hard_constraint_violation counts={'retrieved_raw': 584, 'deduplicated_unique': 152, 'after_hard_constraints': 152, 'after_source_balancing': 152, 'after_candidate_limit': 152, 'sent_to_deterministic_reranker': 60, 'sent_to_llm_reranker': 0, 'sent_to_preview': 0, 'preview_success': 0, 'preview_failed': 0, 'unique_preview_requests': 0, 'final_results': 10, 'candidate_limit_note': 'source_balancing_and_candidate_limit_are_applied_in_one_merge_step'}
- `ROA bank Polsko`: geo_hard_constraint_violation counts={'retrieved_raw': 386, 'deduplicated_unique': 152, 'after_hard_constraints': 152, 'after_source_balancing': 152, 'after_candidate_limit': 152, 'sent_to_deterministic_reranker': 60, 'sent_to_llm_reranker': 0, 'sent_to_preview': 0, 'preview_success': 0, 'preview_failed': 0, 'unique_preview_requests': 0, 'final_results': 10, 'candidate_limit_note': 'source_balancing_and_candidate_limit_are_applied_in_one_merge_step'}

## Source Tests

| Query | Expected | OK | Count | Top source | Top title |
|---|---|---:|---:|---|---|
| ROA bank pouze ECB | ecb2 | True | 10 | ecb2 | ROA Rakousko |
| ROA bank pouze ARAD | arad | True | 10 | arad | daně |
| inflace Spanelsko pouze Eurostat | eurostat | True | 10 | eurostat | HICP - měsíční míra změny |
| HDP Polska pouze IMF | imf | True | 10 | imf | HDP deflátor Polsko |
| EUR/USD pouze ECB | ecb2 | True | 10 | ecb2 | Australian dollar · Average · Spot (Euro) |

## Wrong Top 1 Candidates

- `inflace CR`: wrong_source -> oecd4 / Hlavní inflace
- `jadrova inflace Cesko`: wrong_source -> oecd4 / Jádrová inflace
- `realne mzdy CR`: wrong_source -> ecb2 / LabourCostIndex - wages and salaries · Real estate activities
- `cisty urokovy vynos bank`: wrong_source -> data360 / Čistý úrokový příjem
- `prumyslova vyroba Nemecko`: wrong_source -> data360 / Průmyslová výroba
- `HDP Polska pouze IMF`: forbidden_concept -> imf / HDP deflátor Polsko

## Empty Results

- None.

## Candidate Pool Diagnostics

- No provisional gold miss was proven absent from the merged top-200 pool.

### Candidate in pool but ranked below top 1
- `inflace CR`: wrong_source -> oecd4 / Hlavní inflace
- `jadrova inflace Cesko`: wrong_source -> oecd4 / Jádrová inflace
- `realne mzdy CR`: wrong_source -> ecb2 / LabourCostIndex - wages and salaries · Real estate activities
- `cisty urokovy vynos bank`: wrong_source -> data360 / Čistý úrokový příjem
- `prumyslova vyroba Nemecko`: wrong_source -> data360 / Průmyslová výroba
- `HDP Polska pouze IMF`: forbidden_concept -> imf / HDP deflátor Polsko

## Smoke Top 10


### inflace CR

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | oecd4 | CZ | Hlavní inflace | economic_outlook_118/CZE/CPI_YTYPCT/_/A | primary | candidate |
| 2 | oecd4 | CZ | Hlavní inflace v ČR | economic_outlook_117/CZE/CPI_YTYPCT/_/A | primary | candidate |
| 3 | data360 | GLOBAL | Inflace, roční % změna | WEF_GCIHH/WEF_GCIHH_INFLAYRAVG | primary | candidate |
| 4 | data360 | GLOBAL | Míra inflace, % změna | WB_MPO/WB_MPO_FPCPITOTLXNZ | primary | candidate |
| 5 | data360 | GLOBAL | Inflace cen potravin | FAO_CP/FAO_CP_23014 | primary | candidate |
| 6 | eurostat |  | HICP - váhy zemí | prc_hicp_cow | primary | candidate |
| 7 | oecd4 |  | Cenové indexy (CPI/HICP) | OECD4/consumer_prices_cpi/dataset | primary | candidate |
| 8 | data360 | GLOBAL | Inflace, spotřebitelské ceny | WB_WDI/WB_WDI_FP_CPI_TOTL_ZG | primary | candidate |
| 9 | data360 | GLOBAL | Inflace, spotřebitelské ceny | WB_GS/WB_GS_FP_CPI_TOTL_ZG | primary | candidate |
| 10 | eurostat |  | HICP - váhy položek | prc_hicp_inw | primary | candidate |

### jadrova inflace Cesko

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | oecd4 | CZ | Jádrová inflace | economic_outlook_118/CZE/PCORE_YTYPCT/_/A | primary | candidate |
| 2 | oecd4 | CZ | Jádrová inflace | economic_outlook_117/CZE/PCORE_YTYPCT/_/A | primary | candidate |
| 3 | ecb2 |  | Jádrová inflace HICP — bez energie a potravin (ECB ICP) | ecb_icp_inflace_jadrova | primary | candidate |
| 4 | oecd4 | CZ | Jádrový index inflace | economic_outlook_117/CZE/PCORE/_/A | primary | candidate |
| 5 | oecd4 | CZ | Jádrový index inflace | economic_outlook_118/CZE/PCORE/_/A | primary | candidate |
| 6 | eurostat |  | Jádrová inflace - rozdíl vůči EA | tipscp10 | context | candidate |

### realne mzdy CR

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | ecb2 | U6 | LabourCostIndex - wages and salaries · Real estate activities | LCI/Q.I9.N.LCI_WAG.L | primary | candidate |
| 2 | ecb2 | U6 | LabourCostIndex - labour costs other than wages and salaries · Real estate activities | LCI/Q.I9.N.LCI_O.L | primary | candidate |
| 3 | ecb2 | U6 | LabourCostIndex - wages and salaries · Financial and insurance activities; real estate activities… | LCI/Q.I9.N.LCI_WAG.KTN | primary | candidate |
| 4 | ecb2 | U6 | LabourCostIndex - wages and salaries · Calendar and seasonally adjusted data · Real estate activities | LCI/Q.I9.Y.LCI_WAG.L | primary | candidate |
| 5 | ecb2 | U6 | LabourCostIndex - wages and salaries · Calendar adjusted data, not seasonally adjusted · Real estate activities | LCI/Q.I9.W.LCI_WAG.L | primary | candidate |
| 6 | ecb2 | U6 | LabourCostIndex - labour costs other than wages and salaries · Financial and insurance activities; real estate activities… | LCI/Q.I9.N.LCI_O.KTN | primary | candidate |
| 7 | ecb2 | U6 | LabourCostIndex - labour costs other than wages and salaries · Calendar and seasonally adjusted data · Real estate activities | LCI/Q.I9.Y.LCI_O.L | primary | candidate |
| 8 | ecb2 | U6 | LabourCostIndex - wages and salaries · Calendar and seasonally adjusted data · Financial and insurance activities; real estate activities… | LCI/Q.I9.Y.LCI_WAG.KTN | primary | candidate |
| 9 | ecb2 | U6 | LabourCostIndex - labour costs other than wages and salaries · Calendar adjusted data, not seasonally adjusted · Real estate activities | LCI/Q.I9.W.LCI_O.L | primary | candidate |
| 10 | ecb2 | U6 | LabourCostIndex - wages and salaries · Calendar adjusted data, not seasonally adjusted · Financial and insurance activities; real estate activities… | LCI/Q.I9.W.LCI_WAG.KTN | primary | candidate |

### mzdy v Cesku

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | oecd4 | CZ | Mzdy v ekonomice | economic_outlook_118/CZE/WAGE/_/A | primary | candidate |
| 2 | arad | CZ | mzdy a platy | 1117:SGFSDBY008 | primary | candidate |
| 3 | oecd4 | CZ | Mzdy v ekonomice | economic_outlook_117/CZE/WAGE/_/A | primary | candidate |
| 4 | oecd4 | CZ | Hodinové mzdy | kei_short_term/CZE/H_EARN/C/A | primary | candidate |
| 5 | csu | Stát | Průměrné mzdy - časová řada | MZDKQT1 | primary | candidate |
| 6 | csu | CZ | Průměrné mzdy za ČR | MZDCRRT1 | primary | candidate |
| 7 | csu | CZ | Průměrné mzdy dle sekce CZ-NACE | MZDKQT2 | primary | candidate |
| 8 | csu | CZ | Průměrné mzdy dle sekce CZ-NACE | MZDRT2 | primary | candidate |
| 9 | csu | CZ | Průměrné mzdy dle sekce CZ-NACE | MZDQ1T2 | primary | candidate |
| 10 | csu | CZ | Průměrné mzdy dle sekce CZ-NACE | MZDCRRT2 | primary | candidate |

### cisty urokovy vynos bank

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | data360 | GLOBAL | Čistý úrokový příjem | IMF_FSI/IMF_FSI_FS_ODX_IIN | primary | candidate |
| 2 | fred | US | Index poskytovatelů finančních dat (čistý výnos) | NASDAQNQUSB30201030N | primary | candidate |
| 3 | ecb2 | DE | 10Y Bund výnos — Bankovnictví a finance | ecb:DE:vynosy_10let | primary | candidate |
| 4 | data360 | GLOBAL | Úrokový příjem bank | IMF_FSI/IMF_FSI_FS_ODX_II | primary | candidate |
| 5 | fred | US | Index financí a úvěrů USA (čistý výnos) | NASDAQNQUSB302010N | primary | candidate |
| 6 | arad | CZ | Úrokové sazby bank — NFC | arad_bank_interest_rates_nfc | primary | candidate |
| 7 | eurostat |  | Tier-1 kapitálová přiměřenost bank | tipsbd30 | primary | candidate |
| 8 | csu | Stát | Náklady bank | FIN03BANKNAK | primary | candidate |
| 9 | bis | WS | BIS credit to private sector — Bankovnictví a finance | BIS/WS_TC/META | primary | candidate |
| 10 | fred | US | Index investičních služeb USA - čistý výnos | NASDAQNQUSS30202015N | primary | candidate |

### zisk bank v Cesku

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | data360 | GLOBAL | Čistý zisk bank | IMF_FSI/IMF_FSI_FS_ODX_INAET | primary | candidate |
| 2 | data360 | GLOBAL | Zisk bank před daněmi | IMF_FSI/IMF_FSI_FS_ODX_INBT | primary | candidate |
| 3 | data360 | GLOBAL | Prorated earnings bank | IMF_FSI/IMF_FSI_FS_ODX_INIP | primary | candidate |
| 4 | data360 | GLOBAL | BOP, nefinanční korporace | IMF_BOP/IMF_BOP_BFPEONF_BP6 | primary | candidate |
| 5 | data360 | GLOBAL | Nákladové příjmy bank | IMF_FSI/IMF_FSI_FS_ODX_INI | primary | candidate |
| 6 | data360 | GLOBAL | Ostatní příjmy bank | IMF_FSI/IMF_FSI_FS_ODX_INIO | primary | candidate |
| 7 | data360 | GLOBAL | Poplatky a provize bank | IMF_FSI/IMF_FSI_FS_ODX_INIF | primary | candidate |
| 8 | data360 | GLOBAL | Obchodní příjmy bank | IMF_FSI/IMF_FSI_FS_ODX_INIGL_FSTI | primary | candidate |
| 9 | data360 | GLOBAL | Výkazy a účetnictví bank | IMF_FSI/IMF_FSI_FS_ODX_DP | primary | candidate |
| 10 | data360 | GLOBAL | Hrubý příjem bank | IMF_FSI/IMF_FSI_FS_ODX_IG | primary | candidate |

### nove hypoteky Cesko

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | ecb2 |  | Hypoteční sazba — nové úvěry na bydlení (MIR, měsíčně) | ecb_mir_sazba_hypoteky | primary | candidate |
| 2 | csu | CZ | Nové průmyslové zakázky měsíční meziroční bez očištění | PRU10AT1 | primary | candidate |
| 3 | ecb2 |  | Úroková sazba — nové úvěry nefinančním podnikům (MIR) | ecb_mir_sazba_uvery_podniky | primary | candidate |
| 4 | arad | CZ | Úrokové sazby hypoték stavebních spořitelen | 1147 | primary | candidate |
| 5 | arad | CZ | Statistika úrokových sazeb - nové obchody - úvěry:Měsíční, Objem, Domácnosti + NISD, CZK, Čisté nové úvěry včetně navýšení, Banky včetně stavebních spořitelen | 1146:SMIRNOOBUVMOBJ301CZK013111 | primary | candidate |
| 6 | arad | CZ | Statistika úrokových sazeb - nové obchody - úvěry:Měsíční, Úroková sazba, Domácnosti + NISD, CZK, Čisté nové úvěry včetně navýšení, Banky včetně stavebních spořitelen | 1146:SMIRNOOBUVMIRS301CZK013111 | primary | candidate |
| 7 | arad | CZ | Statistika úrokových sazeb - nové obchody - úvěry:Měsíční, Objem, Domácnosti + NISD, Na bydlení , CZK, Čisté nové úvěry včetně navýšení, Banky včetně stavebních spořitelen | 1146:SMIRNOOBUVMOBJ305CZK013111 | primary | candidate |
| 8 | arad | CZ | Statistika úrokových sazeb - nové obchody - úvěry:Měsíční, Objem, Domácnosti + NISD, Ostatní úvěry, CZK, Čisté nové úvěry včetně navýšení, Banky včetně stavebních spořitelen | 1146:SMIRNOOBUVMOBJ325CZK013111 | primary | candidate |
| 9 | arad | CZ | Statistika úrokových sazeb - nové obchody - úvěry:Měsíční, Úroková sazba, Domácnosti + NISD, Na bydlení , CZK, Čisté nové úvěry včetně navýšení, Banky včetně stavebních spořitelen | 1146:SMIRNOOBUVMIRS305CZK013111 | primary | candidate |
| 10 | arad | CZ | Statistika úrokových sazeb - nové obchody - úvěry:Měsíční, Úroková sazba, Domácnosti + NISD, Ostatní úvěry, CZK, Čisté nové úvěry včetně navýšení, Banky včetně stavebních spořitelen | 1146:SMIRNOOBUVMIRS325CZK013111 | primary | candidate |

### sazby CNB

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | arad | CZ | Diskontní sazba | 1119:SFTP02M11 | primary | candidate |
| 2 | arad | CZ | Lombardní sazba | 1119:SFTP03M11 | primary | candidate |
| 3 | arad | CZ | Diskontní sazba | 1169:SFTP02M11 | primary | candidate |
| 4 | arad | CZ | Lombardní sazba | 1169:SFTP03M11 | primary | candidate |
| 5 | arad | CZ | 2T repo sazba | 1119:SFTP01M11 | primary | candidate |
| 6 | arad | CZ | ČNB 2T repo sazba | 1169:SFTP01M11 | primary | candidate |
| 7 | arad | CZ | Lombardní sazba | 1169 | primary | candidate |
| 8 | arad | CZ | Diskontní sazba ČNB | arad_discount_rate | primary | candidate |
| 9 | arad | CZ | Lombardní sazba ČNB | arad_lombard_rate | primary | candidate |
| 10 | arad | CZ | 2T repo sazba ČNB | arad_repo_rate | primary | candidate |

### sazby CNB a hypoteky

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | arad | CZ | Diskontní sazba ČNB | arad_discount_rate | primary | candidate |
| 2 | arad | CZ | Diskontní sazba | 1119:SFTP02M11 | primary | candidate |
| 3 | arad | CZ | Lombardní sazba ČNB | arad_lombard_rate | primary | candidate |
| 4 | arad | CZ | Lombardní sazba | 1119:SFTP03M11 | primary | candidate |
| 5 | arad | CZ | 2T repo sazba ČNB | arad_repo_rate | primary | candidate |
| 6 | arad | CZ | Diskontní sazba | 1169:SFTP02M11 | primary | candidate |
| 7 | arad | CZ | Lombardní sazba | 1169 | primary | candidate |
| 8 | arad | CZ | Lombardní sazba | 1169:SFTP03M11 | primary | candidate |
| 9 | arad | CZ | ČNB 2T repo sazba | 1169:SFTP01M11 | primary | candidate |
| 10 | arad | CZ | 2T repo sazba | 1119:SFTP01M11 | primary | candidate |

### prumyslova vyroba Nemecko

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | data360 | GLOBAL | Průmyslová výroba | IMF_IFS/IMF_IFS_AIP | primary | candidate |
| 2 | data360 | GLOBAL | Průmyslová výroba energie | IMF_IFS/IMF_IFS_AIPEE | primary | candidate |
| 3 | data360 | GLOBAL | Průmyslová výroba ve stavebnictví | IMF_IFS/IMF_IFS_AIPCO | primary | candidate |
| 4 | data360 | GLOBAL | Průmyslová výroba v těžbě | IMF_IFS/IMF_IFS_AIPMI | primary | candidate |
| 5 | eurostat |  | Průmyslová výroba - měsíční data | ei_is_m_vtgfix | primary | candidate |
| 6 | eurostat |  | Průmyslová výroba - měsíční data | ei_is_m_vtg | primary | candidate |
| 7 | eurostat |  | Výroba v průmyslu | sts_inpr_a | primary | candidate |
| 8 | eurostat |  | Průmyslová produkce — zpracovatelský průmysl celkem (B-E36), měsíčně | sts_inpr_m_total | primary | candidate |
| 9 | eurostat |  | Průmyslová produkce — zpracovatelský průmysl NACE C (měsíčně) | sts_inpr_m_manufacturing_total | primary | candidate |
| 10 | eurostat |  | Průmyslová produkce — C291 (měsíčně, index) | sts_inpr_m_c291 | primary | candidate |

### vyroba automobilu Polsko

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | eurostat |  | Průmyslová produkce — C29 (měsíčně, index) | sts_inpr_m_c29 | primary | candidate |

### ceny nemovitosti Slovensko

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | data360 | GLOBAL | Ceny komerčních nemovitostí | IMF_FSI/IMF_FSI_FSREPCR | primary | candidate |
| 2 | csu | Stát, Kraj | Průměrné ceny nemovitostí | PRUMCENEMOT1 | primary | candidate |
| 3 | csu | Stát, Kraj | Kupní ceny nemovitostí | WCEN04T01 | primary | candidate |
| 4 | data360 | GLOBAL | Ceny rezidenčních nemovitostí | IMF_FSI/IMF_FSI_FSREPRR | primary | candidate |
| 5 | eurostat |  | Index cen nemovitostí (2015 = 100) - čtvrtletní data | teicp270 | primary | candidate |
| 6 | ecb2 |  | Bydlení a ceny nemovitostí | RESH | primary | candidate |
| 7 | csu | Stát, Kraj | Ceny nemovitostí - územní srovnání | PRUMCENEMOT2 | primary | candidate |
| 8 | eurostat |  | Index cen nemovitostí čtvrtletně | tipsho40 | primary | candidate |
| 9 | oecd4 |  | Indikátory cen nemovitostí | OECD4/housing_prices/dataset | primary | candidate |
| 10 | eurostat |  | Index cen nemovitostí deflovaný ročně | tipsho10 | primary | candidate |

### cena zlata

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | fred | US | Index zlata | NASDAQQGLDI | primary | candidate |
| 2 | commodities |  | Gold | GOLD | primary | candidate |
| 3 | data360 | GLOBAL | Cena zlata v SDR za unci | IMF_IFS/IMF_IFS_EDG_XDR_OZT | primary | candidate |
| 4 | data360 | GLOBAL | Cena zlata v USD za unci | IMF_IFS/IMF_IFS_EDG_USD_OZT | primary | candidate |
| 5 | data360 | GLOBAL | Upravené úspory, vyčerpání minerálů (běžné USD) | WB_WDI/WB_WDI_NY_ADJ_DMIN_CD | primary | candidate |

### cena ropy Brent

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | fred | US | Cena ropy Brent | DCOILBRENTEU | primary | candidate |
| 2 | fred | US | Global price of Brent Crude | POILBREUSDM | primary | candidate |
| 3 | fred | US | Cena ropy WTI | DCOILWTICO | primary | candidate |
| 4 | fred | US | OMX Helsinki cena ropy a plynu | NASDAQHX601010PI | primary | candidate |
| 5 | fred | US | Globální cena indexu ropy APSP | POILAPSPINDEXA | primary | candidate |
| 6 | fred | US | Globální cena indexu ropy APSP | POILAPSPINDEXM | primary | candidate |
| 7 | fred | US | Globální cena indexu ropy APSP | POILAPSPINDEXQ | primary | candidate |
| 8 | fred | US | Cenový index ropy, plynu a uhlí ve Švédsku | NASDAQSX601010PI | primary | candidate |
| 9 | fred | US | Index ceny ropy WTI NASDAQ | NASDAQQUSOI | primary | candidate |
| 10 | fred | US | Cena benzínu USA (konvenční) | GASALLCOVA | primary | candidate |

### akcie CEZ

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | stocks |  | CEZ | CEZ.PR | primary | candidate |
| 2 | stocks |  | CEZ AS | CEZK.XD | primary | candidate |

### vynos desetileteho ceskeho dluhopisu

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | arad | CZ | Výnos desetiletého dluhopisu | 1170 | primary | candidate |
| 2 | arad | CZ | 10Y výnos CZ — Bankovnictví a finance | 1170:SVSDM12 | primary | candidate |

### EUR/USD ECB

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | ecb2 | AUD | Australian dollar · Average · Spot (Euro) | EXR/A.AUD.EUR.SP00.A | primary | candidate |
| 2 | ecb2 | TD | Average · Canadian dollar · Spot (Euro) | EXR/A.CAD.EUR.SP00.A | primary | candidate |
| 3 | ecb2 | SG | Average · Singapore dollar · Spot (Euro) | EXR/A.SGD.EUR.SP00.A | primary | candidate |
| 4 | ecb2 | USD | Average · Spot · US dollar (Euro) | EXR/A.USD.EUR.SP00.A | primary | candidate |
| 5 | ecb2 | AUD | Australian dollar · Average · Spot (Euro) | EXR/D.AUD.EUR.SP00.A | primary | candidate |
| 6 | ecb2 | TD | Average · Canadian dollar · Spot (Euro) | EXR/D.CAD.EUR.SP00.A | primary | candidate |
| 7 | ecb2 | SG | Average · Singapore dollar · Spot (Euro) | EXR/D.SGD.EUR.SP00.A | primary | candidate |
| 8 | ecb2 | USD | Average · Spot · US dollar (Euro) | EXR/D.USD.EUR.SP00.A | primary | candidate |
| 9 | ecb2 | AUD | Australian dollar · Average · Spot (Euro) | EXR/M.AUD.EUR.SP00.A | primary | candidate |
| 10 | ecb2 | TD | Average · Canadian dollar · Spot (Euro) | EXR/M.CAD.EUR.SP00.A | primary | candidate |

### CZK/EUR

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | ecb2 | CZ | Czech koruna · Average · Spot (Euro) | EXR/A.CZK.EUR.SP00.A | primary | candidate |
| 2 | ecb2 | SK | Slovak koruna · Average · Spot (Euro) | EXR/A.SKK.EUR.SP00.A | primary | candidate |
| 3 | ecb2 | CZ | Czech koruna · Average · Spot (Euro) | EXR/D.CZK.EUR.SP00.A | primary | candidate |
| 4 | ecb2 | SK | Slovak koruna · Average · Spot (Euro) | EXR/D.SKK.EUR.SP00.A | primary | candidate |
| 5 | ecb2 | SK | Slovak koruna · Average · Spot (Euro) | EXR/M.SKK.EUR.SP00.A | primary | candidate |
| 6 | ecb2 | CZ | Czech koruna · Average · Spot (Euro) | EXR/Q.CZK.EUR.SP00.A | primary | candidate |
| 7 | ecb2 | SK | Slovak koruna · Average · Spot (Euro) | EXR/Q.SKK.EUR.SP00.A | primary | candidate |
| 8 | ecb2 | CZ | Czech koruna · End-of-period · Spot (Euro) | EXR/A.CZK.EUR.SP00.E | primary | candidate |
| 9 | ecb2 |  | Referenční devizový kurz EUR — měsíční průměr (ECB EXR) | ecb_exr_eur_spot | primary | candidate |
| 10 | ecb2 | CZ | Half-yearly · Czech koruna · Average · Spot (Euro) | EXR/H.CZK.EUR.SP00.A | primary | candidate |

### HDP Polska

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | eurostat |  | Hrubý domácí produkt | med_ec1 | primary | candidate |
| 2 | eurostat |  | Hrubý domácí produkt (HDP) | nama_10_gdp | primary | candidate |
| 3 | eurostat |  | HDP a hlavní složky — hrubý domácí produkt (B1GQ), čtvrtletně | namq_10_gdp_b1gq | primary | candidate |
| 4 | data360 | GLOBAL | Hrubý domácí příjem | WB_WDI/WB_WDI_NY_GDY_TOTL_KN | primary | candidate |
| 5 | data360 | GLOBAL | HDP | IMF_FSI/IMF_FSI_NGDP | primary | candidate |
| 6 | data360 | GLOBAL | HDP (aktuální US$) | WB_WDI/WB_WDI_NY_GDP_MKTP_CD | primary | candidate |
| 7 | data360 | GLOBAL | HDP (aktuální LCU) | WB_WDI/WB_WDI_NY_GDP_MKTP_CN | primary | candidate |
| 8 | data360 | GLOBAL | HDP (stálé LCU) | WB_WDI/WB_WDI_NY_GDP_MKTP_KN | primary | candidate |
| 9 | data360 | GLOBAL | HDP (běžné USD) | WB_GS/WB_GS_NY_GDP_MKTP_CD | primary | candidate |
| 10 | data360 | GLOBAL | Roční emise CO2 na GDP včetně LUC | OWID_CB/OWID_CB_CO2_INCLUDING_LUC_PER_GDP | primary | candidate |

### HDP Polska pouze IMF

| Rank | Source | Geo | Title | Series | Role | Preview |
|---:|---|---|---|---|---|---|
| 1 | imf | PL | HDP deflátor Polsko | IMF/IMF.RES/WEO/9.0.0/POL.NGDP_D | primary | candidate |
| 2 | imf | PL | HDP Polsko (reálný růst) | IMF/IMF.RES/WEO/9.0.0/POL.NGDP_RPCH | primary | candidate |
| 3 | imf | PL | HDP Polsko (USD) | IMF/IMF.RES/WEO/9.0.0/POL.NGDPD | primary | candidate |
| 4 | imf | PL | HDP na obyvatele Polsko (USD) | IMF/IMF.RES/WEO/9.0.0/POL.NGDPDPC | primary | candidate |
| 5 | imf | PL | HDP Polsko | IMF/IMF.FAD/ICSD/1.0.0/POL.B1GQ_V_XDC.A | primary | candidate |
| 6 | imf | PL | Hodnocení zranitelnosti Polska | IMF/IMF.STA/NDGAIN/1.0.1/POL.VSD_NUM.A | primary | candidate |
| 7 | imf | PL | Hrubý dluh vlády Polska | IMF/IMF.FAD/FM/5.0.0/POL.G63G_S13_POGDP_PT | primary | candidate |
| 8 | imf | PL | HDP na obyvatele Polsko (PPP) | IMF/IMF.RES/WEO/9.0.0/POL.PPPPC | primary | candidate |
| 9 | imf | PL | HDP exportérů Polsko | IMF/IMF.RES/EQ/2.0.0/POL.EXPRGDP_PERCAPITA_USD.0.A | primary | candidate |
| 10 | imf | PL | HDP Polsko (stálé ceny) | IMF/IMF.RES/WEO/9.0.0/POL.NGDP_R | primary | candidate |