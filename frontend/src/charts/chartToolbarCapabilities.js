/**
 * Toolbar capability resolver — only offer actions that make sense for data.
 */

import { CHART_TRANSFORM_TYPES, CHART_TYPES, TRANSFORM_MIN_PERIODS } from "./chartTypes";
import { distinctSeriesUnits } from "./chartDataContract";
import { parseChartPeriod } from "@/lib/chartPeriodParse";

export const TOOLBAR_GROUPS = Object.freeze({
  VIEW: "view",
  TRANSFORM: "transform",
  ANALYTICS: "analytics",
  EXPORT: "export",
  DISPLAY: "display",
});

export const TOOLBAR_ACTIONS = Object.freeze({
  LINE: "line",
  BAR: "bar",
  SCATTER: "scatter",
  TABLE: "table",
  RAW: "raw",
  INDEX_100: "index_100",
  YOY: "yoy",
  MOM: "mom",
  QOQ: "qoq",
  ROLLING_AVERAGE: "rolling_average",
  ROLLING_MEDIAN: "rolling_median",
  SPREAD: "spread",
  RATIO: "ratio",
  MEAN: "mean",
  MEDIAN: "median",
  TREND: "trend",
  CORRELATION: "correlation",
  VOLATILITY: "volatility",
  OUTLIERS: "outliers",
  CSV: "csv",
  XLSX: "xlsx",
  PNG: "png",
  CLIPBOARD: "clipboard",
  LEGEND: "legend",
  DATA_TABLE: "data_table",
  FULLSCREEN: "fullscreen",
  HIGHLIGHT_LATEST: "highlight_latest",
  HIGHLIGHT_EXTREMA: "highlight_extrema",
});

function countPeriods(contract) {
  return new Set((contract.data || []).map((pt) => pt.period)).size;
}

function countNumericSeries(contract) {
  return (contract.series || []).length;
}

/**
 * Časová řada = většina období jde přeložit na datum.
 *
 * Dřív se testoval jen tvar `^\d{4}` / `Q\d`, což vyřadilo česká období z ČSÚ
 * („prosinec 2024“) — řada se pak nevyhodnotila jako časová a v liště zůstalo
 * jen `raw`, takže se celá skryla. `parseChartPeriod` česká období umí.
 *
 * Práh je potřeba proto, že `parseChartPeriod` vrátí datum pro cokoli se čtyřmi
 * číslicemi; bez něj by se jako časová řada tvářily i kategorie („Praha 2020“).
 */
function isTimeSeries(contract) {
  const mode = contract.metadata?.data_mode;
  if (mode === "latest") return false;
  const periods = [...new Set((contract.data || []).map((pt) => String(pt.period ?? "").trim()))].filter(Boolean);
  if (periods.length < 2) return false;
  const parsed = periods.filter((p) => parseChartPeriod(p) instanceof Date).length;
  return parsed >= Math.ceil(periods.length * 0.8);
}

