import {
  buildLocalCrossSearchFlatResults,
  catalogSearchHitToFlatEntry,
  catalogSearchScore,
  CLASSIC_SEARCH_SCOPE_ALL,
  filterHitsByCatalogScope,
  resolveClassicSearchCatalogDefs,
  runCatalogCrossSearch,
  sortCatalogSearchHits,
} from "./catalogCrossSearch";

const CATALOGS = [
  { id: "eurostat", label: "Eurostat", sourceType: "eurostat" },
  { id: "arad", label: "ARAD", sourceType: "arad" },
];

describe("resolveClassicSearchCatalogDefs", () => {
  it("returns single catalog when scope is arad", () => {
    const defs = resolveClassicSearchCatalogDefs(CATALOGS, "arad", new Set(["arad", "eurostat"]));
    expect(defs).toHaveLength(1);
    expect(defs[0].id).toBe("arad");
  });

  it("returns all catalogs when scope is all", () => {
    const defs = resolveClassicSearchCatalogDefs(
      CATALOGS,
      CLASSIC_SEARCH_SCOPE_ALL,
      new Set(["arad"]),
    );
    expect(defs.map((d) => d.id).sort()).toEqual(["arad", "eurostat"]);
  });
});

describe("filterHitsByCatalogScope", () => {
  it("drops hits from other catalogs", () => {
    const hits = [
      { catalog_id: "arad", set_id: "a1", name: "ARAD row" },
      { catalog_id: "eurostat", set_id: "e1", name: "Eurostat row" },
    ];
    const filtered = filterHitsByCatalogScope(hits, new Set(["arad"]));
    expect(filtered).toHaveLength(1);
    expect(filtered[0].catalog_id).toBe("arad");
  });
});

describe("runCatalogCrossSearch source scope", () => {
  it("when only ARAD selected never merges eurostat hits", async () => {
    const api = {
      post: jest.fn(async (_path, body) => {
        if (body.source === "arad") {
          return {
            data: {
              results: [
                {
                  set_id: "ARAD1",
                  catalog_id: "arad",
                  _search_score: 900,
                  name: "Bank profit",
                  row: { kind: "set", set_id: "ARAD1", name: "Bank profit" },
                },
              ],
            },
          };
        }
        return {
          data: {
            results: [
              {
                set_id: "EU1",
                catalog_id: "eurostat",
                _search_score: 999,
                name: "Should not appear",
                row: { kind: "set", set_id: "EU1", name: "Should not appear" },
              },
            ],
          },
        };
      }),
    };
    const result = await runCatalogCrossSearch(api, {
      query: "zisk bank",
      catalogDefs: [CATALOGS[1]],
    });
    expect(result.hits.every((h) => h.catalog_id === "arad")).toBe(true);
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.post.mock.calls[0][1].source).toBe("arad");
  });
});

describe("catalogSearchScore", () => {
  it("reads _search_score from backend hit", () => {
    expect(catalogSearchScore({ _search_score: 420 })).toBe(420);
    expect(catalogSearchScore({})).toBe(0);
  });
});

describe("sortCatalogSearchHits", () => {
  it("orders by _search_score desc, not alphabetically", () => {
    const hits = [
      { set_id: "b", name: "Alpha weights", catalog_label: "Eurostat", _search_score: 50 },
      { set_id: "a", name: "Zulu HICP Czech Republic", catalog_label: "Eurostat", _search_score: 900, previewable: true },
    ];
    const sorted = sortCatalogSearchHits(hits);
    expect(sorted[0].set_id).toBe("a");
    expect(catalogSearchScore(sorted[0])).toBeGreaterThan(catalogSearchScore(sorted[1]));
  });

  it("prefers higher score over previewable-only tie", () => {
    const hits = [
      { set_id: "low", _search_score: 100, previewable: true, catalog_label: "A", name: "A" },
      { set_id: "high", _search_score: 800, previewable: false, catalog_label: "B", name: "B" },
    ];
    expect(sortCatalogSearchHits(hits)[0].set_id).toBe("high");
  });
});

