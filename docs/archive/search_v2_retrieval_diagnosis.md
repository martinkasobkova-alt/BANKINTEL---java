# Search V2 Retrieval Diagnosis

- Mode: `metadata_only`
- Queries: 40
- Retrieval failure share: 0.8
- Reranking failure share: 0.2

## Variant Summary

| Variant | Candidate R@20 | Candidate R@50 | Candidate R@100 | P@5 | MRR | nDCG@10 | Empty | Median ms | P95 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A_legacy_fallback | 0.425 | 0.475 | 0.525 | 0.38 | 0.4258333333333333 | 0.44432579722012794 | 2 | 2663.0 | 4072.0 |
| C_sidecar_fallback | 0.4166666666666667 | 0.4916666666666667 | 0.5 | 0.48500000000000004 | 0.6297269616348563 | 0.6500101292862663 | 1 | 141.0 | 2356.0 |

## Failure Buckets (A legacy fallback)

`{relevant_series_retrieved_but_reranked_low=4, relevant_series_retrieved_but_truncated=1, relevant_series_not_retrieved=14, not_applicable_clarification=1}`

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
| pl_gdp_imf_only | HDP Polska pouze IMF | ok | ok | Polsko · HDP deflátor | HDP Polsko (podíl na světovém HDP) |
| de_gdp_growth | rust nemecke ekonomiky | relevant_series_not_retrieved | relevant_series_not_retrieved | Vyspělé ekonomiky · HDP — reálný růst | Index blockchain ekonomiky NASDAQ |
| cz_wages | mzdy v Cesku | ok | ok | Výdaje sektoru vládních institucí:Roční, Mzdy a platy | mzdy a platy |
| cz_real_wages | realne mzdy CR | relevant_series_not_retrieved | ok | Výdaje sektoru vládních institucí:Roční, Mzdy a platy | Změny reálné hodnoty portfolia |
| v4_unemployment | nezamestnanost V4 | ok | ok | Ostatní vyspělé ekonomiky · Unemployment rate | Registrovaná nezaměstnanost podle krajů |
| de_industrial_production | prumyslova vyroba Nemecko | relevant_series_not_retrieved | ok | Backward looking three months · Credit standards · Loan supply · Diffusion index · All banks · Impact of bank competition | Průmyslová výroba - měsíční data |
| pl_car_production | vyroba automobilu Polsko | relevant_series_not_retrieved | ok | 3.F. Field burning of agricultural residues (A) · Polsko | Nosnost nových nákladních automobilů (do 2012) |
| pl_retail_sales | maloobchodni trzby Polsko | relevant_series_not_retrieved | relevant_series_not_retrieved | Polsko · Domestic finance, S1 - Credit controls | Předběžné maloobchodní a stravovací tržby |
| sk_real_estate_prices | ceny nemovitosti Slovensko | relevant_series_not_retrieved | ok | General government employment (A) · Slovensko | Bydlení a ceny nemovitostí |
| cz_new_mortgages | nove hypoteky Cesko | relevant_series_not_retrieved | ok | Nové průmyslové zakázky: data kumulovaná od počátku roku bez očištění (vybrané oddíly CZ-NACE) - meziroční a bazický index | Hypoteční sazba APRC — nové úvěry na bydlení (MIR, měsíčně) |
| cnb_rates | sazby CNB | relevant_series_not_retrieved | relevant_series_retrieved_but_reranked_low | Statistika úrokových sazeb - stavy obchodů - úvěry v CZK:Měsíční, Objem, Domácnosti + NISD, CZK | Úrokové sazby bank — NFC |
| cnb_rates_mortgages | sazby CNB a hypoteky | relevant_series_not_retrieved | ok | Diskontní sazba:Měsíční, Úrokové sazby ČNB, ke konci měsíce | hypotéky |
| energy_germany_industry | ceny energii a nemecky prumysl | relevant_series_retrieved_but_reranked_low | ok |  | Importní ceny - zpracovatelský průmysl |
| cz_electricity_consumption | spotreba elektriny Cesko | relevant_series_not_retrieved | ok | Úvěry klientské:Měsíční, Celkem, D,E. Výroba a rozvod elektřiny, plynu, tepla, vzduchu, vody, odpadní vody (35-39), Stav (zůstatek) | Dodávky a spotřeba elektřiny |
| gold_price | cena zlata | relevant_series_retrieved_but_reranked_low | ok | Stav devizových rezerv a ostatních devizových aktiv:Měsíční, Zlato (včetně zlatých depozit a zlata ve swapových operacích), USD | Cena zlata v SDR za unci |
| brent_oil_price | cena ropy Brent | ok | ok | Oil price, brent crude -1 month forward (Euro) | Cena ropy Brent |
| eur_usd_ecb | EUR/USD ECB | ok | ok | Average · Spot · US dollar (Euro) | Sazba nových úvěrů nefinančním podnikům (MIR, národní měna/EUR) |
| czk_eur | CZK/EUR | ok | ok | Statistika úrokových sazeb - nové obchody - úvěry:Měsíční, Objem, Nefinanční podniky, Celkem bez kontokorentů, revolvingů a pohledávek z kreditních karet, EUR, Od 7,5 mil. CZK do 30 mil. CZK včetně, Banky včetně stavebních spořitelen | Úrokové sazby úvěrů do 30 mil. CZK (EUR) |
| cez_stock | akcie CEZ | relevant_series_not_retrieved | relevant_series_not_retrieved | Platební bilance, finanční účet:Čtvrtletní, CZK, Bilance, Finanční deriváty (jiné než rezervy) a zaměstnanecké opce na akcie, Transakce | FDI akcie v % HDP |
| cz_10y_bond_yield | vynos desetileteho ceskeho dluhopisu | ok | ok | Výnosy státních dluhopisů:Měsíční, Výnos desetiletého státního dluhopisu (maastrichtské kriterium), měsíční průměr | Výnos desetiletého dluhopisu |
| en_bank_profit_cz | bank profit Czech Republic | ok | ok | Debt securities held · Czech Republic · Q · Households and non-profit institutions serving… | Cenné papíry bank |
| en_czech_inflation | Czech inflation | relevant_series_retrieved_but_reranked_low | ok | Inflation, consumer prices for the Czech Republic | deflátor |
| en_poland_gdp | Poland GDP | ok | ok | Real GDP per capita | Gross domestic product (GDP) and main components (output, expenditure and income) - annual data |
| en_german_industrial_production | German industrial production | ok | relevant_series_retrieved_but_reranked_low | Industrial Production Index, Construction | Průmyslová výroba - měsíční data |
| en_gold_price | gold price | ok | relevant_series_not_retrieved | Producer Price Index by Commodity: Miscellaneous Products: Jewelry, Gold and Platinum | Zlato devizové rezervy CZK |
| arad_bank_profit | ARAD zisk bank | ok | ok | Bilance obchodních bank - pasiva:Měsíční, Zisk(+) / ztráta (-) běžného období, Nerezidenti, Všechny měny celkem | Rentabilita bank (ROE) |
| arad_only_bank_profit | data o zisku bank pouze z ARAD | ok | ok | Banky - Výkaz zisku a ztráty:Čtvrtletní, Pobočky zahraničních bank v EU, Negativní goodwill účtovaný do výkazu zisku nebo ztráty | Finanční aktiva vykázané do zisku nebo ztráty |
| ambiguous_increase_production_de | navysit vyrobu v Nemecku | not_applicable_clarification | not_applicable_clarification |  |  |
| cz_household_wellbeing | jak se dari ceskym domacnostem | relevant_series_not_retrieved | ok | Cenné papíry:Čtvrtletní, Stavy, Aktiva, Celkem, Domácnosti a neziskové instituce sloužící domácnostem | Úvěry domácnostem celkem |
| house_price_drivers | co ovlivnuje ceny bytu | relevant_series_not_retrieved | ok | Index cen nákupu obydlí celkem (včetně pozemků):Čtvrtletní, 2015 | Výstavba bytů |
