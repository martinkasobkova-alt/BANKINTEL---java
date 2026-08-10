import { createEmptyChartContract, normalizeChartPoint } from "@/charts/chartDataContract";

const CHART_VIEW_TYPES = new Set([
  "arad_view",
  "computed_view",
  "user_upload_chart",
  "formula_chart",
  "dataset_chart",
  "external_catalog_chart",
  "eurostat_view",
  "csu_view",
  "ecb_view",
  "fred_view",
  "alphavantage_view",
  "worldbank_view",
  "world_bank_data360_view",
  "bis_view",
  "imf_view",
  "oecd_view",
  "external_catalog_view",
]);

const X_KEY_CANDIDATES = [
  "period",
  "x",
  "date",
  "time",
  "year",
  "month",
  "quarter",
  "observation_date",
  "ref_period",
  "period_label",
];

const VALUE_KEY_CANDIDATES = ["value_raw", "value", "y", "result", "amount", "rate", "index"];
const NON_SERIES_KEYS = new Set([
  ...X_KEY_CANDIDATES,
  "id",
  "key",
  "label",
  "name",
  "title",
  "source",
  "dataset",
  "metadata",
  "unit",
  "frequency",
  "series_id",
  "series_label",
]);

const MAX_SERIES_PER_WIDGET = 12;
const MAX_POINTS_PER_CONTRACT = 8000;

function asString(value) {
  return String(value ?? "").trim();
}

function safeId(value, fallback = "series") {
  const text = asString(value)
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^A-Za-z0-9_-]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, 80);
  return text || fallback;
}

function toNumber(value) {
  if (value == null || value === "") return null;
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value === "string") {
    const normalized = value.replace(/\s+/g, "").replace(",", ".");
    const parsed = Number(normalized);
    return Number.isFinite(parsed) ? parsed : null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function getWidgetViewType(widget) {
  return asString(widget?.engine_type || widget?.type).toLowerCase();
}

function getWidgetTitle(widget, fallback = "Graf") {
  return asString(widget?.title || widget?.config?.title || widget?.data?.title || fallback);
}

function getWidgetGraphIndex(widget) {
  const index = Number(widget?.__dashboard_graph_index);
  return Number.isFinite(index) && index > 0 ? Math.floor(index) : null;
}

function labelWithGraphIndex(widget, label) {
  const clean = asString(label);
  const index = getWidgetGraphIndex(widget);
  if (!index || clean.startsWith(`Graf ${index}:`)) return clean;
  return `Graf ${index}: ${clean || "Graf"}`;
}

function getWidgetData(widget) {
  const data = widget?.data;
  return data && typeof data === "object" ? data : null;
}

function isPrivateWidget(widget) {
  const data = getWidgetData(widget) || {};
  const config = widget?.config || {};
  const viewType = getWidgetViewType(widget);
  const sourceType = asString(config.source_type || data.source_type || data.kind).toLowerCase();
  const privacy = asString(config.privacy || data.privacy || data.privacy_mode).toLowerCase();
  return (
    viewType === "user_upload_chart" ||
    widget?.type === "uploaded_data_chart" ||
    ["private", "confidential", "strict_private"].includes(privacy) ||
    ["user_upload", "uploaded_data_chart", "user_upload_chart", "file_upload", "private", "company"].includes(sourceType) ||
    Boolean(config.user_upload_id || config.upload_id || config.file_upload_id || data.user_upload_id || data.upload_id)
  );
}

function isChartLikeWidget(widget) {
  if (!widget || widget._loading) return false;
  const viewType = getWidgetViewType(widget);
  const data = getWidgetData(widget);
  if (CHART_VIEW_TYPES.has(viewType)) return true;
  if (data?.view === "chart") return true;
  if (Array.isArray(data?.rows) || Array.isArray(data?.records) || Array.isArray(data?.series)) return true;
  return false;
}

export function getPresentableDashboardWidgets(widgets = []) {
  return (Array.isArray(widgets) ? widgets : []).filter((widget) => {
    if (!isChartLikeWidget(widget)) return false;
    const viewType = getWidgetViewType(widget);
    return viewType !== "ad" && viewType !== "markdown" && widget?.type !== "text";
  });
}

function foldText(value) {
  return asString(value)
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[–—]/g, "-");
}

