import { eurostatAiRowNeedsOpenInCatalog } from "./eurostatQueryableSlice";

describe("eurostatQueryableSlice", () => {
  test("AI eurostat row without catalog node ref needs open catalog UX", () => {
    expect(
      eurostatAiRowNeedsOpenInCatalog({ sourceType: "eurostat" }, { fromDeepAi: true }),
    ).toBe(true);
    expect(
      eurostatAiRowNeedsOpenInCatalog(
        { sourceType: "eurostat" },
        {
          fromDeepAi: true,
          catalog_node_ref: {
            source: "eurostat",
            set_id: "nama_10_gdp",
            add_source_endpoint: "/api/eurostat/catalog/add-source",
          },
        },
      ),
    ).toBe(false);
    expect(
      eurostatAiRowNeedsOpenInCatalog(
        { sourceType: "eurostat" },
        { fromDeepAi: true, catalog_node_ref: { source: "eurostat", set_id: "x", add_source_endpoint: "/wrong" } },
      ),
    ).toBe(true);
  });
});
