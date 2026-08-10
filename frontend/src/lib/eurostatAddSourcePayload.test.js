import { buildEurostatAddSourceBody, sanitizeEurostatQueryParams } from "./eurostatAddSourcePayload";

describe("buildEurostatAddSourceBody", () => {
  test("manual tree add keeps minimal body", () => {
    expect(buildEurostatAddSourceBody({ set_id: "nama_10_gdp" })).toEqual({ set_id: "nama_10_gdp" });
  });

  test("ai add carries node ref and from_ai_search", () => {
    const payload = buildEurostatAddSourceBody(
      {
        set_id: "nama_10_gdp",
        full_path: "National accounts > GDP",
        query_params: { geo: "CZ" },
      },
      { fromAiSearch: true },
    );
    expect(payload.set_id).toBe("nama_10_gdp");
    expect(payload.from_ai_search).toBe(true);
    expect(payload.catalog_node_ref?.add_source_endpoint).toBe("/api/eurostat/catalog/add-source");
    expect(payload.query_params?.geo).toBe("CZ");
  });

  test("sanitizeEurostatQueryParams normalizes multi-geo csv to array", () => {
    const qp = sanitizeEurostatQueryParams({
      geo: "DE,CZ",
      coicop: "CP00",
      query_mode: "preview",
      lastTimePeriod: "1",
    });
    expect(qp.geo).toEqual(["DE", "CZ"]);
    expect(qp.query_mode).toBeUndefined();
    expect(qp.lastTimePeriod).toBeUndefined();
  });
});
