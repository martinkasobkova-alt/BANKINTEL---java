import {
  coerceChartNumeric,
  getFullXDomain,
  getVisibleData,
  getSafeYDomain,
  buildSafeNumericAxis,
  buildRechartsValueDomain,
  getBarChartValueAxisSpec,
  getYAxisDomainForChart,
  chartBarPointValue,
  chartRowsWithZeroBaselineBars,
  collectOverlayYValues,
  computeNextXZoom,
} from "./chartZoomHelpers";
import { computeZeroBaselineBarGeometry } from "./chartZeroBaselineBarShape";
import { parseNumber } from "@/lib/format";

describe("chartZoomHelpers", () => {
  test("getVisibleData slices by index domain", () => {
    const data = [{ x: "A", y: 1 }, { x: "B", y: 2 }, { x: "C", y: 3 }, { x: "D", y: 4 }];
    expect(getFullXDomain(data)).toEqual([0, 3]);
    expect(getVisibleData(data, [1, 2])).toEqual([
      { x: "B", y: 2 },
      { x: "C", y: 3 },
    ]);
  });

  test("buildSafeNumericAxis anchors at zero for non-negative data", () => {
    const axis = buildSafeNumericAxis([8.23, 12.92, 17.61, 22.29], 4);
    expect(axis.min).toBe(0);
    expect(axis.max).toBeCloseTo(22.29 * 1.06, 5);
    expect(axis.ticks[0]).toBe(0);
    expect(axis.ticks[axis.ticks.length - 1]).toBeCloseTo(22.29 * 1.06, 5);
  });

  test("buildSafeNumericAxis anchors at zero for non-positive data", () => {
    const axis = buildSafeNumericAxis([-12, -8, -3], 4);
    expect(axis.max).toBe(0);
    expect(axis.min).toBeCloseTo(-12 * 1.06, 5);
    expect(axis.ticks[axis.ticks.length - 1]).toBe(0);
  });

  test("buildSafeNumericAxis keeps full span when data crosses zero", () => {
    const axis = buildSafeNumericAxis([-5, 2, 10], 4);
    expect(axis.min).toBeCloseTo(-5.45, 2);
    expect(axis.max).toBeCloseTo(10.45, 2);
  });

  test("getYAxisDomainForChart bar with only positive values starts at zero", () => {
    const values = [5.95, 6.85, 8.1];
    const spec = getYAxisDomainForChart("bar", values, { tickCount: 3 });
    expect(spec.domain[0]).toBe(0);
    expect(spec.domain[1]).toBeGreaterThanOrEqual(8.1);
    expect(spec.useZeroBaselineShape).toBe(true);
    expect(spec.axis.ticks[0]).toBe(0);
  });

  test("Germany bar grows from zero baseline not from mid-axis tick", () => {
    const values = [5.95, 6.85, 8.1];
    const { domain } = getYAxisDomainForChart("bar", values, { tickCount: 3 });
    const plotHeight = 200;
    const germany = computeZeroBaselineBarGeometry(domain[0], domain[1], 5.95, plotHeight);
    const france = computeZeroBaselineBarGeometry(domain[0], domain[1], 6.85, plotHeight);
    expect(germany).not.toBeNull();
    expect(france).not.toBeNull();
    expect(germany.barHeight).toBeLessThan(france.barHeight);
    expect(germany.barHeight).toBeGreaterThan(plotHeight * 0.25);
    const wrongBaselineHeight = ((5.95 - 6.85) / (domain[1] - domain[0])) * plotHeight;
    expect(germany.barHeight).toBeGreaterThan(Math.max(0, wrongBaselineHeight) + 10);
    expect(germany.barY).toBeGreaterThan(france.barY);
  });

  const ROE_EU_ROWS = [
    { x: "Germany", y: 5.6 },
    { x: "France", y: 6.85 },
    { x: "Malta", y: 8.1 },
    { x: "Euro area", y: 13.71 },
    { x: "Netherlands", y: 20.56 },
  ];

  test("ROE EU bar chart uses full dataset max (Netherlands 20.56)", () => {
    const spec = getBarChartValueAxisSpec(ROE_EU_ROWS, [], 4);
    expect(spec.domain[0]).toBe(0);
    expect(spec.domain[1]).toBeGreaterThanOrEqual(20.56);
    expect(spec.useZeroBaselineShape).toBe(true);
  });

  test("ROE EU Germany height ratio ~27% against full domain max", () => {
    const spec = getBarChartValueAxisSpec(ROE_EU_ROWS, [], 4);
    const plotHeight = 200;
    const germany = computeZeroBaselineBarGeometry(spec.domain[0], spec.domain[1], 5.6, plotHeight);
    const france = computeZeroBaselineBarGeometry(spec.domain[0], spec.domain[1], 6.85, plotHeight);
    const tick685Ratio = 6.85 / spec.domain[1];
    expect(germany.ratio).toBeCloseTo(5.6 / spec.domain[1], 2);
    expect(germany.ratio).toBeLessThan(tick685Ratio);
    expect(germany.ratio).toBeGreaterThan(0.24);
    expect(germany.ratio).toBeLessThan(0.29);
    expect(germany.barY).toBeGreaterThan(france.barY);
  });

  test("zoomed bar subset must not shrink Y domain used by sticky axis and bars", () => {
    const zoomed = ROE_EU_ROWS.slice(0, 3);
    const fullSpec = getBarChartValueAxisSpec(ROE_EU_ROWS, [], 4);
    const zoomedOnly = getYAxisDomainForChart(
      "bar",
      zoomed.map((r) => r.y),
      { tickCount: 4 }
    );
    expect(fullSpec.domain[1]).toBeGreaterThan(zoomedOnly.domain[1]);
    expect(fullSpec.domain[1]).toBeGreaterThanOrEqual(20.56);
  });

  test("getYAxisDomainForChart line keeps auto domain for mixed positive data", () => {
    const values = [5.95, 6.85, 8.1];
    const spec = getYAxisDomainForChart("line", values, { tickCount: 4 });
    expect(spec.domain[0]).toBe(0);
    expect(spec.useZeroBaselineShape).toBe(false);
  });

  test("buildRechartsValueDomain forces zero baseline for non-negative bar data", () => {
    const axis = buildSafeNumericAxis([5.6, 6.6, 15.1, 19.4], 5);
    const domain = buildRechartsValueDomain(axis, [5.6, 6.6, 15.1, 19.4]);
    expect(domain[0]).toBe(0);
    expect(domain[1]).toBeGreaterThanOrEqual(19.4);
  });

  test("chartRowsWithZeroBaselineBars wraps values as [0, y]", () => {
    const rows = [{ x: "DE", y: 5.6 }, { x: "CZ", y: 15.1 }];
    expect(chartRowsWithZeroBaselineBars(rows)).toEqual([
      { x: "DE", y: [0, 5.6] },
      { x: "CZ", y: [0, 15.1] },
    ]);
    expect(chartBarPointValue([0, 5.6])).toBeCloseTo(5.6, 5);
    expect(chartBarPointValue([0, 103693])).toBe(103693);
  });

  test("chartBarPointValue avoids parseNumber comma trap on [0, y] arrays", () => {
    const range = [0, 71000];
    expect(String(range)).toBe("0,71000");
    expect(parseNumber(range)).toBeCloseTo(0.71, 5);
    expect(chartBarPointValue(range)).toBe(71000);
  });

  test("getSafeYDomain uses parseNumber for string y (visible slice)", () => {
    const visible = [{ x: "DE", y: "5,6" }, { x: "FR", y: "6,6" }];
    const fullFallback = { min: 0, max: 100, ticks: [0, 25, 50, 75, 100] };
    const axis = getSafeYDomain([{ y: 0 }, ...visible], ["y"], fullFallback, 5);
    expect(axis.max).toBeLessThan(20);
    expect(axis.max).toBeGreaterThanOrEqual(6.6);
    expect(axis.min).toBe(0);
  });

  test("coerceChartNumeric parses first token before unit text", () => {
    expect(coerceChartNumeric("8,9 Percentage")).toBeCloseTo(8.9, 5);
  });

  test("getSafeYDomain falls back when no numeric points", () => {
    const fb = { min: 0, max: 10, ticks: [0, 5, 10] };
    const axis = getSafeYDomain([{ y: "x" }, { y: null }], ["y"], fb, 3);
    expect(axis).toEqual(fb);
  });

  test("collectOverlayYValues gathers reference line y values", () => {
    expect(
      collectOverlayYValues({
        average: 10,
        median: 8,
        trendSegment: { yStart: 5, yEnd: 12 },
      })
    ).toEqual([10, 8, 5, 12]);
  });

  test("computeNextXZoom zooms in toward cursor and out to full range", () => {
    const n = 100;
    const rectLeft = 0;
    const rectWidth = 200;
    const centerX = 100;

    const first = computeNextXZoom(null, n, -120, centerX, rectLeft, rectWidth);
    expect(first).not.toBeNull();
    expect(first.end - first.start + 1).toBeLessThan(n);

    const tighter = computeNextXZoom(first, n, -120, centerX, rectLeft, rectWidth);
    expect(tighter.end - tighter.start + 1).toBeLessThan(first.end - first.start + 1);

    let zoom = tighter;
    for (let i = 0; i < 24 && zoom; i += 1) {
      zoom = computeNextXZoom(zoom, n, 120, centerX, rectLeft, rectWidth);
    }
    expect(zoom).toBeNull();
  });

  test("computeNextXZoom returns null when already at full range and zooming out", () => {
    expect(computeNextXZoom(null, 50, 120, 50, 0, 100)).toBeNull();
  });
});
