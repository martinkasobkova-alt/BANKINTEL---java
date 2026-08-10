import {
  correlation,
  mean,
  median,
  rollingAverage,
  rollingCorrelation,
  standardDeviation,
} from "@/charts/chartAnalytics";
import { compareChartPeriods } from "@/lib/exploreChartCompare";

function asNumber(value) {
  if (value == null) return null;
  if (typeof value === "string" && !value.trim()) return null;
  const n = typeof value === "number" ? value : Number(String(value).replace(/\s/g, "").replace(",", "."));
  return Number.isFinite(n) ? n : null;
}

function pctChange(previous, current) {
  if (previous == null || current == null || previous === 0) return null;
  return ((current - previous) / Math.abs(previous)) * 100;
}

function yoyLagForFrequency(frequency) {
  const freq = String(frequency || "").trim().toUpperCase();
  if (freq === "D") return 365;
  if (freq === "W") return 52;
  if (freq === "M") return 12;
  if (freq === "Q") return 4;
  if (freq === "H") return 2;
  return 1;
}

function rollingWindowForFrequency(frequency, pointCount) {
  const freq = String(frequency || "").trim().toUpperCase();
  const preferred = freq === "D" ? 30 : freq === "W" ? 12 : freq === "M" ? 12 : freq === "Q" ? 4 : 3;
  return Math.max(2, Math.min(preferred, Math.max(2, pointCount)));
}

function seriesLabel(series, index, displayLabels = []) {
  return String(
    displayLabels[index] ||
      series?.name ||
      series?.label ||
      series?.indicator_id ||
      series?.key ||
      `Rada ${index + 1}`
  ).trim();
}

function valuesForSeries(chartRows, key) {
  return (Array.isArray(chartRows) ? chartRows : [])
    .map((row) => {
      const value = asNumber(row?.[key]);
      const period = String(row?.x ?? row?.period ?? "").trim();
      return period && value != null ? { period, value } : null;
    })
    .filter(Boolean)
    .sort((a, b) => compareChartPeriods(a.period, b.period));
}

function percentChanges(values) {
  const out = [];
  for (let i = 1; i < values.length; i += 1) {
    const pct = pctChange(values[i - 1], values[i]);
    if (pct != null && Number.isFinite(pct)) out.push(pct);
  }
  return out;
}

function linearRegression(xValues, yValues) {
  const n = Math.min(xValues.length, yValues.length);
  if (n < 2) return null;
  const x = xValues.slice(0, n);
  const y = yValues.slice(0, n);
  const meanX = mean(x);
  const meanY = mean(y);
  if (meanX == null || meanY == null) return null;
  let num = 0;
  let den = 0;
  for (let i = 0; i < n; i += 1) {
    const dx = x[i] - meanX;
    num += dx * (y[i] - meanY);
    den += dx * dx;
  }
  if (den === 0) return null;
  const beta = num / den;
  const alpha = meanY - beta * meanX;
  const corr = correlation(x, y);
  return {
    alpha,
    beta,
    r2: corr == null ? null : corr * corr,
  };
}

function commonPairRows(chartRows, keyA, keyB) {
  return (Array.isArray(chartRows) ? chartRows : [])
    .map((row) => {
      const a = asNumber(row?.[keyA]);
      const b = asNumber(row?.[keyB]);
      const period = String(row?.x ?? row?.period ?? "").trim();
      return period && a != null && b != null ? { period, a, b } : null;
    })
    .filter(Boolean)
    .sort((x, y) => compareChartPeriods(x.period, y.period));
}

function leadLagCorrelation(points, maxLag = 4) {
  if (!Array.isArray(points) || points.length < 4) return null;
  let best = null;
  for (let lag = -maxLag; lag <= maxLag; lag += 1) {
    const a = [];
    const b = [];
    for (let i = 0; i < points.length; i += 1) {
      const j = i + lag;
      if (j < 0 || j >= points.length) continue;
      a.push(points[i].a);
      b.push(points[j].b);
    }
    if (a.length < 3) continue;
    const corr = correlation(a, b);
    if (corr == null) continue;
    if (!best || Math.abs(corr) > Math.abs(best.correlation)) {
      best = { lag, correlation: corr, count: a.length };
    }
  }
  return best;
}

function latestCompleteRow(chartRows, keys) {
  const rows = Array.isArray(chartRows) ? chartRows : [];
  for (let i = rows.length - 1; i >= 0; i -= 1) {
    const values = keys
      .map((key) => ({ key, value: asNumber(rows[i]?.[key]) }))
      .filter((item) => item.value != null);
    if (values.length >= 2) {
      return {
        period: String(rows[i]?.x ?? rows[i]?.period ?? ""),
        values,
      };
    }
  }
  return null;
}

