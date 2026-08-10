import {
  addCatalogPreviewToPersonalDashboard,
  buildCatalogChartActionsProps,
} from "@/lib/catalogPageDashboard";
import { buildExternalCatalogChartConfig } from "@/lib/catalogPersonalDashboard";
import { buildAradDataFromCatalogPreview } from "@/lib/mapCatalogPreviewToArad";
import { buildChartExportSheets, buildClipboardTextWide } from "@/charts/chartExport";
import { contractFromAradViewState } from "@/charts/contractFromAradViewState";
import { extractSelectedDimensionsForStorage } from "@/lib/catalogDimensions";
import { readSourceModuleByBasename } from "@/test/readSourceModule";

function readPage(name) {
  return readSourceModuleByBasename(name);
}

describe("catalog page migration — uses CatalogChartPreview", () => {
  test.each([
    ["BisCatalogPage.jsx"],
    ["OecdCatalogPage.jsx"],
    ["FredCatalogPage.jsx"],
    ["CommoditiesPage.jsx"],
    ["CatalogTopicBrowsePage.jsx"],
  ])("%s imports CatalogChartPreview and not standalone SourcePreview", (file) => {
    const src = readPage(file);
    expect(src).toContain("CatalogChartPreview");
    expect(src).not.toMatch(/import SourcePreview from/);
  });
});

describe("BisCatalogPage preview integration", () => {
  const bisPreview = {
    rows: [
      { period: "2020-Q1", value: 100 },
      { period: "2020-Q2", value: 110 },
    ],
    metadata: { filters_applied: { geo: "CZ", freq: "Q" }, unit: "USD" },
    chart_frequency: "Q",
  };

  test("AradView rows map from BIS preview", () => {
    const arad = buildAradDataFromCatalogPreview(bisPreview, "BIS řada");
    expect(arad.rows.length).toBeGreaterThanOrEqual(2);
    expect(arad.frequency).toBeTruthy();
  });

  test("Copy Excel wide export for BIS preview", () => {
    const arad = buildAradDataFromCatalogPreview(bisPreview, "BIS");
    const contract = contractFromAradViewState({
      title: "BIS",
      unit: arad.unit || "",
      chartKind: "line",
      latestDataMode: false,
      isMultiSeries: false,
      seriesList: [{ key: "main", label: "BIS" }],
      chartRows: arad.rows.map((r) => ({ period: r.period, x: r.period, value: r.value })),
      data: arad,
      widget: { id: "bis", config: { selected_dimensions: extractSelectedDimensionsForStorage(bisPreview) } },
    });
    const text = buildClipboardTextWide(contract, { locale: "cs-CZ" });
    expect(text).toContain("\t");
    expect(text).not.toContain("series_id");
  });
});

describe("OecdCatalogPage dashboard config", () => {
  test("geo/indicator selected_dimensions stored for OECD row", () => {
    const preview = {
      rows: [{ x: "2020", y: 1 }, { x: "2021", y: 2 }],
      fields: ["x", "y"],
      metadata: { filters_applied: { geo: "DE" } },
      selected_indicator: "GDP",
    };
    const built = buildExternalCatalogChartConfig(
      { id: "oecd" },
      preview,
      { set_id: "SDMX2|OECD|DF|+|DE.GDP", name: "OECD" }
    );
    expect(built.config.selected_dimensions.geo).toBe("DE");
    expect(built.config.set_id).toContain("SDMX2");
  });
});

describe("FredCatalogPage single-series preview", () => {
  test("single-series AradView and wide export", () => {
    const preview = {
      rows: [
        { period: "2020-01-01", value: 1.5 },
        { period: "2020-02-01", value: 1.6 },
      ],
      chart_frequency: "M",
    };
    const arad = buildAradDataFromCatalogPreview(preview, "DGS10");
    expect(arad.rows).toHaveLength(2);
    const contract = contractFromAradViewState({
      title: "DGS10",
      unit: "%",
      chartKind: "line",
      latestDataMode: false,
      isMultiSeries: false,
      seriesList: [{ key: "main", label: "DGS10" }],
      chartRows: arad.rows.map((r) => ({ period: r.period, x: r.period, value: r.value })),
      data: arad,
    });
    const sheets = buildChartExportSheets(contract);
    expect(sheets.Data_Wide.columns[0]).toBe("Období");
  });
});

