import React, { useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  AlertTriangle,
  ChevronDown,
  FileText,
  Info,
  Loader2,
  RefreshCw,
  ShieldAlert,
  Sparkles,
  Target,
  TrendingUp,
  X,
} from "lucide-react";
import api from "@/lib/api";
import { fmtNumber } from "@/lib/format";
import ExportMenu from "@/components/widgets/ExportMenu";

/**
 * Forecast panel — Fáze 1 UI pro predikční engine (POST /api/catalog/forecast).
 *
 * Vykresluje: historickou řadu + p10/p90 pásmo nejistoty + mediánový forecast, KPI strip,
 * drivers panel, caveat badge (data_quality.status), methodology drawer a export tlačítka.
 * Žádné číslo se v komponentě nepočítá „navíc" — vše se čte z Java backendu.
 */

const STATUS_BADGE = {
  ok: { label: "Odhad je připraven", className: "bg-emerald-50 text-emerald-800 border-emerald-200", Icon: TrendingUp },
  warning: { label: "Upozornění k historii dat", className: "bg-amber-50 text-amber-800 border-amber-200", Icon: AlertTriangle },
  not_reliable: { label: "Pro tuto řadu nelze odhad bezpečně vytvořit", className: "bg-rose-50 text-rose-800 border-rose-200", Icon: ShieldAlert },
};

function qualityBadge(dataQuality) {
  const status = dataQuality?.status || "not_reliable";
  const base = STATUS_BADGE[status] || STATUS_BADGE.not_reliable;
  if (status !== "warning") return base;

  const warningText = (dataQuality?.warnings || []).join(" ").toLocaleLowerCase("cs-CZ");
  if (warningText.includes("odlehl")) {
    return { ...base, label: "Historie obsahuje neobvyklou hodnotu" };
  }
  if (warningText.includes("strukturální") || warningText.includes("zlom")) {
    return { ...base, label: "Historie může obsahovat změnu režimu" };
  }
  return base;
}

/** Stejná fallback logika jako `ForecastSeriesNormalizer` na Java straně — heterogenní pole
 * napříč konektory (date/TIME_PERIOD/period, value/amount/OBS_VALUE). */
function normalizeHistoricalRows(rawRows) {
  const out = [];
  for (const row of rawRows || []) {
    const date = row?.date ?? row?.TIME_PERIOD ?? row?.period ?? row?.time;
    const rawValue = row?.value ?? row?.amount ?? row?.OBS_VALUE;
    const value = typeof rawValue === "number" ? rawValue : parseFloat(String(rawValue ?? "").replace(",", "."));
    if (!date || !Number.isFinite(value)) continue;
    out.push({ date: String(date).trim(), value });
  }
  out.sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0));
  return out;
}

function buildChartRows(historical, forecastPoints) {
  const rows = [];
  const histTail = historical.slice(-36);
  for (const h of histTail) {
    rows.push({ date: h.date, historical: h.value });
  }
  for (const f of forecastPoints || []) {
    rows.push({
      date: f.date,
      forecast_p50: f.p50,
      forecast_band: f.p90 != null && f.p10 != null ? [f.p10, f.p90] : undefined,
      forecast_p10: f.p10,
      forecast_p90: f.p90,
    });
  }
  return rows;
}

function downloadTextBlob(filename, text) {
  const blob = new Blob([text], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 200);
}

function frequencyLabel(value) {
  return ({ D: "Denní", W: "Týdenní", M: "Měsíční", Q: "Čtvrtletní", A: "Roční", Y: "Roční" })[String(value || "").toUpperCase()] || value;
}

function unitLabel(value) {
  const key = String(value || "").trim().toUpperCase();
  if (key === "PC" || key === "PERCENT" || key === "PERCENTAGE") return "Procenta";
  return value;
}

function isGeoComparisonDimension(value) {
  const key = String(value || "").trim().toLowerCase();
  return key === "geo" || key === "ref_area" || key === "country";
}

