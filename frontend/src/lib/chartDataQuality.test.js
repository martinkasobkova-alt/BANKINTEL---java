import { describe, expect, it } from "vitest";
import {
  enrichChartExplainMeta,
  extractLastChartObservation,
  isChartPeriodStale,
} from "@/lib/chartDataQuality";

describe("chartDataQuality", () => {
  it("detects stale last observation", () => {
    const rows = [{ date: "2015", value: 1 }, { date: "2017", value: 0.99 }];
    const last = extractLastChartObservation(rows, { timeField: "date", valueField: "value" });
    expect(last?.period).toBe("2017");
    expect(isChartPeriodStale(last.period, 2024)).toBe(true);
  });

  it("enriches explain meta with chart context", () => {
    const meta = enrichChartExplainMeta(
      { title: "Test", set_id: "X" },
      [{ period: "2025-Q1", obs_value: 3 }],
      { timeField: "period", valueField: "obs_value" },
    );
    expect(meta.last_period).toBe("2025-Q1");
    expect(meta.data_stale).toBe(false);
  });
});
