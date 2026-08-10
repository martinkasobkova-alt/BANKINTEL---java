/**
 * Aplikuje sdílené chart transformace na AradView chartRows (bez úpravy renderChart).
 */

import { applyChartTransform } from "./chartTransforms";
import { resolveNativeFrequencyCode } from "@/lib/chartFrequencyInfer";

function seriesRowsFromChart(chartRows, key) {
  return (chartRows || [])
    .map((r) => {
      const y = typeof r[key] === "number" ? r[key] : r.y;
      return { x: r.x, y, period: r.x };
    })
    .filter((r) => r.x != null && r.y != null && Number.isFinite(r.y));
}

function applyToSingleSeriesRows(chartRows, transformId, options) {
  const input = (chartRows || [])
    .map((r) => ({ x: r.x, y: r.y, period: r.x }))
    .filter((r) => r.y != null && Number.isFinite(r.y));
  if (!input.length) return chartRows;
  const result = applyChartTransform(transformId, input, options);
  if (!result?.ok || !result.transformed_series?.[0]?.length) return chartRows;
  const byX = new Map(result.transformed_series[0].map((p) => [String(p.x), p.y]));
  return chartRows
    .map((r) => {
      const key = String(r.x);
      if (!byX.has(key)) return null;
      return { ...r, y: byX.get(key) };
    })
    .filter(Boolean);
}

function applyToMultiSeriesRows(chartRows, seriesList, transformId, options) {
  const out = (chartRows || []).map((r) => ({ x: r.x }));
  let any = false;
  for (const s of seriesList || []) {
    const key = s.key;
    if (!key) continue;
    const input = seriesRowsFromChart(chartRows, key);
    if (input.length < 2) continue;
    const result = applyChartTransform(transformId, input, options);
    if (!result?.ok || !result.transformed_series?.[0]?.length) continue;
    const byX = new Map(result.transformed_series[0].map((p) => [String(p.x), p.y]));
    chartRows.forEach((r, idx) => {
      const x = String(r.x);
      if (byX.has(x)) {
        out[idx][key] = byX.get(x);
        any = true;
      }
    });
  }
  return any ? out.filter((r) => Object.keys(r).length > 1) : chartRows;
}

/**
 * @param {Array} chartRows
 * @param {string} transformId — raw | index_100 | yoy | rolling_average
 * @param {Object} options
 */
export function applyAradViewDisplayTransform(chartRows, transformId, options = {}) {
  const id = String(transformId || "raw").toLowerCase();
  if (!id || id === "raw" || id === "none") return chartRows || [];

  const freq = resolveNativeFrequencyCode({
    explicitFrequency: options.frequency,
    rows: (chartRows || []).map((r) => ({ period: r?.x ?? r?.period })),
    isMultiSeries: Boolean(options.isMultiSeries),
  });
  const transformOptions = {
    freq:
      freq === "Y"
        ? "annual"
        : freq === "M"
          ? "monthly"
          : freq === "D"
            ? "daily"
            : freq === "W"
              ? "weekly"
              : freq === "H"
                ? "semiannual"
                : "quarterly",
    window:
      options.window ??
      (freq === "M" ? 12 : freq === "D" ? 7 : freq === "Y" ? 1 : freq === "W" ? 4 : 4),
  };

  if (options.isMultiSeries && options.seriesList?.length) {
    return applyToMultiSeriesRows(chartRows, options.seriesList, id, transformOptions);
  }
  return applyToSingleSeriesRows(chartRows, id, transformOptions);
}

export const ARAD_VIEW_TRANSFORMS = Object.freeze([
  { id: "raw", label: "Raw", title: "Původní hodnoty bez transformace" },
  { id: "mom", label: "Stac. %", title: "Stacionarizace: procentní změna mezi obdobími" },
  { id: "index_100", label: "Index 100", title: "Normalizace řady na bázi 100" },
  { id: "yoy", label: "YoY", title: "Meziroční procentní změna" },
  { id: "rolling_average", label: "Ø klouzavý", title: "Vyhlazení klouzavým průměrem" },
]);
