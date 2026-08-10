import React, { useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import {
  Activity,
  AlertTriangle,
  ArrowDownRight,
  ArrowUpRight,
  BarChart3,
  FileText,
  Gauge,
  Lightbulb,
  Loader2,
  RefreshCw,
  ShieldAlert,
  Sparkles,
  X,
} from "lucide-react";
import api from "@/lib/api";
import { fmtCompact, fmtNumber, fmtPeriod } from "@/lib/format";
import ExportMenu from "@/components/widgets/ExportMenu";

function formatAnalyticsValue(item) {
  if (item?.display_value) return String(item.display_value);
  const rawUnit = String(item?.unit || "").trim();
  const unit = /^(percentage|percent|pct)$/i.test(rawUnit) ? "%" : rawUnit;
  const value = item?.value;
  if (value == null || Number.isNaN(Number(value))) return "—";
  if (unit === "%" || unit === "percentil") {
    return `${fmtNumber(value, { digits: 1 })} ${unit === "percentil" ? "percentil" : "%"}`;
  }
  const n = Number(value);
  if (Math.abs(n) >= 1e6) return fmtCompact(n);
  return fmtNumber(n, { digits: Math.abs(n) >= 100 ? 1 : 2 });
}

function normalizeDisplayValue(item) {
  const formatted = formatAnalyticsValue(item);
  return formatted
    .replace(/\s+(Percentage|Percent|pct)$/i, " %")
    .replace(/\s+percentil$/i, ". percentil");
}

function humanizeAnalyticsText(value) {
  return String(value || "")
    .replace(/\b(Percentage|Percent|pct)\b/gi, "%")
    .replace(/\bperiod\b/gi, "období")
    .replace(/mean-reversion/gi, "návrat k dlouhodobému trendu")
    .replace(/\bje výrazné\s+—/g, "je výrazná —")
    .replace(/\bje mírné\s+—/g, "je mírná —")
    .replace(/(-?\d+)\.(\d+)(?=\s*%)/g, "$1,$2");
}

function normalizedUnit(value) {
  const unit = String(value || "").trim();
  return /^(percentage|percent|pct)$/i.test(unit) ? "%" : unit;
}

function formatMetric(value, unit = "", { digits = 2, signed = false } = {}) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "—";
  const sign = signed && number > 0 ? "+" : "";
  const suffix = normalizedUnit(unit);
  return `${sign}${fmtNumber(number, { digits })}${suffix ? ` ${suffix}` : ""}`;
}

function formatDifference(value, unit = "", options = {}) {
  const differenceUnit = normalizedUnit(unit) === "%" ? "p. b." : unit;
  return formatMetric(value, differenceUnit, options);
}

function periodUnitLabel(frequency) {
  switch (String(frequency || "").toUpperCase()) {
    case "A":
    case "Y":
      return "rok";
    case "Q":
      return "čtvrtletí";
    case "W":
      return "týden";
    case "D":
      return "den";
    default:
      return "období";
  }
}

function dynamicMetrics(data) {
  const history = data?.metrics?.history || {};
  const volatility = Number(data?.metrics?.volatility?.value);
  const unit = normalizedUnit(data?.target_resolution?.unit);
  const frequency = periodUnitLabel(data?.target_resolution?.frequency);
  const shortTrend = Number(data?.trend?.short_term_trend_slope);
  const longTrend = Number(data?.trend?.long_term_trend_slope);
  const trendUnit = unit === "%" ? "p. b." : unit;
  const varianceUnit = unit ? `${unit}²` : "";
  return [
    {
      label: "Historický průměr",
      value: formatMetric(history.mean, unit),
      hint: "Průměr všech dostupných hodnot",
    },
    {
      label: "Historický medián",
      value: formatMetric(history.median, unit),
      hint: "Polovina hodnot byla nižší a polovina vyšší",
    },
    {
      label: "Rozptyl",
      value: formatMetric(history.variance, varianceUnit),
      hint: "Míra rozptýlení hodnot kolem průměru",
    },
    {
      label: "Volatilita",
      value: Number.isFinite(volatility) ? formatMetric(volatility * 100, "%", { digits: 1 }) : "—",
      hint: `Kolísání procentních změn mezi sousedními obdobími (za ${frequency})`,
    },
    {
      label: "Krátkodobý trend",
      value: formatMetric(shortTrend, trendUnit, { signed: true }),
      hint: `Průměrný posun za ${frequency} v poslední části řady`,
      direction: Number.isFinite(shortTrend) ? Math.sign(shortTrend) : 0,
    },
    {
      label: "Dlouhodobý trend",
      value: formatMetric(longTrend, trendUnit, { signed: true }),
      hint: `Průměrný posun za ${frequency} v celé historii`,
      direction: Number.isFinite(longTrend) ? Math.sign(longTrend) : 0,
    },
  ].filter((metric) => metric.value !== "—");
}

