import {
  buildCatalogPathPrefixes,
  buildCatalogPathSegments,
  resolveCatalogCategoryPathPrefixes,
  splitCatalogPath,
} from "./catalogSearchPathNav";

describe("catalogSearchPathNav", () => {
  const categories = new Set([
    "Database by themes",
    "Database by themes > Population and social conditions",
    "Database by themes > Population and social conditions > Labour market",
    "Database by themes > Population and social conditions > Labour market > Digital platform employment - experimental statistics",
  ]);

  const samplePath =
    "Database by themes > Population and social conditions > Labour market > Digital platform employment - experimental statistics > Distribution of digital platform workers";

  it("splitCatalogPath parses breadcrumb", () => {
    expect(splitCatalogPath("A > B > C")).toEqual(["A", "B", "C"]);
  });

  it("resolveCatalogCategoryPathPrefixes drops dataset leaf title", () => {
    expect(resolveCatalogCategoryPathPrefixes(samplePath, categories)).toEqual([
      "Database by themes",
      "Database by themes > Population and social conditions",
      "Database by themes > Population and social conditions > Labour market",
      "Database by themes > Population and social conditions > Labour market > Digital platform employment - experimental statistics",
    ]);
  });

  it("buildCatalogPathSegments marks dataset leaf as non-clickable", () => {
    const segs = buildCatalogPathSegments(samplePath, categories);
    expect(segs[segs.length - 1].clickable).toBe(false);
    expect(segs[0].clickable).toBe(true);
  });

  it("buildCatalogPathPrefixes returns cumulative prefixes", () => {
    expect(buildCatalogPathPrefixes("A > B > C")).toEqual(["A", "A > B", "A > B > C"]);
  });
});
