import { CHART_SIZE_VARIANTS } from "./chartTypes";

/**
 * Unified chart dimension presets — single source for container/plot/margins.
 * Dashboard widgets use `standard`; Manager Explorer cards use `standard`/`analytical`.
 */

const BASE = Object.freeze({
  axisGutterWidth: 52,
  axisGutterWidthCompact: 46,
  legendHeight: 40,
  legendHeightCompact: 36,
  tableMaxRows: 12,
});

export const CHART_SIZE_PRESETS = Object.freeze({
  [CHART_SIZE_VARIANTS.COMPACT]: {
    containerHeight: 160,
    plotMinHeight: 120,
    margin: { top: 12, right: 14, left: 4, bottom: 14 },
    marginMultiSeries: { top: 12, right: 18, left: 4, bottom: 28 },
    axisGutterWidth: BASE.axisGutterWidthCompact,
    tickFontSize: 9,
    legendHeight: BASE.legendHeightCompact,
    showLegendDefault: false,
    showTableDefault: false,
    tooltipCompact: true,
    stickyYAxis: false,
    minWidth: 200,
  },
  [CHART_SIZE_VARIANTS.STANDARD]: {
    containerHeight: 280,
    plotMinHeight: 240,
    margin: { top: 16, right: 24, left: 10, bottom: 18 },
    marginMultiSeries: { top: 16, right: 28, left: 10, bottom: 36 },
    axisGutterWidth: BASE.axisGutterWidth,
    tickFontSize: 10,
    legendHeight: BASE.legendHeight,
    showLegendDefault: true,
    showTableDefault: false,
    tooltipCompact: false,
    stickyYAxis: true,
    minWidth: 280,
  },
  [CHART_SIZE_VARIANTS.ANALYTICAL]: {
    containerHeight: 420,
    plotMinHeight: 360,
    margin: { top: 20, right: 28, left: 12, bottom: 22 },
    marginMultiSeries: { top: 20, right: 32, left: 12, bottom: 44 },
    axisGutterWidth: 56,
    tickFontSize: 11,
    legendHeight: 48,
    showLegendDefault: true,
    showTableDefault: true,
    tooltipCompact: false,
    stickyYAxis: true,
    minWidth: 360,
  },
  [CHART_SIZE_VARIANTS.FULLSCREEN]: {
    containerHeight: "100%",
    plotMinHeight: 400,
    margin: { top: 24, right: 32, left: 16, bottom: 24 },
    marginMultiSeries: { top: 24, right: 36, left: 16, bottom: 48 },
    axisGutterWidth: 60,
    tickFontSize: 11,
    legendHeight: 52,
    showLegendDefault: true,
    showTableDefault: true,
    tooltipCompact: false,
    stickyYAxis: true,
    minWidth: 320,
  },
});

export function resolveChartSize(size = CHART_SIZE_VARIANTS.STANDARD, overrides = {}) {
  const preset = CHART_SIZE_PRESETS[size] || CHART_SIZE_PRESETS[CHART_SIZE_VARIANTS.STANDARD];
  return { ...preset, size, ...overrides };
}

export function resolveChartMargin(sizeSpec, { multiSeries = false } = {}) {
  return multiSeries ? sizeSpec.marginMultiSeries : sizeSpec.margin;
}

/** Responsive height for notebook/mobile — never below plotMinHeight. */
export function resolveResponsiveHeight(sizeSpec, viewportWidth) {
  const base = sizeSpec.containerHeight;
  if (base === "100%") return base;
  if (!Number.isFinite(viewportWidth) || viewportWidth >= 768) return base;
  if (viewportWidth < 480) return Math.max(sizeSpec.plotMinHeight, Math.round(base * 0.85));
  return Math.max(sizeSpec.plotMinHeight, Math.round(base * 0.92));
}
