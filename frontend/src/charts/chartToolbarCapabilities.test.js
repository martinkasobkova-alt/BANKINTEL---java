import { resolveToolbarCapabilities, isToolbarActionAllowed, TOOLBAR_ACTIONS } from "./chartToolbarCapabilities";
import { createEmptyChartContract, normalizeChartPoint } from "./chartDataContract";

describe("chartToolbarCapabilities", () => {
  const timeSeriesContract = createEmptyChartContract({
    chart_id: "ts",
    series: [
      { id: "a", key: "a", label: "A", unit: "%" },
      { id: "b", key: "b", label: "B", unit: "index" },
    ],
    data: Array.from({ length: 14 }, (_, i) =>
      normalizeChartPoint({
        period: `2020-${String(i + 1).padStart(2, "0")}`,
        value_raw: 100 + i,
        series_id: i % 2 === 0 ? "a" : "b",
        series_label: i % 2 === 0 ? "A" : "B",
      })
    ),
    metadata: { data_mode: "time_series" },
  });

  test("dual-axis available only with different units", () => {
    const caps = resolveToolbarCapabilities(timeSeriesContract);
    expect(caps.dualAxisAllowed).toBe(true);
  });

  test("single unit series — no dual axis auto", () => {
    const singleUnit = createEmptyChartContract({
      chart_id: "single",
      series: [
        { id: "a", key: "a", label: "A", unit: "%" },
        { id: "b", key: "b", label: "B", unit: "%" },
      ],
      data: [
        normalizeChartPoint({ period: "2020", value_raw: 1, series_id: "a" }),
        normalizeChartPoint({ period: "2020", value_raw: 2, series_id: "b" }),
      ],
    });
    const caps = resolveToolbarCapabilities(singleUnit);
    expect(caps.dualAxisAllowed).toBe(false);
  });

  test("scatter/correlation only with two numeric series", () => {
    const caps = resolveToolbarCapabilities(timeSeriesContract);
    expect(caps.scatterAllowed).toBe(true);
    expect(caps.correlationAllowed).toBe(true);
    expect(caps.transform).toContain(TOOLBAR_ACTIONS.SPREAD);
  });

  test("YoY only with sufficient history", () => {
    const short = createEmptyChartContract({
      chart_id: "short",
      series: [{ id: "a", key: "a", label: "A" }],
      data: [1, 2, 3].map((v, i) =>
        normalizeChartPoint({ period: `2020-Q${i + 1}`, value_raw: v, series_id: "a" })
      ),
    });
    const capsShort = resolveToolbarCapabilities(short);
    expect(capsShort.yoyAllowed).toBe(false);
    expect(capsShort.transform).not.toContain(TOOLBAR_ACTIONS.YOY);

    const capsLong = resolveToolbarCapabilities(timeSeriesContract);
    expect(capsLong.yoyAllowed).toBe(true);
  });

  test("isToolbarActionAllowed respects capabilities", () => {
    const caps = resolveToolbarCapabilities(timeSeriesContract);
    expect(isToolbarActionAllowed(caps, TOOLBAR_ACTIONS.YOY)).toBe(true);
    expect(isToolbarActionAllowed(caps, "nonexistent_action")).toBe(false);
  });
});
