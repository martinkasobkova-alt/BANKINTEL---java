import React from "react";
import { Activity, Calendar, TrendingDown, TrendingUp } from "lucide-react";
import ChartSummaryTooltip from "@/charts/ChartSummaryTooltip";
import { fmtPeriodLabel } from "@/lib/format";
import { resolveValueCompareSummaryDensity } from "@/charts/chartValueCompareStats";
import {
  formatAbsoluteChange,
  formatRelativeChange,
  formatSeriesStatValue,
  isPercentUnit,
} from "@/charts/chartTimeSeriesStats";

function changeTone(delta) {
  if (delta == null || !Number.isFinite(delta) || delta === 0) return "text-slate-600";
  return delta > 0 ? "text-emerald-600" : "text-rose-600";
}

function SummaryCard({ title, children, density, icon: Icon, iconTone, tooltipText }) {
  const isMicro = density === "mini";
  const labelClass = isMicro
    ? "text-[6px]"
    : density === "compact"
      ? "text-[8px]"
      : density === "expanded"
        ? "text-[11px]"
        : "text-[9px]";
  const padClass = isMicro
    ? "px-1 py-0.5"
    : density === "compact"
      ? "px-2 py-1.5"
      : density === "expanded"
        ? "px-3 py-2.5"
        : "px-2.5 py-2";
  const shellClass = isMicro
    ? "flex min-h-[1.15rem] min-w-0 items-center gap-1 overflow-hidden rounded border border-border/45 bg-white/95 shadow-sm"
    : "flex min-h-[2.75rem] min-w-0 flex-col justify-between gap-1 overflow-hidden rounded-lg border border-border/50 bg-white/95 shadow-sm";
  const iconWrap = isMicro ? "h-3 w-3" : density === "expanded" ? "h-6 w-6" : "h-5 w-5";
  const iconSize = isMicro ? "h-2 w-2" : density === "expanded" ? "h-3.5 w-3.5" : "h-3 w-3";

  return (
    <ChartSummaryTooltip text={tooltipText}>
      <div className={`${shellClass} ${padClass}`}>
        <div className="flex min-w-0 shrink-0 items-center gap-0.5 overflow-hidden">
          {Icon ? (
            <span
              className={`inline-flex shrink-0 items-center justify-center rounded border ${iconWrap} ${iconTone}`}
              aria-hidden
            >
              <Icon className={iconSize} />
            </span>
          ) : null}
          <span
            className={`min-w-0 truncate font-medium uppercase tracking-[0.06em] text-slate-500 ${labelClass}`}
          >
            {title}
          </span>
        </div>
        <div
          className={
            isMicro
              ? "flex min-w-0 flex-1 basis-0 flex-nowrap items-center justify-end overflow-hidden text-right leading-none"
              : "flex min-w-0 w-full flex-nowrap items-baseline justify-end overflow-hidden text-right leading-none"
          }
        >
          {children}
        </div>
      </div>
    </ChartSummaryTooltip>
  );
}

function splitLastValue(lastValueText, unit) {
  const text = String(lastValueText || "").trim();
  if (!text) return { parts: [text] };
  if (isPercentUnit(unit)) {
    const m = text.match(/^(.+?)\s*%$/);
    if (m) return { parts: [m[1].trim(), "%"] };
  }
  const u = String(unit || "").trim();
  if (u && text.endsWith(` ${u}`)) {
    return { parts: [text.slice(0, -(u.length + 1)).trim(), u] };
  }
  return { parts: [text] };
}

/**
 * Poslední / změna / období pro časové řady — 3 samostatné karty s ikonami.
 */
