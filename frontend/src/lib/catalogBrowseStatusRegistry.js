/**
 * Globální katalog (/search/catalog) — časové limity, štítky ve výběru a texty fallbacků.
 * Backend může duplikovat část metadat v GET /api/catalog/status.
 */

/** Tvrdý limit UI (HTTP + zploštění) — musí být vyšší než HTTP timeout. */
export const GLOBAL_CATALOG_BROWSE_UI_TIMEOUT_MS = 55000;

/** HTTP GET na /api/.../catalog — musí překrýt backend SLA (ARAD ~32 s, Eurostat ~28 s, ČSÚ cold load). */
export const GLOBAL_CATALOG_BROWSE_HTTP_TIMEOUT_MS = 48000;

/** Bezpečnostní limit zploštění u velmi velkých stromů (asynchronní yield). */
export const GLOBAL_CATALOG_FLATTEN_GUARD_MS = 45000;

/**
 * @param {{ id: string, label: string, badge?: string }} def
 */
export function getCatalogBrowseDropdownLabel(def) {
  return String(def?.label || "").trim() || def?.id || "";
}

/** React Router — dedikované stránky pro fallback (Úkol D). */
export const GLOBAL_BROWSE_FALLBACK_ROUTE = Object.freeze({
  fred: "/fred/catalog",
  worldbank: "/search/catalog?catalog=data360",
  data360: "/data360/catalog",
  bis: "/bis/catalog",
  imf: "/imf/catalog",
  imf2: "/search/catalog?catalog=imf",
  oecd: "/oecd/catalog",
  oecd2: "/search/catalog?catalog=oecd4",
  oecd3: "/oecd3/catalog",
  oecd4: "/search/catalog",
  commodities: "/commodities",
  ecb2: "/search/catalog?catalog=ecb2",
});

/**
 * @param {object} def – položka z CATALOGS
 * @returns {boolean}
 */
export function globalBrowseShowsDedicatedFallback(def) {
  return Boolean(def?.id && GLOBAL_BROWSE_FALLBACK_ROUTE[def.id]);
}

/**
 * Hlavička pevného timeoutu (globální nebo přepsání u zdroje, např. ARAD).
 * @param {object} def
 * @param {number} [uiTimeoutMs]
 */
export function buildGlobalBrowseTimeoutHeadlineCz(
  def,
  uiTimeoutMs = GLOBAL_CATALOG_BROWSE_UI_TIMEOUT_MS
) {
  return `Katalog se nepodařilo načíst do ${Math.round(uiTimeoutMs / 1000)} sekund (${def?.label ?? "nezámý zdroj"}).`;
}

/**
 * Doporučený další krok podle ID zdroje.
 * @param {object} def
 */
