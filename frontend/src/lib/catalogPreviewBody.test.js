import { buildCatalogPreviewBody, resolveCatalogRowSetId } from "./catalogPreviewBody";

jest.mock(
  "@/lib/eurostatAddSourcePayload",
  () => ({
    sanitizeEurostatQueryParams: (value) => value || {},
  }),
  { virtual: true },
);

describe("buildCatalogPreviewBody", () => {
  it("ARAD: posílá selected_indicator z row.indicator_id", () => {
    const def = { sourceType: "arad", needsCountry: false };
    const row = {
      set_id: "202",
      name: "Výnos 10letého státního dluhopisu",
      title: "SVSDM42 - Výnosy státních dluhopisů: Roční, Výnos 10letého státního dluhopisu",
      indicator_id: "SVSDM42",
      indicator_name: "Výnosy státních dluhopisů: Roční, Výnos 10letého státního dluhopisu",
    };
    const body = buildCatalogPreviewBody(def, row, "CZE");
    expect(body.source_type).toBe("arad");
    expect(body.set_id).toBe("202");
    expect(body.selected_indicator).toBe("SVSDM42");
    expect(body.name).toBe(
      "SVSDM42 - Výnosy státních dluhopisů: Roční, Výnos 10letého státního dluhopisu",
    );
  });

  it("ARAD: selected_indicator z row.selected_indicator když chybí indicator_id", () => {
    const def = { sourceType: "arad", needsCountry: false };
    const row = { set_id: "1058", name: "Test", selected_indicator: "ABC123" };
    const body = buildCatalogPreviewBody(def, row, "CZE");
    expect(body.selected_indicator).toBe("ABC123");
  });

  it("IMF: předá imf_country, flow a indicator do query_params", () => {
    const def = { sourceType: "imf", needsCountry: false };
    const row = {
      set_id: "IMF|IMF.RES|WEO|9.0.0|CZE.TM_RPCH",
      name: "Dovoz",
      imf_country: "CZ",
      imf_flow: "WEO",
      imf_indicator: "TM_RPCH",
    };
    const body = buildCatalogPreviewBody(def, row, "CZE");
    expect(body.set_id).toBe("IMF|IMF.RES|WEO|9.0.0|CZE.TM_RPCH");
    expect(body.query_params).toEqual({
      imf_country: "CZ",
      imf_flow: "WEO",
      imf_indicator: "TM_RPCH",
    });
  });

  it("OECD: předá alternativní set_id varianty pro fallback preview", () => {
    const def = { sourceType: "oecd", needsCountry: false };
    const row = {
      set_id: "SDMX2|OECD.SDD.STES|DSD_KEI@DF_KEI|4.0|IRL.A.IM.GR._T.Y._Z",
      name: "Imports · Growth rate · Annual",
      query_params: { provider: "oecd" },
      oecd_alternative_set_ids: [
        "SDMX2|OECD.SDD.STES|DSD_KEI@DF_KEI|4.0|IRL.A.IM.GR._T.Y.GY",
        "SDMX2|OECD.SDD.STES|DSD_KEI@DF_KEI|4.0|IRL.A.IM.GR._T.Y.G1",
      ],
    };
    const body = buildCatalogPreviewBody(def, row, "IRL");
    expect(body.oecd_alternative_set_ids).toEqual(row.oecd_alternative_set_ids);
  });
});

describe("resolveCatalogRowSetId", () => {
  it("Eurostat: composite series_id → dataset_id", () => {
    const row = {
      set_id: "prc_hicp_midx_hicp_housing_energy",
      dataset_id: "prc_hicp_midx",
    };
    expect(resolveCatalogRowSetId(row)).toBe("prc_hicp_midx");
  });

  it("Eurostat: uses preview dataset_id when row set_id is sidecar suffix", () => {
    const row = { set_id: "prc_hicp_midx_hicp_housing" };
    const preview = { dataset_id: "prc_hicp_midx" };
    expect(resolveCatalogRowSetId(row, preview)).toBe("prc_hicp_midx");
  });

  it("ECB: keeps FLOW/SERIES_KEY unchanged", () => {
    const sid = "ICP/M.CZ.N.040000.4.ANR";
    expect(resolveCatalogRowSetId({ set_id: sid })).toBe(sid);
  });

  it("ECB: uses preview request country for curated alias rows", () => {
    const def = { sourceType: "ecb", needsCountry: false };
    const row = {
      set_id: "ecb_mir_mortgage_rate_new_business",
      name: "Mortgage rate",
      preview_request_payload: { country: "SE" },
    };
    const body = buildCatalogPreviewBody(def, row, "CZE");
    expect(body.set_id).toBe("ecb_mir_mortgage_rate_new_business");
    expect(body.country).toBe("SE");
    expect(body.query_params).toEqual({ country: "SE" });
  });

  it("buildCatalogPreviewBody uses resolved Eurostat set_id", () => {
    const def = { sourceType: "eurostat", needsCountry: false };
    const row = {
      set_id: "prc_hicp_midx_hicp_housing_energy",
      dataset_id: "prc_hicp_midx",
      query_params: { geo: "CZ" },
    };
    const body = buildCatalogPreviewBody(def, row, "CZE");
    expect(body.set_id).toBe("prc_hicp_midx");
    expect(body.query_params.geo).toBe("CZ");
  });
});