export default function ChartTimeSeriesSummary({
  stats,
  unit = "",
  miniChartMode = false,
  veryNarrowWidget = false,
  chartCompact = false,
  fsExpand = false,
  kpiMode = "full",
}) {
  if (!stats) return null;
  if (kpiMode === "hidden") return null;

  const density = resolveValueCompareSummaryDensity({
    miniChartMode,
    veryNarrowWidget,
    chartCompact,
    fsExpand,
  });

  const lastValueText = formatSeriesStatValue(stats.lastValue, unit);
  const lastValueParts = splitLastValue(lastValueText, unit).parts;
  const absChangeText = stats.hasChange ? formatAbsoluteChange(stats.delta, unit) : null;
  const relChangeText = stats.hasChange
    ? formatRelativeChange(stats.lastValue, stats.prevValue)
    : null;
  const periodText = fmtPeriodLabel(stats.lastPeriod);
  const deltaTone = changeTone(stats.delta);
  const ChangeIcon =
    stats.delta == null || stats.delta === 0
      ? null
      : stats.delta > 0
        ? TrendingUp
        : TrendingDown;
  const changeIconTone =
    stats.delta == null || stats.delta === 0
      ? "bg-slate-50 text-slate-500 border-slate-100"
      : stats.delta > 0
        ? "bg-emerald-50 text-emerald-600 border-emerald-100"
        : "bg-rose-50 text-rose-600 border-rose-100";

  const valueClass =
    density === "mini"
      ? "text-[8px] leading-none"
      : density === "compact"
        ? "text-[10px]"
        : density === "expanded"
          ? "text-base"
          : "text-sm";

  const valueSpan = (text, className, tone = "") => (
    <span
      className={`block w-full min-w-0 truncate whitespace-nowrap font-mono font-semibold ${tone} ${className}`}
      title={text}
    >
      {text}
    </span>
  );

  if (kpiMode === "mini") {
    const tooltipText = `Poslední: ${lastValueText}`;
    return (
      <ChartSummaryTooltip text={tooltipText}>
        <div
          className="mb-0.5 flex min-w-0 items-center justify-between gap-2 rounded-lg border border-border/50 bg-white/95 px-2 py-1 text-[10px] shadow-sm"
          data-testid="chart-time-series-summary"
          aria-label="Souhrn poslední hodnoty"
        >
          <span className="inline-flex items-center gap-1 min-w-0 truncate font-medium uppercase tracking-[0.08em] text-slate-500">
            <Activity className="h-3 w-3 shrink-0 text-indigo-500" aria-hidden />
            Poslední
          </span>
          <span className="min-w-0 flex-1 truncate whitespace-nowrap text-right font-mono font-semibold text-slate-900">
            {lastValueText}
          </span>
        </div>
      </ChartSummaryTooltip>
    );
  }

  return (
    <div
      className={`shrink-0 w-full min-w-0 grid grid-cols-3 ${
        density === "mini" ? "mb-0.5 gap-0.5" : density === "expanded" ? "mb-2 gap-1.5" : "mb-1 gap-1.5"
      }`}
      data-testid="chart-time-series-summary"
      data-kpi-layout={kpiMode}
      aria-label="Souhrn poslední, změny a období"
    >
      <SummaryCard
        title="Poslední"
        density={density}
        icon={Activity}
        iconTone="bg-indigo-50 text-indigo-600 border-indigo-100"
        tooltipText={`Poslední: ${lastValueText}`}
      >
        {valueSpan(lastValueParts.join("\u00a0"), valueClass, "text-slate-900")}
      </SummaryCard>

      <SummaryCard
        title="Změna"
        density={density}
        icon={ChangeIcon || TrendingUp}
        iconTone={changeIconTone}
        tooltipText={
          stats.hasChange
            ? `Změna vs. předchozí období: ${[absChangeText, relChangeText].filter(Boolean).join(" · ")}`
            : "Změna vs. předchozí období: —"
        }
      >
        {stats.hasChange ? (
          valueSpan(
            [absChangeText, relChangeText].filter(Boolean).join("\u00a0"),
            valueClass,
            deltaTone,
          )
        ) : (
          valueSpan("—", valueClass, "text-slate-400")
        )}
      </SummaryCard>

      <SummaryCard
        title="Období"
        density={density}
        icon={Calendar}
        iconTone="bg-sky-50 text-sky-700 border-sky-100"
        tooltipText={`Poslední období: ${periodText}`}
      >
        {valueSpan(periodText, valueClass, "text-slate-800")}
      </SummaryCard>
    </div>
  );
}
