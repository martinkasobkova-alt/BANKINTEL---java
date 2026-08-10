import {
  aggregateBucketValues,
  buildSingleSeriesFromRows,
  buildTimeSeriesPivotFromRows,
  isAdditiveMetric,
  isCategoricalGroupField,
} from "./chartTimeSeriesPivot";

describe("chartTimeSeriesPivot", () => {
  const rows = [
    { date: "2024", geo: "CZ", value: 14 },
    { date: "2024", geo: "FR", value: 6 },
    { date: "2024", geo: "PL", value: 14 },
    { date: "2023", geo: "CZ", value: 13 },
    { date: "2023", geo: "FR", value: 7 },
    { date: "2023", geo: "PL", value: 12 },
  ];

  test("time series pivot keeps separate geo lines — never sums categories", () => {
    const pivot = buildTimeSeriesPivotFromRows(rows, { groupField: "geo" });
    expect(pivot.multiSeries).toBe(true);
    expect(pivot.seriesIds).toEqual(["CZ", "FR", "PL"]);

    const y2024 = pivot.rows.find((r) => r.period === "2024");
    const y2023 = pivot.rows.find((r) => r.period === "2023");
    expect(y2024.CZ).toBe(14);
    expect(y2024.FR).toBe(6);
    expect(y2024.PL).toBe(14);
    expect(y2023.CZ).toBe(13);
    expect(y2023.FR).toBe(7);
    expect(y2023.PL).toBe(12);

    const single = buildSingleSeriesFromRows(rows);
    expect(single).toHaveLength(2);
    expect(single.find((r) => r.period === "2024")?.value).not.toBe(34);
    expect(single.find((r) => r.period === "2023")?.value).not.toBe(32);
  });

  test("isCategoricalGroupField recognizes geo-like dimensions", () => {
    expect(isCategoricalGroupField("geo")).toBe(true);
    expect(isCategoricalGroupField("REF_AREA")).toBe(true);
    expect(isCategoricalGroupField("nace_r2")).toBe(true);
    expect(isCategoricalGroupField("TIME_PERIOD")).toBe(false);
  });

  test("isAdditiveMetric treats ROE and percentages as non-additive", () => {
    expect(isAdditiveMetric({ title: "Return on equity of banks", unit: "PC" })).toBe(false);
    expect(isAdditiveMetric({ unit: "%" })).toBe(false);
    expect(isAdditiveMetric({ title: "Bank NPL ratio" })).toBe(false);
    expect(isAdditiveMetric({ title: "Total revenue", unit: "EUR" })).toBe(true);
    expect(isAdditiveMetric({ title: "Počet zaměstnanců" })).toBe(true);
  });

  test("aggregateBucketValues does not sum non-additive metrics", () => {
    expect(
      aggregateBucketValues([14, 6, 14], "sum", { unit: "PC", title: "Return on equity" }),
    ).toBe(14);
    expect(
      aggregateBucketValues([10, 20], "sum", { title: "Total sales volume", unit: "EUR" }),
    ).toBe(30);
  });
});
