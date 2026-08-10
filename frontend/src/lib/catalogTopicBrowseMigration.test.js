import { buildAradDataFromCatalogPreview } from "@/lib/mapCatalogPreviewToArad";
import { catalogPreviewNeedsDimensionChoice } from "@/lib/catalogDimensions";
import { buildExternalCatalogChartConfig } from "@/lib/catalogPersonalDashboard";
import { buildClipboardTextWide } from "@/charts/chartExport";
import { contractFromAradViewState } from "@/charts/contractFromAradViewState";
import { extractSelectedDimensionsForStorage } from "@/lib/catalogDimensions";
import { canRenderAradCatalogChart } from "@/lib/mapCatalogPreviewToArad";
import { readSourceModuleByBasename } from "@/test/readSourceModule";

describe("CatalogTopicBrowsePage migration", () => {
  test("uses CatalogChartPreview instead of direct AradView/SourcePreview", () => {
    const src = readSourceModuleByBasename("CatalogTopicBrowsePage.jsx");
    expect(src).toContain("CatalogChartPreview");
    expect(src).toContain("CatalogPreviewFullscreenOverlay");
    expect(src).toContain('catalogChartSize="fullscreen"');
    expect(src).not.toMatch(/import SourcePreview from/);
    expect(src).not.toMatch(/import AradView from/);
    expect(src).toContain("addCatalogPreviewToPersonalDashboard");
    // AradView graf (KPI, období, možnosti, fullscreen) má být dostupný i pro IMF.
    expect(src).toContain("preferAradView");
  });

  test("dimension change produces different AradView rows", () => {
    const cz = buildAradDataFromCatalogPreview(
      {
        rows: [
          { period: "2020", value: 100 },
          { period: "2021", value: 110 },
        ],
        metadata: { filters_applied: { geo: "CZ" } },
      },
      "HDP CZ"
    );
    const de = buildAradDataFromCatalogPreview(
      {
        rows: [
          { period: "2020", value: 200 },
          { period: "2021", value: 220 },
        ],
        metadata: { filters_applied: { geo: "DE" } },
      },
      "HDP DE"
    );
    expect(cz.rows[0].value).not.toBe(de.rows[0].value);
  });

  test("add to dashboard stores selected_dimensions", () => {
    const preview = {
      rows: [{ x: "2020", y: 10 }, { x: "2021", y: 11 }],
      fields: ["x", "y"],
      metadata: { filters_applied: { geo: "CZ", freq: "A" } },
      selected_indicator: "GDP",
    };
    const built = buildExternalCatalogChartConfig(
      { id: "eurostat" },
      preview,
      { set_id: "nama_10_gdp", name: "HDP" }
    );
    expect(built.config.selected_dimensions.geo).toBe("CZ");
    expect(built.config.selected_dimensions.freq).toBe("A");
  });

  test("Copy Excel uses current dimensions", () => {
    const preview = {
      rows: [
        { period: "2020", value: 1.5 },
        { period: "2021", value: 2.5 },
      ],
      metadata: { filters_applied: { geo: "SK" } },
    };
    const arad = buildAradDataFromCatalogPreview(preview, "Test");
    const contract = contractFromAradViewState({
      title: "Test",
      chartKind: "line",
      latestDataMode: false,
      isMultiSeries: false,
      seriesList: [{ key: "main", label: "Test" }],
      chartRows: arad.rows.map((r) => ({ period: r.period, x: r.period, value: r.value })),
      data: arad,
      widget: { id: "t", config: { selected_dimensions: extractSelectedDimensionsForStorage(preview) } },
    });
    const text = buildClipboardTextWide(contract, { locale: "cs-CZ" });
    expect(text).toContain("\t");
    expect(text).not.toContain("value_raw");
  });

  test("needs_filters blocks broken AradView chart", () => {
    expect(catalogPreviewNeedsDimensionChoice({ status: "needs_filters", missing_filters: ["geo"] })).toBe(true);
    expect(
      canRenderAradCatalogChart(
        { status: "needs_filters", rows: [] },
        "",
        "eurostat"
      )
    ).toBe(false);
  });

  test("fallback when AradView cannot render single row", () => {
    const preview = { rows: [{ period: "2020", value: 1 }] };
    expect(catalogPreviewNeedsDimensionChoice(preview)).toBe(true);
    expect(canRenderAradCatalogChart(preview, "", "eurostat")).toBe(false);
  });
});
