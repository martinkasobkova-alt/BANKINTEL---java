import {
  buildCatalogDeepSearchBody,
  buildCatalogSourceStatusRows,
  extractDeepSearchPipelineDiagnostics,
  normalizeDeepSearchResultRows,
  sourceIdsFromCatalogRoute,
} from "./catalogDeepSearchClient";

describe("catalogDeepSearchClient", () => {
  test("buildCatalogDeepSearchBody always sends use_ai true", () => {
    const body = buildCatalogDeepSearchBody({
      query: "inflace Česko",
      sources: ["eurostat", "imf", "tradingeconomics"],
    });
    expect(body.use_ai).toBe(true);
    expect(body.use_ai_story).toBe(false);
    expect(body.query).toBe("inflace Česko");
    expect(body.sources).toEqual(["eurostat", "imf"]);
    expect(body.mode).toBe("multi");
    expect(body.limit_per_source).toBe(20);
  });

  test("normalizeDeepSearchResultRows reads grouped_results.candidates", () => {
    const rows = normalizeDeepSearchResultRows({
      grouped_results: {
        verified: [{ set_id: "A", catalog_id: "imf" }],
        candidates: [{ set_id: "B", catalog_id: "eurostat", status: "candidate" }],
      },
    });
    expect(rows.verified).toHaveLength(1);
    expect(rows.candidates).toHaveLength(1);
    expect(rows.possible).toHaveLength(1);
  });

  test("extractDeepSearchPipelineDiagnostics maps search_diagnostics", () => {
    const diag = extractDeepSearchPipelineDiagnostics({
      search_diagnostics: {
        intent: { query_domain: "macro" },
        resolved_geo: { country_codes: ["CZ"] },
        searched_sources: ["eurostat", "imf"],
        pipeline_telemetry: {
          duration_ms_total: 42000,
          max_retrieval_queries_per_source: 10,
          timeout_count: 0,
          fallback_count: 1,
          gpt_model: "gpt-5.4",
        },
      },
    });
    expect(diag.queryDomain).toBe("macro");
    expect(diag.resolvedGeo.country_codes).toEqual(["CZ"]);
    expect(diag.searchedSources).toEqual(["eurostat", "imf"]);
    expect(diag.durationMsTotal).toBe(42000);
    expect(diag.retrievalQueryCap).toBe(10);
    expect(diag.fallbackCount).toBe(1);
    expect(diag.gptModel).toBe("gpt-5.4");
  });

  test("sourceIdsFromCatalogRoute reads selected_sources", () => {
    expect(sourceIdsFromCatalogRoute({ selected_sources: ["arad", "ecb2", "bis"] })).toEqual([
      "arad",
      "ecb2",
      "bis",
    ]);
  });

  test("buildCatalogSourceStatusRows builds pending rows", () => {
    const rows = buildCatalogSourceStatusRows(["arad", "bis"], (id) => id.toUpperCase(), "running");
    expect(rows).toEqual([
      { source: "arad", label: "ARAD", status: "running", row_count: 0, duration_ms: 0, message_cs: "" },
      { source: "bis", label: "BIS", status: "running", row_count: 0, duration_ms: 0, message_cs: "" },
    ]);
  });
});