function describeSeries({ key, label, points, frequency }) {
  const values = points.map((point) => point.value);
  if (!values.length) return null;
  const lag = yoyLagForFrequency(frequency);
  const latest = points[points.length - 1];
  const previousValue = values.length >= 2 ? values[values.length - 2] : null;
  const yoyBase = values.length > lag ? values[values.length - 1 - lag] : null;
  const changes = percentChanges(values);
  // mean/sd počítej jen jednou a znovupoužij pro každý bod - dřív volal zScore(value, values) uvnitř
  // .filter, což u kazdeho prvku znovu prepocitalo mean+sd pres CELE pole (O(n) na volani), tedy
  // O(n^2) celkem. U dlouhé denní řady (tisíce bodů, napr. cela historie akcie) to synchronně
  // zablokovalo hlavní vlákno na desítky sekund až minuty ("Možnosti grafu" se jevily jako zamrzlé).
  const seriesMean = mean(values);
  const seriesStdDev = standardDeviation(values);
  const zScoreFor = (value) =>
    seriesMean == null || seriesStdDev == null || seriesStdDev === 0
      ? null
      : (value - seriesMean) / seriesStdDev;
  const latestZ = zScoreFor(latest.value);
  const anomalyCount = values.filter((value) => {
    const z = zScoreFor(value);
    return z != null && Math.abs(z) >= 2.5;
  }).length;
  const window = rollingWindowForFrequency(frequency, values.length);
  return {
    key,
    label,
    count: values.length,
    latestPeriod: latest.period,
    latestValue: latest.value,
    change: previousValue == null ? null : latest.value - previousValue,
    changePct: pctChange(previousValue, latest.value),
    yoyPct: pctChange(yoyBase, latest.value),
    min: Math.min(...values),
    max: Math.max(...values),
    mean: seriesMean,
    median: median(values),
    stdDev: seriesStdDev,
    volatility: changes.length >= 2 ? standardDeviation(changes) : null,
    movingAverage: rollingAverage(values, window),
    movingAverageWindow: window,
    index100: values[0] ? (latest.value / values[0]) * 100 : null,
    zScore: latestZ,
    anomalyCount,
  };
}

export function buildSingleSeriesStatistics({ chartRows = [], frequency = "", label = "Řada" } = {}) {
  const points = (Array.isArray(chartRows) ? chartRows : [])
    .map((row) => {
      const value = asNumber(row?.y ?? row?.value ?? row?.value_raw);
      const period = String(row?.x ?? row?.period ?? "").trim();
      return period && value != null ? { period, value } : null;
    })
    .filter(Boolean)
    .sort((a, b) => compareChartPeriods(a.period, b.period));
  const series = describeSeries({ key: "y", label, points, frequency });
  return series && series.count >= 2 ? { ok: true, series } : { ok: false, reason: "not_enough_points" };
}

export function buildChartSeriesStatistics({
  chartRows = [],
  seriesList = [],
  frequency = "",
  displayLabels = [],
} = {}) {
  const series = (Array.isArray(seriesList) ? seriesList : [])
    .map((item, index) => ({
      key: String(item?.key || item?.id || "").trim(),
      label: seriesLabel(item, index, displayLabels),
    }))
    .filter((item) => item.key);

  if (series.length < 2 || !Array.isArray(chartRows) || chartRows.length < 2) {
    return { ok: false, reason: "not_enough_series", series: [], pairs: [] };
  }

  const perSeries = series
    .map((item) => {
      const points = valuesForSeries(chartRows, item.key);
      return describeSeries({ ...item, points, frequency });
    })
    .filter(Boolean);

  const pairs = [];
  for (let i = 0; i < series.length; i += 1) {
    for (let j = i + 1; j < series.length; j += 1) {
      const a = series[i];
      const b = series[j];
      const points = commonPairRows(chartRows, a.key, b.key);
      if (points.length < 2) continue;
      const aValues = points.map((point) => point.a);
      const bValues = points.map((point) => point.b);
      const latest = points[points.length - 1];
      const corr = correlation(aValues, bValues);
      const rollWindow = rollingWindowForFrequency(frequency, points.length);
      const diffs = points.map((point) => point.a - point.b);
      pairs.push({
        aKey: a.key,
        bKey: b.key,
        aLabel: a.label,
        bLabel: b.label,
        count: points.length,
        correlation: corr,
        rollingCorrelation: points.length >= 3 ? rollingCorrelation(aValues, bValues, rollWindow) : null,
        rollingWindow: rollWindow,
        leadLag: leadLagCorrelation(points),
        regression: linearRegression(aValues, bValues),
        latestPeriod: latest.period,
        latestSpread: latest.a - latest.b,
        latestRatio: latest.b !== 0 ? latest.a / latest.b : null,
        meanAbsDiff: mean(diffs.map((value) => Math.abs(value))),
      });
    }
  }

  let collinearity = null;
  for (const pair of pairs) {
    if (pair.correlation == null) continue;
    const abs = Math.abs(pair.correlation);
    if (!collinearity || abs > collinearity.maxAbsCorrelation) {
      collinearity = {
        maxAbsCorrelation: abs,
        correlation: pair.correlation,
        aLabel: pair.aLabel,
        bLabel: pair.bLabel,
        high: abs >= 0.85,
      };
    }
  }

  const latestRow = latestCompleteRow(chartRows, series.map((item) => item.key));
  const ranking = latestRow
    ? (() => {
        const avg = mean(latestRow.values.map((item) => item.value));
        const labelByKey = new Map(series.map((item) => [item.key, item.label]));
        const items = latestRow.values
          .map((item) => ({
            key: item.key,
            label: labelByKey.get(item.key) || item.key,
            value: item.value,
            deviation: avg == null ? null : item.value - avg,
            deviationPct: avg ? ((item.value - avg) / Math.abs(avg)) * 100 : null,
          }))
          .sort((a, b) => b.value - a.value)
          .map((item, index) => ({ ...item, rank: index + 1 }));
        return { period: latestRow.period, average: avg, items };
      })()
    : null;

  return {
    ok: perSeries.length >= 2,
    pointCount: chartRows.length,
    series: perSeries,
    pairs,
    collinearity,
    ranking,
  };
}
