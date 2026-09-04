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

  test("česká období z ČSÚ se berou jako časová řada", () => {
    const months = [
      "prosinec 2024", "leden 2025", "únor 2025", "březen 2025", "duben 2025",
      "květen 2025", "červen 2025", "červenec 2025", "srpen 2025", "září 2025",
      "říjen 2025", "listopad 2025", "prosinec 2025",
    ];
    const csu = createEmptyChartContract({
      chart_id: "csu",
      series: [{ id: "a", key: "a", label: "HICP", unit: "index 2015=100" }],
      data: months.map((period, i) =>
        normalizeChartPoint({ period, value_raw: 152 + i * 0.3, series_id: "a" })
      ),
      metadata: { data_mode: "time_series" },
    });
    const caps = resolveToolbarCapabilities(csu);
    expect(caps.transform).toContain(TOOLBAR_ACTIONS.INDEX_100);
    expect(caps.transform).toContain(TOOLBAR_ACTIONS.MOM);
    expect(caps.transform).toContain(TOOLBAR_ACTIONS.ROLLING_AVERAGE);
    // 13 období je přesně práh pro YoY
    expect(caps.yoyAllowed).toBe(true);
  });

  test("kategorie nejsou časová řada, i když obsahují letopočet", () => {
    const kraje = ["Praha 2020", "Brno", "Ostrava", "Plzeň", "Liberec"];
    const categorical = createEmptyChartContract({
      chart_id: "kraje",
      series: [{ id: "a", key: "a", label: "Počet", unit: "osob" }],
      data: kraje.map((period, i) =>
        normalizeChartPoint({ period, value_raw: 100 + i, series_id: "a" })
      ),
    });
    const caps = resolveToolbarCapabilities(categorical);
    expect(caps.transform).toEqual([TOOLBAR_ACTIONS.RAW]);
    expect(caps.yoyAllowed).toBe(false);
  });

  test("jediné období není časová řada", () => {
    const single = createEmptyChartContract({
      chart_id: "single-period",
      series: [{ id: "a", key: "a", label: "A" }],
      data: [normalizeChartPoint({ period: "2020-01", value_raw: 1, series_id: "a" })],
    });
    expect(resolveToolbarCapabilities(single).transform).toEqual([TOOLBAR_ACTIONS.RAW]);
  });

  test("latest mode transformace nenabízí", () => {
    const latest = createEmptyChartContract({
      chart_id: "latest",
      series: [{ id: "a", key: "a", label: "A" }],
      data: ["Česko", "Německo", "Polsko"].map((period, i) =>
        normalizeChartPoint({ period, value_raw: i, series_id: "a" })
      ),
      metadata: { data_mode: "latest" },
    });
    expect(resolveToolbarCapabilities(latest).transform).toEqual([TOOLBAR_ACTIONS.RAW]);
  });

  test("isToolbarActionAllowed respects capabilities", () => {
    const caps = resolveToolbarCapabilities(timeSeriesContract);
    expect(isToolbarActionAllowed(caps, TOOLBAR_ACTIONS.YOY)).toBe(true);
    expect(isToolbarActionAllowed(caps, "nonexistent_action")).toBe(false);
  });
});
