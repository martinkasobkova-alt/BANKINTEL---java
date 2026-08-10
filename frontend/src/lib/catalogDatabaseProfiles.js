/**
 * Ekonomické profily katalogů — tagline, oblasti dat a typické využití.
 * Zobrazuje se po kliknutí na ikonu ℹ u každé databáze.
 */

/** @typedef {{ tagline: string, summary: string, geography: string, areas: string[], frequency?: string, typicalUse: string, examples?: string[] }} CatalogDatabaseProfile */

/** @type {Record<string, CatalogDatabaseProfile>} */
export const CATALOG_DATABASE_PROFILES = {
  arad: {
    tagline: "Oficiální statistiky ČNB (ARAD)",
    summary:
      "ARAD (Analytický reporting a databáze) je hlavní veřejný datový portál ČNB. Obsahuje makroekonomické, měnové a finanční ukazatele pro Českou republiku — často dříve než agregovaná data Eurostatu.",
    geography: "Česká republika",
    areas: [
      "Měnová politika a úrokové sazby",
      "Měnové agregáty (M1, M2, M3)",
      "Úvěry a vklady bankovního sektoru",
      "Kurzy CZK, devizové rezervy",
      "Inflace, HDP, platy a trh práce (vazba na ČSÚ)",
      "Státní dluh a veřejné finance",
    ],
    frequency: "denní až roční",
    typicalUse:
      "Bankovní analýza, měnová politika, srovnání s ECB/Eurostat, domácí makro pro ČR. Ideální pro české instituce a firmy s domácím zaměřením.",
    examples: ["Repo sazba ČNB", "Úvěry domácnostem", "Kurz EUR/CZK", "M2"],
  },
  csu: {
    tagline: "Statistická data z České republiky",
    summary:
      "ČSÚ (DataStat) publikuje oficiální statistiky státu — od cen a mezd po stavebnictví, průmysl a demografii. Data jsou základem pro segmentové a regionální analýzy v ČR.",
    geography: "Česká republika (regiony, okresy u vybraných ukazatelů)",
    areas: [
      "Ceny a inflace (CPI, PPI)",
      "Trh práce — nezaměstnanost, mzdy, pracovní síla",
      "Stavebnictví a bydlení",
      "Průmyslová produkce a obraty",
      "Maloobchod a služby",
      "Demografie a společenské ukazatele",
      "Zahraniční obchod",
    ],
    frequency: "měsíční, čtvrtletní, roční",
    typicalUse:
      "Segmentové reporty (energetika, retail, stavebnictví…), srovnání regionů, domácí makro. Doplňuje Eurostat o detailnější české řady.",
    examples: ["CPI meziročně", "Průmyslová produkce", "Stavební povolení", "Průměrná mzda"],
  },
  eurostat: {
    tagline: "Statistiky Evropské unie a srovnání zemí EU",
    summary:
      "Eurostat je statistický úřad EU — největší zdroj srovnatelných dat napříč 27 členskými státy. Pokrývá HDP, inflaci, trh práce, odvětví podle NACE, energii, obchod i demografii.",
    geography: "EU, eurozóna, jednotlivé členské státy + některé partnery",
    areas: [
      "HDP, investice, spotřeba (národní účty)",
      "HICP inflace a cenové indexy",
      "Trh práce a mzdy",
      "Průmysl, stavebnictví, maloobchod (podle NACE)",
      "Energetika a emise CO₂",
      "Zahraniční obchod",
      "Demografie a sociální ukazatele",
    ],
    frequency: "měsíční až roční (podle datasetu)",
    typicalUse:
      "Mezinárodní srovnání, segmentové analýzy v EU, benchmarking ČR vs. Německo/Polsko. Klíčový zdroj pro odvětvové reporty v BankoApp.",
    examples: ["HICP", "HDP růst QoQ", "Nezaměstnanost", "Produkce v odvětví"],
  },
  ecb2: {
    tagline: "Měnová politika a finanční trhy eurozóny",
    summary:
      "ECB prochází země → dataset (MIR, BSI, ICP…) → tematické skupiny → konkrétní časové řady (~211 tis. ověřených sérií). Pokrývá sazby, úvěry, inflaci, kurzy a bankovní statistiky včetně protistran, splatností a sektorů.",
    geography: "Eurozóna a další země v ECB datech",
    areas: [
      "Úrokové sazby MIR (úvěry, vklady podle protistrany)",
      "Bankovní bilance BSI",
      "Inflace ICP podle COICOP",
      "Směnné kurzy a měnové agregáty",
    ],
    frequency: "denní až roční",
    typicalUse:
      "Detailní analýza bankovního sektoru, sazeb podle splatnosti a protistrany, srovnání zemí eurozóny na úrovni jednotlivých řad.",
    examples: ["MIR sazby domácnostem", "BSI aktiva bank", "ICP energie"],
  },
  fred: {
    tagline: "Americká a globální makrodata z Fed St. Louis",
    summary:
      "FRED (Federal Reserve Economic Data) je rozsáhlá databáze Středního fedu — desítky tisíc řad o US ekonomice, trzích, cenách komodit i globálních ukazatelích.",
    geography: "Primárně USA, mnoho globálních řad",
    areas: [
      "US HDP, inflace, trh práce",
      "Úrokové sazby Fed a Treasury",
      "Akciové indexy a volatility (VIX)",
      "Komodity a energie",
      "Housing a stavebnictví v USA",
      "Mezinárodní řady (vybrané)",
    ],
    frequency: "denní až roční",
    typicalUse:
      "Globální makro, US cyklus, trhy a komodity. Vyžaduje FRED API klíč na serveru.",
    examples: ["Fed Funds Rate", "CPI USA", "10Y Treasury", "WTI ropa"],
  },
  data360: {
    tagline: "Světová banka — globální rozvojové a makro ukazatele",
    summary:
      "World Bank je hlavní katalog ukazatelů Světové banky (Data360 API). Obsahuje World Development Indicators (WDI) a další databáze — HDP, chudobu, vzdělání, infrastrukturu, klimat a finance pro téměř všechny země světa.",
    geography: "Globální — 200+ zemí a agregáty (svět, regiony, income groups)",
    areas: [
      "HDP a růst (nominal, PPP, per capita)",
      "Inflace a ceny",
      "Chudoba a nezaměstnanost",
      "Vzdělání a zdraví",
      "Energie a emise",
      "Zadlužení a veřejné finance",
      "Obchod a investice",
    ],
    frequency: "roční (většina), některé měsíční",
    typicalUse:
      "Globální srovnání zemí mimo EU, emerging markets a rozvojové ukazatele.",
    examples: ["NY.GDP.MKTP.CD", "FP.CPI.TOTL.ZG", "SL.UEM.TOTL.ZS", "SP.POP.TOTL"],
  },
  bis: {
    tagline: "Mezinárodní bankovní statistiky a finanční stabilita",
    summary:
      "BIS (Bank for International Settlements) shromažďuje data o mezinárodním bankovnictví, úvěrech, devizových kurzech, dluhopisech a finanční stabilitě. Klíčový zdroj pro globální finanční systém.",
    geography: "Globální — země, eurozóna, agregáty",
    areas: [
      "Mezinárodní bankovní pozice (LBS)",
      "Úvěrové registry a koncentrace",
      "Devizové kurzy (EER)",
      "Dluhopisové trhy a úrokové sazby",
      "Platební bilance a IIP",
      "Finanční stabilita a stress",
    ],
    frequency: "čtvrtletní až roční (podle toku)",
    typicalUse:
      "Analýza mezinárodních bank, cross-border úvěrů, kurzů a finanční stability. Některé toky vyžadují výběr dimenzí (SDMX wizard).",
    examples: ["WS_EER kurzy", "LBS cross-border úvěry", "Credit to GDP"],
  },
  imf: {
    tagline: "Globální makroekonomická data a projekce IMF",
    summary:
      "IMF publikuje World Economic Outlook (WEO), inflaci, veřejné finance, platební bilance a další makro ukazatele pro téměř všechny země — včetně projekcí do budoucna.",
    geography: "Globální — 190+ zemí",
    areas: [
      "WEO — HDP, inflace, nezaměstnanost (projekce)",
      "Veřejné finance a dluh",
      "Platební bilance a IIP",
      "Měnová a fiskální politika",
      "Finanční sektor",
      "Ceny a CPI",
    ],
    frequency: "čtvrtletní a roční (+ projekce WEO)",
    typicalUse:
      "Globální makro, emerging markets, projekce růstu a inflace. WEO obsahuje odhady IMF na 5 let dopředu.",
    examples: ["WEO HDP růst", "CPI inflace", "Current account", "General government debt"],
  },
  oecd4: {
    tagline: "OECD — výhledy a makro bez limitu API",
    summary:
      "OECD čte předstažené snímky vybraných datasetů z lokálního disku (Economic Outlook, KEI, zaměstnanost, ceny, průmysl…). Žádné živé dotazy → okamžitý náhled, funguje i bez přístupu k OECD API.",
    geography: "OECD země, EU agregáty, hlavní světové ekonomiky",
    areas: [
      "OECD Economic Outlook — HDP, inflace, nezaměstnanost, projekce",
      "Key short-term indicators (KEI)",
      "Zaměstnanost a trh práce (LFS)",
      "Ceny (CPI), bydlení, průmysl a služby",
      "Energetické a uhlíkové daně, emise CO₂",
      "Obchod s komoditami (BIMTS)",
    ],
    frequency: "měsíční až roční (podle datasetu)",
    typicalUse:
      "Výhledové a srovnávací analýzy OECD, segmentové reporty s projekcemi, doplněk k Eurostatu/IMF WEO. Ideální když živé OECD API vrací 429.",
    examples: ["EO118 HDP projekce", "KEI CPI", "LFS nezaměstnanost", "House prices"],
  },
  yahoo_finance: {
    tagline: "Indexy, ETF a komodity z Yahoo Finance",
    summary:
      "Denní historická tržní data (OHLCV) přes knihovnu yfinance — bez API klíče. Kurátorované tickery: S&P 500, Nasdaq-100, SPY, QQQ, VIX, zlato a další.",
    geography: "Globální trhy (primárně USA)",
    areas: [
      "Akciové indexy (S&P 500, Nasdaq-100)",
      "ETF (SPY, QQQ, pákové fondy)",
      "Volatilita (VIX)",
      "Zlato (GLD, futures)",
    ],
    frequency: "denní",
    typicalUse:
      "Rychlý náhled a stažení historických cen indexů a ETF pro analýzy a reporty.",
    examples: ["^GSPC", "SPY", "QQQ", "^VIX", "GLD"],
  },
  alphavantage: {
    tagline: "Akciové trhy, indexy a FX — vyhledávání podle tickeru",
    summary:
      "Alpha Vantage nemá klasický katalog řad k proklikání. Zadejte název firmy nebo ticker (AAPL, Apple, SPY…) do vyhledávání — aplikace symbol dohledá přes API. Vhodné pro tržní data (akcie, ETF, indexy, FX, krypto), ne pro makrostatistiku.",
    geography: "Globální trhy (primárně USA)",
    areas: [
      "Akcie a ETF",
      "Globální indexy",
      "Devizové kurzy",
      "Kryptoměny",
      "Technické indikátory",
    ],
    frequency: "denní, intraday (podle endpointu)",
    typicalUse:
      "Vyhledání akcie nebo indexu podle názvu/tickeru, náhled grafu, uložení jako zdroj. Vyžaduje ALPHAVANTAGE_API_KEY na serveru.",
    examples: ["AAPL", "SPY", "EUR/USD", "BTC/USD"],
  },
  commodities: {
    tagline: "Ceny komodit a prognózy World Bank (Pink Sheet)",
    summary:
      "Měsíční ceny klíčových komodit (ropa, plyn, kovy, potraviny) z oficiálního Pink Sheet Světové banky plus CMO prognózy. Standard pro komoditní benchmarky v rozvojové ekonomii.",
    geography: "Globální trhy",
    areas: [
      "Ropa Brent a WTI",
      "Zemní plyn",
      "Kovy (měď, hliník, železo)",
      "Potraviny a zemědělské komodity",
      "CMO prognózy cen",
    ],
    frequency: "měsíční",
    typicalUse:
      "Energetika, průmysl, inflační tlaky z komodit, scenáře pro odvětvové reporty.",
    examples: ["Brent crude", "Natural gas Europe", "Copper", "Wheat"],
  },
};

/**
 * @param {string} catalogId
 * @returns {CatalogDatabaseProfile | null}
 */
const CATALOG_PROFILE_ALIASES = {
  ecb: "ecb2",
  worldbank: "data360",
  oecd: "oecd4",
  oecd2: "oecd4",
  oecd3: "oecd4",
};

export function getCatalogDatabaseProfile(catalogId) {
  const raw = String(catalogId || "").trim().toLowerCase();
  const id = CATALOG_PROFILE_ALIASES[raw] || raw;
  return CATALOG_DATABASE_PROFILES[id] || null;
}
