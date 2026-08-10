import { buildAradDataFromCatalogPreview } from "@/lib/mapCatalogPreviewToArad";
import { buildCatalogPreviewState, catalogPreviewNeedsDimensionChoice } from "@/lib/catalogDimensions";
import { extractSelectedDimensionsForStorage } from "@/lib/catalogDimensions";
import { buildExternalCatalogChartConfig } from "@/lib/catalogPersonalDashboard";
import { buildUserChoiceDimensions } from "@/lib/sourcePreviewUserChoiceDimensions";

describe("CatalogChartPreview dimension parity helpers", () => {
  test("needs_filters blocks chart until dimensions chosen", () => {
    expect(catalogPreviewNeedsDimensionChoice({ status: "needs_filters", missing_filters: ["geo"] })).toBe(true);
  });

  test("geo change changes AradView row values", () => {
    const czRows = [
      { period: "2020", value: 100 },
      { period: "2021", value: 110 },
    ];
    const deRows = [
      { period: "2020", value: 200 },
      { period: "2021", value: 210 },
    ];
    const cz = buildAradDataFromCatalogPreview({ rows: czRows }, "HDP");
    const de = buildAradDataFromCatalogPreview({ rows: deRows }, "HDP");
    expect(cz.rows[0].value).toBe(100);
    expect(de.rows[0].value).toBe(200);
  });

  test("metric change changes series context via selected_indicator in storage", () => {
    const previewA = {
      rows: [{ x: "2020", y: 1 }, { x: "2021", y: 2 }],
      fields: ["x", "y"],
      selected_indicator: "IND_A",
      metadata: { filters_applied: { geo: "CZ" } },
    };
    const previewB = { ...previewA, selected_indicator: "IND_B" };
    expect(extractSelectedDimensionsForStorage(previewA).selected_indicator).toBe("IND_A");
    expect(extractSelectedDimensionsForStorage(previewB).selected_indicator).toBe("IND_B");
  });

  test("dashboard config stores selected_dimensions for snapshot refresh", () => {
    const preview = {
      rows: [{ x: "2020", y: 10 }, { x: "2021", y: 11 }],
      fields: ["x", "y"],
      metadata: { filters_applied: { geo: "DE", freq: "A" } },
      selected_indicator: "GDP",
    };
    const built = buildExternalCatalogChartConfig({ id: "eurostat" }, preview, { set_id: "nama_10_gdp", name: "HDP" });
    expect(built.config.selected_dimensions.geo).toBe("DE");
    expect(built.config.selected_dimensions.freq).toBe("A");
  });

  test("eurostat cascade selection preserved in preview state", () => {
    const preview = {
      status: "ok",
      rows: [{ period: "2020", value: 1 }, { period: "2021", value: 2 }],
      available_dimensions: {
        geo: { label: "Země", sample_options: [{ code: "CZ", label: "Česko" }] },
        freq: { label: "Frekvence", sample_options: [{ code: "A", label: "Roční" }] },
      },
      metadata: { filters_applied: { geo: "CZ", freq: "A", unit: "CP_MEUR" } },
    };
    const state = buildCatalogPreviewState(preview, { sourceType: "eurostat" });
    expect(state.selected_dimensions.geo).toBe("CZ");
    expect(state.selected_dimensions.freq).toBe("A");
    expect(state.needs_selection).toBe(false);
  });

  test("eurostat generic available dimensions are user-selectable even without selectable_dimensions", () => {
    const dims = buildUserChoiceDimensions(
      {
        currency: {
          label: "Currency",
          sample_options: [
            { code: "ALL", label: "All currencies" },
            { code: "CZK", label: "Czech koruna" },
            { code: "EUR", label: "Euro" },
          ],
        },
        statinfo: {
          label: "Statistical information",
          values: ["AVG", "END"],
        },
        time: {
          label: "Time",
          values: ["2025-01", "2025-02"],
        },
      },
      {
        datasetId: "ert_bil_eur_m",
        appliedFilters: { currency: "ALL", statinfo: "AVG" },
        selectableDimensions: [],
      },
    );
    expect(dims.map((d) => d.field)).toEqual(["currency", "statinfo"]);
    expect(dims.find((d) => d.field === "currency")?.selected).toBe("ALL");
  });
});
