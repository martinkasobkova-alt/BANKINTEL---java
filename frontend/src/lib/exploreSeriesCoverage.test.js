import {
  buildSeriesCoverageIndex,
  enrichUsedSeriesRow,
  normalizeUsedSeriesRow,
} from "@/lib/exploreSeriesCoverage";

describe("buildSeriesCoverageIndex", () => {
  it("indexes an array of per-series rows by title and series_id", () => {
    const index = buildSeriesCoverageIndex([
      { title: "HDP Německo", series_id: "IMF_HDP_DE", status: "ok" },
      { title: "Inflace CZ", series_id: "ECB_INF_CZ", status: "stale" },
    ]);
    expect(index.byTitle.size).toBe(2);
    expect(index.bySeriesId.size).toBe(2);
    expect(index.bySeriesId.get("imf_hdp_de")?.status).toBe("ok");
  });

  it("does not throw and returns an empty index when given the backend's summary-count object shape", () => {
    // Real production payload from /explore/summarize: {"loaded":1,"failed":0,"requested":1}.
    const summaryObject = { loaded: 1, failed: 0, requested: 1 };
    expect(() => buildSeriesCoverageIndex(summaryObject)).not.toThrow();
    const index = buildSeriesCoverageIndex(summaryObject);
    expect(index.byTitle.size).toBe(0);
    expect(index.bySeriesId.size).toBe(0);
  });

  it("does not throw for null/undefined/string input", () => {
    expect(() => buildSeriesCoverageIndex(null)).not.toThrow();
    expect(() => buildSeriesCoverageIndex(undefined)).not.toThrow();
    expect(() => buildSeriesCoverageIndex("not an array")).not.toThrow();
    expect(buildSeriesCoverageIndex(undefined).byTitle.size).toBe(0);
  });

  it("skips non-object rows inside an array without throwing", () => {
    const index = buildSeriesCoverageIndex([null, "x", 42, { title: "OK row" }]);
    expect(index.byTitle.size).toBe(1);
  });
});

describe("normalizeUsedSeriesRow", () => {
  it("normalizes a string row into a title-only object", () => {
    expect(normalizeUsedSeriesRow("Some series")).toEqual({ title: "Some series" });
  });

  it("normalizes an object row, preferring series_id/dataset_id/set_id", () => {
    const normalized = normalizeUsedSeriesRow({
      title: "Test",
      series_id: "s1",
      set_id: "set1",
    });
    expect(normalized.title).toBe("Test");
    expect(normalized.series_id).toBe("s1");
    expect(normalized.dataset_id).toBe("set1");
    expect(normalized.set_id).toBe("set1");
  });

  it("preserves source identity used for exact chart matching", () => {
    const normalized = normalizeUsedSeriesRow({
      title: "Test",
      source_type: "ecb2",
      series_id: "series-1",
      set_id: "dataset-1",
    });
    expect(normalized.source_type).toBe("ecb2");
  });
});

describe("enrichUsedSeriesRow", () => {
  it("enriches a used-series row with matching coverage data by series_id", () => {
    const coverageIndex = buildSeriesCoverageIndex([
      { title: "HDP Německo", series_id: "IMF_HDP_DE", status: "ok", fact: "growing" },
    ]);
    const enriched = enrichUsedSeriesRow({ series_id: "IMF_HDP_DE" }, coverageIndex);
    expect(enriched.status).toBe("ok");
    expect(enriched.fact).toBe("growing");
  });

  it("falls back to the row itself (no crash, no enrichment) when coverageIndex is empty due to object-shaped series_coverage", () => {
    const coverageIndex = buildSeriesCoverageIndex({ loaded: 1, failed: 0, requested: 1 });
    const enriched = enrichUsedSeriesRow({ title: "Some row", series_id: "X" }, coverageIndex);
    expect(enriched.title).toBe("Some row");
    expect(enriched.status).toBeNull();
  });

  it("returns the normalized row unchanged when coverageIndex is null", () => {
    const enriched = enrichUsedSeriesRow("plain string row", null);
    expect(enriched).toEqual({ title: "plain string row" });
  });
});
