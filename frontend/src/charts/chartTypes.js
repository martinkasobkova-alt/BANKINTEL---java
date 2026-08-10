/** Canonical chart types and transform identifiers for the unified Chart System. */

export const CHART_TYPES = Object.freeze({
  LINE: "line",
  BAR: "bar",
  AREA: "area",
  SCATTER: "scatter",
  DOT: "dot",
  PIE: "pie",
  COMPOSED: "composed",
  GEO_MAP: "geo_map",
  PICTOGRAM: "pictogram",
  ICON_CHART: "icon_chart",
});

export const CHART_VIEW_MODES = Object.freeze({
  LINE: "line",
  BAR: "bar",
  SCATTER: "scatter",
  TABLE: "table",
});

export const CHART_TRANSFORM_TYPES = Object.freeze({
  RAW: "none",
  INDEX_100: "index_base_100",
  YOY: "yoy_change",
  MOM: "period_change",
  QOQ: "period_change",
  ROLLING_AVERAGE: "rolling_average",
  ROLLING_MEDIAN: "rolling_median",
  SPREAD: "spread",
  RATIO: "ratio",
  PERCENT_GAP: "percent_gap",
});

export const CHART_SIZE_VARIANTS = Object.freeze({
  COMPACT: "compact",
  STANDARD: "standard",
  ANALYTICAL: "analytical",
  FULLSCREEN: "fullscreen",
});

export const CHART_DATA_MODES = Object.freeze({
  TIME_SERIES: "time_series",
  LATEST: "latest",
  COMPARISON: "comparison",
});

/** Minimum periods required for transform eligibility. */
export const TRANSFORM_MIN_PERIODS = Object.freeze({
  yoy_change: 13,
  period_change: 3,
  rolling_average: 4,
  rolling_median: 4,
  index_base_100: 2,
  spread: 2,
  ratio: 2,
  correlation: 4,
});
