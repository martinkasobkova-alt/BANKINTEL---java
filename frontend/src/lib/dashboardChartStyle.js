/**
 * Sdílený vizuální jazyk grafů — stejný jako widgety na dashboardu (AradView).
 */

import { CHART_THEME_DEFAULT } from "@/lib/chartTheme";

export const DASHBOARD_CHART_COLORS = Object.freeze({
  primary: "hsl(202 90% 52%)",
  primarySoft: "hsl(208 75% 48%)",
  grid: CHART_THEME_DEFAULT.grid,
  axis: CHART_THEME_DEFAULT.axis,
});

/** Paleta pro více řad — shodná s AradView / PIE_COLORS. */
export const DASHBOARD_SERIES_COLORS = [
  "hsl(202 90% 52%)",
  "hsl(218 65% 28%)",
  "hsl(208 75% 48%)",
  "hsl(205 65% 78%)",
  "hsl(225 55% 65%)",
  "hsl(188 65% 52%)",
  "hsl(240 55% 70%)",
  "hsl(344 70% 70%)",
  "hsl(36 90% 68%)",
  "hsl(18 75% 62%)",
  "hsl(165 60% 48%)",
  "hsl(275 45% 68%)",
];

export function dashboardChartMargin({ multiSeries = false, compact = false } = {}) {
  return {
    top: compact ? 12 : 16,
    right: multiSeries ? (compact ? 18 : 24) : compact ? 14 : 20,
    left: compact ? 4 : 8,
    bottom: multiSeries ? (compact ? 28 : 32) : compact ? 14 : 16,
  };
}

export function dashboardAxisTickStyle(chartTheme, { compact = false } = {}) {
  const fill = chartTheme?.axis || DASHBOARD_CHART_COLORS.axis;
  return {
    fontSize: compact ? 9 : 10,
    fill,
    fontFamily: "JetBrains Mono, ui-monospace, monospace",
  };
}

export function dashboardGridStroke(chartTheme) {
  return chartTheme?.grid || DASHBOARD_CHART_COLORS.grid;
}
