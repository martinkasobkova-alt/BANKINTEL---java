/**
 * Převod aktuálního stavu AradView na ChartDataContract pro sdílený export.
 */

import {
  createEmptyChartContract,
  normalizeChartPoint,
  isLatestDataMode as contractIsLatest,
} from "./chartDataContract";
import { CHART_DATA_MODES, CHART_TYPES } from "./chartTypes";
import { resolveNativeFrequencyCode } from "@/lib/chartFrequencyInfer";
import { unitAfterTransform } from "./chartTransforms";
import {
  normalizeExportPeriod,
  resolveExportFrequency,
  sanitizeExportValueRaw,
} from "./chartExportSanitize";

function resolveSourceMeta(data = {}, widget = {}) {
  const cfg = widget?.config || {};
  const dataset = String(data?.dataset || cfg?.dataset || "").trim();
  const source =
    String(data?.source || cfg?.source || cfg?.source_label || widget?.source || "").trim() ||
    (dataset.startsWith("catalog:") ? "Katalog" : "");
  return { source, dataset };
}

function isPrivateSeriesLike(obj = {}) {
  const meta = obj?.metadata && typeof obj.metadata === "object" ? obj.metadata : {};
  const privacy = String(
    obj?.privacy || obj?.privacy_level || obj?.privacy_mode || meta.privacy || meta.privacy_level || meta.privacy_mode || ""
  ).toLowerCase();
  if (["private", "confidential", "strict_private"].includes(privacy)) return true;
  const sourceType = String(
    obj?.source_type || obj?.kind || obj?.engine_type || obj?.type || meta.source_type || meta.kind || ""
  ).toLowerCase();
  if (["user_upload", "uploaded_data_chart", "user_upload_chart", "file_upload", "private", "company"].includes(sourceType)) {
    return true;
  }
  return Boolean(obj?.user_upload_id || obj?.upload_id || obj?.file_upload_id || meta.user_upload_id);
}

function resolveWidgetPrivateMeta(widget = {}, data = {}) {
  const cfg = widget?.config || {};
  const engine = String(widget?.engine_type || widget?.type || "").toLowerCase();
  const sourceType = String(cfg.source_type || cfg.catalog || data?.source_type || "").toLowerCase();
  const uploadId = String(cfg.user_upload_id || cfg.upload_id || cfg.file_upload_id || data?.user_upload_id || "").trim();
  const privateSource = engine === "user_upload_chart" || sourceType === "user_upload" || sourceType === "file_upload" || Boolean(uploadId);
  return {
    isPrivate: privateSource || isPrivateSeriesLike(data),
    sourceType: privateSource ? "user_upload" : sourceType,
    uploadId,
  };
}

function mapChartKind(kind) {
  const k = String(kind || "line").toLowerCase();
  if (k === "pie") return CHART_TYPES.PIE;
  if (k === "bar") return CHART_TYPES.BAR;
  if (k === "area") return CHART_TYPES.AREA;
  return CHART_TYPES.LINE;
}

/**
 * @param {Object} params
 * @param {string} params.title
 * @param {string} [params.subtitle]
 * @param {string} [params.unit]
 * @param {string} params.chartKind
 * @param {boolean} params.latestDataMode
 * @param {boolean} params.isMultiSeries
 * @param {Array} params.seriesList
 * @param {Array} params.chartRows — aktuálně zobrazené řádky (po filtru/zoom/transformaci)
 * @param {Object} [params.data]
 * @param {Object} [params.widget]
 * @param {string} [params.frequency]
 * @param {string} [params.transformId]
 */
