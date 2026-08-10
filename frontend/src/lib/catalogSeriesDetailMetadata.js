import { inferNativeFrequencyFromChartRows, guessFrequencyCodeFromPeriodSample } from "@/lib/chartFrequencyInfer";
import { catalogResultMetaFromRow } from "@/components/catalog/search/CatalogResultCard";
import { fmtCompact, fmtNumber, fmtPeriodLabel, parseNumber } from "@/lib/format";
import { resolveCatalogPreviewNativeFrequency } from "@/lib/mapCatalogPreviewToArad";

const FREQ_LABEL_CS = {
  D: "Denní",
  W: "Týdenní",
  M: "Měsíční",
  Q: "Čtvrtletní",
  H: "Pololetní",
  Y: "Roční",
  A: "Roční",
};

const PERIOD_FIELD_RE = /period|datum|date|obdob|time/i;

function pickPeriodField(previewData) {
  const rows = Array.isArray(previewData?.rows) ? previewData.rows : [];
  if (!rows.length) return null;
  const first = rows[0];
  if (!first || typeof first !== "object") return null;

  for (const key of ["period", "x", "datum", "date", "TIME_PERIOD", "Obdobi", "obdobi"]) {
    if (first[key] != null && String(first[key]).trim()) return key;
  }

  const columns = Array.isArray(previewData?.columns) ? previewData.columns : [];
  const fromCol = columns.find((col) => {
    const key = String(col?.key || col?.field || "").trim();
    const label = String(col?.label || col?.name || "").trim();
    return PERIOD_FIELD_RE.test(key) || PERIOD_FIELD_RE.test(label);
  });
  if (fromCol?.key) return String(fromCol.key);

  return Object.keys(first).find((key) => PERIOD_FIELD_RE.test(key)) || null;
}

export function extractLatestPeriodFromPreview(previewData) {
  const rows = Array.isArray(previewData?.rows) ? previewData.rows : [];
  if (!rows.length) return "";
  const periodKey = pickPeriodField(previewData);
  if (!periodKey) return "";

  let latest = "";
  for (const row of rows) {
    const value = String(row?.[periodKey] ?? "").trim();
    if (!value) continue;
    if (!latest || value.localeCompare(latest, undefined, { numeric: true }) > 0) {
      latest = value;
    }
  }
  return latest;
}

function samplePeriodValuesFromPreview(previewData, max = 96) {
  const rows = Array.isArray(previewData?.rows) ? previewData.rows : [];
  if (!rows.length) return [];
  const periodKey = pickPeriodField(previewData);
  if (!periodKey) return [];
  return rows
    .slice(0, max)
    .map((row) => String(row?.[periodKey] ?? "").trim())
    .filter(Boolean);
}

function inferFrequencyFromPeriodSamples(previewData) {
  const samples = samplePeriodValuesFromPreview(previewData);
  if (!samples.length) return "";

  const normalizedRows = samples.map((period) => ({ period }));
  const fromRows = inferNativeFrequencyFromChartRows(normalizedRows, false);
  if (fromRows) return fromRows;

  const codes = samples
    .map((sample) => guessFrequencyCodeFromPeriodSample(sample))
    .filter(Boolean);
  if (!codes.length) return "";

  const counts = new Map();
  for (const code of codes) {
    counts.set(code, (counts.get(code) || 0) + 1);
  }
  let best = "";
  let bestCount = 0;
  for (const [code, count] of counts) {
    if (count > bestCount) {
      best = code;
      bestCount = count;
    }
  }
  return best;
}

function formatFrequencyDisplay(raw) {
  const text = String(raw ?? "").trim();
  if (!text) return "";
  const code = text.toUpperCase();
  if (FREQ_LABEL_CS[code]) return FREQ_LABEL_CS[code];
  return text;
}

export function formatCatalogMetadataDate(raw) {
  const text = String(raw ?? "").trim();
  if (!text) return "";
  return fmtPeriodLabel(text);
}

function formatFrequencyLabel(previewData, row, previewEffectiveRow) {
  const label = String(
    previewData?.chart_frequency_label ||
      previewData?.metadata?.chart_frequency_label ||
      "",
  ).trim();
  if (label) return label;

  const nativeCode = resolveCatalogPreviewNativeFrequency(previewData);
  if (nativeCode && FREQ_LABEL_CS[nativeCode]) return FREQ_LABEL_CS[nativeCode];
  if (nativeCode) return nativeCode;

  const rowCode = String(
    previewEffectiveRow?.frekvence ||
      previewEffectiveRow?.query_params?.imf_frekvence ||
      row?.frekvence ||
      row?.query_params?.imf_frekvence ||
      "",
  )
    .trim()
    .toUpperCase();
  if (rowCode && FREQ_LABEL_CS[rowCode]) return FREQ_LABEL_CS[rowCode];
  if (rowCode) return rowCode;

  const inferred = inferFrequencyFromPeriodSamples(previewData);
  if (inferred && FREQ_LABEL_CS[inferred]) return FREQ_LABEL_CS[inferred];
  return inferred || "";
}

