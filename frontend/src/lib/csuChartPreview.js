/**
 * ČSÚ náhled — filtry dimenzí, volba metriky (cena vs. index) a správný tvar řad pro AradView.
 */

import {
  buildTimeSeriesPivotFromRows,
  extractChartPeriod,
  extractChartValue,
} from "@/lib/chartTimeSeriesPivot";

const METRIC_FIELD_CANDIDATES = ["Ukazatel", "Druh nemovitosti", "Druh zboží"];
const TYPE_FIELD_CANDIDATES = ["Typ údaje", "Typ udaje", "Typ indexu"];
const GEO_FIELD_CANDIDATES = ["ÚZEMÍ-Kraj", "Území-kraj", "ÚZEMÍ-OKRES", "Území-okres"];
const PERIOD_FIELD_CANDIDATES = [
  "Roky",
  "Čtvrtletí",
  "Ctvrtleti",
  "Tříleté období",
  "Kumulace měsíců",
  "Kumulace mesicu",
  "Měsíce",
  "Mesice",
  "date",
  "year",
  "period",
  "TIME_PERIOD",
];
const VALUE_FIELD_CANDIDATES = ["Hodnota", "hodnota", "value", "y", "amount"];
const POPULATION_FIELD_CANDIDATES = ["Počet obyvatel obce", "Pocet obyvatel obce"];

const CZ_MONTH_NUMBERS = {
  leden: 1,
  unor: 2,
  brezen: 3,
  duben: 4,
  kveten: 5,
  cervenec: 7,
  cerven: 6,
  srpen: 8,
  zari: 9,
  rijen: 10,
  listopad: 11,
  prosinec: 12,
};

