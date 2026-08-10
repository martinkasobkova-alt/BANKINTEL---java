/**
 * Unified scale/domain helpers — wraps chartZoomHelpers (dashboard reference).
 * Sticky Y gutter and main plot MUST share the same domain spec.
 */

export {
  buildSafeNumericAxis,
  buildRechartsValueDomain,
  getYAxisDomainForChart,
  getBarChartValueAxisSpec,
  getLineChartValueAxisSpec,
  barChartValueAxisProps,
  chartBarPointValue,
  chartRowsWithZeroBaselineBars,
  coerceChartNumeric,
  collectOverlayYValues,
  getSafeYDomain,
  BAR_VALUE_AXIS,
} from "@/lib/chartZoomHelpers";

import {
  getBarChartValueAxisSpec,
  getLineChartValueAxisSpec,
  getYAxisDomainForChart,
} from "@/lib/chartZoomHelpers";

/**
 * Build sticky Y-axis gutter spec aligned with Recharts plot margins.
 * Used by AradView FrozenYAxisGutter and ChartRenderer scroll mode.
 */
export function buildStickyYAxisSpec({
  values = [],
  chartType = "line",
  plotMargin = {},
  axisGutterWidth = 52,
  tickFontSize = 10,
  tickCount = 5,
  overlayValues = [],
  allRowsForBar = null,
} = {}) {
  const isBar = String(chartType).toLowerCase() === "bar";
  const spec = isBar
    ? getBarChartValueAxisSpec(allRowsForBar || values.map((v) => ({ y: v })), overlayValues, tickCount)
    : getLineChartValueAxisSpec(
        Array.isArray(values[0]) ? values : values.map((y) => ({ y })),
        overlayValues,
        tickCount
      );

  const axis = spec?.axis || spec;
  return {
    min: axis.min,
    max: axis.max,
    ticks: axis.ticks,
    top: Number(plotMargin.top) || 0,
    bottom: Number(plotMargin.bottom) || 0,
    width: axisGutterWidth,
    fontSize: tickFontSize,
    domain: spec?.domain,
  };
}

/** Ensure bar chart and sticky gutter share identical domain. */
export function assertMatchingBarDomains(plotDomain, gutterSpec) {
  if (!plotDomain || !gutterSpec) return true;
  const [p0, p1] = plotDomain;
  const g0 = gutterSpec.min;
  const g1 = gutterSpec.max;
  return Math.abs(p0 - g0) < 1e-9 && Math.abs(p1 - g1) < 1e-9;
}

export function resolveValueAxisForChartType(chartType, rows, options = {}) {
  const kind = String(chartType || "line").toLowerCase();
  const values = (rows || []).flatMap((row) => {
    if (row?.y != null) return [row.y];
    return Object.keys(row || {})
      .filter((k) => k !== "x" && k !== "period" && k !== "period_label")
      .map((k) => row[k])
      .filter((v) => typeof v === "number");
  });
  return getYAxisDomainForChart(kind === "bar" ? "bar" : "line", values, options);
}
