# Search V2 Evaluation Report

- Mode: `metadata_only`
- Queries: 40

| Metric | V1 | V2 |
|---|---:|---:|
| precision_at_5 | 0.0 | 0.395 |
| precision_at_10 | 0.0 | 0.355 |
| recall_at_20 | 0.0 | 0.35750000000000004 |
| mrr | 0.0 | 0.4258333333333333 |
| ndcg_at_10 | 0.0 | 0.44937033273025684 |
| median_latency_ms | 0.0 | 2724.0 |
| p95_latency_ms | 0.0 | 4072.0 |
| empty_results | 40 | 2 |
| source_constraint_accuracy | 1.0 | 1.0 |

## Per Query Top Results

| ID | Query | V1 label | V1 top | V2 label | V2 top |
|---|---|---|---|---|---|
| cz_bank_profit | zisk bank v Cesku |  |  | good | Bilance obchodních bank - pasiva:Měsíční, Zisk(+) / ztráta (-) běžného období, Nerezidenti, Všechny měny celkem |
| cz_bank_roa | ROA ceskych bank |  |  | bad | Balance Sheets and Income Statements, Other financial corporations, Life Insurance Corporations, Data series for compiling FSIs: ROA and ROE, Average capital and reserves |
| ea_bank_roe | ROE bank v eurozone |  |  | partial | Return on equity of banks |
| eu_bank_capital_ratio | kapitalova primerenost bank v EU |  |  | bad | Backward looking three months · Credit standards · Loan supply · Diffusion index · All banks · Austria |
| bank_net_interest_income | cisty urokovy vynos bank |  |  | good | Backward looking three months · Credit standards · Loan supply · Diffusion index · All banks · Austria |
| cz_inflation | inflace CR |  |  | partial | Inflation, consumer prices (annual % growth) |
| cz_core_inflation | jadrova inflace Cesko |  |  | good | Harmonizovaný index spotřebitelských cen (HICP) - měsíční |
| es_inflation_eurostat | inflace Spanelsko Eurostat |  |  | good | HICP - monthly data (index) (1996-2025) |
| pl_food_prices | ceny potravin v Polsku |  |  | bad | West Bank and Gaza · Potraviny a nealkoholické nápoje — Váha |
| pl_gdp | HDP Polska |  |  | good | Gross domestic product (GDP) at market prices - annual data |
| pl_gdp_imf_only | HDP Polska pouze IMF |  |  | good | Polsko · HDP deflátor |
| de_gdp_growth | rust nemecke ekonomiky |  |  | bad | Vyspělé ekonomiky · HDP — reálný růst |
| cz_wages | mzdy v Cesku |  |  | good | Výdaje sektoru vládních institucí:Roční, Mzdy a platy |
| cz_real_wages | realne mzdy CR |  |  | bad | Výdaje sektoru vládních institucí:Roční, Mzdy a platy |
| v4_unemployment | nezamestnanost V4 |  |  | partial | Ostatní vyspělé ekonomiky · Unemployment rate |
| de_industrial_production | prumyslova vyroba Nemecko |  |  | bad | Backward looking three months · Credit standards · Loan supply · Diffusion index · All banks · Impact of bank competition |
| pl_car_production | vyroba automobilu Polsko |  |  | bad | 3.F. Field burning of agricultural residues (A) · Polsko |
| pl_retail_sales | maloobchodni trzby Polsko |  |  | bad | Polsko · Domestic finance, S1 - Credit controls |
| sk_real_estate_prices | ceny nemovitosti Slovensko |  |  | bad | General government employment (A) · Slovensko |
| cz_new_mortgages | nove hypoteky Cesko |  |  | bad | Nové průmyslové zakázky: data kumulovaná od počátku roku bez očištění (vybrané oddíly CZ-NACE) - meziroční a bazický index |
| cnb_rates | sazby CNB |  |  | bad | Statistika úrokových sazeb - stavy obchodů - úvěry v CZK:Měsíční, Objem, Domácnosti + NISD, CZK |
| cnb_rates_mortgages | sazby CNB a hypoteky |  |  | bad | Diskontní sazba:Měsíční, Úrokové sazby ČNB, ke konci měsíce |
| energy_germany_industry | ceny energii a nemecky prumysl |  |  | bad |  |
| cz_electricity_consumption | spotreba elektriny Cesko |  |  | bad | Úvěry klientské:Měsíční, Celkem, D,E. Výroba a rozvod elektřiny, plynu, tepla, vzduchu, vody, odpadní vody (35-39), Stav (zůstatek) |
| gold_price | cena zlata |  |  | bad | Stav devizových rezerv a ostatních devizových aktiv:Měsíční, Zlato (včetně zlatých depozit a zlata ve swapových operacích), USD |
| brent_oil_price | cena ropy Brent |  |  | good | Oil price, brent crude -1 month forward (Euro) |
| eur_usd_ecb | EUR/USD ECB |  |  | good | Average · Spot · US dollar (Euro) |
| czk_eur | CZK/EUR |  |  | good | Statistika úrokových sazeb - nové obchody - úvěry:Měsíční, Objem, Nefinanční podniky, Celkem bez kontokorentů, revolvingů a pohledávek z kreditních karet, EUR, Od 7,5 mil. CZK do 30 mil. CZK včetně, Banky včetně stavebních spořitelen |
| cez_stock | akcie CEZ |  |  | bad | Platební bilance, finanční účet:Čtvrtletní, CZK, Bilance, Finanční deriváty (jiné než rezervy) a zaměstnanecké opce na akcie, Transakce |
| cz_10y_bond_yield | vynos desetileteho ceskeho dluhopisu |  |  | good | Výnosy státních dluhopisů:Měsíční, Výnos desetiletého státního dluhopisu (maastrichtské kriterium), měsíční průměr |
| en_bank_profit_cz | bank profit Czech Republic |  |  | good | Debt securities held · Czech Republic · Q · Households and non-profit institutions serving… |
| en_czech_inflation | Czech inflation |  |  | bad | Inflation, consumer prices for the Czech Republic |
| en_poland_gdp | Poland GDP |  |  | good | Real GDP per capita |
| en_german_industrial_production | German industrial production |  |  | partial | Industrial Production Index, Construction |
| en_gold_price | gold price |  |  | good | Producer Price Index by Commodity: Miscellaneous Products: Jewelry, Gold and Platinum |
| arad_bank_profit | ARAD zisk bank |  |  | good | Bilance obchodních bank - pasiva:Měsíční, Zisk(+) / ztráta (-) běžného období, Nerezidenti, Všechny měny celkem |
| arad_only_bank_profit | data o zisku bank pouze z ARAD |  |  | good | Banky - Výkaz zisku a ztráty:Čtvrtletní, Pobočky zahraničních bank v EU, Negativní goodwill účtovaný do výkazu zisku nebo ztráty |
| ambiguous_increase_production_de | navysit vyrobu v Nemecku |  |  | good |  |
| cz_household_wellbeing | jak se dari ceskym domacnostem |  |  | bad | Cenné papíry:Čtvrtletní, Stavy, Aktiva, Celkem, Domácnosti a neziskové instituce sloužící domácnostem |
| house_price_drivers | co ovlivnuje ceny bytu |  |  | bad | Index cen nákupu obydlí celkem (včetně pozemků):Čtvrtletní, 2015 |
