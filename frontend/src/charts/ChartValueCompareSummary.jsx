import React from "react";
import { BarChart2, TrendingDown, TrendingUp } from "lucide-react";
import ChartSummaryTooltip from "@/charts/ChartSummaryTooltip";
import { fmtCompact } from "@/lib/format";
import { resolveValueCompareSummaryDensity } from "@/charts/chartValueCompareStats";

function ellipsizeLabel(value, max = 18) {
  const s = String(value ?? "").trim();
  if (!s || s.length <= max) return s;
  return `${s.slice(0, Math.max(1, max - 1)).trimEnd()}…`;
}

function splitStatValue(value, unit) {
  const number = fmtCompact(value);
  const u = String(unit || "").trim();
  if (!u) return { number, unit: "" };
  if (u === "%" || u.toLowerCase() === "percentage" || u.toLowerCase() === "procent") {
    return { number, unit: "%" };
  }
  return { number, unit: u };
}

function SummaryCard({ kind, title, nameLabel, fullNameLabel = "", value, unit, density }) {
  const isMicro = density === "mini";
  const Icon = kind === "max" ? TrendingUp : kind === "min" ? TrendingDown : BarChart2;
  const iconWrap = isMicro ? "h-3 w-3" : density === "expanded" ? "h-6 w-6" : "h-5 w-5";
  const iconSize = isMicro ? "h-2 w-2" : density === "expanded" ? "h-3.5 w-3.5" : "h-3 w-3";
  const iconTone =
    kind === "max"
      ? "bg-emerald-50 text-emerald-600 border-emerald-100"
      : kind === "min"
        ? "bg-rose-50 text-rose-600 border-rose-100"
        : "bg-sky-50 text-sky-700 border-sky-100";

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
  const valueLineClass =
    "block w-full min-w-0 truncate whitespace-nowrap font-mono font-semibold leading-none";
  const titleClass = isMicro
    ? "text-[6px]"
    : density === "compact"
      ? "text-[8px]"
      : density === "expanded"
        ? "text-[11px]"
        : "text-[9px]";
  const valueClass = isMicro
    ? "text-[8px] leading-none"
    : density === "compact"
      ? "text-[10px]"
      : density === "expanded"
        ? "text-sm"
        : "text-[11px]";

  const { number, unit: unitText } = splitStatValue(value, unit);
  const tooltipValue = `${number}${unitText ? ` ${unitText}` : ""}`;
  const tooltipText = `${title}: ${fullNameLabel ? `${fullNameLabel} ` : ""}${tooltipValue}`;
  const displayValue = [nameLabel, number, unitText].filter(Boolean).join("\u00a0");

  return (
    <ChartSummaryTooltip text={tooltipText}>
      <div className={`${shellClass} ${padClass}`}>
        <div className="flex min-w-0 shrink-0 items-center gap-0.5 overflow-hidden">
          <span
            className={`inline-flex shrink-0 items-center justify-center rounded border ${iconWrap} ${iconTone}`}
            aria-hidden
          >
            <Icon className={iconSize} />
          </span>
          <span className={`min-w-0 truncate font-medium uppercase tracking-[0.06em] text-slate-500 ${titleClass}`}>
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
          <span
            className={`${valueLineClass} text-slate-900 ${valueClass}`}
            title={displayValue}
          >
            {displayValue}
          </span>
        </div>
      </div>
    </ChartSummaryTooltip>
  );
}

/**
 * Nejvyšší / nejnižší / medián pro porovnávací grafy (režim latest).
 */
export default function ChartValueCompareSummary({
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
  const labelMax =
    density === "mini" ? 8 : density === "compact" ? 10 : density === "expanded" ? 32 : 18;
  const medianValue = splitStatValue(stats.median, unit);
  const formatParts = ({ number, unit: unitText }) => `${number}${unitText ? ` ${unitText}` : ""}`;

  if (kpiMode === "mini") {
    const text = `Medián: ${formatParts(medianValue)}`;
    return (
      <ChartSummaryTooltip text={text}>
        <div
          className="mb-0.5 flex min-w-0 items-center justify-between gap-2 rounded-lg border border-border/50 bg-white/90 px-2 py-1 text-[10px] shadow-sm"
          data-testid="chart-value-compare-summary"
          aria-label="Souhrn mediánu"
        >
          <span className="min-w-0 truncate font-medium uppercase tracking-[0.08em] text-slate-500">Medián</span>
          <span className="shrink-0 whitespace-nowrap font-mono font-semibold text-slate-900">{formatParts(medianValue)}</span>
        </div>
      </ChartSummaryTooltip>
    );
  }

  if (kpiMode === "compact" || kpiMode === "full") {
    return (
      <div
        className={`shrink-0 w-full min-w-0 ${density === "mini" ? "mb-0.5" : density === "expanded" ? "mb-2" : "mb-1"}`}
        data-testid="chart-value-compare-summary"
        data-kpi-layout={kpiMode}
        aria-label="Souhrn nejvyšší, nejnižší a mediánu"
      >
        <div className={`grid w-full grid-cols-3 ${density === "mini" ? "gap-0.5" : "gap-1.5"}`}>
          <SummaryCard
            kind="max"
            title="Nejvyšší"
            nameLabel={ellipsizeLabel(stats.max.label, labelMax)}
            fullNameLabel={stats.max.label}
            value={stats.max.value}
            unit={unit}
            density={density}
          />
          <SummaryCard
            kind="min"
            title="Nejnižší"
            nameLabel={ellipsizeLabel(stats.min.label, labelMax)}
            fullNameLabel={stats.min.label}
            value={stats.min.value}
            unit={unit}
            density={density}
          />
          <SummaryCard
            kind="median"
            title="Medián"
            nameLabel={null}
            value={stats.median}
            unit={unit}
            density={density}
          />
        </div>
      </div>
    );
  }

  return null;
}
