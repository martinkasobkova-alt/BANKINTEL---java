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

/**
 * Co si uživatel nastavil v náhledu, to musí widget na dashboardu zopakovat.
 *
 * Kontext: v katalogovém náhledu šlo naklikat „sloupec za každou zemi", jenže při ukládání
 * se posílala jen dimenze a hodnoty — a `chart_series_dim_values` navíc nikdo nečetl.
 * Widget se proto vrátil k časové řadě přes všechny hodnoty a sloupce zmizely.
 */
describe("buildExternalCatalogChartConfig — přenos nastavení z náhledu", () => {
  const def = { id: "eurostat", sourceType: "eurostat", label: "Eurostat" };
  const row = { set_id: "tipsbd40", name: "Return on equity of banks" };
  const preview = {
    rows: [
      { geo: "CZ", geo_label: "Česko", time: "2025", value: 15.3 },
      { geo: "DE", geo_label: "Německo", time: "2025", value: 5.9 },
    ],
  };

  test("srovnání hodnot se uloží jako sloupcový graf s posledním údajem", () => {
    const built = buildExternalCatalogChartConfig(def, preview, row, null, {
      displayMode: "bars_latest",
      crossSectionDim: "geo",
      crossSectionValues: ["CZ", "DE"],
    });

    expect(built.config.chart_data_mode).toBe("latest");
    expect(built.config.chart_type).toBe("bar");
    expect(built.config.chart_series_dim).toBe("geo");
    expect(built.config.chart_series_dim_values).toEqual(["CZ", "DE"]);
    // Jedna řada o N bodech, ne N řad — jinak legenda se všemi zeměmi sebere výšku grafu
    // a na sloupce nezbude místo.
    expect(built.config.chart_series_mode).toBeUndefined();
    expect(built.config.chart_compare_with).toBeUndefined();
  });

  test("časová řada si nese vybranou dimenzi i vybrané hodnoty", () => {
    const built = buildExternalCatalogChartConfig(def, preview, row, null, {
      displayMode: "time_series",
      seriesGroupDim: "geo",
      seriesSelection: ["CZ", "DE"],
    });

    expect(built.config.chart_series_dim).toBe("geo");
    expect(built.config.chart_series_dim_values).toEqual(["CZ", "DE"]);
    expect(built.config.chart_data_mode).toBe("history");
    expect(built.config.chart_type).toBe("line");
  });

  test("bez výběru hodnot se uloží jen dimenze — graf pak ukáže všechno", () => {
    const built = buildExternalCatalogChartConfig(def, preview, row, null, {
      displayMode: "time_series",
      seriesGroupDim: "geo",
      seriesSelection: [],
    });

    expect(built.config.chart_series_dim).toBe("geo");
    expect(built.config.chart_series_dim_values).toBeUndefined();
  });

  test("výběr zemí přežije i když ho náhled v odpovědi nezopakuje", () => {
    const built = buildExternalCatalogChartConfig(def, preview, row, null, {
      displayMode: "time_series",
      selectedGeo: ["CZ", "DE", "AT"],
    });

    expect(built.config.query_params.geo).toEqual(["CZ", "DE", "AT"]);
  });

  test("bez nastavení z náhledu zůstane konfigurace jako dřív", () => {
    const built = buildExternalCatalogChartConfig(def, preview, row, null, null);

    expect(built.config.chart_series_dim).toBeUndefined();
    expect(built.config.chart_series_dim_values).toBeUndefined();
    expect(built.config.chart_data_mode).toBe("history");
  });
});
