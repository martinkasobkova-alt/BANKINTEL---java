/**
 * Finální regresní QA — sjednocená katalogová preview architektura.
 * Pokrývá všechny migrované stránky + export + snapshot + compare + layout parity.
 */
import {
  buildChartExportSheets,
  buildClipboardTextWide,
  buildUserWideTable,
  TECHNICAL_CLIPBOARD_COLUMNS,
} from "@/charts/chartExport";
import { contractFromAradViewState } from "@/charts/contractFromAradViewState";
import { createEmptyChartContract, normalizeChartPoint } from "@/charts/chartDataContract";
import {
  buildCatalogPreviewState,
  buildCompareConfigFromPreview,
  catalogPreviewNeedsDimensionChoice,
  extractSelectedDimensionsForStorage,
} from "@/lib/catalogDimensions";
import { buildExternalCatalogChartConfig } from "@/lib/catalogPersonalDashboard";
import { createExternalCatalogWidgetWithSnapshot } from "@/lib/catalogDashboardWidget";
import { buildAradDataFromCatalogPreview, canRenderAradCatalogChart } from "@/lib/mapCatalogPreviewToArad";
import { widgetPatchAffectsDataCache, widgetInitialFromListRow } from "@/lib/widgetSnapshot";
import { readSourceModuleByBasename } from "@/test/readSourceModule";

const MIGRATED_PAGES = [
  { file: "GlobalCatalogSearchPage.jsx", via: "CatalogLiveChartPreview", compare: true },
  { file: "BisCatalogPage.jsx", via: "CatalogChartPreview", compare: true },
  { file: "OecdCatalogPage.jsx", via: "CatalogChartPreview", compare: false },
  { file: "FredCatalogPage.jsx", via: "CatalogChartPreview", compare: false },
  { file: "CommoditiesPage.jsx", via: "CatalogChartPreview", compare: false },
  { file: "CatalogTopicBrowsePage.jsx", via: "CatalogChartPreview", compare: true },
];

function readPage(name) {
  return readSourceModuleByBasename(name);
}

function buildContractFromPreview(preview, title = "Test") {
  const arad = buildAradDataFromCatalogPreview(preview, title);
  return contractFromAradViewState({
    title,
    unit: arad.unit || preview?.metadata?.unit || "",
    chartKind: "line",
    latestDataMode: false,
    isMultiSeries: Boolean(arad.multi_series),
    seriesList: arad.multi_series
      ? (arad.series || []).map((s) => ({ key: s.key, label: s.label }))
      : [{ key: "main", label: title }],
    chartRows: arad.multi_series
      ? arad.rows.map((r) => ({ period: r.period, x: r.period, ...r }))
      : arad.rows.map((r) => ({ period: r.period, x: r.period, value: r.value })),
    data: arad,
    widget: { id: "qa", config: { selected_dimensions: extractSelectedDimensionsForStorage(preview) } },
    frequency: arad.frequency || preview?.chart_frequency || "",
  });
}

describe("final regression — migrated pages use unified preview", () => {
  test.each(MIGRATED_PAGES)("$file uses CatalogChartPreview stack", ({ file, via }) => {
    const src = readPage(file);
    expect(src).toContain(via);
    expect(src).not.toMatch(/import SourcePreview from/);
    expect(src).not.toMatch(/import AradView from/);
  });

  test("CatalogChartPreview forces compact KPI summary in AradView preview", () => {
    const src = readSourceModuleByBasename("CatalogChartPreview.jsx");
    expect(src).toContain("kpiSummaryMode={catalogKpiSummaryMode(catalogChartSize)}");
    expect(src).toContain("catalogChartSize={catalogChartSize}");
  });

  test.each([
    "BisCatalogPage.jsx",
    "OecdCatalogPage.jsx",
    "FredCatalogPage.jsx",
    "CommoditiesPage.jsx",
    "CatalogTopicBrowsePage.jsx",
  ])("%s wires dashboard add helper", (file) => {
    const src = readPage(file);
    expect(src).toContain("addCatalogPreviewToPersonalDashboard");
    expect(src).toContain("buildCatalogChartActionsProps");
  });

  test("GlobalCatalogSearchPage wires compare save", () => {
    const src = readPage("GlobalCatalogSearchPage.jsx");
    expect(src).toContain("onCompareSave");
    expect(src).toContain("previewCompareList");
  });
});