function addGraphNumber(out, raw, maxCount) {
  const n = Number(raw);
  if (!Number.isInteger(n) || n < 1) return;
  if (Number.isFinite(maxCount) && n > maxCount) return;
  out.add(n);
}

function addGraphRange(out, rawA, rawB, maxCount) {
  const a = Number(rawA);
  const b = Number(rawB);
  if (!Number.isInteger(a) || !Number.isInteger(b)) return;
  const from = Math.min(a, b);
  const to = Math.max(a, b);
  if (to - from > 12) return;
  for (let n = from; n <= to; n += 1) addGraphNumber(out, n, maxCount);
}

export function parseDashboardWidgetReferenceNumbers(question, maxCount = Infinity) {
  const q = foldText(question);
  if (!q) return [];
  const out = new Set();
  const subject =
    "(?:graf(?:u|em|y|ech|e)?|slide(?:u|m|ch|y)?|slid(?:u|em|y|ech)?|snimek|snimku|snimkem|snimky)";
  const direct = new RegExp(`${subject}\\s*(?:c(?:islo)?\\.?\\s*)?(\\d{1,3})(?:\\s*(?:a|i|,|;|\\/|nebo)\\s*(\\d{1,3}))?`, "g");
  let match;
  while ((match = direct.exec(q))) {
    addGraphNumber(out, match[1], maxCount);
    if (match[2]) addGraphNumber(out, match[2], maxCount);
  }

  const range = new RegExp(`${subject}\\s*(?:c(?:islo)?\\.?\\s*)?(\\d{1,3})\\s*(?:-|az|až)\\s*(\\d{1,3})`, "g");
  while ((match = range.exec(q))) {
    addGraphRange(out, match[1], match[2], maxCount);
  }

  if (out.size === 0 && new RegExp(subject).test(q)) {
    const between = /mezi\s+(\d{1,3})\s*(?:a|i|,|;|\/|nebo|-|az|až)\s*(\d{1,3})/g;
    while ((match = between.exec(q))) {
      if (match[0].includes("-") || /\baz\b|až/.test(match[0])) {
        addGraphRange(out, match[1], match[2], maxCount);
      } else {
        addGraphNumber(out, match[1], maxCount);
        addGraphNumber(out, match[2], maxCount);
      }
    }
  }

  return [...out].sort((a, b) => a - b);
}

function rowsFromWidget(widget) {
  const data = getWidgetData(widget);
  if (!data) return [];
  if (Array.isArray(data.rows)) return data.rows;
  if (Array.isArray(data.records)) return data.records;
  if (Array.isArray(data.values)) return data.values;
  if (Array.isArray(data.data)) return data.data;
  if (Array.isArray(data)) return data;
  return [];
}

function inferXKey(rows) {
  const sample = rows.find((row) => row && typeof row === "object" && !Array.isArray(row));
  if (!sample) return "";
  const keys = Object.keys(sample);
  for (const candidate of X_KEY_CANDIDATES) {
    const hit = keys.find((key) => key.toLowerCase() === candidate);
    if (hit) return hit;
  }
  return keys.find((key) => toNumber(sample[key]) == null) || keys[0] || "";
}

function inferValueKey(rows) {
  const sample = rows.find((row) => row && typeof row === "object" && !Array.isArray(row));
  if (!sample) return "";
  const keys = Object.keys(sample);
  for (const candidate of VALUE_KEY_CANDIDATES) {
    const hit = keys.find((key) => key.toLowerCase() === candidate && toNumber(sample[key]) != null);
    if (hit) return hit;
  }
  return keys.find((key) => !NON_SERIES_KEYS.has(key.toLowerCase()) && toNumber(sample[key]) != null) || "";
}

function inferWideValueKeys(rows, xKey) {
  const counts = new Map();
  for (const row of rows.slice(0, 50)) {
    if (!row || typeof row !== "object" || Array.isArray(row)) continue;
    for (const key of Object.keys(row)) {
      if (key === xKey || NON_SERIES_KEYS.has(key.toLowerCase())) continue;
      if (toNumber(row[key]) == null) continue;
      counts.set(key, (counts.get(key) || 0) + 1);
    }
  }
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, MAX_SERIES_PER_WIDGET)
    .map(([key]) => key);
}

