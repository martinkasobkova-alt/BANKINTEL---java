import {
  catalogHeaderFiltersActiveCount,
  CATALOG_HEADER_FILTERS_STORAGE_KEY,
  defaultCatalogHeaderFilters,
  HEADER_FILTER_STOCKS_ID,
  isStockSearchSelected,
  loadCatalogHeaderFilters,
  saveCatalogHeaderFilters,
} from "@/lib/catalogHeaderFilters";

describe("catalogHeaderFilters", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("returns defaults when storage empty", () => {
    const prefs = loadCatalogHeaderFilters();
    expect(prefs.selectedIds.length).toBeGreaterThan(0);
    expect(prefs.browseLocalBranchOnly).toBe(false);
  });

  it("persists and reloads filter prefs", () => {
    saveCatalogHeaderFilters({
      ...defaultCatalogHeaderFilters(),
      browseSearchAcrossSelected: true,
      selectedIds: ["arad", "csu"],
    });
    const loaded = loadCatalogHeaderFilters();
    expect(loaded.browseSearchAcrossSelected).toBe(true);
    expect(loaded.selectedIds).toEqual(["arad", "csu"]);
  });

  it("persists empty catalog selection when clearing all", () => {
    saveCatalogHeaderFilters({
      ...defaultCatalogHeaderFilters(),
      selectedIds: [],
    });
    const loaded = loadCatalogHeaderFilters();
    expect(loaded.selectedIds).toEqual([]);
  });

  it("counts active non-default filters", () => {
    const count = catalogHeaderFiltersActiveCount({
      ...defaultCatalogHeaderFilters(),
      browseLocalBranchOnly: true,
    });
    expect(count).toBe(1);
  });
});

/**
 * Akcie přibyly do seznamu zdrojů až dodatečně. Kdo měl filtry uložené z dřívějška, ten je
 * v uloženém výběru nemá — ale nikdy je neodškrtl, jen o nich nastavení nevědělo. Bez
 * migrace by mu akcie z výsledků zmizely jen kvůli aktualizaci.
 */
describe("catalogHeaderFilters — akcie ve filtru zdrojů", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("ve výchozím stavu jsou akcie zapnuté", () => {
    expect(loadCatalogHeaderFilters().selectedIds).toContain(HEADER_FILTER_STOCKS_ID);
    expect(isStockSearchSelected(defaultCatalogHeaderFilters().selectedIds)).toBe(true);
  });

  it("staršímu uloženému nastavení akcie doplní", () => {
    localStorage.setItem(
      CATALOG_HEADER_FILTERS_STORAGE_KEY,
      JSON.stringify({ selectedIds: ["arad", "csu"], browseLocalBranchOnly: false }),
    );
    expect(loadCatalogHeaderFilters().selectedIds).toContain(HEADER_FILTER_STOCKS_ID);
  });

  it("vědomé odškrtnutí akcií zůstane odškrtnuté", () => {
    saveCatalogHeaderFilters({ ...defaultCatalogHeaderFilters(), selectedIds: ["arad", "csu"] });
    const loaded = loadCatalogHeaderFilters();
    expect(loaded.selectedIds).not.toContain(HEADER_FILTER_STOCKS_ID);
    expect(isStockSearchSelected(loaded.selectedIds)).toBe(false);
  });
});
