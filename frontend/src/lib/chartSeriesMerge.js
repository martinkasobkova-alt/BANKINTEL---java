import { compareChartPeriods } from "@/lib/exploreChartCompare";
import { DASHBOARD_SERIES_COLORS } from "@/lib/dashboardChartStyle";

/**
 * Sloučí více časových řad do formátu pro ExploreReportChart (merged + series).
 */
export function mergeChartLinesForExplore(lines) {
  const filtered = (lines || [])
    .map((line, idx) => ({
      key: line.key || `y${idx}`,
      name: line.name,
      color: line.color || DASHBOARD_SERIES_COLORS[idx % DASHBOARD_SERIES_COLORS.length],
      axis: line.axis === "right" ? "right" : "left",
      rows: Array.isArray(line.rows) ? line.rows : [],
    }))
    .filter((line) => line.rows.length > 0);

  if (!filtered.length) return { merged: [], series: [] };

  const byX = new Map();
  for (const line of filtered) {
    for (const row of line.rows) {
      const x = String(row.x || "").trim();
      if (!x || !Number.isFinite(Number(row.y))) continue;
      if (!byX.has(x)) byX.set(x, { x });
      byX.get(x)[line.key] = Number(row.y);
    }
  }

  const merged = [...byX.values()].sort((a, b) => compareChartPeriods(a.x, b.x));
  return { merged, series: filtered };
}
