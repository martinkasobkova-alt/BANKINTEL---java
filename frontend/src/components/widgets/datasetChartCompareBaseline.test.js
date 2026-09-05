import {
  canPersistChartCompare,
  isExternalCatalogWidgetEngine,
  isUploadPrimaryWidgetEngine,
  resolveDatasetChartCompareBaseline,
  resolveExternalCatalogCompareBaseline,
} from "./datasetChartCompareBaseline";

describe("resolveDatasetChartCompareBaseline", () => {
  test("reads source_id, series_field and series_value from config", () => {
    expect(
      resolveDatasetChartCompareBaseline(
        { config: { source_id: "ds1", series_field: "geo", series_value: "CZ" } },
        {}
      )
    ).toEqual({ sid: "ds1", sf: "geo", sv: "CZ" });
  });

  test("falls back to group_field and selected_indicator", () => {
    expect(
      resolveDatasetChartCompareBaseline(
        { config: { source_id: "ds1", selected_indicator: "TOTAL" } },
        { group_field: "Ukazatel", selected_indicator: "TOTAL" }
      )
    ).toEqual({ sid: "ds1", sf: "Ukazatel", sv: "TOTAL" });
  });

  test("uses single dimension_filters key as series_field", () => {
    expect(
      resolveDatasetChartCompareBaseline(
        { config: { source_id: "ds1", dimension_filters: { geo: "DE" } } },
        {}
      )
    ).toEqual({ sid: "ds1", sf: "geo", sv: "DE" });
  });
});

describe("resolveExternalCatalogCompareBaseline", () => {
  test("detects catalog chart widgets with catalog and set_id", () => {
    expect(
      resolveExternalCatalogCompareBaseline(
        {
          config: {
            catalog: "eurostat",
            set_id: "nama_10_gdp",
            selected_indicator: "B1GQ",
          },
        },
        { selected_indicator_label: "HDP" }
      )
    ).toMatchObject({
      catalog: "eurostat",
      setId: "nama_10_gdp",
      mainIndicator: "B1GQ",
      mainLabel: "HDP",
      ready: true,
    });
  });

  test("is not ready without set_id", () => {
    expect(resolveExternalCatalogCompareBaseline({ config: { catalog: "csu" } }, {}).ready).toBe(false);
  });
});

describe("isExternalCatalogWidgetEngine", () => {
  test("matches external catalog engines", () => {
    expect(isExternalCatalogWidgetEngine("external_catalog_chart")).toBe(true);
    expect(isExternalCatalogWidgetEngine("eurostat_view")).toBe(false);
  });
});

describe("isUploadPrimaryWidgetEngine", () => {
  test("matches user upload chart engines", () => {
    expect(isUploadPrimaryWidgetEngine("user_upload_chart")).toBe(true);
    expect(isUploadPrimaryWidgetEngine("uploaded_data_chart")).toBe(true);
    expect(isUploadPrimaryWidgetEngine("external_catalog_chart")).toBe(false);
  });
});

// Živě zjištěno: "Srovnat s řadou" na grafu z vlastních dat se dřív vždy uložil jen jako
// dočasný náhled, protože handleUnifiedCompareSave umělo trvale uložit srovnání jen pro
// katalogové widgety — widget z vlastních dat nikdy nesplnil žádnou z tehdejších podmínek.
describe("canPersistChartCompare", () => {
  test("persists for an external-catalog-primary widget with a patch handler and id", () => {
    expect(
      canPersistChartCompare({
        isExternalCatalogPrimary: true,
        isUploadPrimary: false,
        hasWidgetConfigPatch: true,
        hasWidgetId: true,
      })
    ).toBe(true);
  });

  test("persists for an upload-primary widget with a patch handler and id", () => {
    expect(
      canPersistChartCompare({
        isExternalCatalogPrimary: false,
        isUploadPrimary: true,
        hasWidgetConfigPatch: true,
        hasWidgetId: true,
      })
    ).toBe(true);
  });

  test("does not persist without a patch handler even for an eligible primary", () => {
    expect(
      canPersistChartCompare({
        isExternalCatalogPrimary: true,
        isUploadPrimary: true,
        hasWidgetConfigPatch: false,
        hasWidgetId: true,
      })
    ).toBe(false);
  });

  test("does not persist when neither primary kind is eligible", () => {
    expect(
      canPersistChartCompare({
        isExternalCatalogPrimary: false,
        isUploadPrimary: false,
        hasWidgetConfigPatch: true,
        hasWidgetId: true,
      })
    ).toBe(false);
  });
});