export function buildGlobalBrowseTimeoutNextStepCz(def) {
  const id = def?.id;
  const fb = GLOBAL_BROWSE_FALLBACK_ROUTE[id];
  switch (id) {
    case "fred":
      return `Doporučený další krok: použijte vyhledávání konkrétní série (series ID), nebo otevřete dedikovaný katalog${fb ? ` (${fb}).` : "."}`;
    case "alphavantage":
      return "Alpha Vantage nemá browse strom — do vyhledávání napište název firmy nebo ticker (např. Apple, AAPL, SPY).";
    case "yahoo_finance":
      return "Yahoo Finance — do vyhledávání napište ticker nebo název (SPY, Nasdaq, S&P 500, VIX, GLD).";
    case "worldbank":
      return "Doporučený další krok: vyzkoušejte kombinaci indikátor + země na dedikované stránce (např. NY.GDP.MKTP.CD, FP.CPI.TOTL.ZG, SL.UEM.TOTL.ZS).";
    case "data360":
      return "Doporučený další krok: použijte Data360 Search / AI vyhledávání; globální strom nemusí být dostupný.";
    case "bis":
      return "Doporučený další krok: otevřete BIS katalog — dataflow a dimenze nad globálním stromem nejsou spolehlivé.";
    case "imf":
      return "IMF upstream často timeoutuje; otevřete /imf/catalog nebo použijte IMF 2 country-first browse.";
    case "imf2":
      return "IMF 2 zobrazuje ověřené CompactData řady podle země — bez skládání dotazů.";
    case "oecd":
      return "OECD browse strom se zde nenačetl včas. Ověřený seed: OECD Data API OECD.SDD.STES / KEI — nebo použijte OECD 2 / AI vyhledávání.";
    case "oecd2":
      return "OECD 2 zobrazuje ověřené SDMX v2 řady podle země — bez skládání dimenzí.";
    case "oecd3":
      return "OECD 3 zobrazuje vsechny OECD Data Explorer datasety podle tematu; casove rady se stahuji az po vyberu konkretni struktury/dimenzi.";
    case "oecd4":
      return "OECD — offline mirror: kategorie → dataset → země → ukazatel. Data z lokálního snímku, bez limitu OECD API.";
    case "ecb":
      return "Doporučený další krok: pokračujte na ECB katalog.";
    case "ecb2":
      return "ECB: země → dataset (BSI = banky, …) → A–Z → ukazatele.";
    case "csu":
      return "ČSÚ katalog je rozsáhlý (~1500 výběrů) — první načtení může trvat desítky sekund. Zkuste znovu za chvíli nebo použijte AI vyhledávání níže.";
    case "arad":
      return "Doporučený další krok: otevřete dedikovaný ARAD katalog (/arad/catalog) nebo zkuste znovu za chvíli — ČNB někdy odpovídá pomalu.";
    case "eurostat":
      return "Eurostat TOC je velký (~7k datasetů). Při prvním načtení nebo po restartu backendu to může trvat déle — zkuste znovu za chvíli nebo použijte AI vyhledávání níže.";
    default:
      return "Zkuste jiný zdroj, AI vyhledávání níže na stránce nebo zkuste později.";
  }
}

/**
 * Stavové metadaty (frontend; GET /api/catalog/status může vracet totéž).
 * browse_supported: "full" | "limited" | "partial" | "tree_beta"
 */
