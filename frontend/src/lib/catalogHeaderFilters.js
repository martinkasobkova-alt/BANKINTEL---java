import { CATALOGS, CATALOGS_DEFAULT_SELECTED_IDS, isCatalogHidden, WB_DEFAULT_COUNTRY } from "@/lib/catalogDefinitions";

export const CATALOG_HEADER_FILTERS_STORAGE_KEY = "bankoapp:catalog-header-filters";
export const CATALOG_HEADER_FILTERS_EVENT = "bankoapp:catalog-header-filters-changed";

/**
 * Verze uloženého nastavení filtrů. Zvyšuje se, když do seznamu zdrojů přibude položka,
 * která má být od začátku zapnutá — starší uložený výběr ji totiž neobsahuje a bez
 * migrace by se lidem tiše vypnula funkce, kterou do teď měli.
 */
const FILTERS_VERSION = 2;

export const HEADER_FILTER_IN_APP_ID = "app";
/**
 * Akcie nejsou statistický katalog — mají vlastní backend (Yahoo Finance / Alpha Vantage)
 * a vlastní skupinu ve výsledcích. Ve filtru ale patří mezi ostatní zdroje: hledání je
 * jedno a uživatel musí umět říct, jestli ho tržní instrumenty zajímají, nebo ne.
 */
export const HEADER_FILTER_STOCKS_ID = "stocks";

export const HEADER_FILTER_CATALOG_OPTIONS = Object.freeze([
  Object.freeze({
    id: HEADER_FILTER_IN_APP_ID,
    label: "V aplikaci",
    appSearch: true,
    tier: "production",
  }),
  ...CATALOGS.filter((c) => !isCatalogHidden(c)),
  Object.freeze({
    id: HEADER_FILTER_STOCKS_ID,
    label: "Akcie",
    stockSearch: true,
    tier: "production",
  }),
]);

const DEFAULT_SELECTED_IDS = [
  HEADER_FILTER_IN_APP_ID,
  ...CATALOGS_DEFAULT_SELECTED_IDS.filter((id) => !isCatalogHidden({ id })),
  // Zapnuto ve výchozím stavu — akcie se ve výsledcích ukazovaly vždycky, filtr je tedy
  // možnost je vypnout, ne nová věc, kterou by si uživatel musel zapnout.
  HEADER_FILTER_STOCKS_ID,
];

/** Mají se do výsledků přimíchat akcie? Bez uložených filtrů ano (výchozí stav). */
export function isStockSearchSelected(selectedIds) {
  if (!Array.isArray(selectedIds)) return true;
  return selectedIds.includes(HEADER_FILTER_STOCKS_ID);
}

function normalizeSelectedIds(raw) {
  if (!Array.isArray(raw)) return null;
  const allowed = new Set(HEADER_FILTER_CATALOG_OPTIONS.map((c) => c.id));
  return raw.map((x) => String(x || "").trim()).filter((id) => allowed.has(id));
}

function resolveSelectedIds(raw) {
  if (Array.isArray(raw)) return normalizeSelectedIds(raw);
  const normalized = normalizeSelectedIds(raw);
  return normalized ?? [...DEFAULT_SELECTED_IDS];
}

export function defaultCatalogHeaderFilters() {
  return {
    v: FILTERS_VERSION,
    selectedIds: [...DEFAULT_SELECTED_IDS],
    browseLocalBranchOnly: false,
    browseSearchAcrossSelected: false,
    browseSearchCategoriesOnly: false,
    wbCountry: WB_DEFAULT_COUNTRY,
  };
}

export function loadCatalogHeaderFilters() {
  try {
    const raw = localStorage.getItem(CATALOG_HEADER_FILTERS_STORAGE_KEY);
    if (!raw) return defaultCatalogHeaderFilters();
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return defaultCatalogHeaderFilters();
    let selectedIds = Array.isArray(parsed.selectedIds)
      ? normalizeSelectedIds(parsed.selectedIds)
      : defaultCatalogHeaderFilters().selectedIds;
    // Nastavení uložené dřív, než akcie do seznamu přibyly, je nemá odškrtnuté — jen o nich
    // neví. Doplníme je, ať nikomu nezmizí akcie z výsledků jen kvůli aktualizaci.
    if (Number(parsed.v || 0) < FILTERS_VERSION && !selectedIds.includes(HEADER_FILTER_STOCKS_ID)) {
      selectedIds = [...selectedIds, HEADER_FILTER_STOCKS_ID];
    }
    return {
      v: FILTERS_VERSION,
      selectedIds,
      browseLocalBranchOnly: Boolean(parsed.browseLocalBranchOnly),
      browseSearchAcrossSelected: Boolean(parsed.browseSearchAcrossSelected),
      browseSearchCategoriesOnly: Boolean(parsed.browseSearchCategoriesOnly),
      wbCountry: String(parsed.wbCountry || WB_DEFAULT_COUNTRY).trim() || WB_DEFAULT_COUNTRY,
    };
  } catch {
    return defaultCatalogHeaderFilters();
  }
}

export function saveCatalogHeaderFilters(next) {
  const normalized = {
    ...defaultCatalogHeaderFilters(),
    ...(next && typeof next === "object" ? next : {}),
    v: FILTERS_VERSION,
    selectedIds: Array.isArray(next?.selectedIds)
      ? normalizeSelectedIds(next.selectedIds)
      : resolveSelectedIds(next?.selectedIds),
  };
  try {
    localStorage.setItem(CATALOG_HEADER_FILTERS_STORAGE_KEY, JSON.stringify(normalized));
  } catch {
    /* ignore */
  }
  if (typeof window !== "undefined") {
    // Defer to macro-task so it never fires during a React concurrent render unit.
    // Promise.resolve().then() (microtask) can still run between React work units
    // within the same render batch — setTimeout(0) guarantees post-batch execution.
    const detail = normalized;
    window.setTimeout(() =>
      window.dispatchEvent(new CustomEvent(CATALOG_HEADER_FILTERS_EVENT, { detail })),
    0);
  }
  return normalized;
}

export function catalogHeaderFiltersActiveCount(prefs) {
  const base = defaultCatalogHeaderFilters();
  const p = prefs && typeof prefs === "object" ? prefs : base;
  let count = 0;
  if (p.browseLocalBranchOnly) count += 1;
  if (p.browseSearchAcrossSelected) count += 1;
  if (p.browseSearchCategoriesOnly) count += 1;
  if (p.wbCountry && p.wbCountry !== base.wbCountry) count += 1;
  const selected = new Set(Array.isArray(p.selectedIds) ? p.selectedIds : base.selectedIds);
  const defaultSet = new Set(base.selectedIds);
  if (selected.size !== defaultSet.size || [...selected].some((id) => !defaultSet.has(id))) {
    count += 1;
  }
  return count;
}
