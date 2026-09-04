/**
 * Chart transforms — wraps managerChartTransforms + chartAnalytics extensions.
 */

export {
  SUPPORTED_TRANSFORM_TYPES,
  normalizeTransformType,
  normalizeTransformWindow,
  applyTransformNone,
  applyIndexBase100,
  applyYoyChange,
  applyPeriodChange,
  applyRollingAverage,
  applySpread,
  applyRatio,
  applyPercentGap,
  applySeriesTransform,
} from "@/lib/managerChartTransforms";

import {
  applyIndexBase100,
  applyYoyChange,
  applyPeriodChange,
  applyRollingAverage,
  applySpread,
  applyRatio,
  applySeriesTransform,
} from "@/lib/managerChartTransforms";
import {
  indexSeriesTo100,
  rollingAverageSeries,
  rollingMedianSeries,
  spreadSeries,
  ratioSeries,
} from "./chartAnalytics";
import { CHART_TRANSFORM_TYPES } from "./chartTypes";

/** Map toolbar transform id to canonical type. */
export const TRANSFORM_ALIASES = Object.freeze({
  raw: CHART_TRANSFORM_TYPES.RAW,
  index_100: CHART_TRANSFORM_TYPES.INDEX_100,
  yoy: CHART_TRANSFORM_TYPES.YOY,
  mom: CHART_TRANSFORM_TYPES.MOM,
  qoq: CHART_TRANSFORM_TYPES.QOQ,
  rolling_average: CHART_TRANSFORM_TYPES.ROLLING_AVERAGE,
  rolling_median: CHART_TRANSFORM_TYPES.ROLLING_MEDIAN,
  spread: CHART_TRANSFORM_TYPES.SPREAD,
  ratio: CHART_TRANSFORM_TYPES.RATIO,
});

export function applyChartTransform(transformId, rows, options = {}) {
  const type = TRANSFORM_ALIASES[transformId] || transformId;
  switch (type) {
    case CHART_TRANSFORM_TYPES.RAW:
      return applySeriesTransform("none", rows, options);
    case CHART_TRANSFORM_TYPES.INDEX_100:
      return applyIndexBase100([rows], { basePeriod: options.basePeriod });
    case CHART_TRANSFORM_TYPES.YOY:
      return applyYoyChange(rows, options.freq);
    case CHART_TRANSFORM_TYPES.MOM:
    case CHART_TRANSFORM_TYPES.QOQ:
      return applyPeriodChange(rows);
    case CHART_TRANSFORM_TYPES.ROLLING_AVERAGE:
      return applyRollingAverage(rows, options.window ?? 12);
    case CHART_TRANSFORM_TYPES.ROLLING_MEDIAN: {
      const transformed = rollingMedianSeries(
        rows.map((r) => ({ x: r.x, y: r.y, period: r.x })),
        options.window ?? 12
      );
      if (transformed.length < 2) {
        return { ok: false, transformed_series: [], reason: "insufficient_points_for_rolling_median" };
      }
      return { ok: true, transformed_series: [transformed], reason: "ok", data_quality_notes: [`Klouzavý medián ${options.window ?? 12}`] };
    }
    case CHART_TRANSFORM_TYPES.SPREAD:
      return applySpread(rows, options.otherRows);
    case CHART_TRANSFORM_TYPES.RATIO:
      return applyRatio(rows, options.otherRows);
    default:
      return applySeriesTransform(type, rows, options);
  }
}

/** Apply transform to contract and return updated contract. */
export function applyTransformToContract(contract, transformId, options = {}) {
  const seriesList = contract.series || [];
  if (!seriesList.length) return contract;

  const primaryKey = seriesList[0].key || seriesList[0].id;
  const primaryRows = (contract.data || [])
    .filter((pt) => pt.series_id === primaryKey)
    .map((pt) => ({ x: pt.period, y: pt.value_raw, period: pt.period }));

  const otherKey = seriesList[1]?.key || seriesList[1]?.id;
  const otherRows = otherKey
    ? (contract.data || [])
        .filter((pt) => pt.series_id === otherKey)
        .map((pt) => ({ x: pt.period, y: pt.value_raw, period: pt.period }))
    : [];

  const result = applyChartTransform(transformId, primaryRows, {
    ...options,
    otherRows,
    freq: contract.metadata?.frequency,
  });

  if (!result.ok) return contract;

  const transformedRows = result.transformed_series[0] || [];
  const newData = transformedRows.map((r) => ({
    period: String(r.x ?? r.period),
    period_label: String(r.x ?? r.period),
    series_id: primaryKey,
    series_label: seriesList[0].label || seriesList[0].name,
    value_raw: Number(r.y),
    unit: seriesList[0].unit || "",
    transformation: TRANSFORM_ALIASES[transformId] || transformId,
    source: contract.source?.name || "",
    dataset: contract.metadata?.dataset || "",
  }));

  return {
    ...contract,
    data: newData,
    transformations: [...(contract.transformations || []), { type: transformId, at: new Date().toISOString() }],
    metadata: {
      ...contract.metadata,
      last_transform: transformId,
      data_quality_notes: result.data_quality_notes,
    },
  };
}

export { indexSeriesTo100, rollingAverageSeries, rollingMedianSeries, spreadSeries, ratioSeries };

/**
 * Jednotka řady po zobrazovací transformaci.
 *
 * Bez toho zůstávala v hlavičce exportu původní jednotka — po přepnutí na YoY nebo index=100
 * stálo v CSV i v XLSX například „Objem (mil. Kč)", ačkoli hodnoty byly procenta. Uživatel pak
 * exportovaná čísla přečetl ve špatné jednotce a neměl jak to poznat.
 */
export function unitAfterTransform(unit, transformId) {
  switch (String(transformId || "raw")) {
    case "mom":
    case "yoy":
      return "%";
    case "index_100":
      return "index (100 = první období)";
    default:
      return unit || "";
  }
}