function normKey(value) {
  return String(value || "")
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function fieldNamesFromRows(rows) {
  const keys = new Set();
  for (const row of rows || []) {
    if (row && typeof row === "object") {
      Object.keys(row).forEach((k) => keys.add(k));
    }
  }
  return [...keys];
}

function matchField(keys, candidates) {
  for (const cand of candidates) {
    const hit = keys.find((k) => normKey(k) === normKey(cand));
    if (hit) return hit;
  }
  return null;
}

function isGeoFieldName(field) {
  const n = normKey(field).replace(/\s+/g, "");
  return /(kraj|okres|region|geo|refarea|country|uzemi)/.test(n) && !/stat/.test(n);
}

function uniqueFieldValues(rows, field) {
  if (!field) return [];
  const vals = new Set();
  for (const row of rows || []) {
    const v = String(row?.[field] ?? "").trim();
    if (v) vals.add(v);
  }
  return [...vals].sort((a, b) => a.localeCompare(b, "cs"));
}

function parseCsuPeriodKey(value) {
  const raw = String(value || "").trim();
  if (!raw) return null;
  const norm = normKey(raw);

  let match = raw.match(/\b(19\d{2}|20\d{2})[-/ ]?([01]\d)\b/);
  if (match) {
    const month = Number(match[2]);
    if (month >= 1 && month <= 12) return Number(match[1]) * 100 + month;
  }

  match = norm.match(/\b([1-4])\s*ctvrtleti\s*(19\d{2}|20\d{2})\b/);
  if (match) return Number(match[2]) * 100 + Number(match[1]) * 3;

  const year = norm.match(/\b(19\d{2}|20\d{2})\b/)?.[1];
  if (year) {
    for (const [monthName, month] of Object.entries(CZ_MONTH_NUMBERS)) {
      if (norm.includes(monthName)) return Number(year) * 100 + month;
    }
    return Number(year) * 100;
  }

  return null;
}

function compareCsuPeriods(a, b) {
  const ak = parseCsuPeriodKey(a);
  const bk = parseCsuPeriodKey(b);
  if (ak != null && bk != null && ak !== bk) return ak - bk;
  if (ak != null && bk == null) return -1;
  if (ak == null && bk != null) return 1;
  return String(a || "").localeCompare(String(b || ""), "cs", { numeric: true });
}

function inferCsuFrequency(periods) {
  const values = Array.isArray(periods) ? periods : [];
  if (
    values.some((p) => {
      const n = normKey(p);
      return Object.keys(CZ_MONTH_NUMBERS).some((m) => n.includes(m)) || /\b\d{4}[-/ ]?[01]\d\b/.test(String(p || ""));
    })
  ) {
    return "M";
  }
  if (values.some((p) => /ctvrtleti|q[1-4]/i.test(normKey(p)))) return "Q";
  return "Y";
}

function scoreMetricValue(value, title) {
  const name = normKey(value);
  const hint = normKey(title);
  let score = 0;
  if (/kupn|cena|ceny|kc|za m2|za m²|price/.test(name)) score += 40;
  if (/index|meziro/.test(name)) score -= 30;
  if (/byt/.test(name)) score += 25;
  if (hint.includes("byt") && /byt/.test(name)) score += 30;
  if ((hint.includes("cena") || hint.includes("ceny") || hint.includes("kupn")) && /kupn|cena|ceny|kc/.test(name)) {
    score += 30;
  }
  return score;
}

function pickMetricField(rows, groupField) {
  if (groupField && !isGeoFieldName(groupField)) return groupField;
  const keys = fieldNamesFromRows(rows);
  for (const cand of METRIC_FIELD_CANDIDATES) {
    const field = matchField(keys, [cand]);
    if (field && uniqueFieldValues(rows, field).length > 1) return field;
  }
  return groupField && !isGeoFieldName(groupField) ? groupField : null;
}

function pickMetricValue(rows, metricField, title, selected) {
  const sel = String(selected || "").trim();
  if (sel) return sel;
  const vals = uniqueFieldValues(rows, metricField);
  if (!vals.length) return "";
  if (vals.length === 1) return vals[0];
  let best = vals[0];
  let bestScore = -Infinity;
  for (const v of vals) {
    const score = scoreMetricValue(v, title);
    if (score > bestScore) {
      bestScore = score;
      best = v;
    }
  }
  return bestScore > -20 ? best : vals[0];
}

function baseYear(value) {
  const years = [...String(value || "").matchAll(/\b(19\d{2}|20\d{2})\b/g)].map((m) => Number(m[1]));
  return years.length ? Math.max(...years) : 0;
}

function baseValueScore(value) {
  const name = normKey(value);
  const monthlyRebase = Object.keys(CZ_MONTH_NUMBERS).some((month) => name.includes(month));
  return [baseYear(value), monthlyRebase ? -1 : 0, String(value || "")];
}

function pickTypeValue(rows, typeField, title) {
  const vals = uniqueFieldValues(rows, typeField);
  if (!vals.length) return "";
  if (vals.length === 1) return vals[0];

  const hint = normKey(title);
  if (hint.includes("meziroc") || hint.includes("yoy") || hint.includes("year on year")) {
    const yoy = vals.find((v) => normKey(v).includes("meziroc"));
    if (yoy) return yoy;
  }
  if (hint.includes("mezimes") || hint.includes("mom") || hint.includes("month on month")) {
    const mom = vals.find((v) => normKey(v).includes("mezimes"));
    if (mom) return mom;
  }

  const titleYear = baseYear(title);
  const baseVals = vals.filter((v) => normKey(v).includes("bazick"));
  if (titleYear) {
    const sameBase = baseVals.find((v) => baseYear(v) === titleYear);
    if (sameBase) return sameBase;
  }
  if (baseVals.length) {
    return [...baseVals].sort((a, b) => {
      const as = baseValueScore(a);
      const bs = baseValueScore(b);
      return bs[0] - as[0] || bs[1] - as[1] || String(b).localeCompare(String(a), "cs");
    })[0];
  }

  return vals.find((v) => !normKey(v).includes("meziroc") && !normKey(v).includes("mezimes")) || vals[0];
}

function applyPreviewDimensionFilters(preview, rows) {
  let out = [...(rows || [])];
  const filters = {
    ...(preview?.metadata?.filters_applied && typeof preview.metadata.filters_applied === "object"
      ? preview.metadata.filters_applied
      : {}),
    ...(preview?.dimension_filters && typeof preview.dimension_filters === "object"
      ? preview.dimension_filters
      : {}),
  };
  const entries = Object.entries(filters).filter(([, value]) => value != null && String(value).trim() !== "");
  if (!entries.length) return out;

  const chartSeriesField = preview?.chart_series_dim
    ? matchField(fieldNamesFromRows(out), [preview.chart_series_dim])
    : null;

  for (const [rawField, rawValue] of entries) {
    const field = matchField(fieldNamesFromRows(out), [rawField]);
    if (!field || (chartSeriesField && field === chartSeriesField)) continue;
    const value = String(rawValue).trim();
    const filtered = out.filter((r) => String(r?.[field] ?? "").trim() === value);
    if (!filtered.length) continue;
    if (chartSeriesField) {
      const hadPivot = out.some((r) => String(r?.[chartSeriesField] ?? "").trim());
      const keepsPivot = filtered.some((r) => String(r?.[chartSeriesField] ?? "").trim());
      if (hadPivot && !keepsPivot) continue;
    }
    out = filtered;
  }
  return out;
}

function normalizeRowShape(row, periodField) {
  if (!row || typeof row !== "object") return row;
  const period =
    extractChartPeriod(row) ||
    String(periodField && row[periodField] != null ? row[periodField] : "").trim();
  const value = extractChartValue(row);
  const out = { ...row };
  if (period && !out.period) out.period = period;
  if (value != null && out.value == null && out.y == null) out.value = value;
  return out;
}

function filterCsuRows(preview, rows, title) {
  let out = [...(rows || [])];
  const keys = fieldNamesFromRows(out);
  const popField = matchField(keys, POPULATION_FIELD_CANDIDATES);
  if (popField && out.some((r) => String(r?.[popField] ?? "").trim() === "Celkem")) {
    out = out.filter((r) => String(r?.[popField] ?? "").trim() === "Celkem");
  }
  out = applyPreviewDimensionFilters(preview, out);

  const metricField = pickMetricField(out, preview?.group_field);
  const metricValue = pickMetricValue(
    out,
    metricField,
    title,
    preview?.selected_indicator,
  );
  if (metricField && metricValue) {
    out = out.filter((r) => String(r?.[metricField] ?? "").trim() === metricValue);
  }
  const typeField = matchField(fieldNamesFromRows(out), TYPE_FIELD_CANDIDATES);
  const typeValue = pickTypeValue(out, typeField, title);
  if (typeField && typeValue && uniqueFieldValues(out, typeField).length > 1) {
    out = out.filter((r) => String(r?.[typeField] ?? "").trim() === typeValue);
  }

  const periodField = matchField(fieldNamesFromRows(out), PERIOD_FIELD_CANDIDATES);
  const valueField = matchField(fieldNamesFromRows(out), VALUE_FIELD_CANDIDATES);
  out = out.map((r) => normalizeRowShape(r, periodField));
  return { rows: out, metricField, metricValue, periodField, valueField, geoField: matchField(fieldNamesFromRows(out), GEO_FIELD_CANDIDATES) };
}

/**
 * @returns {object|null}
 */
export function buildCsuAradDataFromCatalogPreview(preview, title = "") {
  const rawRows = Array.isArray(preview?.rows) ? preview.rows : [];
  if (!rawRows.length) return null;

  const previewTitle = String(title || preview?.source?.name || preview?.title || "Graf").trim() || "Graf";
  const {
    rows,
    metricField,
    metricValue,
    periodField,
    geoField: geoFromRows,
  } = filterCsuRows(preview, rawRows, previewTitle);

  const geoField =
    (preview?.chart_series_dim && isGeoFieldName(preview.chart_series_dim)
      ? matchField(fieldNamesFromRows(rows), [preview.chart_series_dim]) || preview.chart_series_dim
      : null) || geoFromRows;

  if (!rows.length || !periodField) return null;

  const periods = uniqueFieldValues(rows, periodField);
  const geoValues = geoField ? uniqueFieldValues(rows, geoField) : [];

  if (geoField && geoValues.length >= 2 && periods.length <= 1) {
    const latestRows = rows
      .map((r) => {
        const x = String(r?.[geoField] ?? "").trim();
        const y = extractChartValue(r);
        return x && y != null ? { x, y, period: String(r?.[periodField] ?? "") } : null;
      })
      .filter(Boolean);
    if (latestRows.length >= 2) {
      return {
        title: previewTitle,
        rows: latestRows,
        chart_data_mode: preview?.chart_data_mode || "latest",
        chart_type: preview?.chart_type || "bar",
        group_field: geoField,
        selected_indicator: metricValue || preview?.selected_indicator || "",
        series_field: metricField || undefined,
        series_value: metricValue || undefined,
        frequency: inferCsuFrequency(periods),
        view: "chart",
      };
    }
  }

  if (geoField && geoValues.length >= 2 && periods.length > 1) {
    const pivot = buildTimeSeriesPivotFromRows(rows, {
      groupField: geoField,
      seriesIds: geoValues.slice(0, 14),
      maxSeries: 14,
    });
    if (pivot.multiSeries && pivot.rows.length >= 2) {
      const series = pivot.seriesIds.map((id) => ({ key: id, label: id, name: id }));
      return {
        title: previewTitle,
        rows: pivot.rows,
        multi_series: true,
        series,
        group_field: geoField,
        selected_indicator: metricValue || preview?.selected_indicator || "",
        series_field: metricField || undefined,
        series_value: metricValue || undefined,
        frequency: inferCsuFrequency(periods),
        view: "chart",
      };
    }
  }

  const single = rows
    .map((r) => {
      const period = String(r?.period ?? r?.[periodField] ?? "").trim();
      const value = extractChartValue(r);
      return period && value != null ? { period, value, x: period, y: value } : null;
    })
    .filter(Boolean)
    .sort((a, b) => compareCsuPeriods(a.period, b.period));

  if (single.length < 2) {
    if (single.length === 1) {
      return {
        title: previewTitle,
        rows: single,
        chart_data_mode: preview?.chart_data_mode || "latest",
        chart_type: preview?.chart_type || "bar",
        group_field: metricField || preview?.group_field,
        selected_indicator: metricValue || preview?.selected_indicator || "",
        series_field: metricField || undefined,
        series_value: metricValue || undefined,
        frequency: inferCsuFrequency(single.map((row) => row.period)),
        view: "chart",
      };
    }
    return null;
  }

  return {
    title: previewTitle,
    rows: single,
    group_field: metricField || preview?.group_field,
    selected_indicator: metricValue || preview?.selected_indicator || "",
    series_field: metricField || undefined,
    series_value: metricValue || undefined,
    frequency: inferCsuFrequency(single.map((row) => row.period)),
    view: "chart",
  };
}

export function isCsuCatalogPreview(preview) {
  return String(preview?.source?.source_type || preview?.source_type || "").trim().toLowerCase() === "csu";
}