function normalizeSeriesArray(widget) {
  const data = getWidgetData(widget);
  const sourceSeries = Array.isArray(data?.series)
    ? data.series
    : Array.isArray(data?.datasets)
      ? data.datasets
      : [];
  if (!sourceSeries.length) return null;

  const widgetTitle = getWidgetTitle(widget);
  const widgetId = safeId(widget?.id || widgetTitle, "widget");
  const privateWidget = isPrivateWidget(widget);
  const out = { series: [], points: [] };

  sourceSeries.slice(0, MAX_SERIES_PER_WIDGET).forEach((series, idx) => {
    if (!series || typeof series !== "object") return;
    const sid = `${widgetId}_${safeId(series.id || series.key || series.label || series.name || idx)}`;
    const label = labelWithGraphIndex(widget, series.label || series.name || series.title || `${widgetTitle} ${idx + 1}`);
    const unit = asString(series.unit || data?.unit || widget?.config?.unit);
    const frequency = asString(series.frequency || data?.frequency || widget?.config?.frequency);
    const privateSeries = privateWidget || ["private", "confidential", "strict_private"].includes(asString(series.privacy).toLowerCase());
    out.series.push({
      id: sid,
      key: sid,
      label,
      unit,
      frequency,
      privacy: privateSeries ? "private" : "public",
      source_type: privateSeries ? "user_upload" : asString(series.source_type || data?.source_type),
      user_upload_id: asString(series.user_upload_id || widget?.config?.user_upload_id || data?.user_upload_id),
      metadata: {
        widget_id: widget?.id || "",
        dashboard_graph_index: getWidgetGraphIndex(widget),
      },
    });

    const points = Array.isArray(series.data) ? series.data : Array.isArray(series.points) ? series.points : [];
    for (const raw of points) {
      const row = Array.isArray(raw) ? { x: raw[0], y: raw[1] } : raw;
      if (!row || typeof row !== "object") continue;
      const period = asString(row.period || row.x || row.date || row.time);
      const value = toNumber(row.value_raw ?? row.value ?? row.y);
      if (!period || value == null) continue;
      out.points.push(
        normalizeChartPoint({
          period,
          series_id: sid,
          series_label: label,
          value_raw: value,
          unit,
          frequency,
          source: asString(row.source || data?.source || widget?.config?.source_label),
          dataset: asString(row.dataset || data?.dataset || widget?.config?.dataset),
          metadata: {
            dashboard_graph_index: getWidgetGraphIndex(widget),
            widget_id: widget?.id || "",
            privacy: privateSeries ? "private" : "public",
            source_type: privateSeries ? "user_upload" : asString(series.source_type || data?.source_type),
            user_upload_id: asString(series.user_upload_id || widget?.config?.user_upload_id || data?.user_upload_id),
          },
        })
      );
    }
  });

  return out.points.length ? out : null;
}