function correlationMeaning(value) {
  const correlation = Number(value);
  if (!Number.isFinite(correlation)) return "Vztah nelze spolehlivě určit.";
  const strength = Math.abs(correlation);
  if (strength >= 0.8) return correlation > 0 ? "Řady se pohybují velmi podobně." : "Řady se obvykle pohybují opačným směrem.";
  if (strength >= 0.5) return correlation > 0 ? "Řady mají podobný vývoj." : "Řady mají spíše opačný vývoj.";
  if (strength >= 0.25) return "Souvislost vývoje je mírná.";
  return "Vývoj řad spolu souvisí jen slabě.";
}

function comparisonNoun(dimension, count) {
  const geo = ["geo", "country", "ref_area"].includes(String(dimension || "").toLowerCase());
  if (!geo) return `${count} řad`;
  return `${count} zemí`;
}

function isGeoComparisonDimension(dimension) {
  return ["geo", "country", "ref_area"].includes(String(dimension || "").toLowerCase());
}

function GroupComparisonSection({ comparison, unit }) {
  if (comparison?.status !== "ok") return null;
  const rankingBlock = comparison?.ranking || {};
  const ranking = Array.isArray(rankingBlock.ranking) ? rankingBlock.ranking : [];
  if (ranking.length < 2) return null;
  const period = rankingBlock.period;
  const previousPeriod = rankingBlock.previous_period;
  const values = ranking.map((item) => Number(item.value)).filter(Number.isFinite);
  const min = values.length ? Math.min(...values) : 0;
  const max = values.length ? Math.max(...values) : 0;
  const range = max - min;
  const leader = rankingBlock.leader || ranking[0];
  const laggard = rankingBlock.laggard || ranking[ranking.length - 1];
  const spread = Number(leader?.value) - Number(laggard?.value);
  const pairwise = Array.isArray(comparison.pairwise) ? comparison.pairwise : [];
  const members = Array.isArray(comparison.members) ? comparison.members : [];
  const memberByLabel = new Map(members.map((member) => [member.label, member]));
  const orderedMembers = ranking.map((item) => memberByLabel.get(item.label)).filter(Boolean);

  return (
    <section className="rounded-lg border border-emerald-200 bg-emerald-50/30 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <BarChart3 className="h-4 w-4 text-emerald-700" />
          <h3 className="text-[11px] font-semibold uppercase text-emerald-950">
            Srovnání {comparisonNoun(comparison.dimension, ranking.length)}
          </h3>
        </div>
        {period ? <span className="rounded-md bg-white px-2 py-1 text-[10px] font-medium text-emerald-800">{fmtPeriod(period)}</span> : null}
      </div>

      <div className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
        <div className="rounded-lg border border-emerald-100 bg-white px-3 py-2.5">
          <div className="text-[10px] uppercase text-muted-foreground">Nejvyšší hodnota</div>
          <div className="mt-0.5 text-sm font-semibold">{leader?.label}</div>
          <div className="text-lg font-semibold tabular-nums text-emerald-700">{formatMetric(leader?.value, unit)}</div>
        </div>
        <div className="rounded-lg border border-emerald-100 bg-white px-3 py-2.5">
          <div className="text-[10px] uppercase text-muted-foreground">Rozdíl mezi nejvyšší a nejnižší</div>
          <div className="mt-0.5 text-sm font-semibold">{leader?.label} vs. {laggard?.label}</div>
          <div className="text-lg font-semibold tabular-nums text-sky-700">{formatDifference(spread, unit)}</div>
        </div>
      </div>

      <div className="mt-3 space-y-2">
        {ranking.map((item) => {
          const value = Number(item.value);
          const previousValue = Number(item.previous_value);
          const periodChange = Number.isFinite(value) && Number.isFinite(previousValue) ? value - previousValue : null;
          const width = range > 0 && Number.isFinite(value) ? 18 + ((value - min) / range) * 82 : 100;
          return (
            <div key={item.label} className="grid grid-cols-[1.5rem_minmax(0,1fr)_auto] items-center gap-2">
              <span className="text-center text-[11px] font-semibold text-muted-foreground">{item.rank}.</span>
              <div className="min-w-0">
                <div className="truncate text-[12px] font-medium">{item.label}</div>
                {periodChange != null ? (
                  <div className="truncate text-[10px] text-muted-foreground">
                    proti {fmtPeriod(previousPeriod)}: {formatDifference(periodChange, unit, { signed: true })}
                  </div>
                ) : null}
                <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-emerald-100">
                  <div className="h-full rounded-full bg-emerald-500" style={{ width: `${width}%` }} />
                </div>
              </div>
              <span className="text-[12px] font-semibold tabular-nums">{formatMetric(item.value, unit)}</span>
            </div>
          );
        })}
      </div>

      {orderedMembers.length ? (
        <div className="mt-4 border-t border-emerald-200/70 pt-3">
          <div className="mb-1 text-[10px] font-semibold uppercase text-emerald-950/80">Statistiky jednotlivých zemí</div>
          <p className="mb-2 text-[10px] leading-snug text-muted-foreground">
            Každý panel počítá metriky pouze z historie uvedené země.
          </p>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {orderedMembers.map((member) => {
              const metrics = member.metrics || {};
              const history = metrics.history || {};
              const volatility = Number(metrics.volatility?.value);
              const shortTrend = Number(member.trend?.short_term_trend_slope);
              return (
                <div key={member.value || member.label} className="rounded-lg border border-emerald-100 bg-white px-3 py-2.5">
                  <div className="flex items-baseline justify-between gap-2">
                    <div className="truncate text-[12px] font-semibold">{member.label}</div>
                    <div className="text-sm font-semibold tabular-nums text-emerald-700">
                      {formatMetric(metrics.current?.last_value, unit)}
                    </div>
                  </div>
                  <div className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1.5 text-[10px]">
                    <div><span className="text-muted-foreground">Průměr</span><div className="font-medium tabular-nums">{formatMetric(history.mean, unit)}</div></div>
                    <div><span className="text-muted-foreground">Medián</span><div className="font-medium tabular-nums">{formatMetric(history.median, unit)}</div></div>
                    <div><span className="text-muted-foreground">Volatilita</span><div className="font-medium tabular-nums">{Number.isFinite(volatility) ? formatMetric(volatility * 100, "%", { digits: 1 }) : "—"}</div></div>
                    <div><span className="text-muted-foreground">Krátkodobý trend</span><div className="font-medium tabular-nums">{formatDifference(shortTrend, unit, { signed: true })}</div></div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      ) : null}

      {pairwise.length ? (
        <div className="mt-4 border-t border-emerald-200/70 pt-3">
          <div className="mb-1 text-[10px] font-semibold uppercase text-emerald-950/80">Korelace mezi zeměmi</div>
          <p className="mb-2 text-[10px] leading-snug text-muted-foreground">
            +1 znamená velmi podobný pohyb, 0 slabou lineární souvislost a -1 opačný pohyb.
          </p>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {pairwise.map((pair, index) => (
              <div key={`${pair.label_a}-${pair.label_b}-${index}`} className="rounded-lg border border-emerald-100 bg-white px-3 py-2">
                <div className="truncate text-[11px] font-semibold">{pair.label_a} × {pair.label_b}</div>
                <p className="mt-0.5 text-[11px] text-muted-foreground">{correlationMeaning(pair.correlation)}</p>
                <div className="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-[10px] text-foreground/80">
                  {Number.isFinite(Number(pair.correlation)) ? <span>Korelace: {fmtNumber(pair.correlation, { digits: 2 })}</span> : null}
                  {Number.isFinite(Number(pair.latest_spread)) ? (
                    <span>Rozdíl v {fmtPeriod(pair.latest_period)}: {formatDifference(Math.abs(pair.latest_spread), unit)}</span>
                  ) : null}
                </div>
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </section>
  );
}

function kpiPresentation(item) {
  const label = String(item?.label || "").toLocaleLowerCase("cs");
  const value = Number(item?.value);
  if (label.includes("histor")) {
    return { Icon: Gauge, accent: "text-violet-700", iconBg: "bg-violet-50", bar: true };
  }
  if (label.includes("změn")) {
    return {
      Icon: Number.isFinite(value) && value < 0 ? ArrowDownRight : ArrowUpRight,
      accent: Number.isFinite(value) && value < 0 ? "text-rose-700" : "text-sky-700",
      iconBg: Number.isFinite(value) && value < 0 ? "bg-rose-50" : "bg-sky-50",
      bar: false,
    };
  }
  return { Icon: BarChart3, accent: "text-emerald-700", iconBg: "bg-emerald-50", bar: false };
}

function anomalyPresentation(anomaly) {
  const type = String(anomaly?.type || "");
  const descriptions = {
    zscore_outlier: "Hodnota je výrazně mimo obvyklé historické rozpětí.",
    possible_structural_break: "Novější část řady se pohybuje na jiné úrovni než starší historie.",
    regime_change: "Poslední období ukazují zřetelný posun oproti předchozí části historie.",
    sharp_acceleration: "Krátkodobý pohyb je výrazně rychlejší než dlouhodobý trend.",
    trend_reversal: "Krátkodobý vývoj se obrátil proti dosavadnímu dlouhodobému trendu.",
    trend_deviation_above_trend: "Poslední hodnota je výrazně nad dlouhodobým trendem.",
    trend_deviation_below_trend: "Poslední hodnota je výrazně pod dlouhodobým trendem.",
  };
  let label = "Důležitý signál";
  let Icon = Activity;
  if (type === "historical_high") label = "Historické maximum";
  else if (type === "historical_low") label = "Historické minimum";
  else if (type.startsWith("largest_rise")) {
    label = "Největší růst";
    Icon = ArrowUpRight;
  } else if (type.startsWith("largest_fall")) {
    label = "Největší pokles";
    Icon = ArrowDownRight;
  } else if (type === "zscore_outlier") label = "Neobvyklá hodnota";
  else if (type === "possible_structural_break" || type === "regime_change") label = "Změna dlouhodobé úrovně";
  else if (type === "volatility_spike") label = "Vyšší kolísání";
  else if (type === "sharp_acceleration") label = "Zrychlení trendu";
  else if (type === "trend_reversal") label = "Obrat trendu";
  else if (type === "long_run_up") label = "Souvislý růst";
  else if (type === "long_run_down") label = "Souvislý pokles";
  else if (type.startsWith("trend_deviation")) label = "Odchylka od trendu";
  const description = humanizeAnalyticsText(descriptions[type] || anomaly?.description);
  return { label, description, Icon };
}

function humanRelationshipLabel(rel) {
  return rel?.concept || rel?.series_name || rel?.label_b || "související ukazatel";
}

const STATUS_BADGE = {
  ok: { label: "Analytický přehled", className: "bg-emerald-50 text-emerald-800 border-emerald-200", Icon: BarChart3 },
  warning: { label: "Analýza s výhradami", className: "bg-amber-50 text-amber-800 border-amber-200", Icon: AlertTriangle },
  not_reliable: { label: "Analýza nespolehlivá", className: "bg-rose-50 text-rose-800 border-rose-200", Icon: ShieldAlert },
};

function downloadTextBlob(text, filename) {
  const blob = new Blob([text], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

function buildExportText(data) {
  const n = data?.narrative || {};
  const lines = [
    humanizeAnalyticsText(n.executive_summary),
    humanizeAnalyticsText(n.main_insight),
    humanizeAnalyticsText(n.forecast_sentence),
    humanizeAnalyticsText(n.manager_sentence),
    "",
    "Klíčová čísla:",
    ...(data?.key_numbers || []).map((k) => `- ${k.label}: ${normalizeDisplayValue(k)} (${k.period || "n/a"})`),
    "",
    "Upozornění:",
    ...(data?.quality_warnings || []).map((w) => `- ${w}`),
  ].filter(Boolean);
  return lines.join("\n");
}

export default function AnalyticsPanel({
  open,
  onClose,
  sourceType,
  setId,
  name,
  geo,
  queryParams = null,
  dimensionFilters,
  selectedIndicator = "",
  selectedIndicators = [],
  comparisonDimension = "",
  comparisonGroups = [],
  query,
  targetFrequency = "",
}) {
  const primaryOptions = useMemo(
    () => (Array.isArray(comparisonGroups) ? comparisonGroups : [])
      .map((group) => ({ value: String(group?.value || "").trim(), label: String(group?.label || group?.value || "").trim() }))
      .filter((group) => group.value),
    [comparisonGroups],
  );
  const defaultPrimarySelection = String(selectedIndicator || geo || primaryOptions[0]?.value || "").trim();
  const [primarySelection, setPrimarySelection] = useState(defaultPrimarySelection);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [data, setData] = useState(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!open) return;
    setPrimarySelection((current) => {
      if (primaryOptions.some((option) => option.value === current)) return current;
      return defaultPrimarySelection;
    });
  }, [open, setId, defaultPrimarySelection, primaryOptions]);

  useEffect(() => {
    if (!open || !sourceType || !setId) return;
    let cancelled = false;
    const activePrimary = primarySelection || defaultPrimarySelection;
    const activeDimensionFilters = comparisonDimension && activePrimary
      ? { ...(dimensionFilters || {}), [comparisonDimension]: activePrimary }
      : dimensionFilters;
    const activeGeo = isGeoComparisonDimension(comparisonDimension) && activePrimary ? activePrimary : geo;
    setLoading(true);
    setError("");
    api
      .post("/catalog/analytics", {
        source_type: sourceType,
        set_id: setId,
        name,
        geo: activeGeo,
        query: query || name,
        include_forecast: false,
        relationship_limit: 0,
        query_params: queryParams || undefined,
        dimension_filters: activeDimensionFilters || undefined,
        selected_indicator: activePrimary || undefined,
        selected_indicators: selectedIndicators?.length ? selectedIndicators : undefined,
        comparison_dimension: comparisonDimension || undefined,
        comparison_groups: comparisonGroups?.length > 1 ? comparisonGroups : undefined,
        target_frequency: targetFrequency || undefined,
      })
      .then((res) => {
        if (!cancelled) setData(res.data);
      })
      .catch((err) => {
        if (!cancelled) setError(err?.response?.data?.message || err.message || "Analytický výpočet selhal.");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, sourceType, setId, name, geo, queryParams, dimensionFilters, selectedIndicators, comparisonDimension, comparisonGroups, query, reloadToken, primarySelection, defaultPrimarySelection, targetFrequency]);

  const status = data?.quality_status || "ok";
  const badge = STATUS_BADGE[status] || STATUS_BADGE.ok;
  const BadgeIcon = badge.Icon;

  const kpiItems = useMemo(() => {
    if (!data?.key_numbers?.length) return [];
    return data.key_numbers.slice(0, 6);
  }, [data]);
  const dynamicMetricItems = useMemo(() => dynamicMetrics(data), [data]);
  const seriesUnit = normalizedUnit(data?.target_resolution?.unit);
  const comparisonMembers = Array.isArray(data?.group_comparison?.members) ? data.group_comparison.members : [];
  const hasGroupComparison = data?.group_comparison?.status === "ok" && comparisonMembers.length > 1;
  const primaryProfileLabel = useMemo(() => {
    if (!comparisonMembers.length) return "";
    const wanted = String(primarySelection || selectedIndicator || geo || "").trim().toLowerCase();
    return comparisonMembers.find((member) => String(member.value || "").trim().toLowerCase() === wanted)?.label
      || comparisonMembers[0]?.label
      || "";
  }, [comparisonMembers, primarySelection, selectedIndicator, geo]);

  const exportRows = useMemo(() => {
    const rows = [];
    for (const k of data?.key_numbers || []) {
      const unit = /^(percentage|percent|pct)$/i.test(String(k.unit || "").trim()) ? "%" : (k.unit || "");
      rows.push({ label: k.label, value: normalizeDisplayValue(k), period: k.period, unit });
    }
    for (const a of data?.anomalies || []) {
      rows.push({ label: a.type, value: a.value, period: a.period, unit: a.description });
    }
    return rows;
  }, [data]);

  if (!open) return null;

  return createPortal(
    <div className="fixed inset-0 z-[260] flex justify-end" data-export-ignore="true">
      <button type="button" className="absolute inset-0 bg-black/30" aria-label="Zavřít" onClick={onClose} />
      <aside className="relative flex h-full w-full max-w-2xl flex-col border-l border-border bg-card shadow-2xl">
        <header className="flex items-start justify-between gap-3 border-b border-border/70 px-4 py-3">
          <div className="min-w-0 space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <BarChart3 className="h-4 w-4 text-sky-600 shrink-0" />
              <h2 className="text-sm font-semibold truncate">Analytický přehled</h2>
              {data ? (
                <span className={`inline-flex items-center gap-1 rounded-md border px-2 py-0.5 text-[10px] font-medium ${badge.className}`}>
                  <BadgeIcon className="h-3 w-3" />
                  {badge.label}
                </span>
              ) : null}
            </div>
            <p className="text-[12px] text-muted-foreground truncate" title={name || setId}>
              {name || setId}
            </p>
          </div>
          <div className="flex items-center gap-1 shrink-0">
            <button
              type="button"
              className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-border/70 hover:bg-muted/50"
              title="Obnovit"
              onClick={() => setReloadToken((t) => t + 1)}
            >
              <RefreshCw className={`h-3.5 w-3.5 ${loading ? "animate-spin" : ""}`} />
            </button>
            <button type="button" className="inline-flex h-8 w-8 items-center justify-center rounded-md hover:bg-muted/50" onClick={onClose}>
              <X className="h-4 w-4" />
            </button>
          </div>
        </header>

        <div className="flex-1 overflow-y-auto px-4 py-3 space-y-3">
          {loading ? (
            <div className="flex items-center gap-2 text-sm text-muted-foreground py-8 justify-center">
              <Loader2 className="h-4 w-4 animate-spin" /> Počítám metriky…
            </div>
          ) : null}
          {error ? <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-[13px] text-rose-800">{error}</div> : null}

          {!loading && !error && data ? (
            <>
              {primaryOptions.length > 1 ? (
                <section className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border/60 bg-card px-3 py-2.5">
                  <label htmlFor="analytics-primary-series" className="text-[11px] font-semibold uppercase text-foreground/80">
                    Hlavní řada
                  </label>
                  <select
                    id="analytics-primary-series"
                    aria-label="Hlavní řada analytiky"
                    value={primarySelection}
                    onChange={(event) => setPrimarySelection(event.target.value)}
                    className="min-w-48 rounded-md border border-border bg-background px-2.5 py-1.5 text-[12px] font-medium outline-none focus:border-sky-400"
                  >
                    {primaryOptions.map((option) => (
                      <option key={option.value} value={option.value}>{option.label}</option>
                    ))}
                  </select>
                </section>
              ) : null}

              <GroupComparisonSection comparison={data.group_comparison} unit={seriesUnit} />

              {hasGroupComparison && primaryProfileLabel ? (
                <div className="rounded-md border border-sky-100 bg-sky-50/50 px-3 py-2 text-[11px] text-sky-950">
                  Následující detailní část se vztahuje k základní řadě <strong>{primaryProfileLabel}</strong>. Srovnání všech vybraných zemí je uvedeno výše.
                </div>
              ) : null}

              <section className="rounded-lg border border-sky-200 bg-sky-50/60 p-4">
                <div className="mb-2 flex items-center gap-2 text-sky-800">
                  <span className="flex h-7 w-7 items-center justify-center rounded-md bg-white shadow-sm">
                    <Sparkles className="h-4 w-4" />
                  </span>
                  <h3 className="text-[11px] font-semibold uppercase">Co z dat vyplývá</h3>
                </div>
                <p className="text-base font-semibold leading-snug text-foreground">{humanizeAnalyticsText(data.narrative?.main_insight)}</p>
                <p className="mt-2 text-[13px] leading-relaxed text-muted-foreground">
                  {humanizeAnalyticsText(data.executive_summary || data.narrative?.executive_summary)}
                </p>
                {data.narrative?.forecast_sentence ? <p className="mt-2 text-[13px] text-foreground/90">{humanizeAnalyticsText(data.narrative.forecast_sentence)}</p> : null}
              </section>

              {kpiItems.length ? (
                <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                  {kpiItems.map((k, i) => {
                    const presentation = kpiPresentation(k);
                    const KpiIcon = presentation.Icon;
                    const percentile = Math.max(0, Math.min(100, Number(k.value) || 0));
                    return (
                      <div key={i} className="min-w-0 rounded-lg border border-border/60 bg-card px-3 py-3 shadow-sm">
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0">
                            <div className="text-[10px] font-semibold uppercase text-muted-foreground">{k.label}</div>
                            <div className={`mt-1 text-xl font-semibold tabular-nums ${presentation.accent}`}>
                              {normalizeDisplayValue(k)}
                            </div>
                          </div>
                          <span className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-md ${presentation.iconBg} ${presentation.accent}`}>
                            <KpiIcon className="h-4 w-4" />
                          </span>
                        </div>
                        {presentation.bar ? (
                          <>
                            <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-muted">
                              <div className="h-full rounded-full bg-violet-500" style={{ width: `${percentile}%` }} />
                            </div>
                            <p className="mt-1.5 text-[10px] leading-snug text-muted-foreground">
                              Přibližně {fmtNumber(percentile, { digits: 0 })} % historických hodnot bylo nižších než aktuální hodnota.
                            </p>
                          </>
                        ) : null}
                        <div className="mt-2 text-[10px] text-muted-foreground">{k.period ? fmtPeriod(k.period) : "—"}</div>
                      </div>
                    );
                  })}
                </div>
              ) : null}

              {dynamicMetricItems.length ? (
                <section className="rounded-lg border border-border/60 bg-card p-3">
                  <div className="mb-2 flex items-center gap-2">
                    <Activity className="h-4 w-4 text-sky-700" />
                    <h3 className="text-[11px] font-semibold uppercase text-foreground/80">
                      Statistický profil{primaryProfileLabel ? `: ${primaryProfileLabel}` : ""}
                    </h3>
                  </div>
                  <div className="grid grid-cols-1 gap-px overflow-hidden rounded-lg border border-border/60 bg-border/60 sm:grid-cols-2">
                    {dynamicMetricItems.map((metric) => {
                      const TrendIcon = metric.direction > 0 ? ArrowUpRight : metric.direction < 0 ? ArrowDownRight : Activity;
                      return (
                        <div key={metric.label} className="flex min-w-0 gap-3 bg-card px-3 py-2.5">
                          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-sky-50 text-sky-700">
                            <TrendIcon className="h-4 w-4" />
                          </span>
                          <div className="min-w-0">
                            <div className="text-[10px] font-semibold uppercase text-muted-foreground">{metric.label}</div>
                            <div className="text-base font-semibold tabular-nums text-foreground">{metric.value}</div>
                            <p className="text-[10px] leading-snug text-muted-foreground">{metric.hint}</p>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </section>
              ) : null}

              {(data.anomalies || []).length ? (
                <section className="rounded-lg border border-border/60 bg-card p-3">
                  <div className="mb-2 flex items-center gap-2">
                    <Lightbulb className="h-4 w-4 text-amber-600" />
                    <h3 className="text-[11px] font-semibold uppercase text-foreground/80">Důležité momenty</h3>
                  </div>
                  <div className="divide-y divide-border/60">
                    {data.anomalies.slice(0, 5).map((a, i) => {
                      const signal = anomalyPresentation(a);
                      const SignalIcon = signal.Icon;
                      return (
                        <div key={i} className="flex gap-3 py-2 first:pt-0 last:pb-0">
                          <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-amber-50 text-amber-700">
                            <SignalIcon className="h-3.5 w-3.5" />
                          </span>
                          <div className="min-w-0">
                            <div className="flex flex-wrap items-center gap-2">
                              <div className="text-[12px] font-semibold text-foreground">{signal.label}</div>
                              {a.period ? (
                                <span className="rounded-md bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground">
                                  {fmtPeriod(a.period)}
                                </span>
                              ) : null}
                            </div>
                            <p className="text-[12px] leading-relaxed text-muted-foreground">{signal.description}</p>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </section>
              ) : null}

              {(data.relationships || []).filter((r) => r.status === "ok").length ? (
                <div className="rounded-xl border border-border/60 p-3 space-y-1.5">
                  <div className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">Ekonomické vztahy</div>
                  <ul className="text-[12px] space-y-1.5">
                    {data.relationships
                      .filter((r) => r.status === "ok")
                      .slice(0, 4)
                      .map((r, i) => (
                        <li key={i}>
                          <span className="font-medium">{humanRelationshipLabel(r)}</span>
                          {r.correlation != null ? ` — souvislost r=${fmtNumber(r.correlation, { digits: 2 })}` : ""}
                          {r.best_lag?.lag != null ? `, nejsilnější zpoždění ${r.best_lag.lag} období` : ""}
                        </li>
                      ))}
                  </ul>
                </div>
              ) : null}

              {(data.real_values?.status === "ok") ? (
                <div className="rounded-xl border border-border/60 p-3 text-[12px] space-y-1">
                  <div className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">Reálné hodnoty</div>
                  <p>
                    Reálný meziroční růst: {fmtNumber(data.real_values.real_yoy_pct, { digits: 1 })} % (nominálně{" "}
                    {fmtNumber(data.real_values.nominal_yoy_pct, { digits: 1 })} %, inflace{" "}
                    {fmtNumber(data.real_values.inflation_yoy_pct, { digits: 1 })} %)
                  </p>
                </div>
              ) : null}

              {status !== "ok" && (data.quality_warnings || []).length ? (
                <section className="rounded-lg border border-amber-200 bg-amber-50/50 px-3 py-2.5">
                  <div className="flex items-center gap-2 text-[12px] font-semibold text-amber-900">
                    <AlertTriangle className="h-4 w-4" /> Upozornění k datům
                  </div>
                  <ul className="mt-1.5 space-y-1 text-[12px] text-amber-950/80">
                    {data.quality_warnings.map((warning, i) => <li key={i}>{warning}</li>)}
                  </ul>
                </section>
              ) : null}
            </>
          ) : null}
        </div>

        {!loading && !error && data ? (
          <footer className="border-t border-border/70 px-4 py-2 flex flex-wrap items-center gap-2">
            <ExportMenu
              label="Export tabulky"
              rows={exportRows}
              columns={[
                { key: "label", label: "Položka" },
                { key: "value", label: "Hodnota" },
                { key: "period", label: "Období" },
                { key: "unit", label: "Poznámka" },
              ]}
              filename={`analytics-${setId}`.replace(/[^\w.-]+/g, "_").slice(0, 80)}
            />
            <button
              type="button"
              className="inline-flex items-center gap-1 h-8 px-2.5 text-[11px] rounded-lg border border-border/70 hover:bg-muted/50"
              onClick={() => downloadTextBlob(buildExportText(data), `analytics-${setId}.txt`.replace(/[^\w.-]+/g, "_").slice(0, 80))}
            >
              <FileText className="h-3 w-3" /> Export textu
            </button>
          </footer>
        ) : null}
      </aside>
    </div>,
    document.body,
  );
}
