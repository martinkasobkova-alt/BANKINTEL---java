import {
  buildExploreComparePreviewBody,
  compareChartPeriods,
  indexChartRows,
  mapEcbCountryCodeToEurostatGeo,
} from "./exploreChartCompare";

describe("exploreChartCompare", () => {
  it("builds ECB compare body from bare indicator id", () => {
    const body = buildExploreComparePreviewBody(
      {
        source_type: "ecb",
        compare_mode: "ecb",
        ecb_indicator_id: "inflace_jadrova",
        set_id: "inflace_jadrova",
        query_params: {},
      },
      "DE"
    );
    expect(body.set_id).toBe("ecb:DE:inflace_jadrova");
    expect(body.country).toBe("DE");
  });

  it("indexes chart rows to base 100", () => {
    const indexed = indexChartRows([
      { x: "2024-01", y: 2 },
      { x: "2024-02", y: 2.2 },
    ]);
    expect(indexed[0].y).toBe(100);
    expect(indexed[1].y).toBeCloseTo(110);
  });

  it("builds ECB compare body", () => {
    const body = buildExploreComparePreviewBody(
      {
        source_type: "ecb",
        compare_mode: "ecb",
        ecb_indicator_id: "ICP",
        set_id: "ecb:AT:ICP",
        query_params: {},
      },
      "DE"
    );
    expect(body.set_id).toBe("ecb:DE:ICP");
    expect(body.country).toBe("DE");
  });

  it("sorts quarterly periods", () => {
    expect(compareChartPeriods("2024-Q1", "2024-Q2")).toBeLessThan(0);
  });

  it("maps ECB EU aggregate to Eurostat geo", () => {
    expect(mapEcbCountryCodeToEurostatGeo("U6")).toBe("EU27_2020");
    expect(mapEcbCountryCodeToEurostatGeo("DE")).toBe("DE");
  });

  it("builds IMF compare body with rebuilt set_id for another country", () => {
    const body = buildExploreComparePreviewBody(
      {
        source_type: "imf",
        compare_mode: "imf",
        set_id: "IMF|IMF.RES|WEO|9.0.0|JPN.NGDP_RPCH",
        imf_set_prefix: "IMF|IMF.RES|WEO|9.0.0",
        imf_indicator_suffix: "NGDP_RPCH",
        imf_flow: "WEO",
        imf_frekvence: "A",
        query_params: {
          imf_country: "JPN",
          imf_flow: "WEO",
          imf_indicator: "NGDP_RPCH",
          imf_frekvence: "A",
        },
      },
      "DE"
    );
    expect(body.set_id).toBe("IMF|IMF.RES|WEO|9.0.0|DEU.NGDP_RPCH");
    expect(body.query_params.imf_country).toBe("DEU");
    expect(body.query_params.imf_indicator).toBe("NGDP_RPCH");
  });

  it("builds world bank compare body with ISO3 country", () => {
    const body = buildExploreComparePreviewBody(
      {
        source_type: "worldbank",
        compare_mode: "wb_country",
        set_id: "FP.CPI.TOTL",
        query_params: { country: "CZE" },
      },
      "DE"
    );
    expect(body.country).toBe("DEU");
    expect(body.query_params.country).toBe("DEU");
  });

  it("builds eurostat compare body with preserved dimensions", () => {
    const body = buildExploreComparePreviewBody(
      {
        source_type: "eurostat",
        compare_mode: "eurostat",
        set_id: "road_eqs_busmot",
        query_params: {
          geo: "CZ",
          unit: "NR",
          nrg_bal: "TOTAL",
        },
      },
      "U6",
      { eurostatGeo: "EU27_2020" }
    );
    expect(body.geo).toBe("EU27_2020");
    expect(body.query_params.geo).toBe("EU27_2020");
    expect(body.query_params.unit).toBe("NR");
    expect(body.query_params.nrg_bal).toBe("TOTAL");
    expect(body.query_params.REF_AREA).toBeUndefined();
  });
});
