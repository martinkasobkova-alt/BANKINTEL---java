import { isExplorerBrowseRowLoading, resolveExplorerLoadingRowKey } from "./catalogExplorerLoading";

describe("catalogExplorerLoading", () => {
  const loading = {
    fredCategories: new Set(["32263|FRED::CAT||32263"]),
    ecb2Countries: new Set(["BG|ECB · ověřené řady > BG"]),
    ecbCountries: new Set(["CZ"]),
  };

  it("detects FRED category loading by path key", () => {
    expect(
      isExplorerBrowseRowLoading(
        {
          path: "FRED::CAT||32263",
          fred_category_id: "32263",
          item_kind: "category",
        },
        "fred",
        loading,
      ),
    ).toBe(true);
  });

  it("detects ECB2 country loading", () => {
    expect(
      isExplorerBrowseRowLoading(
        { path: "ECB · ověřené řady > BG", ecb_country: "BG", kind: "cat" },
        "ecb2",
        loading,
      ),
    ).toBe(true);
  });

  it("resolveExplorerLoadingRowKey walks selection from the end", () => {
    const key = resolveExplorerLoadingRowKey(
      ["FRED", "FRED::CAT||32263"],
      [{ path: "FRED::CAT||32263", fred_category_id: "32263" }],
      "fred",
      loading,
    );
    expect(key).toBe("FRED::CAT||32263");
  });
});