function normalizedComparisonOptions(groups, selectedIndicators) {
  const labels = new Map(
    (Array.isArray(groups) ? groups : [])
      .map((group) => [String(group?.value || "").trim(), String(group?.label || group?.value || "").trim()])
      .filter(([value]) => value),
  );
  const values = [
    ...(Array.isArray(selectedIndicators) ? selectedIndicators : []),
    ...labels.keys(),
  ];
  return [...new Set(values.map((value) => String(value || "").trim()).filter(Boolean))]
    .map((value) => ({ value, label: labels.get(value) || value }));
}

function methodCalculation(model) {
  const key = String(model || "");
  if (key === "naive") {
    return {
      formula: "ŷ(t+h) = y(t)",
      meaning: "Každý budoucí bod se rovná poslední známé hodnotě řady.",
    };
  }
  if (key === "moving_average") {
    return {
      formula: "ŷ(t+h) = (1 / w) · Σ y(t−i),   i = 0…w−1,   w = min(12, n)",
      meaning: "Forecast je průměr nejvýše 12 posledních období. Stejná vyhlazená hodnota se použije pro všechny zvolené horizonty.",
    };
  }
  if (key === "linear_trend") {
    return {
      formula: "y(t) = a + b·t + ε(t)   ⇒   ŷ(t+h) = a + b·(t+h)",
      meaning: "Koeficienty a a b se odhadnou metodou nejmenších čtverců a přímka se prodlouží do budoucnosti.",
    };
  }
  if (key === "holt_trend") {
    return {
      formula: "L(t)=αy(t)+(1−α)[L(t−1)+T(t−1)]\nT(t)=β[L(t)−L(t−1)]+(1−β)T(t−1)\nŷ(t+h)=L(t)+h·T(t)",
      meaning: "Hladina L a trend T se průběžně aktualizují. Java vyzkouší kombinace α a β od 0,1 do 0,9 a ponechá kombinaci s nejmenší chybou.",
    };
  }
  if (key === "seasonal_naive") {
    return {
      formula: "ŷ(t+h) = y(t+h−s)",
      meaning: "Budoucí hodnota se převezme ze stejné pozice předchozí sezóny; s je délka sezóny podle frekvence dat.",
    };
  }
  if (key === "log_linear_trend") {
    return {
      formula: "ln y(t) = a + b·t + ε(t)   ⇒   ŷ(t+h) = exp[a + b·(t+h)]",
      meaning: "Trend se odhaduje na logaritmu kladných hodnot, takže pokračuje historické procentní tempo změny.",
    };
  }
  if (key.startsWith("exog_regression:")) {
    return {
      formula: "y(t) = a + b·x(t) + ε(t)   ⇒   ŷ(t+h) = a + b·x(t+h)",
      meaning: "x je doprovodná řada, která prokazatelně zlepšila historické testy. Není-li známá její budoucnost, použije se poslední dostupná hodnota x.",
    };
  }
  return {
    formula: "ŷ(t+h) = f(y(1), …, y(t))",
    meaning: "Odhad vychází pouze z hodnot cílové řady dostupných do posledního známého období.",
  };
}

function selectionDetails(selection, targetSeries) {
  const ignored = new Set(["geo", "ref_area", "country", "freq", "frequency", "unit", "unit_measure"]);
  const details = [];
  for (const [rawKey, rawValue] of Object.entries(selection?.dimension_filters || {})) {
    const key = String(rawKey).trim().toLowerCase();
    if (ignored.has(key)) continue;
    const values = Array.isArray(rawValue) ? rawValue : [rawValue];
    const label = String(rawKey).replaceAll("_", " ").replace(/^./, (letter) => letter.toUpperCase());
    for (const value of values) {
      const text = String(value ?? "").trim();
      if (text) details.push(`${label}: ${text}`);
    }
  }
  const indicator = String(selection?.selected_indicator || "").trim();
  const geo = String(targetSeries?.geo || "").trim();
  if (indicator && indicator !== geo && !details.some((item) => item.endsWith(`: ${indicator}`))) {
    details.push(`Řada: ${indicator}`);
  }
  return details.slice(0, 4);
}

