import {
  mean,
  median,
  standardDeviation,
  yoyChange,
  momChange,
  correlation,
  linearTrendSlope,
  outlierDetection,
  indexSeriesTo100,
  rollingAverageSeries,
  rollingMedianSeries,
  spreadSeries,
  ratioSeries,
} from "./chartAnalytics";

describe("chartAnalytics", () => {
  test("mean and median", () => {
    expect(mean([1, 2, 3, 4, 5])).toBe(3);
    expect(median([1, 2, 3, 4, 5])).toBe(3);
    expect(median([1, 2, 3, 4])).toBe(2.5);
  });

  test("standard deviation", () => {
    const sd = standardDeviation([2, 4, 4, 4, 5, 5, 7, 9]);
    expect(sd).toBeCloseTo(2, 0);
  });

  test("YoY returns correct values", () => {
    const values = Array.from({ length: 14 }, (_, i) => (i === 13 ? 112 : 100));
    expect(yoyChange(values, 12)).toBeCloseTo(12, 5);
  });

  test("MoM returns correct values", () => {
    expect(momChange([100, 110])).toBeCloseTo(10, 5);
  });

  test("index 100 returns correct values", () => {
    const rows = [
      { x: "2020", y: 50 },
      { x: "2021", y: 75 },
      { x: "2022", y: 100 },
    ];
    const indexed = indexSeriesTo100(rows);
    expect(indexed[0].y).toBe(100);
    expect(indexed[2].y).toBe(200);
  });

  test("rolling average returns correct values", () => {
    const rows = [1, 2, 3, 4, 5].map((y, i) => ({ x: `P${i}`, y }));
    const rolled = rollingAverageSeries(rows, 3);
    expect(rolled.length).toBe(3);
    expect(rolled[0].y).toBeCloseTo(2, 5);
    expect(rolled[2].y).toBeCloseTo(4, 5);
  });

  test("rolling median returns correct values", () => {
    const rows = [1, 100, 2, 99, 3].map((y, i) => ({ x: `P${i}`, y }));
    const rolled = rollingMedianSeries(rows, 3);
    expect(rolled[0].y).toBe(2);
  });

  test("spread returns correct values", () => {
    const a = [{ x: "2020", y: 10 }, { x: "2021", y: 15 }];
    const b = [{ x: "2020", y: 4 }, { x: "2021", y: 5 }];
    const spread = spreadSeries(a, b);
    expect(spread[0].y).toBe(6);
    expect(spread[1].y).toBe(10);
  });

  test("ratio returns correct values", () => {
    const a = [{ x: "2020", y: 10 }, { x: "2021", y: 20 }];
    const b = [{ x: "2020", y: 5 }, { x: "2021", y: 4 }];
    const ratio = ratioSeries(a, b);
    expect(ratio[0].y).toBe(2);
    expect(ratio[1].y).toBe(5);
  });

  test("correlation returns correct values", () => {
    const a = [1, 2, 3, 4, 5];
    const b = [2, 4, 6, 8, 10];
    expect(correlation(a, b)).toBeCloseTo(1, 5);
  });

  test("linear trend slope", () => {
    expect(linearTrendSlope([1, 2, 3, 4, 5])).toBeCloseTo(1, 5);
  });

  test("outlier detection", () => {
    const values = [1, 2, 2, 3, 2, 100];
    const outliers = outlierDetection(values, { threshold: 2 });
    expect(outliers.some((o) => o.value === 100)).toBe(true);
  });
});
