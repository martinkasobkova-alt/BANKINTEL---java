import {
  computeTimeSeriesStats,
  formatAbsoluteChange,
  formatRelativeChange,
  formatSeriesStatValue,
  isPercentUnit,
} from "./chartTimeSeriesStats";

describe("chartTimeSeriesStats", () => {
  test("computes last value, delta and periods", () => {
    const stats = computeTimeSeriesStats([
      { x: "2023", y: 1.16 },
      { x: "2024", y: 1.15 },
      { x: "2025", y: 1.13 },
    ]);
    expect(stats?.lastValue).toBe(1.13);
    expect(stats?.lastPeriod).toBe("2025");
    expect(stats?.prevValue).toBe(1.15);
    expect(stats?.prevPeriod).toBe("2024");
    expect(stats?.delta).toBeCloseTo(-0.02, 5);
    expect(stats?.hasChange).toBe(true);
    expect(stats.relativePct).toBeCloseTo(-1.739, 2);
  });

  test("returns stats with single point without change", () => {
    const stats = computeTimeSeriesStats([{ x: "2025", y: 2.5 }]);
    expect(stats?.lastValue).toBe(2.5);
    expect(stats?.hasChange).toBe(false);
    expect(stats?.delta).toBeNull();
  });

  test("percent unit formatting", () => {
    expect(isPercentUnit("%")).toBe(true);
    expect(formatSeriesStatValue(1.13, "%")).toBe("1,13 %");
    expect(formatAbsoluteChange(-0.03, "%")).toBe("-0,03 p. b.");
    expect(formatRelativeChange(1.13, 1.16)).toBe("-2,6 %");
  });
});
