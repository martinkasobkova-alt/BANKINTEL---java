export const OECD_DATA_API_NOTE_CZ =
  "OECD Data API je SDMX rozhraní pro data OECD. Dotaz se skládá z agency, dataflow, verze a hodnot dimenzí. Pro nejnovější verzi lze použít '+'.";

export const IMF_CATALOG_NOTE_CZ =
  "IMF SDMX 3.0 — země → dataset (WEO, CPI, FM…) → ukazatel. WEO obsahuje projekce. Vyžaduje IMF_API_KEY a imf_availability.json (build_imf_availability.py).";

/** Časové okno aby UI nečekalo věčně při visícím IMF upstream (synchronně s IMF_CATALOG_BUDGET_SEC backendu ~20 s). */
export const IMF_UI_LOAD_DEADLINE_MS = 20_000;

export const DATA360_CATALOG_DESCRIPTION_CZ =
  "World Bank — hlavní katalog ukazatelů Světové banky (WDI a další databáze přes Data360 API). Fulltext „Hledat v celém katalogu“ volá Data360 Search API; náhled dat přes /data360/data.";

export const BIS_STATS_API_NOTE_CZ =
  "BIS Stats API (SDMX RESTful u stats.bis.org/api/v1) slouží k dotazům na data ve tvaru /data/{flow}/{key}/all. Identifikátor řady v katalogu má tvar BIS|flow|key (čárkový zápis více částí toku zůstává zachovaný). Náhled používá Generic Data XML; u key=all je vyžadován limit pozorování nebo období.";

export const ALPHAVANTAGE_CATALOG_NOTE_CZ =
  "Alpha Vantage nemá klasický browse katalog jako Eurostat nebo ARAD — zde neuvidíte strom všech akcií a indexů. Do pole „Hledat v celém katalogu“ napište název firmy (Apple, Microsoft…) nebo ticker (AAPL, MSFT, SPY). Aplikace dohledá symbol přes Alpha Vantage SYMBOL_SEARCH. Nový symbol pak můžete uložit jako zdroj v sekci Zdroje.";

export const YAHOO_FINANCE_CATALOG_NOTE_CZ =
  "Yahoo Finance (yfinance) nabízí denní historická data indexů, ETF a akcií. V katalogu jsou předpřipravené tickery (S&P 500, Nasdaq-100, SPY, QQQ, VIX, GLD…). Do vyhledávání stačí název nebo ticker (Apple, AAPL, nasdaq, SPY) — aplikace dohledá symbol přes Yahoo. U hrubých překlepů zkuste celý název (aple → raději apple). Bez API klíče.";

export const WB_DEFAULT_COUNTRY = "CZE";

/** Skryté katalogy — nejsou v UI ani ve vyhledávání (API routy zůstávají pro legacy zdroje). */
export const CATALOGS_HIDDEN_IDS = new Set([
  "imf2",
  "oecd",
  "oecd2",
  "oecd3",
  "tradingeconomics",
  "ecb",
  "alphavantage",
  "yahoo_finance",
]);

export function isCatalogHidden(defOrId) {
  const id = typeof defOrId === "string" ? defOrId : defOrId?.id;
  return CATALOGS_HIDDEN_IDS.has(String(id || "").trim().toLowerCase());
}

const pathsBase = (p) => {
  const o = {
    catalogPath: p.catalogPath,
    sourceType: p.sourceType,
  };
  if (p.addPath != null && String(p.addPath).trim() !== "") o.addPath = p.addPath;
  if (p.needsCountry) o.needsCountry = true;
  return o;
};

/** Produčně použitelné konektory — výchozí výběr, AI hluboké vyhledávání. */
export const CATALOGS_PRODUCTION = [
  {
    ...pathsBase({
      catalogPath: "/arad/catalog",
      addPath: "/arad/catalog/add-source",
      sourceType: "arad",
    }),
    id: "arad",
    label: "ČNB - ARAD",
    tier: "production",
    usableForAiSearch: true,
    /** ČNB ARAD může na cold startu trvat 30+ s (backend wall ~32 s). */
    browseUiTimeoutMs: 60_000,
    browseHttpTimeoutMs: 52_000,
  },
  {
    ...pathsBase({
      catalogPath: "/csu/catalog",
      addPath: "/csu/catalog/add-source",
      sourceType: "csu",
    }),
    id: "csu",
    label: "ČSÚ",
    tier: "production",
    usableForAiSearch: true,
    /** ČSÚ katalog (~1500 výběrů) — cold load + zploštění může trvat desítky sekund. */
    browseUiTimeoutMs: 60_000,
    browseHttpTimeoutMs: 52_000,
  },
  {
    ...pathsBase({
      catalogPath: "/eurostat/catalog",
      addPath: "/eurostat/catalog/add-source",
      sourceType: "eurostat",
    }),
    id: "eurostat",
    label: "Eurostat",
    tier: "production",
    usableForAiSearch: true,
    /** Cold TOC z ec.europa.eu může trvat 15–30 s; UI limit musí být nad backend wall + zploštění. */
    browseUiTimeoutMs: 60_000,
    browseHttpTimeoutMs: 52_000,
  },
];

export const COMMODITIES_CATALOG_NOTE_CZ =
  "World Bank Pink Sheet (CMO) — měsíční ceny komodit a CMO prognózy. Data se stahují z oficiálních XLS (thedocs.worldbank.org).";

