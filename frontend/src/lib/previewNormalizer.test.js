import {
  buildPreviewPayloadFromStructuredError,
  buildUnknownPreviewShapeMessage,
  normalizePreviewPayload,
  previewShapeDebug,
  unwrapApiErrorPayload,
} from "./previewNormalizer";

describe("previewNormalizer", () => {
  test("normalizes rows+fields to columns metadata", () => {
    const out = normalizePreviewPayload(
      {
        source: { source_type: "eurostat", set_id: "prc_hicp_aind", name: "HICP" },
        fields: ["time", "geo", "value"],
        rows: [{ time: "2024", geo: "IT", value: 2.1 }],
        total_count: 1,
      },
      "eurostat",
    );
    expect(out.columns).toEqual([
      { key: "time", label: "time" },
      { key: "geo", label: "geo" },
      { key: "value", label: "value" },
    ]);
    expect(out.rows).toHaveLength(1);
    expect(out.metadata.row_count).toBe(1);
  });

  test("normalizes data array when rows are missing", () => {
    const out = normalizePreviewPayload(
      {
        source: { source_type: "eurostat" },
        data: [{ TIME_PERIOD: "2024", REF_AREA: "IT", OBS_VALUE: 123.4 }],
      },
      "eurostat",
    );
    expect(out.rows).toHaveLength(1);
    expect(out.columns.some((c) => c.key === "TIME_PERIOD")).toBe(true);
  });

  test("unknown shape debug and message include keys", () => {
    const payload = { foo: 1, bar: true };
    const dbg = previewShapeDebug(payload);
    expect(dbg.keys).toEqual(["foo", "bar"]);
    expect(buildUnknownPreviewShapeMessage(payload)).toContain("foo, bar");
  });

  test("unwraps axios payload with nested detail object", () => {
    const out = unwrapApiErrorPayload({
      detail: { status: "error", message: "upstream returned HTTP 422" },
    });
    expect(out.status).toBe("error");
    expect(out.message).toContain("422");
  });

  test("builds preview payload diagnostics from structured upstream 422", () => {
    const out = buildPreviewPayloadFromStructuredError(
      {
        detail: {
          status: "error",
          error: "EUROSTAT_UPSTREAM_422",
          message: "upstream returned HTTP 422",
          dataset_id: "prc_hicp_inw",
          request_id: "eu-req-1",
          requested_filters: { geo_scope: "all_eu_countries", query_mode: "preview" },
          missing_filters: ["coicop"],
          available_dimensions: { coicop: 94, geo: 45 },
          dropped_filters: { unit: { reason: "dimension_not_available" } },
          warnings: ["budget_guard_blocked_upstream_request"],
          upstream_status: 422,
        },
      },
      { source_type: "eurostat", set_id: "prc_hicp_inw", name: "HICP item weights" },
    );
    expect(out.rows).toEqual([]);
    expect(out.dataset_id).toBe("prc_hicp_inw");
    expect(out.metadata?.diagnostic?.request_id).toBe("eu-req-1");
    expect(out.metadata?.diagnostic?.requested_filters?.geo_scope).toBe("all_eu_countries");
    expect(out.metadata?.diagnostic?.available_dimensions?.coicop).toBe(94);
    expect(out.metadata?.diagnostic?.dropped_filters?.unit?.reason).toBe("dimension_not_available");
    expect(Array.isArray(out.warnings)).toBe(true);
  });
});
