import {
  computeValueCompareStats,
  resolveKpiSummaryMode,
  resolveValueCompareSummaryDensity,
} from "./chartValueCompareStats";

describe("computeValueCompareStats", () => {
  test("returns min, max, median for categorical rows", () => {
    const stats = computeValueCompareStats([
      { x: "DE", y: 5.9 },
      { x: "FR", y: 8.2 },
      { x: "EE", y: 13.8 },
      { x: "MT", y: 7.1 },
    ]);
    expect(stats).not.toBeNull();
    expect(stats.min).toEqual({ label: "DE", value: 5.9 });
    expect(stats.max).toEqual({ label: "EE", value: 13.8 });
    expect(stats.median).toBeCloseTo(7.65, 5);
    expect(stats.count).toBe(4);
  });

  test("needs at least two numeric points", () => {
    expect(computeValueCompareStats([{ x: "A", y: 1 }])).toBeNull();
    expect(computeValueCompareStats([])).toBeNull();
  });

  test("ignores rows without label or numeric value", () => {
    const stats = computeValueCompareStats([
      { x: "", y: 1 },
      { x: "A", y: null },
      { x: "B", y: 3 },
      { x: "C", y: 5 },
    ]);
    expect(stats?.count).toBe(2);
    expect(stats?.median).toBe(4);
  });
});

describe("resolveValueCompareSummaryDensity", () => {
  test("mini for smallest tiles", () => {
    expect(resolveValueCompareSummaryDensity({ miniChartMode: true })).toBe("mini");
    expect(resolveValueCompareSummaryDensity({ veryNarrowWidget: true })).toBe("mini");
  });

  test("expanded in fullscreen", () => {
    expect(resolveValueCompareSummaryDensity({ fsExpand: true })).toBe("expanded");
  });

  test("compact for narrow dashboard tiles", () => {
    expect(resolveValueCompareSummaryDensity({ chartCompact: true })).toBe("mini");
  });

  test("normal as default", () => {
    expect(resolveValueCompareSummaryDensity({})).toBe("normal");
  });
});

describe("resolveKpiSummaryMode", () => {
  test("explicit mode wins", () => {
    expect(resolveKpiSummaryMode({ mode: "hidden", catalogLivePreview: true })).toBe("hidden");
    expect(resolveKpiSummaryMode({ mode: "full", chartCompact: true })).toBe("full");
  });

  test("catalog previews use compact three-cell KPI strip", () => {
    expect(resolveKpiSummaryMode({ catalogLivePreview: true })).toBe("compact");
    expect(resolveKpiSummaryMode({ catalogChartSize: "detail" })).toBe("compact");
    expect(resolveKpiSummaryMode({ catalogChartSize: "detail-expanded" })).toBe("compact");
    expect(resolveKpiSummaryMode({ catalogChartSize: "compact" })).toBe("mini");
    expect(
      resolveKpiSummaryMode({
        catalogLivePreview: true,
        catalogChartSize: "fullscreen",
        isMobileChartUi: true,
      }),
    ).toBe("mini");
    expect(
      resolveKpiSummaryMode({
        catalogLivePreview: true,
        catalogChartSize: "fullscreen",
        isMobileChartUi: false,
      }),
    ).toBe("compact");
  });

  test("dashboard auto follows widget size", () => {
    expect(resolveKpiSummaryMode({})).toBe("compact");
    expect(resolveKpiSummaryMode({ chartCompact: true })).toBe("compact");
    expect(resolveKpiSummaryMode({ veryNarrowWidget: true, miniChartMode: true })).toBe("compact");
    expect(resolveKpiSummaryMode({ miniChartMode: true })).toBe("compact");
    expect(resolveKpiSummaryMode({ fsExpand: true })).toBe("full");
  });

  test("hidden when no stats are available", () => {
    expect(resolveKpiSummaryMode({ hasStats: false })).toBe("hidden");
  });
});