function extractNumericValue(row) {
  const raw = row?.value ?? row?.y ?? row?.OBS_VALUE ?? row?.obs_value ?? row?.amount;
  return parseNumber(raw);
}

function filterPreviewRowsForMetadata(previewData) {
  const rows = Array.isArray(previewData?.rows) ? previewData.rows : [];
  const groupField = String(previewData?.group_field || "").trim();
  const selected = String(
    previewData?.selected_indicator ||
      (Array.isArray(previewData?.selected_indicators) ? previewData.selected_indicators[0] : "") ||
      "",
  ).trim();
  if (!groupField || !selected) return rows;
  return rows.filter((row) => String(row?.[groupField] ?? "").trim() === selected);
}

export function extractLatestValuePointsFromPreview(previewData) {
  const rows = filterPreviewRowsForMetadata(previewData);
  const periodKey = pickPeriodField({ ...previewData, rows });
  if (!periodKey || !rows.length) return [];

  const points = [];
  for (const row of rows) {
    const period = String(row?.[periodKey] ?? "").trim();
    const value = extractNumericValue(row);
    if (!period || value == null) continue;
    points.push({ period, value });
  }

  points.sort((a, b) => a.period.localeCompare(b.period, undefined, { numeric: true }));
  return points;
}

function formatMetadataNumericValue(value) {
  if (value == null) return "";
  const abs = Math.abs(value);
  if (abs >= 1e6) return fmtCompact(value);
  if (Number.isInteger(value)) return fmtNumber(value, { digits: 0 });
  const digits = abs >= 1000 ? 0 : abs >= 100 ? 1 : abs >= 10 ? 2 : 4;
  return fmtNumber(value, { digits });
}

export function formatCatalogMetadataPctChange(current, previous) {
  if (current == null || previous == null || previous === 0) return "";
  const pct = ((current - previous) / Math.abs(previous)) * 100;
  const sign = pct > 0 ? "+" : "";
  return `${sign}${pct.toLocaleString("cs-CZ", { maximumFractionDigits: 2 })} %`;
}

function resolvePreviewUnit(previewData) {
  return String(
    previewData?.unit ||
      previewData?.metadata?.unit ||
      previewData?.metadata?.unit_label_cs ||
      "",
  ).trim();
}

function formatLastValueDisplay(value, unit) {
  const formatted = formatMetadataNumericValue(value);
  if (!formatted || formatted === "—") return "";
  return unit ? `${formatted} ${unit}` : formatted;
}

function extractGeoFromPreview(previewData, def) {
  const filters =
    previewData?.metadata?.filters_applied ||
    previewData?.filters_applied ||
    previewData?.requested_filters ||
    {};
  const raw =
    filters.geo ??
    filters.REF_AREA ??
    filters.ref_area ??
    filters.country ??
    filters.COUNTRY ??
    null;

  if (Array.isArray(raw)) {
    const list = raw.map((v) => String(v || "").trim()).filter(Boolean);
    if (list.length) return list.join(", ");
  } else if (raw != null && String(raw).trim()) {
    return String(raw).trim();
  }

  const rows = Array.isArray(previewData?.rows) ? previewData.rows : [];
  if (rows.length) {
    const geoKey = Object.keys(rows[0] || {}).find((k) => /^(geo|ref_area|country|zeme)$/i.test(k));
    if (geoKey) {
      const values = new Set();
      for (const row of rows) {
        const v = String(row?.[geoKey] ?? "").trim();
        if (v) values.add(v);
      }
      if (values.size === 1) return [...values][0];
      if (values.size > 1) return `${values.size} oblastí`;
    }
  }

  const st = String(def?.sourceType || def?.id || "").toLowerCase();
  if (st === "arad" || st === "csu") return "ČR";
  return "";
}

function pickMetaValue(...values) {
  for (const value of values) {
    const text = String(value ?? "").trim();
    if (text) return text;
  }
  return "";
}

function rowsEqualForChartDisplayState(a, b) {
  if (a === b) return true;
  if (!Array.isArray(a) || !Array.isArray(b)) return !a && !b;
  if (a.length !== b.length) return false;
  if (a.length === 0) return true;
  const af = a[0];
  const al = a[a.length - 1];
  const bf = b[0];
  const bl = b[b.length - 1];
  return (
    af?.period === bf?.period
    && af?.value === bf?.value
    && al?.period === bl?.period
    && al?.value === bl?.value
  );
}