export const CATALOG_SOURCE_STATUS_MAP = Object.freeze({
  arad: {
    available: true,
    beta: false,
    requires_api_key: false,
    browse_supported: "full",
    globally_visible_tree: true,
    search_supported: true,
    preview_supported: true,
    reason_if_disabled: null,
  },
  csu: {
    available: true,
    beta: false,
    requires_api_key: false,
    browse_supported: "full",
    globally_visible_tree: true,
    search_supported: true,
    preview_supported: true,
    reason_if_disabled: null,
  },
  eurostat: {
    available: true,
    beta: false,
    requires_api_key: false,
    browse_supported: "full",
    globally_visible_tree: true,
    search_supported: true,
    preview_supported: true,
    reason_if_disabled: null,
  },
  ecb: {
    available: false,
    beta: true,
    requires_api_key: false,
    browse_supported: "none",
    globally_visible_tree: false,
    search_supported: false,
    preview_supported: true,
    reason_if_disabled: "Kurátorovaný ECB katalog je vypnutý — použijte ECB.",
  },
  ecb2: {
    available: true,
    beta: false,
    requires_api_key: false,
    browse_supported: "full",
    globally_visible_tree: true,
    search_supported: true,
    preview_supported: true,
    reason_if_disabled: null,
  },
  fred: {
    available: true,
    beta: true,
    requires_api_key: true,
    browse_supported: "limited_tree_plus_proxy_search",
    globally_visible_tree: true,
    search_supported: true,
    preview_supported: true,
    reason_if_disabled: "Bez FRED_API_KEY na backendu nejsou kategorie ani řady přes API.",
    api_model:
      "Strom kategorií jen doplňuje; řádné použití vyžaduje search (GET /api/fred/search→ …/fred/series/search) a řady bez leaku klíče.",
  },
  worldbank: {
    available: false,
    beta: true,
    requires_api_key: false,
    browse_supported: "none",
    globally_visible_tree: false,
    search_supported: false,
    preview_supported: true,
    reason_if_disabled: "Klasický World Bank WDI katalog je vypnutý — použijte World Bank.",
  },
  data360: {
    available: true,
    beta: true,
    requires_api_key: false,
    browse_supported: "countries_first",
    globally_visible_tree: true,
    search_supported: true,
    preview_supported: true,
    reason_if_disabled: null,
  },
  bis: {
    available: true,
    beta: true,
    requires_api_key: false,
    browse_supported: "sdmx_dimensions",
    globally_visible_tree: true,
    search_supported: true,
    preview_supported: true,
    reason_if_disabled: null,
    api_model: "SDMX dataflow / key — náhled až po dimenzích; proxy GET /api/bis/dataflows, /api/bis/data",
  },
  imf: {
    available: true,
    beta: true,
    requires_api_key: true,
    browse_supported: "full",
    globally_visible_tree: true,
    search_supported: true,
    preview_supported: true,
    reason_if_disabled: "Vyžaduje IMF_API_KEY a imf_availability.json na serveru.",
    api_model: "SDMX 3.0 country-first + indicator-first (imf_availability.json).",
  },
  imf2: {
    available: false,
    beta: true,
    requires_api_key: false,
    browse_supported: "none",
    globally_visible_tree: false,
    search_supported: false,
    preview_supported: false,
    reason_if_disabled: "IMF 2 je dočasně vypnutý.",
  },
  oecd: {
    available: false,
    beta: true,
    requires_api_key: false,
    browse_supported: "none",
    globally_visible_tree: false,
    search_supported: false,
    preview_supported: false,
    reason_if_disabled: "OECD katalog je dočasně vypnutý.",
  },
  oecd2: {
    available: false,
    beta: true,
    requires_api_key: false,
    browse_supported: "none",
    globally_visible_tree: false,
    search_supported: false,
    preview_supported: false,
    reason_if_disabled: "OECD 2 je dočasně vypnutý.",
  },
  oecd3: {
    available: false,
    beta: true,
    requires_api_key: false,
    browse_supported: "none",
    globally_visible_tree: false,
    search_supported: false,
    preview_supported: false,
    reason_if_disabled: "OECD 3 je dočasně vypnutý.",
  },
  commodities: {
    available: true,
    beta: true,
    requires_api_key: false,
    browse_supported: "full",
    globally_visible_tree: true,
    search_supported: true,
    preview_supported: true,
    reason_if_disabled: null,
  },
  oecd4: {
    available: true,
    beta: true,
    requires_api_key: false,
    browse_supported: "full",
    globally_visible_tree: true,
    search_supported: true,
    preview_supported: true,
    reason_if_disabled: null,
    api_model: "Offline mirror data/oecd4 — kategorie → dataset → země → ukazatel.",
  },
  alphavantage: {
    available: true,
    beta: true,
    requires_api_key: true,
    browse_supported: "limited_tree_plus_proxy_search",
    globally_visible_tree: true,
    search_supported: true,
    preview_supported: true,
    reason_if_disabled: "Bez ALPHAVANTAGE_API_KEY na serveru nelze vyhledávat symboly ani stahovat data.",
    api_model:
      "Bez klasického katalogu — vyhledávání názvem firmy nebo tickerem (SYMBOL_SEARCH). Strom zobrazí jen už napojené zdroje.",
  },
});

/** Global /search/catalog jednotný strom nečteme (Data360 atd.). */
export function shouldSkipUnifiedGlobeBrowseFetch(def) {
  const id = def?.id;
  if (!id) return false;
  const st = CATALOG_SOURCE_STATUS_MAP[id];
  if (!st) return false;
  if (st.browse_supported === "none") return true;
  return st.globally_visible_tree === false;
}

export const UNIFIED_GLOBAL_BROWSE_SKIP_CZ = Object.freeze({
});
