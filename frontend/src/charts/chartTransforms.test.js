import { applyChartTransform, applyTransformToContract } from "./chartTransforms";
import { createEmptyChartContract, normalizeChartPoint } from "./chartDataContract";

describe("chartTransforms", () => {
  const sampleRows = [
    { x: "2020-Q1", y: 100 },
    { x: "2020-Q2", y: 110 },
    { x: "2020-Q3", y: 105 },
    { x: "2020-Q4", y: 120 },
  ];

  test("raw transform passes through", () => {
    const result = applyChartTransform("raw", sampleRows);
    expect(result.ok).toBe(true);
    expect(result.transformed_series[0].length).toBe(4);
  });

  test("YoY transform", () => {
    const longRows = Array.from({ length: 14 }, (_, i) => ({
      x: `2020-${String(i + 1).padStart(2, "0")}`,
      y: 100 + i,
    }));
    const result = applyChartTransform("yoy", longRows, { freq: "monthly" });
    expect(result.ok).toBe(true);
    expect(result.transformed_series[0].length).toBeGreaterThan(0);
  });

  test("MoM/QoQ via period_change", () => {
    const result = applyChartTransform("mom", sampleRows);
    expect(result.ok).toBe(true);
    expect(result.transformed_series[0][0].y).toBeCloseTo(10, 5);
  });

  test("applyTransformToContract updates data", () => {
    const contract = createEmptyChartContract({
      chart_id: "test",
      series: [{ id: "s1", key: "s1", label: "Test" }],
      data: sampleRows.map((r) =>
        normalizeChartPoint({ period: r.x, value_raw: r.y, series_id: "s1", series_label: "Test" })
      ),
    });
    const updated = applyTransformToContract(contract, "mom");
    expect(updated.data.length).toBe(3);
    expect(updated.transformations.length).toBe(1);
  });
});
