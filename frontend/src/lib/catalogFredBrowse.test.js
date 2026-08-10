import {
  rebaseFredExpandNodePaths,
  resolveFredBrowseLazyAction,
} from "./catalogFredBrowse";

describe("catalogFredBrowse", () => {
  it("resolveFredBrowseLazyAction detects FRED category rows", () => {
    expect(
      resolveFredBrowseLazyAction({
        item_kind: "category",
        kind: "set",
        fred_category_id: "32263",
        path: "FRED::CAT||32263",
      }),
    ).toEqual({
      kind: "category",
      id: "32263",
      path: "FRED::CAT||32263",
    });
  });

  it("rebaseFredExpandNodePaths rewrites API paths under catalog path", () => {
    const rebased = rebaseFredExpandNodePaths(
      {
        name: "International Data (32263)",
        path: "FRED::32263",
        children: [],
        sets: [
          {
            set_id: "CAT||33060",
            name: "Academic Data (33060)",
            kind: "category",
            fred_category_id: "33060",
          },
          {
            set_id: "GDP",
            name: "Gross Domestic Product (GDP)",
            kind: "selection",
            fred_series_id: "GDP",
          },
        ],
      },
      "FRED::CAT||32263",
    );
    expect(rebased.path).toBe("FRED::CAT||32263");
    expect(rebased.sets[0].fred_category_id).toBe("33060");
    expect(rebased.sets[1].fred_series_id).toBe("GDP");
  });
});
