import {
  alignMultiSeriesRowsToCoarsestFrequency,
  bucketPeriodForFrequency,
} from "./chartFrequencyAlign";

describe("chartFrequencyAlign", () => {
  test("monthly series aligns to annual by taking the last month in year", () => {
    const rows = [
      { period: "2022", annual: 10 },
      { period: "2023", annual: 20 },
      { period: "2022-01", monthly: 1 },
      { period: "2022-12", monthly: 12 },
      { period: "2023-06", monthly: 16 },
      { period: "2023-12", monthly: 24 },
    ];

    const out = alignMultiSeriesRowsToCoarsestFrequency(rows, [
      { key: "annual" },
      { key: "monthly" },
    ]);

    expect(out.aligned).toBe(true);
    expect(out.targetFrequency).toBe("Y");
    expect(out.rows).toEqual([
      { period: "2022", x: "2022", annual: 10, monthly: 12 },
      { period: "2023", x: "2023", annual: 20, monthly: 24 },
    ]);
  });

  test("daily series aligns to monthly by taking the last day in month", () => {
    const rows = [
      { period: "2024-01", monthly: 100 },
      { period: "2024-02", monthly: 110 },
      { period: "2024-01-01", daily: 1 },
      { period: "2024-01-31", daily: 31 },
      { period: "2024-02-10", daily: 40 },
      { period: "2024-02-29", daily: 59 },
    ];

    const out = alignMultiSeriesRowsToCoarsestFrequency(rows, [
      { key: "monthly" },
      { key: "daily" },
    ]);

    expect(out.aligned).toBe(true);
    expect(out.targetFrequency).toBe("M");
    expect(out.rows).toEqual([
      { period: "2024-01", x: "2024-01", monthly: 100, daily: 31 },
      { period: "2024-02", x: "2024-02", monthly: 110, daily: 59 },
    ]);
  });

  test("weekly series aligns to annual by taking the latest week in year", () => {
    const rows = [
      { period: "2024", annual: 7 },
      { period: "2024-W01", weekly: 1 },
      { period: "2024-W52", weekly: 52 },
    ];

    const out = alignMultiSeriesRowsToCoarsestFrequency(rows, [
      { key: "annual" },
      { key: "weekly" },
    ]);

    expect(out.aligned).toBe(true);
    expect(out.targetFrequency).toBe("Y");
    expect(out.rows).toEqual([{ period: "2024", x: "2024", annual: 7, weekly: 52 }]);
  });

  test("bucketPeriodForFrequency supports quarter and half-year buckets", () => {
    expect(bucketPeriodForFrequency("2024-05-31", "Q")).toBe("2024-Q2");
    expect(bucketPeriodForFrequency("2024-11", "H")).toBe("2024-H2");
  });

  test("userTargetFrequency coarser than the natural intersection is honored", () => {
    const rows = [
      { period: "2024-01-01", a: 1 },
      { period: "2024-01-15", a: 2 },
      { period: "2024-02-01", a: 3 },
      { period: "2024-02-15", a: 4 },
      { period: "2024-01-01", b: 10 },
      { period: "2024-01-15", b: 20 },
      { period: "2024-02-01", b: 30 },
      { period: "2024-02-15", b: 40 },
    ];

    // Both series are native daily, so the natural intersection would be "D" (no
    // aggregation). If the user picks a coarser periodicity in the chart (e.g. "M"),
    // the comparison chart must honor it instead of silently ignoring it.
    const out = alignMultiSeriesRowsToCoarsestFrequency(rows, [{ key: "a" }, { key: "b" }], {
      userTargetFrequency: "M",
    });

    expect(out.aligned).toBe(true);
    expect(out.targetFrequency).toBe("M");
    expect(out.rows).toEqual([
      { period: "2024-01", x: "2024-01", a: 2, b: 20 },
      { period: "2024-02", x: "2024-02", a: 4, b: 40 },
    ]);
  });

  test("userTargetFrequency finer than the natural intersection falls back to the coarsest native frequency", () => {
    const rows = [
      { period: "2022", annual: 10 },
      { period: "2023", annual: 20 },
      { period: "2022-01", monthly: 1 },
      { period: "2022-12", monthly: 12 },
      { period: "2023-06", monthly: 16 },
      { period: "2023-12", monthly: 24 },
    ];

    // The annual series has no monthly (or daily) granularity, so requesting "D" can't
    // be satisfied - the alignment should keep using the natural intersection ("Y").
    const out = alignMultiSeriesRowsToCoarsestFrequency(
      rows,
      [{ key: "annual" }, { key: "monthly" }],
      { userTargetFrequency: "D" }
    );

    expect(out.aligned).toBe(true);
    expect(out.targetFrequency).toBe("Y");
    expect(out.rows).toEqual([
      { period: "2022", x: "2022", annual: 10, monthly: 12 },
      { period: "2023", x: "2023", annual: 20, monthly: 24 },
    ]);
  });
});