describe("catalogSearchHitToFlatEntry", () => {
  it("maps backend hit with nested row", () => {
    const entry = catalogSearchHitToFlatEntry(
      {
        set_id: "prc_hicp_manr",
        catalog_id: "eurostat",
        catalog_label: "Eurostat",
        _search_score: 700,
        previewable: true,
        row: {
          kind: "set",
          item_kind: "dataset",
          set_id: "prc_hicp_manr",
          name: "HICP Czech Republic",
          full_path: "Eurostat > Prices",
        },
      },
      CATALOGS,
    );
    expect(entry.def.id).toBe("eurostat");
    expect(entry.row.set_id).toBe("prc_hicp_manr");
    expect(entry.searchScore).toBe(700);
    expect(entry.resultSource).toBe("backend");
  });
});

describe("buildLocalCrossSearchFlatResults", () => {
  it("uses AND token matching on loaded trees", () => {
    const rows = [
      {
        kind: "set",
        set_id: "x1",
        name: "HICP Czech Republic",
        path: "Prices > HICP CZ",
        parentPath: "Prices",
      },
      {
        kind: "set",
        set_id: "x2",
        name: "HICP weights",
        path: "Prices > weights",
        parentPath: "Prices",
      },
    ];
    const both = buildLocalCrossSearchFlatResults({
      catalogs: CATALOGS,
      selectedIds: new Set(["eurostat"]),
      indexedRowsByCat: { eurostat: rows },
      query: "hicp czech",
    });
    expect(both.some((e) => e.row.set_id === "x1")).toBe(true);
    expect(both.some((e) => e.row.set_id === "x2")).toBe(false);
  });
});

describe("runCatalogCrossSearch", () => {
  it("merges parallel catalog search and sorts by score", async () => {
    const api = {
      post: jest.fn(async (_path, body) => {
        if (body.source === "eurostat") {
          return {
            data: {
              results: [
                {
                  set_id: "ea",
                  name: "HICP euro area",
                  catalog_id: "eurostat",
                  _search_score: 200,
                  row: { kind: "set", set_id: "ea", name: "HICP euro area" },
                },
                {
                  set_id: "cz",
                  name: "HICP Czech Republic",
                  catalog_id: "eurostat",
                  _search_score: 880,
                  previewable: true,
                  row: { kind: "set", set_id: "cz", name: "HICP Czech Republic" },
                },
              ],
            },
          };
        }
        return {
          data: {
            results: [
              {
                set_id: "GDP_PL",
                name: "GDP Poland",
                catalog_id: "arad",
                _search_score: 750,
                row: { kind: "set", set_id: "GDP_PL", name: "GDP Poland", indicator_id: "GDP1" },
              },
            ],
          },
        };
      }),
    };

    const result = await runCatalogCrossSearch(api, {
      query: "inflace Česko",
      catalogDefs: CATALOGS,
    });
    expect(result.hits[0].set_id).toBe("cz");
    expect(result.allFailed).toBe(false);
    expect(api.post).toHaveBeenCalledTimes(2);
  });

  it("marks allFailed when every source rejects", async () => {
    const api = {
      post: jest.fn(async () => {
        throw new Error("network");
      }),
    };
    const result = await runCatalogCrossSearch(api, {
      query: "HDP Polsko",
      catalogDefs: CATALOGS,
    });
    expect(result.allFailed).toBe(true);
    expect(result.hits).toEqual([]);
  });

  it("surfaces partialIndexMissing when a source lacks local index", async () => {
    const api = {
      post: jest.fn(async (_path, body) => {
        if (body.source === "arad") {
          return {
            data: {
              results: [],
              local_index_missing: true,
              index_status: "unavailable_index_missing",
              upstream_unavailable: true,
            },
          };
        }
        return {
          data: {
            results: [{ set_id: "EU1", catalog_id: "eurostat", name: "Row", _search_score: 10, row: {} }],
          },
        };
      }),
    };
    const result = await runCatalogCrossSearch(api, {
      query: "inflace",
      catalogDefs: CATALOGS,
    });
    expect(result.partialIndexMissing).toBe(true);
    expect(result.hits).toHaveLength(1);
    expect(result.sourceSummaries.find((s) => s.id === "arad")?.local_index_missing).toBe(true);
  });
});

describe("geo-relevant ordering", () => {
  it("HDP Polsko keeps Poland row above euro area by score", () => {
    const hits = sortCatalogSearchHits([
      {
        set_id: "GDP_EA",
        name: "GDP euro area",
        catalog_label: "OECD",
        _search_score: 220,
      },
      {
        set_id: "GDP_PL",
        name: "Gross domestic product Poland",
        catalog_label: "OECD",
        _search_score: 910,
        previewable: true,
      },
    ]);
    expect(hits[0].set_id).toBe("GDP_PL");
  });
});
