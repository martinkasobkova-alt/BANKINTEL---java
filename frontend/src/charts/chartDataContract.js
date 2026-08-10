/**
 * Normalized chart data contract — all charts should consume this shape.
 */

import { CHART_TYPES, CHART_DATA_MODES } from "./chartTypes";
import { coerceChartNumeric } from "./chartScales";
import { sanitizeExportValueRaw } from "./chartExportSanitize";

export const CHART_POINT_FIELDS = [
  "period",
  "period_label",
  "geo",
  "geo_label",
  "series_id",
  "series_label",
  "value_raw",
  "value_display",
  "unit",
  "frequency",
  "source",
  "dataset",
  "transformation",
  "is_estimated",
  "is_forecast",
  "metadata",
];

/**
 * @typedef {Object} ChartDataPoint
 * @property {string} period
 * @property {string} [period_label]
 * @property {string} [geo]
 * @property {string} [geo_label]
 * @property {string} series_id
 * @property {string} series_label
 * @property {number|null} value_raw
 * @property {string} [value_display]
 * @property {string} [unit]
 * @property {string} [frequency]
 * @property {string} [source]
 * @property {string} [dataset]
 * @property {string} [transformation]
 * @property {boolean} [is_estimated]
 * @property {boolean} [is_forecast]
 * @property {Object} [metadata]
 */

/**
 * @typedef {Object} ChartDataContract
 * @property {string} chart_id
 * @property {string} chart_type
 * @property {string} [title]
 * @property {string} [subtitle]
 * @property {string} [description]
 * @property {Object} [x_axis]
 * @property {Object} [y_axis]
 * @property {Array} series
 * @property {ChartDataPoint[]} data
 * @property {Object} [metadata]
 * @property {Array} [transformations]
 * @property {Object} [source]
 * @property {Object} [export_config]
 */

export function createEmptyChartContract(overrides = {}) {
  return {
    chart_id: "",
    chart_type: CHART_TYPES.LINE,
    title: "",
    subtitle: "",
    description: "",
    x_axis: { key: "period", label: "Období" },
    y_axis: { label: "" },
    series: [],
    data: [],
    metadata: {},
    transformations: [],
    source: {},
    export_config: {},
    ...overrides,
  };
}

export function normalizeChartPoint(raw = {}, seriesMeta = {}) {
  const period = String(raw.period ?? raw.x ?? "").trim();
  const valueRaw = sanitizeExportValueRaw(raw.value_raw ?? raw.value ?? raw.y, {
    period,
    series_id: raw.series_id ?? seriesMeta.id ?? seriesMeta.key,
  });
  return {
    period,
    period_label: raw.period_label ?? period,
    geo: raw.geo ?? seriesMeta.geo ?? "",
    geo_label: raw.geo_label ?? raw.geo ?? seriesMeta.geo_label ?? "",
    series_id: String(raw.series_id ?? seriesMeta.id ?? seriesMeta.key ?? "series_0"),
    series_label: String(raw.series_label ?? seriesMeta.label ?? seriesMeta.name ?? "Řada"),
    value_raw: valueRaw,
    value_display: raw.value_display ?? (valueRaw != null ? String(valueRaw) : ""),
    unit: raw.unit ?? seriesMeta.unit ?? "",
    frequency: raw.frequency ?? seriesMeta.frequency ?? "",
    source: raw.source ?? seriesMeta.source ?? "",
    dataset: raw.dataset ?? seriesMeta.dataset ?? "",
    transformation: raw.transformation ?? "none",
    is_estimated: Boolean(raw.is_estimated),
    is_forecast: Boolean(raw.is_forecast),
    metadata: raw.metadata && typeof raw.metadata === "object" ? raw.metadata : {},
  };
}

/** Convert legacy ExploreReportChart { merged, series } to ChartDataContract. */
export function contractFromExploreLegacy({ merged = [], series = [], title = "", unit = "" } = {}) {
  const chartId = `explore_${Date.now()}`;
  const seriesMeta = (series || []).map((s, idx) => ({
    id: s.key || `series_${idx}`,
    key: s.key || `series_${idx}`,
    label: s.name || s.label || `Řada ${idx + 1}`,
    unit: s.unit || unit,
    axis: s.axis || s.yAxisId || "left",
    color: s.color,
  }));

  const data = [];
  for (const row of merged || []) {
    const period = String(row.x ?? row.period ?? "").trim();
    for (const s of seriesMeta) {
      const val = coerceChartNumeric(row[s.key]);
      if (val == null) continue;
      data.push(
        normalizeChartPoint(
          { period, value_raw: val, series_id: s.id, series_label: s.label, unit: s.unit },
          s
        )
      );
    }
  }

  return createEmptyChartContract({
    chart_id: chartId,
    chart_type: CHART_TYPES.LINE,
    title,
    series: seriesMeta,
    data,
    metadata: { data_mode: CHART_DATA_MODES.TIME_SERIES },
  });
}