describe("final regression — dimensions and chart states", () => {
  test("needs_filters shows selection required", () => {
    expect(catalogPreviewNeedsDimensionChoice({ status: "needs_filters", missing_filters: ["geo"] })).toBe(true);
    const state = buildCatalogPreviewState(
      { status: "needs_filters", missing_filters: ["geo"], rows: [] },
      { sourceType: "eurostat" }
    );
    expect(state.needs_selection).toBe(true);
  });

  test("placeholder when fewer than 2 rows", () => {
    expect(catalogPreviewNeedsDimensionChoice({ rows: [{ period: "2020", value: 1 }] })).toBe(true);
    expect(canRenderAradCatalogChart({ rows: [{ period: "2020", value: 1 }] }, "", "eurostat")).toBe(false);
  });

  test("AradView renders when >=2 rows", () => {
    const preview = {
      rows: [
        { period: "2020", value: 1 },
        { period: "2021", value: 2 },
      ],
    };
    expect(canRenderAradCatalogChart(preview, "", "eurostat")).toBe(true);
  });

  test("IMF uses AradView renderer (chart features must stay)", () => {
    const preview = {
      rows: [
        { period: "2020", value: 1 },
        { period: "2021", value: 2 },
      ],
    };
    expect(canRenderAradCatalogChart(preview, "", "imf")).toBe(true);
  });

  test("dimension change updates rows and selected_dimensions", () => {
    const cz = buildAradDataFromCatalogPreview(
      { rows: [{ period: "2020", value: 10 }, { period: "2021", value: 11 }], metadata: { filters_applied: { geo: "CZ" } } },
      "HDP"
    );
    const de = buildAradDataFromCatalogPreview(
      { rows: [{ period: "2020", value: 20 }, { period: "2021", value: 21 }], metadata: { filters_applied: { geo: "DE" } } },
      "HDP"
    );
    expect(cz.rows[0].value).not.toBe(de.rows[0].value);
    expect(extractSelectedDimensionsForStorage({ metadata: { filters_applied: { geo: "DE" } } }).geo).toBe("DE");
  });
});

describe("final regression — Copy Excel export", () => {
  const sources = [
    { name: "eurostat", preview: { rows: [{ period: "2020", value: 1.234 }, { period: "2021", value: 2.345 }], metadata: { unit: "%" } } },
    { name: "bis", preview: { rows: [{ period: "2020-Q1", value: 100 }, { period: "2020-Q2", value: 110 }], chart_frequency: "Q" } },
    { name: "fred", preview: { rows: [{ period: "2020-01-01", value: 3.5 }, { period: "2020-02-01", value: 3.6 }] } },
    { name: "oecd", preview: { rows: [{ period: "2020", value: 4.5 }, { period: "2021", value: 4.8 }] } },
    { name: "commodities", preview: { rows: [{ period: "20260101", value: 80.5 }, { period: "20260102", value: 81.2 }] } },
  ];

  test.each(sources)("$name wide export cs-CZ without technical columns", ({ name, preview }) => {
    const contract = buildContractFromPreview(preview, name);
    const text = buildClipboardTextWide(contract, { locale: "cs-CZ" });
    const header = text.split("\n")[0];
    expect(header).toContain("Období");
    expect(header).toContain("\t");
    for (const col of TECHNICAL_CLIPBOARD_COLUMNS) {
      expect(header).not.toContain(col);
    }
    expect(header).not.toContain("value_raw");
    expect(header).not.toContain("period_label");
    const wide = buildUserWideTable(contract);
    for (const row of wide.rows) {
      for (const [k, v] of Object.entries(row)) {
        if (k === wide.headers[0]) continue;
        expect(typeof v).toBe("number");
      }
    }
  });
});

describe("final regression — XLSX sheets", () => {
  test("sheet order and Dimensions metadata", () => {
    const preview = {
      rows: [{ period: "2020", value: 1 }, { period: "2021", value: 2 }],
      metadata: { filters_applied: { geo: "CZ", freq: "A" }, unit: "%" },
    };
    const contract = {
      ...buildContractFromPreview(preview, "HDP"),
      dimensions_meta: extractSelectedDimensionsForStorage(preview),
    };
    const sheets = buildChartExportSheets(contract);
    const names = Object.keys(sheets);
    expect(names[0]).toBe("Data_Wide");
    expect(names[1]).toBe("Data_Long");
    expect(sheets.Dimensions).toBeDefined();
    expect(sheets.Dimensions.rows.some((r) => r.dimension === "geo" && r.value === "CZ")).toBe(true);
    expect(sheets.Metadata).toBeDefined();
    expect(sheets.Sources).toBeDefined();
    for (const row of sheets.Data_Long.rows) {
      for (const v of Object.values(row)) {
        expect(typeof v).not.toBe("object");
      }
      if (row.value_raw != null) expect(typeof row.value_raw).toBe("number");
    }
  });
});

