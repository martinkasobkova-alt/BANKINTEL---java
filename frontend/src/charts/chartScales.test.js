import {
  buildStickyYAxisSpec,
  assertMatchingBarDomains,
  getYAxisDomainForChart,
  getBarChartValueAxisSpec,
} from "./chartScales";

describe("chartScales", () => {
  test("bar chart default starts at 0", () => {
    const spec = getYAxisDomainForChart("bar", [5.95, 6.85, 8.1]);
    expect(spec.domain[0]).toBe(0);
    expect(spec.useZeroBaselineShape).toBe(true);
  });

  test("sticky Y axis has same domain as plot for bar chart", () => {
    const rows = [
      { y: 5.6 },
      { y: 6.85 },
      { y: 8.1 },
      { y: 13.71 },
      { y: 20.56 },
    ];
    const barSpec = getBarChartValueAxisSpec(rows, [], 4);
    const gutterSpec = buildStickyYAxisSpec({
      chartType: "bar",
      allRowsForBar: rows,
      plotMargin: { top: 16, bottom: 16 },
    });
    expect(assertMatchingBarDomains(barSpec.domain, gutterSpec)).toBe(true);
    expect(gutterSpec.min).toBe(0);
  });

  test("line chart domain does not force zero for mixed positive/negative", () => {
    const spec = getYAxisDomainForChart("line", [-5, 2, 10]);
    expect(spec.axis.min).toBeLessThan(0);
    expect(spec.axis.max).toBeGreaterThan(0);
  });
});