/** Je contract v režimu latest comparison (kategorie na ose X)? */
export function isLatestDataMode(contract) {
  if (!contract) return false;
  const mode = String(contract.metadata?.data_mode || "").toLowerCase();
  if (mode === CHART_DATA_MODES.LATEST) return true;
  return Boolean(contract.metadata?.latest_mode);
}

/**
 * Single-series rows { x, y } — time series nebo latest comparison.
 * Pro latest: x = period_label / geo_label, y = value_raw.
 */
export function contractToSingleSeriesRows(contract) {
  const latest = isLatestDataMode(contract);
  const primarySeries = contract.series?.[0];
  const seriesId = primarySeries?.id || primarySeries?.key;

  if (latest || (contract.series?.length === 1 && !contract.data?.some((pt) => /Q\d|^\d{4}/.test(String(pt.period))))) {
    const rows = [];
    for (const pt of contract.data || []) {
      if (seriesId && pt.series_id !== seriesId && contract.series?.length === 1) {
        // single series — accept all
      } else if (seriesId && pt.series_id !== seriesId) continue;
      const x = String(pt.geo_label || pt.period_label || pt.period || "").trim();
      if (!x || pt.value_raw == null) continue;
      rows.push({ x, y: pt.value_raw, period: pt.period, label: x });
    }
    return rows;
  }

  const wide = contractToRechartsWide(contract);
  const key = seriesId || contract.series?.[0]?.key;
  if (!key) return wide.map((row) => ({ x: row.x, y: row[key], period: row.period }));
  return wide.map((row) => ({
    x: row.x,
    y: row[key],
    period: row.period ?? row.x,
  }));
}

/** Convert ChartDataContract back to Recharts wide format { x, [seriesKey]: number }. */
export function contractToRechartsWide(contract) {
  if (isLatestDataMode(contract) && (contract.series?.length || 0) <= 1) {
    return contractToSingleSeriesRows(contract).map((r) => ({ x: r.x, y: r.y }));
  }
  const seriesKeys = (contract.series || []).map((s) => s.key || s.id);
  const byPeriod = new Map();
  for (const pt of contract.data || []) {
    if (!byPeriod.has(pt.period)) {
      byPeriod.set(pt.period, { x: pt.period, period: pt.period });
    }
    const row = byPeriod.get(pt.period);
    const key = seriesKeys.find((k) => k === pt.series_id) || pt.series_id;
    row[key] = pt.value_raw;
  }
  return [...byPeriod.values()];
}

/** Convert ChartDataContract to Recharts series descriptor list. */
export function contractToRechartsSeries(contract) {
  return (contract.series || []).map((s, idx) => ({
    key: s.key || s.id || `series_${idx}`,
    name: s.label || s.name || `Řada ${idx + 1}`,
    color: s.color,
    axis: s.axis === "right" ? "right" : "left",
    yAxisId: s.axis === "right" ? "right" : "left",
  }));
}

/** Long-format rows for export. */
export function contractToLongRows(contract) {
  return (contract.data || []).map((pt) => ({
    period: pt.period,
    period_label: pt.period_label,
    geo: pt.geo,
    geo_label: pt.geo_label,
    series_id: pt.series_id,
    series_label: pt.series_label,
    value_raw: pt.value_raw,
    unit: pt.unit,
    frequency: pt.frequency,
    source: pt.source,
    dataset: pt.dataset,
    transformation: pt.transformation,
    chart_type: contract.chart_type,
  }));
}

/** Validate contract — value_raw must be numeric, no percent strings. */
export function validateChartContract(contract) {
  const errors = [];
  if (!contract?.chart_id) errors.push("missing chart_id");
  if (!contract?.chart_type) errors.push("missing chart_type");
  for (const pt of contract?.data || []) {
    if (pt.value_raw != null && typeof pt.value_raw !== "number") {
      errors.push(`non-numeric value_raw for ${pt.series_id}@${pt.period}`);
    }
    if (typeof pt.value_raw === "number" && !Number.isFinite(pt.value_raw)) {
      errors.push(`non-finite value_raw for ${pt.series_id}@${pt.period}`);
    }
  }
  return { ok: errors.length === 0, errors };
}

/** Detect distinct units across series — dual-axis only when >1 unit. */
export function distinctSeriesUnits(contract) {
  const units = new Set(
    (contract.series || [])
      .map((s) => String(s.unit || "").trim())
      .filter(Boolean)
  );
  return [...units];
}

export function shouldUseDualAxis(contract) {
  const units = distinctSeriesUnits(contract);
  if (units.length > 1) return true;
  return (contract.series || []).some((s) => s.axis === "right" || s.yAxisId === "right");
}
