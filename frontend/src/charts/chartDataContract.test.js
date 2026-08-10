import {
  isLatestDataMode,
  contractToSingleSeriesRows,
  contractToRechartsWide,
  createEmptyChartContract,
  normalizeChartPoint,
} from "./chartDataContract";
import { CHART_DATA_MODES } from "./chartTypes";

describe("chartDataContract latest mode", () => {
  const latestContract = createEmptyChartContract({
    chart_id: "test_latest",
    metadata: { data_mode: CHART_DATA_MODES.LATEST, latest_mode: true },
    series: [{ id: "v", key: "v", label: "Value" }],
    data: [
      normalizeChartPoint({ period: "CZ", geo_label: "Česko", series_id: "v", value_raw: 7.2 }),
      normalizeChartPoint({ period: "DE", geo_label: "Německo", series_id: "v", value_raw: 5.6 }),
    ],
  });

  test("isLatestDataMode detects metadata", () => {
    expect(isLatestDataMode(latestContract)).toBe(true);
    expect(isLatestDataMode({ metadata: {} })).toBe(false);
  });

  test("contractToSingleSeriesRows maps categories to x/y", () => {
    const rows = contractToSingleSeriesRows(latestContract);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toMatchObject({ x: "Česko", y: 7.2 });
  });

  test("contractToRechartsWide for latest returns x/y rows", () => {
    const wide = contractToRechartsWide(latestContract);
    expect(wide[0]).toMatchObject({ x: "Česko", y: 7.2 });
  });
});
