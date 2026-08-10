import { enrichCsuCatalogRow } from "./catalogCsuPreview";
import { buildCatalogPreviewRequestBody } from "./catalogLivePreview";
import { buildCatalogPreviewBody } from "./catalogPreviewBody";

describe("enrichCsuCatalogRow", () => {
  it("doplní dataset_code z plochého indexu browse stromu", () => {
    const row = { set_id: "MZDR07RT3", name: "Test", kind: "set" };
    const indexed = [
      {
        kind: "set",
        set_id: "MZDR07RT3",
        dataset_code: "MZDR07",
        period: "Roky",
      },
    ];
    const enriched = enrichCsuCatalogRow(row, indexed);
    expect(enriched.dataset_code).toBe("MZDR07");
    expect(enriched.period).toBe("Roky");
  });

  it("nemění řádek, který už dataset_code má", () => {
    const row = { set_id: "MZDR07RT3", dataset_code: "MZDR07" };
    expect(enrichCsuCatalogRow(row, [])).toBe(row);
  });

  it("sends explicit CSU dimension filters in live preview requests", () => {
    const def = { sourceType: "csu", needsCountry: false };
    const row = { set_id: "FIN03BANKDHM", name: "Banky", dataset_code: "FIN03" };
    const body = buildCatalogPreviewRequestBody({
      def,
      row,
      dimensionFilters: { Uzemi: "Praha" },
    });

    expect(body.dimension_filters).toEqual({ Uzemi: "Praha" });
  });

  it("sends generic dimension filters to IMF query params and payload", () => {
    const def = { sourceType: "imf", needsCountry: false };
    const row = { set_id: "IMF|IMF.RES|WEO|9.0.0|DEU.PCPIEPCH", name: "Inflation" };
    const body = buildCatalogPreviewRequestBody({
      def,
      row,
      dimensionFilters: { COUNTRY: ["AUT", "NOR"], FREQ: "A" },
    });

    expect(body.dimension_filters).toEqual({ COUNTRY: ["AUT", "NOR"], FREQ: "A" });
    expect(body.query_params.COUNTRY).toEqual(["AUT", "NOR"]);
    expect(body.query_params.FREQ).toBe("A");
  });

  it("sends generic dimension filters to OECD query params and payload", () => {
    const def = { sourceType: "oecd", needsCountry: false };
    const row = { set_id: "OECD4|housing_prices|CZE|RHP|A", name: "Housing prices" };
    const body = buildCatalogPreviewRequestBody({
      def,
      row,
      dimensionFilters: { REF_AREA: ["AUT", "NOR"] },
    });

    expect(body.dimension_filters).toEqual({ REF_AREA: ["AUT", "NOR"] });
    expect(body.query_params.REF_AREA).toEqual(["AUT", "NOR"]);
  });

  it("sends generic dimension filters to ECB query params and payload", () => {
    const def = { sourceType: "ecb", needsCountry: false };
    const row = { set_id: "ICP/M.U2.N.PCCI00.3.3MM", name: "Inflation" };
    const body = buildCatalogPreviewRequestBody({
      def,
      row,
      dimensionFilters: { REF_AREA: ["AT", "DE"] },
    });

    expect(body.dimension_filters).toEqual({ REF_AREA: ["AT", "DE"] });
    expect(body.query_params.REF_AREA).toEqual(["AT", "DE"]);
  });

  it("keeps Eurostat multi-country detail previews as time series, not one-point verification previews", () => {
    const def = { sourceType: "eurostat", needsCountry: false };
    const row = {
      set_id: "teicp000",
      name: "HICP - all items",
      query_params: {
        geo: "AT",
        unit: "I25",
        query_mode: "preview",
        lastTimePeriod: "1",
      },
    };
    const body = buildCatalogPreviewRequestBody({
      def,
      row,
      geoValues: ["AT", "NO"],
      dimensionFilters: {
        geo: ["AT", "NO"],
        unit: "I25",
        query_mode: "preview",
        lastTimePeriod: "1",
      },
    });

    expect(body.dimension_filters).toEqual({ geo: ["AT", "NO"], unit: "I25" });
    expect(body.query_params.geo).toEqual(["AT", "NO"]);
    expect(body.query_params.unit).toBe("I25");
    expect(body.query_params.query_mode).toBeUndefined();
    expect(body.query_params.lastTimePeriod).toBeUndefined();
  });
});

describe("buildCatalogPreviewBody csu", () => {
  it("posílá dataset_code pro POST celé sady", () => {
    const def = { sourceType: "csu", needsCountry: false };
    const row = { set_id: "MZDR07RT3", name: "Mzdy", dataset_code: "MZDR07" };
    const body = buildCatalogPreviewBody(def, row, "CZE");
    expect(body.source_type).toBe("csu");
    expect(body.set_id).toBe("MZDR07RT3");
    expect(body.dataset_code).toBe("MZDR07");
  });

  it("preserves prepared CSU row filters", () => {
    const def = { sourceType: "csu", needsCountry: false };
    const csuFilters = [
      { field: "Typ indexu", contains: "Bazický index" },
      { field: "Území", exact: "Česko" },
      { field: "Klasifikace COICOP 2018-Oddíl", exact: "Pojištění a finanční služby" },
    ];
    const row = {
      set_id: "CEN0101ET03",
      name: "CPI COICOP: Pojištění a finanční služby",
      selected_indicator: "Bazický index (2025 = 100)",
      query_params: { csu_selection_code: "CEN0101ET03", csu_selection_endpoint: "1", csu_filters: csuFilters },
    };
    const body = buildCatalogPreviewBody(def, row, "CZE");
    expect(body.query_params.csu_filters).toEqual(csuFilters);
    expect(body.query_params.csu_selection_endpoint).toBe("1");
    expect(body.selected_indicator).toBe("Bazický index (2025 = 100)");
  });
});
