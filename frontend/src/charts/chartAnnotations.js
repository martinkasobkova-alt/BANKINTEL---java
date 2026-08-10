/**
 * Chart annotations — trend lines, highlight latest/max/min.
 */

import { linearTrendSlope, latestValue, max, min } from "./chartAnalytics";

export function computeTrendAnnotation(values) {
  const slope = linearTrendSlope(values);
  if (slope == null) return null;
  return { type: "trend", slope, label: `Trend: ${slope >= 0 ? "+" : ""}${slope.toFixed(4)}/období` };
}

export function computeLatestHighlight(values, periods) {
  const val = latestValue(values);
  if (val == null || !periods?.length) return null;
  return { type: "latest", period: periods[periods.length - 1], value: val };
}

export function computeExtremaHighlights(values, periods) {
  const nums = (values || []).filter((v) => Number.isFinite(v));
  if (!nums.length || !periods?.length) return [];
  const hi = max(nums);
  const lo = min(nums);
  const out = [];
  nums.forEach((v, idx) => {
    if (v === hi) out.push({ type: "max", period: periods[idx], value: v });
    if (v === lo) out.push({ type: "min", period: periods[idx], value: v });
  });
  return out;
}

export function enrichMergedWithTrends(merged, lines) {
  if (!merged?.length || !lines?.length) return merged;
  const out = merged.map((row) => ({ ...row }));
  for (const line of lines) {
    if (line.isTrend) continue;
    const trendKey = `${line.key}__trend`;
    const values = out.map((row) => row[line.key]);
    const nums = values.filter((v) => Number.isFinite(Number(v))).map(Number);
    if (nums.length < 2) continue;
    const slope = linearTrendSlope(nums);
    if (slope == null) continue;
    const meanY = nums.reduce((a, b) => a + b, 0) / nums.length;
    const meanX = (nums.length - 1) / 2;
    out.forEach((row, idx) => {
      const v = values[idx];
      if (v == null || !Number.isFinite(Number(v))) {
        row[trendKey] = null;
        return;
      }
      const pos = nums.findIndex((_, i) => i === idx);
      row[trendKey] = meanY + slope * (pos - meanX);
    });
  }
  return out;
}
