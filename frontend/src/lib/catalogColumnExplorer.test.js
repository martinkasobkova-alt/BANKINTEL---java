import {
  buildCatalogExplorerColumns,
  buildExplorerBreadcrumbItems,
  explorerRowIsCategory,
  rowParentPathKey,
} from "./catalogColumnExplorer";
import { flattenCatalogCategories, patchBrowseRowsForLazyCountry } from "./catalogTree";
import { rebaseBisSeriesNodePaths } from "./catalogBisBrowse";
import { rebaseFredExpandNodePaths } from "./catalogFredBrowse";

describe("buildCatalogExplorerColumns", () => {
  const rows = [
    { kind: "cat", path: "SDDS Plus", name: "SDDS Plus", parentPath: "", depth: 0 },
    { kind: "cat", path: "Statistická data", name: "Statistická data", parentPath: "", depth: 0 },
    {
      kind: "cat",
      path: "Statistická data > Měnová a finanční statistika",
      name: "Měnová a finanční statistika",
      parentPath: "Statistická data",
      depth: 1,
    },
    {
      kind: "set",
      path: "Statistická data > Měnová a finanční statistika > S1",
      name: "Ukazatel 1",
      parentPath: "Statistická data > Měnová a finanční statistika",
      set_id: "S1",
      depth: 2,
    },
  ];

  it("renders only root column when nothing selected", () => {
    const cols = buildCatalogExplorerColumns(rows, [], null, 4);
    expect(cols).toHaveLength(1);
    expect(cols[0].items.map((r) => r.name)).toEqual(["SDDS Plus", "Statistická data"]);
  });

  it("opens child column after selecting a root folder", () => {
    const cols = buildCatalogExplorerColumns(rows, ["Statistická data"], null, 4);
    expect(cols).toHaveLength(2);
    expect(cols[0].selectedPath).toBe("Statistická data");
    expect(cols[1].parentPath).toBe("Statistická data");
    expect(cols[1].items.map((r) => r.name)).toEqual(["Měnová a finanční statistika"]);
  });

  it("shows series in deepest column", () => {
    const cols = buildCatalogExplorerColumns(
      rows,
      ["Statistická data", "Statistická data > Měnová a finanční statistika"],
      null,
      4,
    );
    expect(cols).toHaveLength(3);
    expect(cols[2].items.map((r) => r.name)).toEqual(["Ukazatel 1"]);
  });

  it("adds leaf column even when maxColumns equals selection depth", () => {
    const deepRows = [
      ...rows,
      {
        kind: "cat",
        path: "Statistická data > Měnová a finanční statistika > Trhy",
        name: "Trhy",
        parentPath: "Statistická data > Měnová a finanční statistika",
        depth: 2,
      },
      {
        kind: "set",
        path: "Statistická data > Měnová a finanční statistika > Trhy::ARAD1",
        name: "Řada A",
        parentPath: "Statistická data > Měnová a finanční statistika > Trhy",
        set_id: "ARAD1",
        depth: 3,
      },
    ];
    const cols = buildCatalogExplorerColumns(
      deepRows,
      [
        "Statistická data",
        "Statistická data > Měnová a finanční statistika",
        "Statistická data > Měnová a finanční statistika > Trhy",
      ],
      null,
      3,
    );
    expect(cols).toHaveLength(4);
    expect(cols[3].items.map((r) => r.name)).toEqual(["Řada A"]);
  });

  it("rowParentPathKey uses parentPath for sets", () => {
    expect(rowParentPathKey(rows[3])).toBe("Statistická data > Měnová a finanční statistika");
  });

  it("buildExplorerBreadcrumbItems includes catalog, folders and series", () => {
    const crumbs = buildExplorerBreadcrumbItems(
      ["Statistická data", "Statistická data > Měnová a finanční statistika"],
      rows,
      "ARAD",
      rows[3],
    );
    expect(crumbs.map((c) => c.label)).toEqual([
      "ARAD",
      "Statistická data",
      "Měnová a finanční statistika",
      "Ukazatel 1",
    ]);
  });

  it("shows ECB 2 flow folders after lazy country patch", () => {
    const rootPath = "ECB · ověřené řady";
    const countryPath = `${rootPath} > U2`;
    const root = {
      path: rootPath,
      name: rootPath,
      children: [
        {
          path: countryPath,
          name: "Eurozóna (EA20) (U2)",
          ecb_country: "U2",
          ecb_country_lazy: true,
          children: [],
          sets: [],
        },
      ],
      sets: [],
    };
    const base = flattenCatalogCategories([root]);
    const lazy = base.find((r) => r.ecb_country === "U2");
    const countryNode = {
      path: countryPath,
      name: "Eurozóna (EA20) (U2)",
      ecb_country: "U2",
      children: [
        {
          path: `${countryPath} > BSI`,
          name: "BSI · bilance bank (100)",
          ecb_country: "U2",
          ecb_flow: "BSI",
          ecb_flow_lazy: true,
          children: [],
          sets: [],
        },
      ],
      sets: [],
    };
    const patched = patchBrowseRowsForLazyCountry(base, countryNode, lazy);
    const cols = buildCatalogExplorerColumns(patched, [rootPath, countryPath], null, 4);
    expect(cols).toHaveLength(3);
    expect(cols[2].items.map((r) => r.ecb_flow)).toEqual(["BSI"]);
  });

  it("treats BIS dataflow rows as categories and opens country column after patch", () => {
    const rootPath = "BIS";
    const letterPath = `${rootPath} > C`;
    const root = {
      path: rootPath,
      name: "BIS — datové toky",
      children: [
        {
          path: letterPath,
          name: "C",
          children: [],
          sets: [
            {
              set_id: "CPMI_CASHLESS||DATAFLOW",
              name: "CPMI cashless payments",
              kind: "dataflow",
              bis_dataflow: "CPMI_CASHLESS",
            },
          ],
        },
      ],
      sets: [],
    };
    const base = flattenCatalogCategories([root]);
    const dataflowRow = base.find((r) => r.bis_dataflow === "CPMI_CASHLESS");
    expect(explorerRowIsCategory(dataflowRow)).toBe(true);
    const dataflowPath = dataflowRow.path;

    const seriesRoot = rebaseBisSeriesNodePaths(
      {
        name: "CPMI cashless payments",
        path: "BIS::CPMI_CASHLESS",
        children: [
          {
            name: "Czech Republic (CZ)",
            path: "BIS::CPMI_CASHLESS > CZ",
            bis_lazy_country: true,
            ref_area: "CZ",
            children: [],
            sets: [],
          },
        ],
        sets: [],
      },
      dataflowPath,
    );
    const patched = patchBrowseRowsForLazyCountry(base, seriesRoot, dataflowRow);
    const cols = buildCatalogExplorerColumns(
      patched,
      [rootPath, letterPath, dataflowPath],
      null,
      4,
    );
    expect(cols).toHaveLength(4);
    expect(cols[3].items.map((r) => r.ref_area)).toEqual(["CZ"]);
  });

  it("treats FRED category rows as categories and opens children after expand patch", () => {
    const rootPath = "FRED";
    const root = {
      path: rootPath,
      name: "FRED — hlavní kategorie",
      children: [],
      sets: [
        {
          set_id: "CAT||32263",
          name: "International Data (32263)",
          kind: "category",
          fred_category_id: "32263",
        },
      ],
    };
    const base = flattenCatalogCategories([root]);
    const categoryRow = base.find((r) => r.fred_category_id === "32263");
    expect(explorerRowIsCategory(categoryRow)).toBe(true);

    const expandRoot = rebaseFredExpandNodePaths(
      {
        name: "International Data (32263)",
        path: "FRED::32263",
        children: [],
        sets: [
          {
            set_id: "GDP",
            name: "Gross Domestic Product (GDP)",
            kind: "selection",
            fred_series_id: "GDP",
          },
        ],
      },
      categoryRow.path,
    );
    const patched = patchBrowseRowsForLazyCountry(base, expandRoot, categoryRow);
    const cols = buildCatalogExplorerColumns(patched, [rootPath, categoryRow.path], null, 4);
    expect(cols).toHaveLength(3);
    expect(cols[2].items.map((r) => r.fred_series_id)).toEqual(["GDP"]);
  });
});
