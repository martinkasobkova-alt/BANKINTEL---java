const MULTI_SERIES_CHART_TYPES = new Set([
  "dual_axis_line",
  "indexed_line",
  "composite_line",
  "company_vs_sector",
  "relationship_line",
  "correlation_scatter",
  "correlation_heatmap",
]);

export const SCATTER_CHART_TYPES = new Set(["scatter", "correlation_scatter"]);

function foldTransformName(transform) {
  return String(transform || "").trim().toLowerCase();
}

export function isPrecomputedSpread(spec) {
  const transforms = Array.isArray(spec?.transforms) ? spec.transforms : [];
  if (spec?.precomputed_spread === true || spec?.metadata?.spread_precomputed === true) return true;
  if (transforms.some((t) => {
    const tr = foldTransformName(t?.type || t?.transform);
    return tr === "spread_precomputed" || tr === "precomputed_spread";
  })) {
    return true;
  }
  const ids = Array.isArray(spec?.series_ids) ? spec.series_ids : [];
  const hasSpreadWith = transforms.some((t) => {
    const tr = foldTransformName(t?.type || t?.transform);
    return tr === "spread" || tr === "spread_with";
  });
  return ids.length <= 1 && !hasSpreadWith;
}

export function isPrecomputedRatio(spec) {
  const transforms = Array.isArray(spec?.transforms) ? spec.transforms : [];
  if (transforms.some((t) => {
    const tr = foldTransformName(t?.type || t?.transform);
    return tr === "ratio" || tr === "ratio_with";
  }) && transforms.length === 1) {
    return false;
  }
  const ids = Array.isArray(spec?.series_ids) ? spec.series_ids : [];
  return ids.length <= 1;
}

export function chartTypeRequiresMultipleSeries(chartType, spec = {}) {
  const type = String(chartType || "").trim().toLowerCase();
  if (MULTI_SERIES_CHART_TYPES.has(type)) return true;
  if (SCATTER_CHART_TYPES.has(type)) return true;
  if (type === "spread_line") return !isPrecomputedSpread(spec);
  if (type === "ratio_line") return !isPrecomputedRatio(spec);
  if (spec?.relationship_id && (Array.isArray(spec.series_ids) ? spec.series_ids.length : 0) > 1) {
    return true;
  }
  return false;
}

export function chartTypeRequiresMultipleRenderSeries(chartType, spec = {}) {
  const type = String(chartType || "").trim().toLowerCase();
  if (type === "spread_line" || type === "ratio_line") return false;
  return chartTypeRequiresMultipleSeries(type, spec);
}
