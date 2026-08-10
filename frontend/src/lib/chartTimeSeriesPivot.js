/**
 * Časové řady v náhledu dat — pivot podle kategorické dimenze místo součtu.
 * Bezpečný default: rozdělit podle dimenze, nesčítat (ROE, %, indexy, …).
 */

import { isPercentUnit } from "@/charts/chartTimeSeriesStats";

/** Dimenzní pole, přes která se nesmí automaticky sčítat. */
export const CATEGORICAL_GROUP_FIELDS = new Set([
  "geo",
  "ref_area",
  "country",
  "sector",
  "category",
  "product",
  "age",
  "sex",
  "nace",
  "nace_r2",
  "industry",
  "partner",
  "currency",
  "cust_lev",
  "indicator_id",
  "indic",
  "series",
  "entity",
  "region",
  "nuts",
  "nuts2",
  "nuts3",
]);

const NON_ADDITIVE_TEXT = [
  /\broe\b/i,
  /return on equity/i,
  /profitability/i,
  /mar[žz]e/i,
  /\bmargin\b/i,
  /\bratio\b/i,
  /pod[íi]l/i,
  /\bshare\b/i,
  /\brate\b/i,
  /sazba/i,
  /\bindex\b/i,
  /inflac/i,
  /\bnpl\b/i,
  /capital adequacy/i,
  /kapit[aá]lov/i,
  /adequacy/i,
  /pr[uů]m[eě]r/i,
  /\baverage\b/i,
  /m[ií]ra\b/i,
  /yield/i,
  /v[yý]nos/i,
];

const ADDITIVE_TEXT = [
  /po[cč]et/i,
  /\bcount\b/i,
  /\bvolume\b/i,
  /objem/i,
  /tržb/i,
  /revenue/i,
  /\bsales\b/i,
  /production/i,
  /produkce/i,
  /\bcelkem\b/i,
  /total amount/i,
  /suma/i,
  /množstv[ií]/i,
  /quantity/i,
  /headcount/i,
  /zam[eě]stnanc/i,
];

export function isCategoricalGroupField(field) {
  const f = String(field || "").trim().toLowerCase();
  if (!f) return false;
  if (CATEGORICAL_GROUP_FIELDS.has(f)) return true;
  if (f.endsWith("_code") || f.endsWith("_id") || f.endsWith("_label")) return true;
  const norm = f.normalize("NFD").replace(/\p{M}/gu, "");
  if (/ukazatel|indicator|druh/.test(norm)) return true;
  if (/kraj|okres|region|uzemi|nuts/.test(norm)) return true;
  return false;
}

/**
 * Je metrika explicitně aditivní (objem, počet, tržby…)?
 * Při nejistotě vrací false — nesčítat.
 */
export function isAdditiveMetric({ unit = "", unitLabel = "", title = "", name = "" } = {}) {
  const u = String(unit || "").trim();
  const ul = String(unitLabel || "").trim();
  if (isPercentUnit(u) || isPercentUnit(ul) || u.toUpperCase() === "PC" || ul.toUpperCase() === "PC") {
    return false;
  }
  const blob = [unit, unitLabel, title, name].filter(Boolean).join(" ");
  if (NON_ADDITIVE_TEXT.some((re) => re.test(blob))) return false;
  if (ADDITIVE_TEXT.some((re) => re.test(blob))) return true;
  return false;
}

export function resolveSeriesIdFromRow(row, groupField) {
  const gf = String(groupField || "").trim();
  if (gf && row?.[gf] != null && String(row[gf]).trim()) return String(row[gf]).trim();
  if (row?.indicator_id != null && String(row.indicator_id).trim()) return String(row.indicator_id).trim();
  return "";
}

export function distinctSeriesIdsInRows(rows, groupField) {
  const ids = new Set();
  for (const row of rows || []) {
    const id = resolveSeriesIdFromRow(row, groupField);
    if (id) ids.add(id);
  }
  return [...ids].sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
}

/**
 * Má se časová řada rozdělit na více linek podle dimenze (bez součtu)?
 */
export function shouldPivotTimeSeriesByDimension({
  rows,
  groupField,
  unit = "",
  unitLabel = "",
  title = "",
} = {}) {
  if (!groupField || !isCategoricalGroupField(groupField)) return false;
  if (distinctSeriesIdsInRows(rows, groupField).length < 2) return false;
  if (isAdditiveMetric({ unit, unitLabel, title })) return true;
  return true;
}