export const COMMODITIES_CATALOG_DEF = {
  id: "commodities",
  label: "Komodity",
  catalogPath: "/commodities/catalog",
  addPath: "/commodities/add-source",
  sourceType: "worldbank_pink_sheet",
  tier: "beta",
  badge: "beta",
  usableForAiSearch: true,
  description: COMMODITIES_CATALOG_NOTE_CZ,
};

/** Experimentální konektory — náhled nemusí fungovat; štítek beta. */
export const CATALOGS_EXPERIMENTAL = [
  {
    id: "commodities",
    label: "Komodity",
    catalogPath: "/commodities/catalog",
    addPath: "/commodities/add-source",
    sourceType: "worldbank_pink_sheet",
    tier: "beta",
    badge: "beta",
    usableForAiSearch: true,
    description: COMMODITIES_CATALOG_NOTE_CZ,
  },
  {
    ...pathsBase({
      catalogPath: "/ecb2/browse-tree",
      addPath: "/ecb/add-source",
      sourceType: "ecb",
    }),
    id: "ecb2",
    label: "ECB",
    description:
      "ECB country-first browse z ověřené availability mřížky (~211k řad). Bez skládání SDMX dimenzí.",
    tier: "beta",
    badge: "beta",
    usableForAiSearch: true,
  },
  {
    ...pathsBase({
      catalogPath: "/fred/catalog",
      addPath: "/fred/catalog/add-source",
      sourceType: "fred",
    }),
    id: "fred",
    label: "FRED",
    tier: "beta",
    badge: "beta",
    usableForAiSearch: true,
  },
  {
    ...pathsBase({
      catalogPath: "/data360/catalog",
      addPath: "/data360/catalog/add-source",
      sourceType: "world_bank_data360",
    }),
    id: "data360",
    label: "World Bank",
    description: DATA360_CATALOG_DESCRIPTION_CZ,
    tier: "beta",
    badge: "beta",
    usableForAiSearch: true,
  },
  {
    ...pathsBase({
      catalogPath: "/bis/catalog",
      addPath: "/bis/catalog/add-source",
      sourceType: "bis",
    }),
    id: "bis",
    label: "BIS",
    description: BIS_STATS_API_NOTE_CZ,
    tier: "beta",
    badge: "beta",
    usableForAiSearch: true,
  },
  {
    ...pathsBase({
      catalogPath: "/imf/browse-tree",
      addPath: "/imf/add-source",
      sourceType: "imf",
    }),
    id: "imf",
    label: "IMF",
    description: IMF_CATALOG_NOTE_CZ,
    tier: "beta",
    badge: "beta",
    usableForAiSearch: true,
  },
  {
    ...pathsBase({
      catalogPath: "/oecd4/browse-tree",
      addPath: "/oecd4/add-source",
      sourceType: "oecd",
    }),
    id: "oecd4",
    label: "OECD",
    description:
      "OECD offline mirror — Economic Outlook, KEI, zaměstnanost, ceny a další datasety stažené dopředu do data/oecd4. Okamžitý náhled bez limitu 429.",
    tier: "beta",
    badge: "beta",
    usableForAiSearch: true,
    browseUiTimeoutMs: 30_000,
    browseHttpTimeoutMs: 35_000,
  },
];

/** Akcie / ETF / indexy — pouze stock search, ne katalogové hledání. */
export const STOCK_MARKET_CATALOGS = [
  {
    ...pathsBase({
      catalogPath: "/alphavantage/catalog",
      addPath: "/alphavantage/catalog/add-source",
      sourceType: "alphavantage",
    }),
    id: "alphavantage",
    label: "ALPHA VANTAGE — akcie / indexy",
    description: ALPHAVANTAGE_CATALOG_NOTE_CZ,
    tier: "beta",
    badge: "beta",
    usableForAiSearch: false,
  },
  {
    ...pathsBase({
      catalogPath: "/yahoo_finance/catalog",
      addPath: "/yahoo_finance/catalog/add-source",
      sourceType: "yahoo_finance",
    }),
    id: "yahoo_finance",
    label: "Yahoo Finance — akcie / indexy",
    description: YAHOO_FINANCE_CATALOG_NOTE_CZ,
    tier: "beta",
    badge: "beta",
    usableForAiSearch: false,
  },
];

/** Pořadí: stabilní → experimentální (bez skrytých katalogů). */
export const CATALOGS = [...CATALOGS_PRODUCTION, ...CATALOGS_EXPERIMENTAL];

const CATALOG_SOURCE_ID_ALIASES = Object.freeze({
  ecb: "ecb2",
  ecb2: "ecb2",
  fred_remote: "fred",
  fred: "fred",
  imf2: "imf",
  imf: "imf",
  oecd: "oecd4",
  oecd2: "oecd4",
  oecd3: "oecd4",
  oecd4: "oecd4",
  worldbank: "data360",
  world_bank: "data360",
  world_bank_data360: "data360",
  data360: "data360",
  commodities: "commodities",
  worldbank_pink_sheet: "commodities",
});

export function normalizeCatalogSourceId(value) {
  const raw = String(value || "").trim().toLowerCase();
  if (!raw) return "";
  return CATALOG_SOURCE_ID_ALIASES[raw] || raw;
}

export function catalogDefMatchesSourceId(def, sourceId) {
  const normalized = normalizeCatalogSourceId(sourceId);
  if (!def || !normalized) return false;
  return (
    normalizeCatalogSourceId(def.id) === normalized ||
    normalizeCatalogSourceId(def.sourceType) === normalized
  );
}

/** Výchozí zaškrtnutí v sekci „Databáze“ na stránce globálního katalogu. */
export const CATALOGS_DEFAULT_SELECTED_IDS = CATALOGS.map((c) => c.id);
