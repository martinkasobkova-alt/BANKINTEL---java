import { buildChartSeriesStatistics, buildSingleSeriesStatistics } from "./chartSeriesStatistics";

describe("chartSeriesStatistics", () => {
  test("computes single-series statistics for ordinary chart analysis", () => {
    const stats = buildSingleSeriesStatistics({
      chartRows: [
        { x: "2020", y: 100 },
        { x: "2021", y: 120 },
        { x: "2022", y: 90 },
        { x: "2023", y: 150 },
      ],
      frequency: "Y",
      label: "ROA",
    });

    expect(stats.ok).toBe(true);
    expect(stats.series.latestValue).toBe(150);
    expect(stats.series.min).toBe(90);
    expect(stats.series.max).toBe(150);
    expect(stats.series.mean).toBe(115);
    expect(stats.series.index100).toBe(150);
  });

  test("computes pair statistics from aligned common periods", () => {
    const rows = [
      { x: "2020", a: 1, b: 2 },
      { x: "2021", a: 2, b: 4 },
      { x: "2022", a: 3, b: 6 },
      { x: "2023", a: 4, b: 8 },
    ];

    const stats = buildChartSeriesStatistics({
      chartRows: rows,
      seriesList: [{ key: "a", name: "A" }, { key: "b", name: "B" }],
      frequency: "Y",
    });

    expect(stats.ok).toBe(true);
    expect(stats.series[0].latestValue).toBe(4);
    expect(stats.series[0].index100).toBe(400);
    expect(stats.pairs[0].correlation).toBeCloseTo(1, 6);
    expect(stats.pairs[0].regression.beta).toBeCloseTo(2, 6);
    expect(stats.pairs[0].regression.r2).toBeCloseTo(1, 6);
  });

  test("returns latest ranking and deviation from average", () => {
    const rows = [
      { x: "2024", cz: 12, de: 10, pl: 8 },
      { x: "2025", cz: 16, de: 10, pl: 4 },
    ];

    const stats = buildChartSeriesStatistics({
      chartRows: rows,
      seriesList: [
        { key: "cz", name: "Czechia" },
        { key: "de", name: "Germany" },
        { key: "pl", name: "Poland" },
      ],
      frequency: "Y",
    });

    expect(stats.ranking.period).toBe("2025");
    expect(stats.ranking.average).toBe(10);
    expect(stats.ranking.items.map((item) => item.key)).toEqual(["cz", "de", "pl"]);
    expect(stats.ranking.items[0].deviation).toBe(6);
  });

  test("detects high collinearity by maximum absolute correlation", () => {
    const rows = [
      { x: "2020", a: 1, b: -1, c: 4 },
      { x: "2021", a: 2, b: -2, c: 4 },
      { x: "2022", a: 3, b: -3, c: 5 },
      { x: "2023", a: 4, b: -4, c: 6 },
    ];

    const stats = buildChartSeriesStatistics({
      chartRows: rows,
      seriesList: [{ key: "a" }, { key: "b" }, { key: "c" }],
      frequency: "Y",
    });

    expect(stats.collinearity.high).toBe(true);
    expect(stats.collinearity.maxAbsCorrelation).toBeCloseTo(1, 6);
  });
});