export default function ForecastPanel({
  open,
  onClose,
  sourceType,
  setId,
  name,
  geo,
  queryParams = null,
  dimensionFilters = null,
  selectedIndicator = "",
  selectedIndicators = [],
  comparisonDimension = "",
  comparisonGroups = [],
  targetFrequency = "",
}) {
  const targetOptions = useMemo(
    () => normalizedComparisonOptions(comparisonGroups, selectedIndicators),
    [comparisonGroups, selectedIndicators],
  );
  const defaultTarget = String(selectedIndicator || selectedIndicators?.[0] || targetOptions[0]?.value || "").trim();
  const [targetSelection, setTargetSelection] = useState(defaultTarget);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [data, setData] = useState(null);
  const [historical, setHistorical] = useState([]);
  const [methodKey, setMethodKey] = useState("");
  const [methodologyOpen, setMethodologyOpen] = useState(true);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!open) return;
    setTargetSelection((current) => {
      if (targetOptions.some((option) => option.value === current)) return current;
      return defaultTarget;
    });
  }, [open, setId, defaultTarget, targetOptions]);

  useEffect(() => {
    if (!open || !sourceType || !setId) return;
    let cancelled = false;
    setLoading(true);
    setError("");
    setData(null);

    const activeIndicator = String(targetSelection || defaultTarget).trim();
    const activeGeo = isGeoComparisonDimension(comparisonDimension) && activeIndicator ? activeIndicator : geo;
    const activeDimensionFilters = {
      ...(dimensionFilters || {}),
      ...(comparisonDimension && activeIndicator ? { [comparisonDimension]: activeIndicator } : {}),
    };

    const forecastBody = {
      source_type: sourceType,
      set_id: setId,
      name,
      geo: activeGeo,
      include_candidate_search: false,
      candidate_fetch_budget_ms: 6000,
    };
    if (queryParams && Object.keys(queryParams).length) {
      forecastBody.query_params = queryParams;
    }
    if (Object.keys(activeDimensionFilters).length) {
      forecastBody.dimension_filters = activeDimensionFilters;
    }
    if (activeIndicator) {
      forecastBody.selected_indicator = activeIndicator;
      forecastBody.selected_indicators = [activeIndicator];
    }
    if (targetFrequency) {
      forecastBody.target_frequency = targetFrequency;
    }

    const previewBody = {
      source_type: sourceType,
      set_id: setId,
      ...(queryParams && Object.keys(queryParams).length ? { query_params: queryParams } : {}),
      ...(Object.keys(activeDimensionFilters).length ? { dimension_filters: activeDimensionFilters } : {}),
      ...(activeIndicator ? { selected_indicator: activeIndicator, selected_indicators: [activeIndicator] } : {}),
    };

    Promise.all([
      api.post("/catalog/forecast", forecastBody),
      api.post("/catalog/preview", previewBody).catch(() => ({ data: { rows: [] } })),
    ])
      .then(([forecastRes, previewRes]) => {
        if (cancelled) return;
        setData(forecastRes.data);
        setMethodKey(forecastRes.data?.model_selection?.selected_model || "");
        setHistorical(normalizeHistoricalRows(previewRes.data?.rows));
      })
      .catch((err) => {
        if (cancelled) return;
        setError(err?.response?.data?.message || err?.message || "Forecast se nepodařilo spočítat.");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [open, sourceType, setId, name, geo, queryParams, dimensionFilters, comparisonDimension, reloadToken, targetSelection, defaultTarget, targetFrequency]);

  const status = data?.data_quality?.status || "not_reliable";
  const badge = qualityBadge(data?.data_quality);

  const modelOptions = useMemo(() => data?.model_alternatives || [], [data]);
  const activeMethod = useMemo(
    () => modelOptions.find((item) => item.model === methodKey) || modelOptions.find((item) => item.recommended) || null,
    [modelOptions, methodKey]
  );
  const displayedForecast = activeMethod?.forecast?.length ? activeMethod.forecast : data?.forecast || [];
  const displayedBacktest = activeMethod || data?.backtest || {};
  const isRecommendedMethod = activeMethod?.recommended !== false;
  const activeCalculation = useMemo(() => methodCalculation(activeMethod?.model), [activeMethod?.model]);
  const selectedSeriesDetails = useMemo(
    () => selectionDetails(data?.target_selection, data?.target_series),
    [data]
  );
  const targetDisplayLabel = useMemo(() => {
    const activeValue = String(data?.target_series?.geo || targetSelection || "").trim();
    return targetOptions.find((option) => option.value === activeValue)?.label || activeValue;
  }, [data, targetOptions, targetSelection]);

  const chartRows = useMemo(() => {
    if (!data) return [];
    return buildChartRows(historical, displayedForecast);
  }, [data, historical, displayedForecast]);

  const kpiHorizons = useMemo(() => {
    if (!displayedForecast?.length) return [];
    const wanted = ["3M", "6M", "12M", "1Q", "2Q", "4Q", "1Y", "2Y"];
    return displayedForecast.filter((f) => wanted.includes(f.horizon));
  }, [displayedForecast]);

  if (!open) return null;

  return createPortal(
    <>
      <button type="button" className="fixed inset-0 z-[260] bg-black/30" aria-label="Zavřít forecast" onClick={onClose} />
      <section
        className="fixed inset-y-0 right-0 z-[261] w-full max-w-2xl shadow-2xl border-l border-sky-200 bg-white flex flex-col animate-in slide-in-from-right"
        aria-label="Forecast panel"
      >
        <div className="shrink-0 flex items-center gap-2 px-4 py-3 border-b border-sky-200 bg-sky-50">
          <TrendingUp className="h-4 w-4 text-sky-600 shrink-0" />
          <h3 className="min-w-0 flex-1 text-sm font-semibold text-foreground truncate" title={name}>
            Výhled — {name}
          </h3>
          <button
            type="button"
            onClick={onClose}
            className="h-8 w-8 shrink-0 inline-flex items-center justify-center rounded-lg border border-border/70 hover:bg-muted/50"
            aria-label="Zavřít"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </div>

        <div className="flex-1 min-h-0 overflow-y-auto px-4 py-4 space-y-4">
          {targetOptions.length > 1 ? (
            <div className="rounded-xl border border-sky-200 bg-sky-50 px-3 py-2.5 shadow-sm">
              <label className="block text-[11px] font-semibold text-sky-950">
                Výhled pro
                <select
                  value={targetSelection}
                  onChange={(event) => setTargetSelection(event.target.value)}
                  disabled={loading}
                  className="mt-1 h-9 w-full rounded-lg border border-sky-300 bg-white px-2.5 text-sm font-medium text-slate-900 shadow-sm focus:border-sky-500 focus:outline-none focus:ring-2 focus:ring-sky-200 disabled:cursor-wait disabled:opacity-70"
                >
                  {targetOptions.map((option) => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
              </label>
            </div>
          ) : null}

          {loading ? (
            <div className="flex items-center gap-2 text-sm text-muted-foreground py-8 justify-center">
              <Loader2 className="h-4 w-4 animate-spin" /> Připravuji odhad vybrané řady…
            </div>
          ) : null}

          {error ? (
            <div className="text-sm rounded-xl border border-rose-200 bg-rose-50/95 text-rose-950 px-3 py-2">
              {error}
            </div>
          ) : null}

          {!loading && !error && data ? (
            <>
              <span
                className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full border text-[11px] font-medium ${badge.className}`}
              >
                <badge.Icon className="h-3.5 w-3.5" />
                {badge.label}
              </span>

              <div className="rounded-lg border border-sky-200 bg-sky-50 px-3 py-3 shadow-sm">
                <div className="flex items-start gap-2">
                  <Target className="mt-0.5 h-4 w-4 shrink-0 text-sky-700" />
                  <div className="min-w-0 flex-1">
                    <div className="text-[10px] font-semibold uppercase text-sky-800">Forecast pro vybranou řadu</div>
                    <div className="text-sm font-medium text-foreground">{data.target_series?.name || name}</div>
                    <div className="mt-1 flex flex-wrap gap-1.5 text-[11px] text-slate-600">
                      {targetDisplayLabel ? (
                        <span className="rounded border border-sky-200 bg-white/80 px-1.5 py-0.5">Země: {targetDisplayLabel}</span>
                      ) : null}
                      {selectedSeriesDetails.map((value) => (
                        <span key={value} className="rounded border border-sky-200 bg-white/80 px-1.5 py-0.5">{value}</span>
                      ))}
                      {data.target_series?.frequency ? <span>{frequencyLabel(data.target_series.frequency)}</span> : null}
                      {data.target_series?.unit ? <span>· {unitLabel(data.target_series.unit)}</span> : null}
                    </div>
                  </div>
                </div>
              </div>

              {status === "not_reliable" ? (
                <div className="rounded-xl border border-border/60 bg-muted/10 p-3 space-y-2">
                  <p className="text-sm text-foreground/90">{data.narrative?.executive_summary}</p>
                  {(data.data_quality?.what_would_help || []).length ? (
                    <ul className="text-[12px] text-muted-foreground list-disc pl-4 space-y-0.5">
                      {data.data_quality.what_would_help.map((w, i) => (
                        <li key={i}>{w}</li>
                      ))}
                    </ul>
                  ) : null}
                </div>
              ) : (
                <>
                  {modelOptions.length ? (
                    <div className="rounded-xl border border-emerald-200 bg-emerald-50/70 p-3 space-y-2 shadow-sm">
                      <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
                        <label className="min-w-0 flex-1 text-[11px] font-semibold text-foreground">
                          Způsob odhadu
                          <select
                            value={activeMethod?.model || ""}
                            onChange={(event) => setMethodKey(event.target.value)}
                            className="mt-1 h-9 w-full rounded-lg border border-emerald-300 bg-white px-2.5 text-sm font-normal text-foreground shadow-sm focus:border-emerald-500 focus:outline-none focus:ring-2 focus:ring-emerald-200"
                          >
                            {modelOptions.map((method) => (
                              <option key={method.model} value={method.model}>
                                {method.label}{method.recommended ? " — doporučeno" : ""}
                              </option>
                            ))}
                          </select>
                        </label>
                        {isRecommendedMethod ? (
                          <span className="inline-flex h-7 items-center gap-1 rounded-full border border-emerald-200 bg-emerald-50 px-2.5 text-[11px] font-medium text-emerald-800">
                            <Sparkles className="h-3 w-3" /> Nejlépe vyšlo na historii
                          </span>
                        ) : null}
                      </div>
                      <p className="text-[12px] leading-relaxed text-muted-foreground">{activeMethod?.description}</p>
                    </div>
                  ) : null}

                  {/* KPI strip */}
                  <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                    <div className="rounded-lg border border-sky-200 bg-sky-50/80 p-2.5">
                      <div className="text-[10px] uppercase tracking-wide text-muted-foreground">Poslední hodnota</div>
                      <div className="text-sm font-semibold text-foreground">
                        {fmtNumber(data.target_series?.last_value)} <span className="text-[10px] text-muted-foreground">{data.target_series?.last_date}</span>
                      </div>
                    </div>
                    {kpiHorizons.map((f) => (
                      <div key={f.horizon} className="rounded-lg border border-emerald-200 bg-emerald-50/60 p-2.5">
                        <div className="text-[10px] uppercase tracking-wide text-muted-foreground">Výhled {f.horizon}</div>
                        <div className="text-sm font-semibold text-foreground">{fmtNumber(f.p50)}</div>
                        <div className="text-[10px] text-muted-foreground">
                          {f.change_pct_vs_last != null ? `${f.change_pct_vs_last > 0 ? "+" : ""}${fmtNumber(f.change_pct_vs_last, { digits: 1 })}%` : "—"}
                        </div>
                      </div>
                    ))}
                    <div className="rounded-lg border border-amber-200 bg-amber-50/70 p-2.5">
                      <div className="text-[10px] uppercase tracking-wide text-muted-foreground">Typická odchylka</div>
                      <div className="text-sm font-semibold text-foreground">
                        ± {fmtNumber(displayedBacktest?.mae)}
                        {data.target_series?.unit ? ` ${unitLabel(data.target_series.unit)}` : ""}
                      </div>
                      <div className="text-[10px] text-muted-foreground">
                        při ověření na minulých datech{data.target_series?.frequency ? ` (krok: ${frequencyLabel(data.target_series.frequency).toLowerCase()})` : ""}
                      </div>
                    </div>
                  </div>

                  {/* Chart: historical + p10/p90 band + median forecast. */}
                  <div className="h-64 rounded-xl border border-sky-200 bg-white p-2 shadow-sm">
                    <ResponsiveContainer width="100%" height="100%">
                      <ComposedChart data={chartRows} margin={{ top: 8, right: 8, bottom: 4, left: 0 }}>
                        <CartesianGrid vertical={false} stroke="hsl(var(--border))" strokeDasharray="2 4" />
                        <XAxis dataKey="date" tick={{ fontSize: 10 }} minTickGap={24} />
                        <YAxis tick={{ fontSize: 10 }} width={48} domain={["auto", "auto"]} />
                        <Tooltip formatter={(v) => (Array.isArray(v) ? v.map((x) => fmtNumber(x)).join(" – ") : fmtNumber(v))} />
                        <Legend wrapperStyle={{ fontSize: 11 }} />
                        <Area
                          dataKey="forecast_band"
                          name="Možné rozpětí"
                          stroke="none"
                          fill="hsl(208 75% 60%)"
                          fillOpacity={0.18}
                          connectNulls
                          isAnimationActive={false}
                        />
                        <Line
                          type="linear"
                          dataKey="historical"
                          name="Dosavadní vývoj"
                          stroke="hsl(215 25% 35%)"
                          strokeWidth={2}
                          dot={false}
                          connectNulls
                          isAnimationActive={false}
                        />
                        <Line
                          type="linear"
                          dataKey="forecast_p50"
                          name="Střed odhadu"
                          stroke="hsl(208 75% 48%)"
                          strokeWidth={2.5}
                          strokeDasharray="5 3"
                          dot={false}
                          connectNulls
                          isAnimationActive={false}
                        />
                      </ComposedChart>
                    </ResponsiveContainer>
                  </div>

                  <div className="rounded-xl border border-sky-200 bg-sky-50/60 p-3 space-y-2">
                    <div className="flex items-center gap-1.5 text-[11px] font-semibold uppercase text-sky-800">
                      <Info className="h-3.5 w-3.5" /> Jak odhad číst
                    </div>
                    <p className="text-[13px] leading-relaxed text-foreground/90">
                      {activeMethod?.description} Střední čára ukazuje očekávaný průběh a barevné pásmo rozpětí,
                      ve kterém se může skutečný vývoj rozumně pohybovat.
                    </p>
                    {displayedForecast.length ? (
                      <p className="text-[13px] font-medium text-foreground">
                        Pro horizont {displayedForecast.at(-1)?.horizon} vychází střed odhadu na {fmtNumber(displayedForecast.at(-1)?.p50)}
                        {data.target_series?.unit ? ` ${unitLabel(data.target_series.unit)}` : ""}; možné rozpětí je {fmtNumber(displayedForecast.at(-1)?.p10)} až {fmtNumber(displayedForecast.at(-1)?.p90)}.
                      </p>
                    ) : null}
                  </div>

                  {/* Methodology drawer */}
                  <details
                    className="rounded-xl border border-amber-200 bg-amber-50/45 overflow-hidden"
                    open={methodologyOpen}
                    onToggle={(e) => setMethodologyOpen(e.currentTarget.open)}
                  >
                    <summary className="flex items-center gap-1.5 px-3 py-2.5 text-[12px] font-semibold text-amber-950 cursor-pointer list-none [&::-webkit-details-marker]:hidden hover:bg-amber-50">
                      <ChevronDown className={`h-3.5 w-3.5 transition-transform ${methodologyOpen ? "rotate-180" : ""}`} />
                      Metodika a ověření
                    </summary>
                    <div className="border-t border-amber-200 px-3 py-3 space-y-3 text-[12px] text-slate-700">
                      <div className="grid grid-cols-2 gap-x-4 gap-y-2 rounded-lg bg-white/80 px-3 py-2.5">
                        <div>
                          <div className="text-[10px] font-semibold uppercase text-slate-500">Vybraná metoda</div>
                          <div className="font-semibold text-slate-900">{activeMethod?.label || "Doporučený odhad"}</div>
                        </div>
                        <div>
                          <div className="text-[10px] font-semibold uppercase text-slate-500">Ověřeno na historii</div>
                          <div className="font-semibold text-slate-900">
                            typická odchylka ± {fmtNumber(displayedBacktest?.mae)}
                            {data.target_series?.unit ? ` ${unitLabel(data.target_series.unit)}` : ""}
                            {data.target_series?.frequency ? ` (krok: ${frequencyLabel(data.target_series.frequency).toLowerCase()})` : ""}
                          </div>
                        </div>
                        <div>
                          <div className="text-[10px] font-semibold uppercase text-slate-500">Porovnané postupy</div>
                          <div className="font-semibold text-slate-900">{modelOptions.length}</div>
                        </div>
                        <div>
                          <div className="text-[10px] font-semibold uppercase text-slate-500">Historická data</div>
                          <div className="font-semibold text-slate-900">{data.data_quality?.common_observations || historical.length} období</div>
                        </div>
                      </div>
                      <p><strong>Cílová řada:</strong> Výpočet používá pouze zvolenou zemi nebo ukazatel a zachovává všechny ostatní aktivní filtry. Hodnoty jiných zemí se do odhadu nepřimíchávají.</p>
                      <p><strong>Výběr metody:</strong> Každý dostupný postup se opakovaně zkouší na starších úsecích řady. Model dostane jen tehdy známou minulost a jeho odhad se porovná se skutečným pokračováním. Doporučen je postup s nejnižší historickou chybou.</p>
                      <p><strong>Rozpětí:</strong> Barevné pásmo vychází z chyb, které metoda dělala při tomto historickém ověření. S delším horizontem se rozšiřuje, protože vzdálenější vývoj je méně jistý.</p>
                      <div className="border-y border-amber-200 py-3 space-y-2">
                        <div className="text-[10px] font-semibold uppercase text-slate-500">Výpočet vybrané metody</div>
                        <pre className="overflow-x-auto whitespace-pre-wrap rounded-lg border border-sky-200 bg-sky-50 px-3 py-2 font-mono text-[12px] font-semibold leading-relaxed text-sky-950">
                          {activeCalculation.formula}
                        </pre>
                        <p>{activeCalculation.meaning}</p>
                        <div className="grid gap-2 sm:grid-cols-3">
                          <div className="rounded-lg bg-white/80 px-2.5 py-2">
                            <div className="text-[10px] font-semibold uppercase text-slate-500">Typická chyba</div>
                            <div className="mt-1 font-mono text-[11px] text-slate-900">MAE = (1/N) · Σ |y − ŷ|</div>
                          </div>
                          <div className="rounded-lg bg-white/80 px-2.5 py-2">
                            <div className="text-[10px] font-semibold uppercase text-slate-500">Výběrové skóre</div>
                            <div className="mt-1 font-mono text-[11px] text-slate-900">RMSE = √[(1/N) · Σ(y − ŷ)²]</div>
                          </div>
                          <div className="rounded-lg bg-white/80 px-2.5 py-2">
                            <div className="text-[10px] font-semibold uppercase text-slate-500">Pásmo odhadu</div>
                            <div className="mt-1 font-mono text-[11px] text-slate-900">ŷ(h) ± 1,2816 · σ(e) · √h</div>
                          </div>
                        </div>
                        <p className="text-slate-600"><strong>Symboly:</strong> y je skutečná hodnota, ŷ odhad, h počet období do budoucnosti, N počet historických testů a σ(e) směrodatná odchylka jejich chyb. Koeficient 1,2816 vytváří centrální 80% pásmo.</p>
                      </div>
                      {modelOptions.length > 1 ? (
                        <div className="space-y-1.5">
                          <div className="grid grid-cols-[1fr_auto_auto] gap-3 text-[10px] font-semibold uppercase text-slate-500">
                            <span>Srovnání metod</span>
                            <span>MAE{data.target_series?.unit ? ` (${unitLabel(data.target_series.unit)})` : ""}</span>
                            <span>RMSE{data.target_series?.unit ? ` (${unitLabel(data.target_series.unit)})` : ""}</span>
                          </div>
                          {modelOptions.map((method) => (
                            <div key={method.model} className="grid grid-cols-[1fr_auto_auto] items-center gap-3 border-t border-amber-100 pt-1.5">
                              <span className={method.model === activeMethod?.model ? "font-semibold text-slate-950" : "text-slate-700"}>
                                {method.label}{method.recommended ? " · doporučeno" : ""}
                              </span>
                              <span className="shrink-0 tabular-nums text-slate-600">{fmtNumber(method.mae)}</span>
                              <span className="shrink-0 tabular-nums text-slate-600">{fmtNumber(method.rmse)}</span>
                            </div>
                          ))}
                        </div>
                      ) : null}
                      <p className="text-slate-600">Forecast není jistota ani investiční doporučení. Je to transparentní datový odhad založený na dosavadním průběhu vybrané řady.</p>
                      {(data.data_quality?.warnings || []).length ? (
                        <ul className="list-disc pl-4 space-y-0.5">
                          {data.data_quality.warnings.map((w, i) => (
                            <li key={i}>{w}</li>
                          ))}
                        </ul>
                      ) : null}
                    </div>
                  </details>
                </>
              )}
            </>
          ) : null}
        </div>

        {!loading && !error && data && status !== "not_reliable" ? (
          <div className="shrink-0 flex flex-wrap gap-2 px-4 py-3 border-t border-border/60 bg-card">
            <ExportMenu
              title={`forecast_${name || setId}`}
              columns={["horizon", "date", "p10", "p50", "p90", "point", "change_vs_last", "change_pct_vs_last"]}
              rows={displayedForecast}
              compact
            />
            <button
              type="button"
              onClick={() =>
                downloadTextBlob(
                  `forecast_${(name || setId || "export").replace(/[^A-Za-z0-9_-]+/g, "_")}.txt`,
                  [
                    `Výhled: ${data.target_series?.name || name}`,
                    `Vybraná metoda: ${activeMethod?.label || "doporučený odhad"}`,
                    activeMethod?.description,
                    "",
                    ...displayedForecast.map((point) =>
                      `${point.horizon}: ${fmtNumber(point.p50)} (možné rozpětí ${fmtNumber(point.p10)} až ${fmtNumber(point.p90)})`
                    ),
                    "",
                    "Odhad byl ověřen na starších částech historie. Skutečný vývoj se může od odhadu odchýlit.",
                  ]
                    .filter((l) => l !== undefined && l !== null)
                    .join("\n")
                )
              }
              className="inline-flex items-center gap-1 h-7 px-3 text-[10px] uppercase tracking-[0.12em] rounded-full border border-border/60 hover:bg-muted/50 text-slate-600"
            >
              <FileText className="h-3.5 w-3.5" /> Export výhledu
            </button>
            <button
              type="button"
              onClick={() => setReloadToken((t) => t + 1)}
              className="ml-auto inline-flex items-center gap-1 h-7 px-3 text-[10px] uppercase tracking-[0.12em] rounded-full border border-border/60 hover:bg-muted/50 text-slate-500"
              title="Znovu spočítat odhad"
            >
              <RefreshCw className="h-3 w-3" />
            </button>
          </div>
        ) : null}
      </section>
    </>,
    document.body,
  );
}
