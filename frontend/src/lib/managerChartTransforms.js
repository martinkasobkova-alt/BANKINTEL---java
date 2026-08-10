import { compareChartPeriods } from "@/lib/exploreChartCompare";

export const SUPPORTED_TRANSFORM_TYPES = new Set([
  "none",
  "index_base_100",
  "yoy_change",
  "period_change",
  "rolling_average",
  "spread",
  "ratio",
  "percent_gap",
]);

/** Map legacy backend transform names to canonical types. */
export function normalizeTransformType(transformSpec) {
  const raw = String(transformSpec?.type || transformSpec?.transform || "none").trim().toLowerCase();
  const map = {
    none: "none",
    index_base_100: "index_base_100",
    yoy_change: "yoy_change",
    period_change: "period_change",
    rolling_average: "rolling_average",
    rolling_average_12: "rolling_average",
    rolling_average_6: "rolling_average",
    rolling_average_3: "rolling_average",
    spread: "spread",
    spread_with: "spread",
    ratio: "ratio",
    ratio_with: "ratio",
    percent_gap: "percent_gap",
    resample_to_common_frequency: "resample_to_common_frequency",
    lagged_comparison: "lagged_comparison",
  };
  return map[raw] || raw;
}

export function normalizeTransformWindow(transformSpec) {
  if (Number.isFinite(Number(transformSpec?.window))) return Number(transformSpec.window);
  const raw = String(transformSpec?.type || transformSpec?.transform || "").toLowerCase();
  const match = raw.match(/rolling_average_(\d+)/);
  if (match) return Number(match[1]);
  return 12;
}

function validRows(rows) {
  return (Array.isArray(rows) ? rows : [])
    .filter((row) => row && row.x != null && row.x !== "" && Number.isFinite(Number(row.y)))
    .map((row) => ({ x: String(row.x).trim(), y: Number(row.y) }));
}

function rowsToMap(rows) {
  const map = new Map();
  for (const row of validRows(rows)) map.set(row.x, row.y);
  return map;
}

export function commonSortedKeys(seriesRowLists) {
  const maps = seriesRowLists.map(rowsToMap);
  if (!maps.length) return [];
  let common = new Set(maps[0].keys());
  for (let i = 1; i < maps.length; i += 1) {
    common = new Set([...common].filter((key) => maps[i].has(key)));
  }
  return [...common].sort(compareChartPeriods);
}

function inferFrequency(keys) {
  const sample = keys.slice(0, 6);
  if (sample.some((k) => /^\d{4}-\d{2}$/.test(k))) return "monthly";
  if (sample.some((k) => /Q\d/i.test(k))) return "quarterly";
  return "annual";
}

function yoyLag(freq) {
  if (freq === "monthly") return 12;
  if (freq === "quarterly") return 4;
  return 1;
}

function fail(reason, notes = []) {
  return { ok: false, transformed_series: [], reason, data_quality_notes: notes };
}

function ok(rowsOrList, notes = []) {
  return {
    ok: true,
    transformed_series: Array.isArray(rowsOrList[0]) ? rowsOrList : [rowsOrList],
    reason: "ok",
    data_quality_notes: notes,
  };
}

export function applyTransformNone(rows) {
  const cleaned = validRows(rows);
  if (cleaned.length < 2) return fail("insufficient_points");
  return ok(cleaned);
}

export function applyIndexBase100(seriesRowLists, { basePeriod = "auto_common_start" } = {}) {
  const maps = seriesRowLists.map(rowsToMap);
  const common = commonSortedKeys(seriesRowLists);
  if (common.length < 2) return fail("missing_base_period");

  let baseKey = String(basePeriod || "").trim();
  if (!baseKey || baseKey === "auto_common_start") baseKey = common[0];
  if (!common.includes(baseKey)) return fail("missing_base_period");

  const transformed = [];
  for (const map of maps) {
    const baseValue = map.get(baseKey);
    if (baseValue == null || baseValue === 0 || !Number.isFinite(baseValue)) {
      return fail("zero_or_missing_base_value");
    }
    const rows = common.map((x) => ({
      x,
      y: (map.get(x) / baseValue) * 100,
    }));
    transformed.push(rows);
  }
  return ok(transformed, [`Index 100, báze ${baseKey}`]);
}