const PERIOD_KEYS = [
  "period",
  "x",
  "TIME_PERIOD",
  "date",
  "time",
  "datum",
  "Obdobi",
  "obdobi",
  "Roky",
  "roky",
  "Tříleté období",
  "Trileté období",
  "year",
];

export function extractChartPeriod(row) {
  if (!row || typeof row !== "object") return "";
  for (const key of PERIOD_KEYS) {
    const value = String(row[key] ?? "").trim();
    if (value) return value;
  }
  return "";
}

export function extractChartValue(row) {
  const raw =
    row?.Hodnota ??
    row?.hodnota ??
    row?.value ??
    row?.y ??
    row?.OBS_VALUE ??
    row?.obs_value ??
    row?.amount;
  const value = typeof raw === "number" ? raw : Number(String(raw ?? "").replace(/\s/g, "").replace(",", "."));
  return Number.isFinite(value) ? value : null;
}

/**
 * Pivot: { period, [seriesId]: value } — žádný součet přes kategorie.
 * @returns {{ multiSeries: boolean, rows: object[], seriesIds: string[] }}
 */
export function buildTimeSeriesPivotFromRows(rawRows, {
  groupField,
  seriesIds = null,
  maxSeries = 12,
} = {}) {
  const gf = String(groupField || "").trim();
  const rowsIn = Array.isArray(rawRows) ? rawRows : [];
  if (!gf || !rowsIn.length) {
    return { multiSeries: false, rows: [], seriesIds: [] };
  }

  let ids = Array.isArray(seriesIds) && seriesIds.length ? [...seriesIds] : distinctSeriesIdsInRows(rowsIn, gf);
  ids = ids.slice(0, maxSeries);
  if (ids.length < 2) {
    return { multiSeries: false, rows: [], seriesIds: ids };
  }

  const allowed = new Set(ids);
  const byPeriod = new Map();
  for (const row of rowsIn) {
    const period = extractChartPeriod(row);
    const seriesId = resolveSeriesIdFromRow(row, gf);
    const value = extractChartValue(row);
    if (!period || !seriesId || value == null || !allowed.has(seriesId)) continue;
    if (!byPeriod.has(period)) byPeriod.set(period, { period });
    byPeriod.get(period)[seriesId] = value;
  }

  const rows = [...byPeriod.values()].sort((a, b) =>
    String(a.period).localeCompare(String(b.period), undefined, { numeric: true }),
  );

  if (rows.length < 1) {
    return { multiSeries: false, rows: [], seriesIds: ids };
  }

  return { multiSeries: true, rows, seriesIds: ids };
}

/** Jedna řada { period, value } — pouze když není víc kategorií k pivotu. */
export function buildSingleSeriesFromRows(rawRows) {
  const byPeriod = new Map();
  for (const row of rawRows || []) {
    const period = extractChartPeriod(row);
    const value = extractChartValue(row);
    if (!period || value == null) continue;
    if (!byPeriod.has(period)) byPeriod.set(period, value);
  }
  return [...byPeriod.entries()]
    .map(([period, value]) => ({ period, value }))
    .sort((a, b) => String(a.period).localeCompare(String(b.period), undefined, { numeric: true }));
}

/**
 * Bezpečná agregace hodnot ve stejném časovém bucketu (frekvence).
 * Non-additive metriky: last (nikdy sum).
 */
export function aggregateBucketValues(values, agg, { unit = "", title = "" } = {}) {
  const nums = (values || []).filter((v) => typeof v === "number" && Number.isFinite(v));
  if (!nums.length) return null;
  let effectiveAgg = String(agg || "").trim().toLowerCase();
  if (effectiveAgg === "sum" && !isAdditiveMetric({ unit, title })) {
    effectiveAgg = nums.length > 1 ? "last" : "sum";
  }
  if (effectiveAgg === "avg") return nums.reduce((s, x) => s + x, 0) / nums.length;
  if (effectiveAgg === "last") return nums[nums.length - 1];
  if (effectiveAgg === "first") return nums[0];
  if (effectiveAgg === "max") return Math.max(...nums);
  if (effectiveAgg === "min") return Math.min(...nums);
  if (effectiveAgg === "count") return nums.length;
  if (effectiveAgg === "sum") return nums.reduce((s, x) => s + x, 0);
  return nums.length === 1 ? nums[0] : nums[nums.length - 1];
}
