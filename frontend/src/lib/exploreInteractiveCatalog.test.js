import { describe, expect, it } from "vitest";
import { buildExploreInteractiveCatalogContext } from "@/lib/exploreInteractiveCatalog";

describe("buildExploreInteractiveCatalogContext", () => {
  it("preserves the verified preview contract from Manager Explorer", () => {
    const context = buildExploreInteractiveCatalogContext({
      name: "Return on equity of banks",
      source: "eurostat",
      setId: "tipsbd40",
      primaryCountryCode: "CZ",
      unit: "PC",
      compareRef: {
        source_type: "eurostat",
        set_id: "tipsbd40",
        selected_indicator: "roe",
        query_params: { geo: "CZ", unit: "PC" },
      },
    });

    expect(context.def.id).toBe("eurostat");
    expect(context.row).toMatchObject({
      set_id: "tipsbd40",
      source_type: "eurostat",
      selected_indicator: "roe",
      indicator_id: "roe",
      geo: "CZ",
      query_params: { geo: "CZ", unit: "PC" },
    });
  });

  it("resolves source aliases and falls back to the chart set id", () => {
    const context = buildExploreInteractiveCatalogContext({
      name: "ECB series",
      source: "ecb2",
      setId: "MIR/M.DE.TEST",
      geo: "DE",
    });

    expect(context.def.id).toBe("ecb2");
    expect(context.row.set_id).toBe("MIR/M.DE.TEST");
    expect(context.row.geo).toBe("DE");
  });

  it("returns null for non-catalog report series", () => {
    expect(buildExploreInteractiveCatalogContext({ name: "Computed score", source: "internal" })).toBeNull();
  });
});