/**
 * Přepíše metadata horního panelu podle aktuálního stavu grafu (frekvence, zoom, agregace).
 */
export function chartDisplayStatesEqual(a, b) {
  if (a === b) return true;
  if (!a || !b) return !a && !b;
  return (
    String(a.frequencyCode || "") === String(b.frequencyCode || "")
    && String(a.frequencyLabel || "") === String(b.frequencyLabel || "")
    && String(a.lastPeriod || "") === String(b.lastPeriod || "")
    && a.lastValue === b.lastValue
    && a.prevValue === b.prevValue
    && String(a.unit || "") === String(b.unit || "")
    && Boolean(a.isAggregated) === Boolean(b.isAggregated)
    // "rows" je (jen délkou + první/poslední bod) porovnávaná agregovaná řada pro synchronizaci
    // vnější tabulky/exportu s vybranou periodicitou grafu - viz CatalogChartPreview.jsx.
    && rowsEqualForChartDisplayState(a.rows, b.rows)
  );
}

export function applyChartDisplayStateToMetadata(meta, chartDisplayState, unit = "") {
  if (!chartDisplayState || typeof chartDisplayState !== "object") return meta;

  const resolvedUnit = String(chartDisplayState.unit || unit || "").trim();
  const frequency = pickMetaValue(
    chartDisplayState.frequencyLabel,
    formatFrequencyDisplay(chartDisplayState.frequencyCode),
    meta.frequency,
  );
  const lastDate = chartDisplayState.lastPeriod
    ? formatCatalogMetadataDate(chartDisplayState.lastPeriod)
    : meta.lastDate;
  const lastValue =
    chartDisplayState.lastValue != null
      ? formatLastValueDisplay(chartDisplayState.lastValue, resolvedUnit)
      : meta.lastValue;
  let valueChangePct = meta.valueChangePct;
  if (chartDisplayState.lastValue != null && chartDisplayState.prevValue != null) {
    valueChangePct = formatCatalogMetadataPctChange(
      chartDisplayState.lastValue,
      chartDisplayState.prevValue,
    );
  }

  return {
    ...meta,
    frequency,
    lastDate,
    lastValue,
    valueChangePct,
  };
}

/**
 * Metadata pro detail panel řady — row + volitelně načtený preview payload.
 */
export function catalogSeriesDetailMetadata(
  def,
  row,
  previewData = null,
  previewEffectiveRow = null,
  chartDisplayState = null,
) {
  const base = catalogResultMetaFromRow(def, previewEffectiveRow || row);
  if (!previewData || typeof previewData !== "object") {
    return {
      ...base,
      frequency: formatFrequencyDisplay(base.frequency),
      lastDate: formatCatalogMetadataDate(base.lastDate),
    };
  }

  const frequency = pickMetaValue(
    formatFrequencyLabel(previewData, row, previewEffectiveRow),
    formatFrequencyDisplay(base.frequency),
  );
  const valuePoints = extractLatestValuePointsFromPreview(previewData);
  const latestPoint = valuePoints.length ? valuePoints[valuePoints.length - 1] : null;
  const previousPoint = valuePoints.length >= 2 ? valuePoints[valuePoints.length - 2] : null;
  const unit = resolvePreviewUnit(previewData);
  const lastDateRaw = pickMetaValue(latestPoint?.period, extractLatestPeriodFromPreview(previewData), base.lastDate);

  const meta = {
    ...base,
    frequency,
    geo: pickMetaValue(extractGeoFromPreview(previewData, def), base.geo),
    lastDate: formatCatalogMetadataDate(lastDateRaw),
    lastValue: formatLastValueDisplay(latestPoint?.value, unit),
    valueChangePct: formatCatalogMetadataPctChange(latestPoint?.value, previousPoint?.value),
  };
  return applyChartDisplayStateToMetadata(meta, chartDisplayState, unit);
}

export function catalogSeriesDetailMetadataItems(
  def,
  row,
  previewData = null,
  previewEffectiveRow = null,
  chartDisplayState = null,
) {
  const meta = catalogSeriesDetailMetadata(
    def,
    row,
    previewData,
    previewEffectiveRow,
    chartDisplayState,
  );
  return [
    { label: "Frekvence", value: meta.frequency || "—" },
    { label: "Geo", value: meta.geo || "—" },
    { label: "Poslední datum", value: meta.lastDate || "—" },
    { label: "Poslední hodnota", value: meta.lastValue || "—" },
    { label: "Změna", value: meta.valueChangePct || "—" },
  ];
}
