import { buildExternalCatalogChartConfig } from "./catalogPersonalDashboard";

describe("buildExternalCatalogChartConfig", () => {
  test("uses CSU period dimension instead of numeric value column for chart X axis", () => {
    const built = buildExternalCatalogChartConfig(
      { id: "csu", sourceType: "csu", label: "ČSÚ" },
      {
        columns: [
          { key: "VALUE", label: "VALUE" },
          { key: "HODNOTA", label: "HODNOTA" },
          { key: "AMOUNT", label: "AMOUNT" },
          { key: "UKAZATEL", label: "UKAZATEL" },
          { key: "ÚZEMÍ-STÁT", label: "ÚZEMÍ-STÁT" },
          { key: "TŘÍLETÉ OBDOBÍ", label: "TŘÍLETÉ OBDOBÍ" },
        ],
        rows: [
          {
            VALUE: "1000",
            HODNOTA: "1000",
            AMOUNT: "1000",
            UKAZATEL: "Byty",
            "ÚZEMÍ-STÁT": "Česko",
            "TŘÍLETÉ OBDOBÍ": "2021",
          },
        ],
      },
      { set_id: "CSU_TEST", name: "ČSÚ test" },
    );

    expect(built.config.x_field).toBe("TŘÍLETÉ OBDOBÍ");
    expect(built.config.y_field).toBe("HODNOTA");
    expect(built.config.agg).toBe("avg");
  });

  test("regional CSU with one year defaults to latest bar chart", () => {
    const built = buildExternalCatalogChartConfig(
      { id: "csu", sourceType: "csu" },
      {
        rows: [
          {
            "Druh nemovitosti": "Byty [Kč/m2]",
            "ÚZEMÍ-Kraj": "Hlavní město Praha",
            "Počet obyvatel obce": "Celkem",
            Roky: "2023",
            Hodnota: "103693",
          },
          {
            "Druh nemovitosti": "Byty [Kč/m2]",
            "ÚZEMÍ-Kraj": "Jihomoravský kraj",
            "Počet obyvatel obce": "Celkem",
            Roky: "2023",
            Hodnota: "77827",
          },
        ],
        group_field: "ÚZEMÍ-Kraj",
        indicators: [
          { id: "Hlavní město Praha", name: "Hlavní město Praha" },
          { id: "Jihomoravský kraj", name: "Jihomoravský kraj" },
        ],
      },
      { set_id: "CEN0402T03", name: "Cena bytů za m² podle krajů" },
    );

    expect(built.config.chart_data_mode).toBe("latest");
    expect(built.config.chart_type).toBe("bar");
    expect(built.config.selected_indicator).toBe("Byty [Kč/m2]");
    expect(built.config.dimension_filters?.["Počet obyvatel obce"]).toBe("Celkem");
  });

  test("stores ECB row query_params and normalized set_id for dashboard widget", () => {
    const built = buildExternalCatalogChartConfig(
      { id: "ecb", sourceType: "ecb" },
      {
        rows: [{ date: "2020-01", value: 100 }, { date: "2021-01", value: 102 }],
        metadata: { filters_applied: { geo: "CZ" } },
        selected_indicator: "040000",
      },
      {
        set_id: "ecb:040000",
        name: "HICP - HOUSING",
        ecb_country: "CZ",
        ecb_indicator_id: "040000",
      },
      "CZE"
    );

    expect(built.config.set_id).toBe("ecb:CZ:040000");
    expect(built.config.query_params?.country).toBe("CZ");
    expect(built.config.query_params?.ecb_indicator_id).toBe("040000");
    expect(built.config.query_params?.geo).toBe("CZ");
  });

  test("Eurostat sidecar row keeps sidecar_series_id and canonical set_id", () => {
    const built = buildExternalCatalogChartConfig(
      { id: "eurostat", sourceType: "eurostat" },
      {
        rows: [{ period: "2024-01", value: 100 }],
        requested_filters: { geo: "CZ", coicop: "CP04", freq: "M" },
      },
      {
        set_id: "prc_hicp_midx_hicp_housing_energy",
        dataset_id: "prc_hicp_midx",
        name: "HICP housing energy",
        query_params: { geo: "CZ" },
      },
      "CZE",
    );

    expect(built.config.set_id).toBe("prc_hicp_midx");
    expect(built.config.series_id).toBe("prc_hicp_midx_hicp_housing_energy");
    expect(built.config.metadata?.sidecar_series_id).toBe("prc_hicp_midx_hicp_housing_energy");
    expect(built.config.query_params?.geo).toBe("CZ");
  });
});