export function contractFromAradViewState({
  title = "",
  subtitle = "",
  unit = "",
  chartKind = "line",
  latestDataMode = false,
  isMultiSeries = false,
  seriesList = [],
  chartRows = [],
  data = {},
  widget = {},
  frequency = "",
  transformId = "raw",
} = {}) {
  const { source, dataset } = resolveSourceMeta(data, widget);
  const chartId = `arad_${widget?.id || data?.indicator_id || "view"}_${Date.now()}`;
  const dataMode = latestDataMode ? CHART_DATA_MODES.LATEST : CHART_DATA_MODES.TIME_SERIES;
  const widgetPrivacy = resolveWidgetPrivateMeta(widget, data);

  const periodRows = (chartRows || [])
    .map((r) => ({ period: String(r?.x ?? r?.period ?? "").trim() }))
    .filter((r) => r.period);
  const nativeFreq = resolveNativeFrequencyCode({
    explicitFrequency: frequency || data?.frequency,
    rows: periodRows,
    isMultiSeries,
  });

  const series = isMultiSeries
    ? seriesList.map((s, idx) => ({
        id: String(s.key || s.indicator_id || `series_${idx}`),
        key: String(s.key || s.indicator_id || `series_${idx}`),
        label: String(s.name || s.indicator_id || s.key || `Řada ${idx + 1}`),
        unit: unitAfterTransform(String(s.unit || unit || "").trim(), transformId),
        axis: String(s.y_axis || "").toLowerCase() === "right" ? "right" : "left",
        source_type: String(s.source_type || "").trim(),
        privacy: isPrivateSeriesLike(s) ? "private" : String(s.privacy || "").trim(),
        user_upload_id: String(s.user_upload_id || "").trim(),
      }))
    : [
        {
          id: "main",
          key: "main",
          label: title || "Hodnota",
          unit: unitAfterTransform(String(unit || "").trim(), transformId),
          source_type: widgetPrivacy.sourceType || "",
          privacy: widgetPrivacy.isPrivate ? "private" : "",
          user_upload_id: widgetPrivacy.uploadId,
        },
      ];
  const containsPrivateSeries = widgetPrivacy.isPrivate || series.some((s) => isPrivateSeriesLike(s));

  const points = [];

  if (isMultiSeries) {
    for (const row of chartRows || []) {
      const rawPeriod = String(row?.x ?? row?.period ?? "").trim();
      if (!rawPeriod) continue;
      const { period, period_label } = normalizeExportPeriod(rawPeriod);
      if (!period) continue;
      for (const s of series) {
        const val = sanitizeExportValueRaw(row[s.key], {
          period,
          series_id: s.id,
        });
        if (val == null || !Number.isFinite(val)) continue;
        points.push(
          normalizeChartPoint({
            period,
            period_label,
            series_id: s.id,
            series_label: s.label,
            value_raw: val,
            unit: unitAfterTransform(s.unit || unit, transformId),
            frequency: nativeFreq,
            source,
            dataset,
            transformation: transformId === "raw" ? "none" : transformId,
            metadata: {
              privacy: isPrivateSeriesLike(s) ? "private" : "public",
              source_type: s.source_type || "",
              user_upload_id: s.user_upload_id || "",
            },
          })
        );
      }
    }
  } else {
    for (const row of chartRows || []) {
      const rawPeriod = String(row?.x ?? row?.period ?? "").trim();
      const { period, period_label } = normalizeExportPeriod(rawPeriod);
      const val = sanitizeExportValueRaw(row?.y ?? row?.value, { period, series_id: "main" });
      if (!period || val == null || !Number.isFinite(val)) continue;
      points.push(
        normalizeChartPoint({
          period,
          period_label,
          geo_label: latestDataMode ? period_label : "",
          series_id: "main",
          series_label: title || "Hodnota",
          value_raw: val,
          unit: unitAfterTransform(unit, transformId),
          frequency: nativeFreq,
          source,
          dataset,
          transformation: transformId === "raw" ? "none" : transformId,
          metadata: {
            privacy: widgetPrivacy.isPrivate ? "private" : "public",
            source_type: widgetPrivacy.sourceType || "",
            user_upload_id: widgetPrivacy.uploadId || "",
          },
        })
      );
    }
  }

  const resolvedFrequency = resolveExportFrequency(
    nativeFreq,
    points.map((p) => p.period)
  );
  for (const pt of points) {
    pt.frequency = resolvedFrequency || pt.frequency;
  }

  const contract = createEmptyChartContract({
    chart_id: chartId,
    chart_type: mapChartKind(chartKind),
    title,
    subtitle,
    series,
    data: points,
    metadata: {
      data_mode: dataMode,
      latest_mode: latestDataMode,
      frequency: resolvedFrequency || nativeFreq,
      source,
      dataset,
      widget_id: widget?.id || null,
      widget_type: widget?.engine_type || widget?.type || "",
      privacy_mode: containsPrivateSeries ? "strict_private" : "public",
      contains_private_series: containsPrivateSeries,
      private_series_count: containsPrivateSeries ? series.filter((s) => isPrivateSeriesLike(s)).length || 1 : 0,
    },
    source: { name: source, dataset },
    transformations:
      transformId && transformId !== "raw"
        ? [{ type: transformId, at: new Date().toISOString() }]
        : [],
  });

  return contract;
}

export function validateAradViewContract(contract) {
  const errors = [];
  if (!contract?.data?.length) errors.push("empty_data");
  for (const pt of contract?.data || []) {
    if (pt.value_raw != null && typeof pt.value_raw !== "number") {
      errors.push(`non_numeric:${pt.period}/${pt.series_id}`);
    }
    for (const [k, v] of Object.entries(pt)) {
      if (k === "metadata") continue;
      if (v != null && typeof v === "object") errors.push(`nested:${k}`);
    }
  }
  return { ok: errors.length === 0, errors };
}

/** Alias pro testy — detekce latest režimu z contractu. */
export { contractIsLatest as isLatestDataMode };
