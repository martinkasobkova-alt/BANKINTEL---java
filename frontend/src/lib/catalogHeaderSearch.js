import { CATALOGS, isCatalogHidden } from "@/lib/catalogDefinitions";
import { normalizeCatalogBrowseIdFromUrlParam } from "@/lib/catalogBackNav";

export const CATALOG_HEADER_SEARCH_EVENT = "bankoapp:catalog-header-search";
export const CATALOG_HEADER_BROWSE_TOGGLE_EVENT = "bankoapp:catalog-header-browse-toggle";
export const CATALOG_HEADER_AI_STORAGE_KEY = "bankoapp:header-catalog-ai";
export const CATALOG_HEADER_AI_SCOPE_STORAGE_KEY = "bankoapp:header-catalog-ai-scope";
export const CATALOG_HUB_PATH = "/search/catalog";

/** Sentinel hodnota — hledej ve všech zdrojích. */
export const AI_SCOPE_ALL = "__all__";
/** Sentinel hodnota — hledej jen ve zdrojích zaškrtnutých na stránce. */
export const AI_SCOPE_SELECTED = "__selected__";

/** Katalogy dostupné ve výběru globální lišty (bez skrytých legacy zdrojů). */
export const HEADER_CATALOG_OPTIONS = CATALOGS.filter((c) => !isCatalogHidden(c));

export const DEFAULT_HEADER_CATALOG_ID =
  HEADER_CATALOG_OPTIONS.find((c) => c.id === "arad")?.id || HEADER_CATALOG_OPTIONS[0]?.id || "arad";

export function readHeaderAiScope() {
  try {
    return localStorage.getItem(CATALOG_HEADER_AI_SCOPE_STORAGE_KEY) || AI_SCOPE_ALL;
  } catch {
    return AI_SCOPE_ALL;
  }
}

export function writeHeaderAiScope(scope) {
  try {
    localStorage.setItem(CATALOG_HEADER_AI_SCOPE_STORAGE_KEY, String(scope || AI_SCOPE_ALL));
  } catch {
    /* ignore */
  }
}

/** @deprecated AI je vždy zapnuto. Ponecháno pro zpětnou kompatibilitu. */
export function readHeaderAiPreference() {
  return true;
}

/** @deprecated AI je vždy zapnuto. Ponecháno pro zpětnou kompatibilitu. */
export function writeHeaderAiPreference(_enabled) {
  /* no-op */
}

/**
 * @param {{ q: string, aiScope?: string, currentSearch?: string, submitNonce?: number }} opts
 *
 * aiScope:
 *   AI_SCOPE_ALL ("__all__")       → hledání ve všech zdrojích (výchozí)
 *   AI_SCOPE_SELECTED ("__selected__") → hledání jen ve vybraných zdrojích
 *   catalogId (např. "arad")        → hledání jen v tomto jednom zdroji
 */
export function buildCatalogHubSearchUrl({
  q,
  aiScope = AI_SCOPE_ALL,
  currentSearch = "",
  submitNonce = Date.now(),
}) {
  const sp = new URLSearchParams(String(currentSearch || "").replace(/^\?/, ""));
  const trimmed = String(q || "").trim();
  if (trimmed.length >= 2) sp.set("q", trimmed);
  else sp.delete("q");

  // scope= pouze pro jednozrojový režim
  if (aiScope && aiScope !== AI_SCOPE_ALL && aiScope !== AI_SCOPE_SELECTED) {
    const scope = normalizeCatalogBrowseIdFromUrlParam(String(aiScope).trim().toLowerCase());
    if (scope && HEADER_CATALOG_OPTIONS.some((c) => c.id === scope)) sp.set("scope", scope);
    else sp.delete("scope");
  } else {
    sp.delete("scope");
  }

  // aiScope= pouze pro "vybrané" (last param, all je výchozí)
  if (aiScope === AI_SCOPE_SELECTED) {
    sp.set("aiScope", "selected");
  } else {
    sp.delete("aiScope");
  }

  sp.delete("set_id");
  sp.delete("preview");
  sp.delete("indicator_id");
  sp.set("hs", String(submitNonce));

  // AI je vždy zapnuto
  sp.set("ai", "1");
  sp.set("runDeep", "1");

  const qs = sp.toString();
  return qs ? `${CATALOG_HUB_PATH}?${qs}` : CATALOG_HUB_PATH;
}

/**
 * Aktualizuje jen `scope` (a zruší preview parametry) — bez nového submitu hledání.
 * @param {{ catalogId?: string, currentSearch?: string }} opts
 */
export function buildCatalogHubScopeUrl({ catalogId = DEFAULT_HEADER_CATALOG_ID, currentSearch = "" }) {
  const sp = new URLSearchParams(String(currentSearch || "").replace(/^\?/, ""));
  const scope = normalizeCatalogBrowseIdFromUrlParam(String(catalogId || "").trim().toLowerCase());
  if (scope && HEADER_CATALOG_OPTIONS.some((c) => c.id === scope)) sp.set("scope", scope);
  else sp.delete("scope");
  sp.delete("aiScope");
  sp.delete("set_id");
  sp.delete("preview");
  sp.delete("indicator_id");
  const qs = sp.toString();
  return qs ? `${CATALOG_HUB_PATH}?${qs}` : CATALOG_HUB_PATH;
}

/**
 * @param {string} search
 */
export function parseCatalogHeaderFromLocation(search) {
  const sp = new URLSearchParams(String(search || "").replace(/^\?/, ""));
  const q = String(sp.get("q") || "").trim();
  const scopeRaw = String(sp.get("scope") || "").trim().toLowerCase();
  const scope = normalizeCatalogBrowseIdFromUrlParam(scopeRaw);
  const catalogId =
    scope && HEADER_CATALOG_OPTIONS.some((c) => c.id === scope) ? scope : DEFAULT_HEADER_CATALOG_ID;

  let aiScope = AI_SCOPE_ALL;
  if (scope && HEADER_CATALOG_OPTIONS.some((c) => c.id === scope)) {
    aiScope = scope;
  } else if (sp.get("aiScope") === "selected") {
    aiScope = AI_SCOPE_SELECTED;
  }

  return {
    q,
    catalogId,
    useAi: true, // vždy AI (ponecháno pro zpětnou kompatibilitu)
    aiScope,
  };
}

/**
 * @param {{ q: string, catalogId?: string, useAi?: boolean }} detail
 */
export function dispatchCatalogHeaderBrowseToggle() {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new CustomEvent(CATALOG_HEADER_BROWSE_TOGGLE_EVENT));
}