describe("final regression — dashboard add + snapshot", () => {
  test("buildExternalCatalogChartConfig includes selected_dimensions for all catalog ids", () => {
    for (const catalog of ["eurostat", "bis", "fred", "oecd", "imf"]) {
      const preview = {
        rows: [{ x: "2020", y: 1 }, { x: "2021", y: 2 }],
        fields: ["x", "y"],
        metadata: { filters_applied: { geo: "CZ" } },
      };
      const built = buildExternalCatalogChartConfig({ id: catalog }, preview, { set_id: "TEST_SET", name: "T" });
      expect(built?.config?.selected_dimensions?.geo).toBe("CZ");
    }
  });

  test("createExternalCatalogWidgetWithSnapshot calls render-widget", async () => {
    const posts = [];
    const api = {
      post: jest.fn(async (url) => {
        posts.push(url);
        if (url.includes("/widgets")) return { data: { id: "w-qa" } };
        return { data: { from_snapshot: false, data: { rows: [] } } };
      }),
    };
    await createExternalCatalogWidgetWithSnapshot(api, "page-1", {
      title: "QA",
      config: { catalog: "fred", set_id: "DGS10", selected_dimensions: { geo: "US" } },
    });
    expect(posts.some((u) => u.includes("render-widget"))).toBe(true);
  });

  test("widgetInitialFromListRow renders from snapshot without loading", () => {
    const w = widgetInitialFromListRow({
      id: "w",
      type: "external_catalog_chart",
      config: { selected_dimensions: { geo: "CZ" } },
      data_snapshot: { data: { rows: [{ period: "2020", value: 1 }, { period: "2021", value: 2 }] } },
    });
    expect(w._loading).toBe(false);
    expect(w.data.rows).toHaveLength(2);
  });
});

describe("final regression — layout patch without refetch", () => {
  test("selected_dimensions patch triggers data cache invalidation", () => {
    expect(widgetPatchAffectsDataCache({ config: { selected_dimensions: { geo: "DE" } } })).toBe(true);
    expect(widgetPatchAffectsDataCache({ config: { dimension_filters: { freq: "A" } } })).toBe(true);
  });

  test("layout-only patch does not trigger refetch", () => {
    expect(widgetPatchAffectsDataCache({ config: { chart_color: "#abc" } })).toBe(false);
    expect(widgetPatchAffectsDataCache({ width: "half" })).toBe(false);
  });
});

describe("final regression — compare", () => {
  test("compare config carries selected_dimensions", () => {
    const cfg = buildCompareConfigFromPreview(
      { id: "eurostat" },
      { selected_indicator: "GDP", metadata: { filters_applied: { geo: "CZ", freq: "A" } } },
      { set_id: "nama_10_gdp" }
    );
    expect(cfg.selected_dimensions.geo).toBe("CZ");
    expect(cfg.catalog).toBe("eurostat");
  });

  test("multi-series export includes compare rows", () => {
    const contract = createEmptyChartContract({
      title: "Compare",
      series: [
        { id: "a", key: "a", label: "A" },
        { id: "b", key: "b", label: "B" },
      ],
      data: [
        normalizeChartPoint({ period: "2020", value_raw: 1, series_id: "a", series_label: "A" }),
        normalizeChartPoint({ period: "2020", value_raw: 2, series_id: "b", series_label: "B" }),
        normalizeChartPoint({ period: "2021", value_raw: 1.1, series_id: "a", series_label: "A" }),
        normalizeChartPoint({ period: "2021", value_raw: 2.2, series_id: "b", series_label: "B" }),
      ],
    });
    const wide = buildUserWideTable(contract);
    expect(wide.headers.length).toBeGreaterThan(2);
    const sheets = buildChartExportSheets(contract);
    expect(sheets.Data_Long.rows.length).toBe(4);
  });
});