describe("CommoditiesPage frequency and cs-CZ export", () => {
  test("daily frequency inferred from commodity preview rows", () => {
    const preview = {
      rows: [
        { period: "20260101", value: 80 },
        { period: "20260102", value: 81 },
        { period: "20260103", value: 82 },
      ],
    };
    const arad = buildAradDataFromCatalogPreview(preview, "Gold");
    expect(arad.frequency).toBe("D");
  });

  test("cs-CZ clipboard uses comma decimals", () => {
    const preview = {
      rows: [
        { period: "2020", value: 12.34 },
        { period: "2021", value: 56.78 },
      ],
    };
    const arad = buildAradDataFromCatalogPreview(preview, "Komodita");
    const contract = contractFromAradViewState({
      title: "Komodita",
      chartKind: "line",
      latestDataMode: false,
      isMultiSeries: false,
      seriesList: [{ key: "main", label: "Komodita" }],
      chartRows: arad.rows.map((r) => ({ period: r.period, x: r.period, value: r.value })),
      data: arad,
    });
    const text = buildClipboardTextWide(contract, { locale: "cs-CZ" });
    expect(text).toMatch(/12,34|56,78/);
  });
});

describe("dashboard add helper", () => {
  test("selected_dimensions in config differ by geo for cache invalidation", () => {
    const preview = {
      rows: [{ x: "2020", y: 1 }, { x: "2021", y: 2 }],
      fields: ["x", "y"],
      metadata: { filters_applied: { geo: "CZ" } },
    };
    const builtCz = buildExternalCatalogChartConfig({ id: "bis" }, preview, { set_id: "BIS:1", name: "Test" });
    const builtDe = buildExternalCatalogChartConfig(
      { id: "bis" },
      { ...preview, metadata: { filters_applied: { geo: "DE" } } },
      { set_id: "BIS:1", name: "Test" }
    );
    expect(builtCz.config.selected_dimensions.geo).toBe("CZ");
    expect(builtDe.config.selected_dimensions.geo).toBe("DE");
    expect(JSON.stringify(builtCz.config.selected_dimensions)).not.toBe(
      JSON.stringify(builtDe.config.selected_dimensions)
    );
  });

  test("buildCatalogChartActionsProps respects feature flags", () => {
    const props = buildCatalogChartActionsProps({
      feature: { isSubscriber: true, canPersonalDashboard: true, canSaveWidget: true },
      previewData: { rows: [{ period: "2020", value: 1 }] },
      previewError: "",
      previewLoading: false,
      onAddToDashboard: () => {},
    });
    expect(props.canAddToDashboard).toBe(true);
  });

  test("addCatalogPreviewToPersonalDashboard rejects non-subscriber", async () => {
    const api = { post: jest.fn(), get: jest.fn() };
    const ok = await addCatalogPreviewToPersonalDashboard({
      api,
      nav: jest.fn(),
      def: { id: "fred" },
      previewData: { rows: [{ x: "2020", y: 1 }, { x: "2021", y: 2 }], fields: ["x", "y"] },
      row: { set_id: "GDP", name: "GDP" },
      feature: { isSubscriber: false, canPersonalDashboard: false },
    });
    expect(ok).toBe(false);
    expect(api.post).not.toHaveBeenCalled();
  });
});

describe("AradView fallback", () => {
  test("single row does not produce renderable arad chart data length >= 2", () => {
    const arad = buildAradDataFromCatalogPreview({ rows: [{ period: "2020", value: 1 }] }, "X");
    expect(arad.rows.length).toBeLessThan(2);
  });
});
