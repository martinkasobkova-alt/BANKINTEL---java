import {
  buildPublicCatalogPath,
  catalogRowHasBrowseFallback,
  resolveBisFlowHint,
  resolveCatalogRowPrimaryAction,
} from "./catalogRowPrimaryAction";

const bisDef = { id: "bis", sourceType: "bis", label: "BIS" };
const ecbDef = { id: "ecb2", sourceType: "ecb", label: "ECB" };

describe("resolveBisFlowHint", () => {
  it("parses dataflow set_id suffix", () => {
    expect(
      resolveBisFlowHint({ set_id: "WS_CBS_PUB||DATAFLOW", item_kind: "dataflow" }),
    ).toBe("WS_CBS_PUB");
  });

  it("uses bis_dataflow field", () => {
    expect(resolveBisFlowHint({ bis_dataflow: "WS_TC", set_id: "x" })).toBe("WS_TC");
  });

  it("maps curated alias to flow", () => {
    expect(resolveBisFlowHint({ set_id: "bis_commercial_property_price_index" })).toBe("WS_CPP");
  });

  it("extracts flow from BIS|pipe id", () => {
    expect(resolveBisFlowHint({ set_id: "BIS|WS_TC|META" })).toBe("WS_TC");
  });
});

describe("resolveCatalogRowPrimaryAction", () => {
  it("builds public catalog URLs without admin-only source routes", () => {
    expect(
      buildPublicCatalogPath("worldbank", { q: "inflation", country: "hu" }),
    ).toBe("/search/catalog?catalog=data360&q=inflation&country=HU");
  });

  it("returns preview for eligible BIS series", () => {
    expect(
      resolveCatalogRowPrimaryAction(bisDef, {
        kind: "set",
        set_id: "BIS|WS_TC|Q.CZ.W0.S1.S1.N.A",
      }),
    ).toEqual({ type: "preview" });
  });

  it("opens inline BIS dataflow preview for dataflow hits", () => {
    const action = resolveCatalogRowPrimaryAction(bisDef, {
      kind: "dataflow",
      item_kind: "dataflow",
      set_id: "WS_CBS_PUB||DATAFLOW",
      bis_dataflow: "WS_CBS_PUB",
      name: "Consolidated banking",
    });
    expect(action.type).toBe("bis-dataflow-preview");
    expect(action.flowHint).toBe("WS_CBS_PUB");
    expect(action.label).toBe("Zobrazit data");
  });

  it("opens inline BIS dataflow preview for curated alias", () => {
    const action = resolveCatalogRowPrimaryAction(bisDef, {
      kind: "set",
      set_id: "bis_commercial_property_price_index",
      name: "Commercial property prices",
    });
    expect(action.type).toBe("bis-dataflow-preview");
    expect(action.flowHint).toBe("WS_CPP");
  });

  it("catalogRowHasBrowseFallback is true for non-previewable BIS hits", () => {
    expect(
      catalogRowHasBrowseFallback(bisDef, {
        kind: "dataflow",
        item_kind: "dataflow",
        set_id: "WS_DEBT_SEC2_PUB||DATAFLOW",
      }),
    ).toBe(true);
  });

  it("opens non-previewable deep-search candidates in the global catalog", () => {
    const action = resolveCatalogRowPrimaryAction(ecbDef, {
      fromDeepAi: true,
      kind: "set",
      set_id: "ecb_mir_sazba_uvery_podniky",
      name: "Úroková sazba — nové úvěry nefinančním podnikům (MIR)",
    });
    expect(action).toEqual({
      type: "navigate",
      path: "/search/catalog?catalog=ecb2&q=ecb_mir_sazba_uvery_podniky",
      label: "Otevřít v katalogu",
    });
    expect(
      catalogRowHasBrowseFallback(ecbDef, {
        fromDeepAi: true,
        kind: "set",
        set_id: "ecb_mir_sazba_uvery_podniky",
      }),
    ).toBe(true);
  });

  it("does not expose source catalogPath for non-previewable deep-search candidates", () => {
    const action = resolveCatalogRowPrimaryAction(
      { ...ecbDef, catalogPath: "/ecb2/browse-tree" },
      {
        fromDeepAi: true,
        kind: "cat",
        set_id: "ecb_mir_mortgage_rate_new_business",
        country: "SE",
      },
    );
    expect(action).toEqual({
      type: "navigate",
      path: "/search/catalog?catalog=ecb2&q=ecb_mir_mortgage_rate_new_business&country=SE",
      label: "Otevřít v katalogu",
    });
  });

  it("opens Eurostat dimension selection through the public catalog", () => {
    const action = resolveCatalogRowPrimaryAction(
      { id: "eurostat", sourceType: "eurostat", catalogPath: "/sources/eurostat" },
      { set_id: "prc_hicp_manr", kind: "set", fromDeepAi: true, requires_dimensions: true },
    );
    expect(action.type).toBe("navigate");
    expect(action.path).toBe(
      "/search/catalog?catalog=eurostat&q=prc_hicp_manr&set_id=prc_hicp_manr&preview=1",
    );
    expect(action.path).not.toContain("/sources/");
  });
});
