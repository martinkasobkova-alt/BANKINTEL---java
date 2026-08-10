import { CATALOGS, CATALOGS_DEFAULT_SELECTED_IDS, isCatalogHidden, WB_DEFAULT_COUNTRY } from "@/lib/catalogDefinitions";

export const CATALOG_HEADER_FILTERS_STORAGE_KEY = "bankoapp:catalog-header-filters";
export const CATALOG_HEADER_FILTERS_EVENT = "bankoapp:catalog-header-filters-changed";

export const HEADER_FILTER_IN_APP_ID = "app";

export const HEADER_FILTER_CATALOG_OPTIONS = Object.freeze([
  Object.freeze({
    id: HEADER_FILTER_IN_APP_ID,
    label: "V aplikaci",
    appSearch: true,
    tier: "production",
  }),
  ...CATALOGS.filter((c) => !isCatalogHidden(c)),
]);

const DEFAULT_SELECTED_IDS = [
  HEADER_FILTER_IN_APP_ID,
  ...CATALOGS_DEFAULT_SELECTED_IDS.filter((id) => !isCatalogHidden({ id })),
];

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
    const selectedIds = Array.isArray(parsed.selectedIds)
      ? normalizeSelectedIds(parsed.selectedIds)
      : defaultCatalogHeaderFilters().selectedIds;
    return {
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
