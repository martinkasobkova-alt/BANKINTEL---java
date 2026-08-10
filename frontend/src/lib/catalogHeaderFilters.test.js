import {
  catalogHeaderFiltersActiveCount,
  defaultCatalogHeaderFilters,
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