export function resolveToolbarCapabilities(contract, { chartType = CHART_TYPES.LINE } = {}) {
  const periods = countPeriods(contract);
  const seriesCount = countNumericSeries(contract);
  const timeSeries = isTimeSeries(contract);
  const units = distinctSeriesUnits(contract);
  const multiSeries = seriesCount >= 2;

  const view = [TOOLBAR_ACTIONS.LINE, TOOLBAR_ACTIONS.TABLE];
  if (chartType !== CHART_TYPES.PIE && !contract.metadata?.latest_mode) {
    view.push(TOOLBAR_ACTIONS.BAR);
  }
  if (multiSeries && seriesCount >= 2) {
    view.push(TOOLBAR_ACTIONS.SCATTER);
  }

  const transform = [TOOLBAR_ACTIONS.RAW];
  if (timeSeries && periods >= TRANSFORM_MIN_PERIODS.index_base_100) {
    transform.push(TOOLBAR_ACTIONS.INDEX_100);
  }
  if (timeSeries && periods >= TRANSFORM_MIN_PERIODS.yoy_change) {
    transform.push(TOOLBAR_ACTIONS.YOY);
  }
  if (timeSeries && periods >= TRANSFORM_MIN_PERIODS.period_change) {
    transform.push(TOOLBAR_ACTIONS.MOM, TOOLBAR_ACTIONS.QOQ);
  }
  if (timeSeries && periods >= TRANSFORM_MIN_PERIODS.rolling_average) {
    transform.push(TOOLBAR_ACTIONS.ROLLING_AVERAGE, TOOLBAR_ACTIONS.ROLLING_MEDIAN);
  }
  if (multiSeries && periods >= TRANSFORM_MIN_PERIODS.spread) {
    transform.push(TOOLBAR_ACTIONS.SPREAD, TOOLBAR_ACTIONS.RATIO);
  }

  const analytics = [TOOLBAR_ACTIONS.MEAN, TOOLBAR_ACTIONS.MEDIAN, TOOLBAR_ACTIONS.TREND];
  if (multiSeries && periods >= TRANSFORM_MIN_PERIODS.correlation) {
    analytics.push(TOOLBAR_ACTIONS.CORRELATION);
  }
  if (timeSeries && periods >= 4) {
    analytics.push(TOOLBAR_ACTIONS.VOLATILITY, TOOLBAR_ACTIONS.OUTLIERS);
  }

  const exportActions = [
    TOOLBAR_ACTIONS.CSV,
    TOOLBAR_ACTIONS.XLSX,
    TOOLBAR_ACTIONS.PNG,
    TOOLBAR_ACTIONS.CLIPBOARD,
  ];

  const display = [
    TOOLBAR_ACTIONS.LEGEND,
    TOOLBAR_ACTIONS.DATA_TABLE,
    TOOLBAR_ACTIONS.FULLSCREEN,
    TOOLBAR_ACTIONS.HIGHLIGHT_LATEST,
    TOOLBAR_ACTIONS.HIGHLIGHT_EXTREMA,
  ];

  return {
    view,
    transform,
    analytics,
    export: exportActions,
    display,
    dualAxisAllowed: units.length > 1,
    dualAxisAuto: units.length > 1,
    scatterAllowed: multiSeries && seriesCount >= 2,
    correlationAllowed: multiSeries && periods >= TRANSFORM_MIN_PERIODS.correlation,
    yoyAllowed: timeSeries && periods >= TRANSFORM_MIN_PERIODS.yoy_change,
  };
}

export function isToolbarActionAllowed(capabilities, action) {
  const all = [
    ...capabilities.view,
    ...capabilities.transform,
    ...capabilities.analytics,
    ...capabilities.export,
    ...capabilities.display,
  ];
  return all.includes(action);
}

/** Map transform action to contract transform type. */
export function actionToTransformType(action) {
  const map = {
    [TOOLBAR_ACTIONS.RAW]: CHART_TRANSFORM_TYPES.RAW,
    [TOOLBAR_ACTIONS.INDEX_100]: CHART_TRANSFORM_TYPES.INDEX_100,
    [TOOLBAR_ACTIONS.YOY]: CHART_TRANSFORM_TYPES.YOY,
    [TOOLBAR_ACTIONS.MOM]: CHART_TRANSFORM_TYPES.MOM,
    [TOOLBAR_ACTIONS.QOQ]: CHART_TRANSFORM_TYPES.QOQ,
    [TOOLBAR_ACTIONS.ROLLING_AVERAGE]: CHART_TRANSFORM_TYPES.ROLLING_AVERAGE,
    [TOOLBAR_ACTIONS.ROLLING_MEDIAN]: CHART_TRANSFORM_TYPES.ROLLING_MEDIAN,
    [TOOLBAR_ACTIONS.SPREAD]: CHART_TRANSFORM_TYPES.SPREAD,
    [TOOLBAR_ACTIONS.RATIO]: CHART_TRANSFORM_TYPES.RATIO,
  };
  return map[action] || null;
}