function normalizeRows(widget) {
  const rows = rowsFromWidget(widget).filter((row) => row && typeof row === "object");
  if (!rows.length) return null;

  const widgetTitle = labelWithGraphIndex(widget, getWidgetTitle(widget));
  const widgetId = safeId(widget?.id || widgetTitle, "widget");
  const data = getWidgetData(widget) || {};
  const xKey = inferXKey(rows);
  const valueKey = inferValueKey(rows);
  const hasLongSeries = rows.some((row) => asString(row.series_id || row.series_label));
  const privateWidget = isPrivateWidget(widget);
  const sourceType = privateWidget ? "user_upload" : asString(widget?.config?.source_type || data.source_type);
  const uploadId = asString(widget?.config?.user_upload_id || data.user_upload_id || data.upload_id);
  const unit = asString(data.unit || widget?.config?.unit);
  const frequency = asString(data.frequency || widget?.config?.frequency);
  const out = { series: [], points: [] };
  const seriesById = new Map();

  const addSeries = (rawId, rawLabel, rawUnit = unit) => {
    const sid = `${widgetId}_${safeId(rawId || rawLabel || "main")}`;
    if (!seriesById.has(sid)) {
      const label = labelWithGraphIndex(widget, rawLabel || widgetTitle);
      const meta = {
        id: sid,
        key: sid,
        label,
        unit: rawUnit,
        frequency,
        privacy: privateWidget ? "private" : "public",
        source_type: sourceType,
        user_upload_id: uploadId,
      };
      seriesById.set(sid, meta);
      out.series.push(meta);
    }
    return seriesById.get(sid);
  };

  if (hasLongSeries && valueKey) {
    for (const row of rows) {
      const period = asString(row[xKey] ?? row.period ?? row.x ?? row.date);
      const value = toNumber(row[valueKey]);
      if (!period || value == null) continue;
      const meta = addSeries(row.series_id || row.series_label, row.series_label || row.series_id || widgetTitle, row.unit || unit);
      out.points.push(
        normalizeChartPoint({
          period,
          series_id: meta.id,
          series_label: meta.label,
          value_raw: value,
          unit: meta.unit,
          frequency,
          source: asString(row.source || data.source || widget?.config?.source_label),
          dataset: asString(row.dataset || data.dataset || widget?.config?.dataset),
        metadata: {
          dashboard_graph_index: getWidgetGraphIndex(widget),
          widget_id: widget?.id || "",
          privacy: privateWidget ? "private" : "public",
          source_type: sourceType,
            user_upload_id: uploadId,
          },
        })
      );
    }
    return out.points.length ? out : null;
  }

  const valueKeys = valueKey && VALUE_KEY_CANDIDATES.includes(valueKey.toLowerCase())
    ? [valueKey]
    : inferWideValueKeys(rows, xKey);
  const finalValueKeys = valueKeys.length ? valueKeys : valueKey ? [valueKey] : [];
  for (const key of finalValueKeys.slice(0, MAX_SERIES_PER_WIDGET)) {
    const label = finalValueKeys.length > 1 ? `${widgetTitle}: ${key}` : widgetTitle;
    const meta = addSeries(key, label, unit);
    for (const row of rows) {
      const period = asString(row[xKey] ?? row.period ?? row.x ?? row.date);
      const value = toNumber(row[key]);
      if (!period || value == null) continue;
      out.points.push(
        normalizeChartPoint({
          period,
          series_id: meta.id,
          series_label: meta.label,
          value_raw: value,
          unit: meta.unit,
          frequency,
          source: asString(row.source || data.source || widget?.config?.source_label),
          dataset: asString(row.dataset || data.dataset || widget?.config?.dataset),
        metadata: {
          dashboard_graph_index: getWidgetGraphIndex(widget),
          widget_id: widget?.id || "",
          privacy: privateWidget ? "private" : "public",
          source_type: sourceType,
            user_upload_id: uploadId,
          },
        })
      );
    }
  }

  return out.points.length ? out : null;
}

function extractWidgetContractParts(widget) {
  return normalizeSeriesArray(widget) || normalizeRows(widget);
}

export function buildDashboardChartContract(widgets = [], options = {}) {
  const selected = getPresentableDashboardWidgets(widgets);
  const series = [];
  const data = [];
  let privateSeriesCount = 0;

  for (const widget of selected) {
    const parts = extractWidgetContractParts(widget);
    if (!parts?.points?.length) continue;
    series.push(...parts.series);
    data.push(...parts.points);
  }

  const limitedData = data.slice(0, MAX_POINTS_PER_CONTRACT);
  const usedSeriesIds = new Set(limitedData.map((point) => point.series_id));
  const limitedSeries = series.filter((item) => usedSeriesIds.has(item.id));
  privateSeriesCount = limitedSeries.filter((item) => item.privacy === "private").length;

  return createEmptyChartContract({
    chart_id: `dashboard_${safeId(options.pageId || options.pageTitle || "page")}_${Date.now()}`,
    chart_type: "line",
    title: asString(options.title || options.pageTitle || "Dashboard"),
    subtitle: selected.length > 1 ? `${selected.length} grafu v dashboardu` : "",
    series: limitedSeries,
    data: limitedData,
    metadata: {
      data_mode: "time_series",
      widget_count: selected.length,
      readable_series_count: limitedSeries.length,
      readable_point_count: limitedData.length,
      truncated: data.length > limitedData.length,
      privacy_mode: privateSeriesCount ? "strict_private" : "public",
      contains_private_series: privateSeriesCount > 0,
      private_series_count: privateSeriesCount,
      dashboard_scope: asString(options.scope || "dashboard"),
    },
    source: { name: "Osobni dashboard", dataset: asString(options.pageTitle || "") },
  });
}
