/**
 * Vizuální tokeny převzaté z AradView / dashboard widgetů.
 * Chart System musí vypadat stejně — ne jako separátní demo knihovna.
 */

import { buildChartTheme, CHART_THEME_DEFAULT } from "@/lib/chartTheme";

export const CHART_CARD_CLASS =
  "explore-dashboard-chart-card widget-panel-white widget-infographic-light flex flex-col overflow-hidden h-full";

export const CHART_HEADER_CLASS = "px-3 py-2.5 border-b border-border/50 shrink-0 bg-white/80";

export const CHART_TITLE_CLASS =
  "text-[11px] sm:text-xs font-extrabold leading-snug tracking-wide uppercase line-clamp-3 break-words text-[hsl(218_65%_28%)]";

export const CHART_SUBTITLE_CLASS = "text-[11px] text-slate-600 leading-snug mt-1";

export const CHART_TOOLBAR_CLASS =
  "px-3 py-2 border-b border-border/40 shrink-0 bg-white/70";

export const CHART_BODY_SLOT_CLASS =
  "chart-body-slot chart-system-plot relative flex flex-1 min-h-0 w-full min-w-0 pl-3 pr-4 pb-2 pt-3";

export const CHART_PLOT_CLASS = "w-full h-full min-h-0 explore-report-chart explore-dashboard-chart-plot";

export function getChartTheme(chartColor = null) {
  return buildChartTheme(chartColor) || CHART_THEME_DEFAULT;
}

export function getActiveControlStyle(chartTheme) {
  const theme = chartTheme || CHART_THEME_DEFAULT;
  return {
    background: theme.accentSoft || "hsl(202 90% 90%)",
    color: theme.accent || "hsl(202 90% 52%)",
    borderColor: "transparent",
  };
}

/** AradView-style pill / segmented control — aktivní stav. */
export function controlButtonClass(active, { disabled = false, compact = false } = {}) {
  const base = compact
    ? "h-6 px-2.5 text-[10px] rounded-md border transition-colors"
    : "h-7 px-3 text-[11px] rounded-full border transition-colors";
  if (disabled) {
    return `${base} border-border/40 text-slate-300 cursor-not-allowed opacity-60`;
  }
  if (active) {
    return `${base} chip-mint border-transparent font-semibold text-[hsl(202_90%_30%)]`;
  }
  return `${base} border-border/60 text-slate-600 hover:bg-slate-50/90 bg-white`;
}

export function exportButtonClass({ disabled = false } = {}) {
  const base =
    "inline-flex items-center justify-center gap-1.5 h-7 px-2.5 text-[11px] rounded-full border border-border/60 bg-white text-slate-600 hover:bg-[hsl(var(--primary-soft))] transition-colors";
  return disabled ? `${base} opacity-40 cursor-not-allowed` : base;
}

export function toolbarGroupLabelClass() {
  return "text-[9px] uppercase tracking-wider text-slate-400 mb-1.5";
}

/** Plot margins — sladěno s AradView renderChart (standard widget). */
export function dashboardPlotMargin({ multiSeries = false, compact = false, legendExtra = 0 } = {}) {
  const bottom = (multiSeries ? 32 : 16) + legendExtra;
  if (compact) {
    return { top: 14, right: 18, left: 8, bottom: multiSeries ? 28 + legendExtra : 14 + legendExtra };
  }
  return { top: 16, right: multiSeries ? 28 : 24, left: 10, bottom };
}

export function estimateLegendHeight(labels, { compact = false } = {}) {
  const safe = (labels || []).map((l) => String(l || "").trim()).filter(Boolean);
  if (!safe.length) return compact ? 36 : 44;
  const maxLen = Math.max(...safe.map((l) => l.length));
  const linesPerItem = maxLen > 36 ? 2 : 1;
  const perRow = safe.length <= 3 ? safe.length : Math.min(3, Math.ceil(safe.length / 2));
  const rows = Math.ceil(safe.length / Math.max(1, perRow));
  const lineHeight = compact ? 15 : 17;
  return Math.min(96, Math.max(compact ? 40 : 48, rows * linesPerItem * lineHeight + 16));
}