export function applyYoyChange(rows, freqHint = "") {
  const cleaned = validRows(rows);
  const keys = cleaned.map((r) => r.x);
  const freq = freqHint || inferFrequency(keys);
  const lag = yoyLag(freq);
  if (cleaned.length <= lag) return fail("insufficient_points_for_yoy");
  const out = [];
  for (let i = lag; i < cleaned.length; i += 1) {
    const prev = cleaned[i - lag].y;
    const cur = cleaned[i].y;
    if (prev === 0 || !Number.isFinite(prev)) return fail("zero_or_missing_base_value");
    out.push({ x: cleaned[i].x, y: ((cur - prev) / Math.abs(prev)) * 100 });
  }
  if (out.length < 2) return fail("insufficient_points_for_yoy");
  return ok(out);
}

export function applyPeriodChange(rows) {
  const cleaned = validRows(rows);
  if (cleaned.length < 3) return fail("insufficient_points_for_period_change");
  const out = [];
  for (let i = 1; i < cleaned.length; i += 1) {
    const prev = cleaned[i - 1].y;
    const cur = cleaned[i].y;
    if (prev === 0 || !Number.isFinite(prev)) return fail("zero_or_missing_base_value");
    out.push({ x: cleaned[i].x, y: ((cur - prev) / Math.abs(prev)) * 100 });
  }
  if (out.length < 2) return fail("insufficient_points_for_period_change");
  return ok(out);
}

export function applyRollingAverage(rows, window = 12) {
  const cleaned = validRows(rows);
  const size = Math.max(1, Number(window) || 12);
  if (cleaned.length < size + 1) return fail("insufficient_points_for_rolling_average");
  const out = [];
  for (let i = size - 1; i < cleaned.length; i += 1) {
    const slice = cleaned.slice(i - size + 1, i + 1);
    const avg = slice.reduce((sum, row) => sum + row.y, 0) / slice.length;
    out.push({ x: cleaned[i].x, y: avg });
  }
  if (out.length < 2) return fail("insufficient_points_for_rolling_average");
  return ok(out, [`Klouzavý průměr ${size}`]);
}

export function applySpread(seriesA, seriesB) {
  const mapA = rowsToMap(seriesA);
  const mapB = rowsToMap(seriesB);
  const common = [...mapA.keys()].filter((k) => mapB.has(k)).sort(compareChartPeriods);
  if (common.length < 2) return fail("insufficient_common_observations");
  const rows = common.map((x) => ({ x, y: mapA.get(x) - mapB.get(x) }));
  return ok(rows, ["Spread A − B"]);
}

export function applyRatio(seriesA, seriesB) {
  const mapA = rowsToMap(seriesA);
  const mapB = rowsToMap(seriesB);
  const common = [...mapA.keys()].filter((k) => mapB.has(k)).sort(compareChartPeriods);
  if (common.length < 2) return fail("insufficient_common_observations");
  const rows = [];
  for (const x of common) {
    const denom = mapB.get(x);
    if (denom === 0 || !Number.isFinite(denom)) return fail("zero_denominator");
    rows.push({ x, y: mapA.get(x) / denom });
  }
  return ok(rows, ["Ratio A / B"]);
}

export function applyPercentGap(seriesA, seriesB) {
  const mapA = rowsToMap(seriesA);
  const mapB = rowsToMap(seriesB);
  const common = [...mapA.keys()].filter((k) => mapB.has(k)).sort(compareChartPeriods);
  if (common.length < 2) return fail("insufficient_common_observations");
  const rows = [];
  for (const x of common) {
    const denom = mapB.get(x);
    if (denom === 0 || !Number.isFinite(denom)) return fail("zero_denominator");
    rows.push({ x, y: ((mapA.get(x) - denom) / Math.abs(denom)) * 100 });
  }
  return ok(rows, ["Percent gap (A−B)/|B|"]);
}

export function applySeriesTransform(type, rows, options = {}) {
  const transformType = normalizeTransformType({ type });
  switch (transformType) {
    case "none":
      return applyTransformNone(rows);
    case "index_base_100":
      return applyIndexBase100([rows], { basePeriod: options.basePeriod });
    case "yoy_change":
      return applyYoyChange(rows, options.freq);
    case "period_change":
      return applyPeriodChange(rows);
    case "rolling_average":
      return applyRollingAverage(rows, options.window);
    case "spread":
      return applySpread(rows, options.otherRows);
    case "ratio":
      return applyRatio(rows, options.otherRows);
    case "percent_gap":
      return applyPercentGap(rows, options.otherRows);
    default:
      return fail("unsupported_transform");
  }
}
