/**
 * Excel-ready chart export — wide (user) / long (advanced) CSV, clipboard, PNG, XLSX workbook model.
 * Numeric values stay numeric; no nested JSON in cells.
 */

import { contractToLongRows } from "./chartDataContract";
import {
  formatExportNumber,
  formatSeriesColumnHeader,
  periodColumnHeader,
  prepareContractForExport,
} from "./chartExportSanitize";

const DEFAULT_WIDE_LOCALE = "cs-CZ";
const DEFAULT_WIDE_DELIMITER = ";";
const DEFAULT_LONG_DELIMITER = ",";

export const EXPORT_LONG_COLUMNS = [
  "period",
  "period_label",
  "geo",
  "geo_label",
  "series_id",
  "series_label",
  "value_raw",
  "unit",
  "frequency",
  "source",
  "dataset",
  "transformation",
  "chart_type",
];

export const TECHNICAL_CLIPBOARD_COLUMNS = new Set([
  "series_id",
  "source",
  "dataset",
  "chart_type",
  "transformation",
  "geo",
  "geo_label",
  "frequency",
  "unit",
]);

export const EXPORT_METADATA_COLUMNS = ["key", "value"];
export const EXPORT_TRANSFORMATIONS_COLUMNS = ["type", "applied_at", "notes"];
export const EXPORT_SOURCES_COLUMNS = ["source", "dataset", "query", "generated_at"];

function escapeCsvCell(value) {
  if (value === null || value === undefined) return "";
  if (typeof value === "object") return escapeCsvCell(JSON.stringify(value));
  const s = String(value);
  return /[",;\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function formatWideRowCell(value, column, periodHeader, locale) {
  if (column === periodHeader) return value ?? "";
  if (typeof value === "number") return formatExportNumber(value, locale);
  return value ?? "";
}

/**
 * @param {object} [opts]
 * @param {string|null} [opts.periodHeader] Sloupec s obdobím — čísla se formátují jen v ostatních.
 * @param {string|null} [opts.locale] Formát čísla; bez `periodHeader` se ignoruje.
 * @param {boolean} [opts.bom] Značka kódování pro Excel. U strojově čtených formátů se vynechává —
 *   naivní `read_csv` si o ni rozbije název prvního sloupce.
 */
function rowsToCsv(columns, rows, delimiter = ";", { periodHeader = null, locale = null, bom = true } = {}) {
  const lines = [columns.join(delimiter)];
  for (const row of rows) {
    lines.push(
      columns
        .map((col) => {
          const raw =
            periodHeader && locale
              ? formatWideRowCell(row[col], col, periodHeader, locale)
              : row[col];
          return escapeCsvCell(raw);
        })
        .join(delimiter)
    );
  }
  return (bom ? "\uFEFF" : "") + lines.join("\n");
}

function buildLongExportColumns(longRows) {
  const hasGeo = (longRows || []).some((r) => String(r.geo || r.geo_label || "").trim());
  if (hasGeo) return [...EXPORT_LONG_COLUMNS];
  return EXPORT_LONG_COLUMNS.filter((c) => c !== "geo" && c !== "geo_label");
}

/** User-facing wide table: Období | series columns with human labels. */
export function buildUserWideTable(contract) {
  const prepared = prepareContractForExport(contract);
  const periodHeader = periodColumnHeader(prepared);
  const fallbackUnit = prepared.metadata?.unit || "";
  const seriesList = prepared.series?.length
    ? prepared.series
    : [{ id: "main", key: "main", label: prepared.title || "Hodnota", unit: fallbackUnit }];

  const headerBySeriesId = new Map();
  const headers = [periodHeader];
  for (const s of seriesList) {
    const header = formatSeriesColumnHeader(s, fallbackUnit);
    headerBySeriesId.set(String(s.id || s.key), header);
    if (!headers.includes(header)) headers.push(header);
  }

  const byPeriod = new Map();
  for (const pt of prepared.data || []) {
    const periodDisplay = String(pt.period_label || pt.period || "").trim();
    if (!periodDisplay) continue;
    if (!byPeriod.has(periodDisplay)) {
      byPeriod.set(periodDisplay, { [periodHeader]: periodDisplay });
    }
    const row = byPeriod.get(periodDisplay);
    const col =
      headerBySeriesId.get(String(pt.series_id)) ||
      formatSeriesColumnHeader({ label: pt.series_label, unit: pt.unit }, fallbackUnit);
    if (col) row[col] = pt.value_raw;
  }

  return { headers, rows: [...byPeriod.values()] };
}

/** Build wide-format rows (internal / legacy). */
export function buildWideRows(contract) {
  const userWide = buildUserWideTable(contract);
  return { columns: userWide.headers, rows: userWide.rows };
}

export function buildChartExportSheets(contract, { query = "", generatedAt = null } = {}) {
  const prepared = prepareContractForExport(contract);
  const ts = generatedAt || new Date().toISOString();
  const longRows = contractToLongRows(prepared);
  const longColumns = buildLongExportColumns(longRows);
  const userWide = buildUserWideTable(prepared);

  const metadataRows = [
    { key: "chart_id", value: prepared.chart_id },
    { key: "chart_type", value: prepared.chart_type },
    { key: "title", value: prepared.title || "" },
    { key: "subtitle", value: prepared.subtitle || "" },
    { key: "description", value: prepared.description || "" },
    { key: "generated_at", value: ts },
    { key: "query", value: query },
    ...(prepared.metadata && typeof prepared.metadata === "object"
      ? Object.entries(prepared.metadata).map(([key, value]) => ({
          key,
          value: typeof value === "object" ? JSON.stringify(value) : value,
        }))
      : []),
  ];

  const transformationRows = (prepared.transformations || []).map((t) => ({
    type: t.type || "",
    applied_at: t.at || "",
    notes: t.notes || "",
  }));

  const sourceName = prepared.source?.name || prepared.metadata?.source || "";
  const datasetName = prepared.metadata?.dataset || "";
  const sourcesRows = [
    {
      source: sourceName,
      dataset: datasetName,
      query,
      generated_at: ts,
    },
  ];

  const sheets = {
    Data_Wide: { columns: userWide.headers, rows: userWide.rows },
    Data_Long: { columns: longColumns, rows: longRows },
    Metadata: { columns: EXPORT_METADATA_COLUMNS, rows: metadataRows },
    Transformations: { columns: EXPORT_TRANSFORMATIONS_COLUMNS, rows: transformationRows },
    Sources: { columns: EXPORT_SOURCES_COLUMNS, rows: sourcesRows },
  };

  const dimsMeta =
    prepared.dimensions_meta ||
    prepared.metadata?.selected_dimensions ||
    prepared.widget?.config?.selected_dimensions;
  if (dimsMeta && typeof dimsMeta === "object" && Object.keys(dimsMeta).length) {
    sheets.Dimensions = {
      columns: ["dimension", "value"],
      rows: Object.entries(dimsMeta).map(([dimension, value]) => ({
        dimension,
        value: Array.isArray(value) ? value.join(", ") : String(value ?? ""),
      })),
    };
  }

  return sheets;
}

/** Advanced CSV — technical long format. */
function uniqueByKey(rows, keyFn) {
  const seen = new Set();
  const out = [];
  for (const row of rows || []) {
    const key = keyFn(row);
    if (!key || seen.has(key)) continue;
    seen.add(key);
    out.push(row);
  }
  return out;
}

function periodParts(period) {
  const s = String(period || "").trim();
  const year = Number((s.match(/\d{4}/) || [])[0]);
  return {
    period: s,
    year: Number.isFinite(year) ? year : null,
    quarter: /Q([1-4])/.test(s) ? Number(s.match(/Q([1-4])/)?.[1]) : null,
    month: /^\d{4}-\d{2}/.test(s) ? Number(s.slice(5, 7)) : null,
  };
}

/** BI/OLAP-ready star-schema package for Power BI, Excel Power Pivot, SQL or warehouse import. */
export function buildOlapCubePackage(contract, { query = "", generatedAt = null } = {}) {
  const prepared = prepareContractForExport(contract);
  const ts = generatedAt || new Date().toISOString();
  const longRows = contractToLongRows(prepared);
  const facts = longRows.map((r, idx) => ({
    fact_id: idx + 1,
    period_id: String(r.period || r.period_label || ""),
    series_id: String(r.series_id || "main"),
    geo_id: String(r.geo || r.geo_label || "all"),
    source_id: String(r.source || prepared.source?.name || prepared.metadata?.source || "source"),
    value: r.value_raw,
    unit: r.unit || "",
    frequency: r.frequency || "",
    transformation: r.transformation || "",
  }));

  const periodDimension = uniqueByKey(
    longRows.map((r) => ({
      period_id: String(r.period || r.period_label || ""),
      period_label: r.period_label || r.period || "",
      ...periodParts(r.period || r.period_label),
    })),
    (r) => r.period_id
  );

  const seriesMap = new Map(
    (prepared.series || []).map((s) => [
      String(s.id || s.key || "main"),
      {
        series_id: String(s.id || s.key || "main"),
        series_label: s.label || s.name || prepared.title || "Hodnota",
        unit: s.unit || prepared.metadata?.unit || "",
      },
    ])
  );
  const seriesDimension = uniqueByKey(
    longRows.map((r) => ({
      series_id: String(r.series_id || "main"),
      series_label: r.series_label || seriesMap.get(String(r.series_id || "main"))?.series_label || "",
      unit: r.unit || seriesMap.get(String(r.series_id || "main"))?.unit || "",
      dataset: r.dataset || prepared.metadata?.dataset || "",
      chart_type: prepared.chart_type || "",
    })),
    (r) => r.series_id
  );

  const geoDimension = uniqueByKey(
    longRows.map((r) => ({
      geo_id: String(r.geo || r.geo_label || "all"),
      geo_code: r.geo || "",
      geo_label: r.geo_label || r.geo || "Celkem",
    })),
    (r) => r.geo_id
  );

  const sourceDimension = uniqueByKey(
    longRows.map((r) => ({
      source_id: String(r.source || prepared.source?.name || prepared.metadata?.source || "source"),
      source: r.source || prepared.source?.name || prepared.metadata?.source || "",
      dataset: r.dataset || prepared.metadata?.dataset || "",
    })),
    (r) => r.source_id
  );

  return {
    type: "bankoapp_olap_cube",
    version: 1,
    generated_at: ts,
    title: prepared.title || "",
    query,
    schema: {
      grain: "one row per period / series / geo / source",
      fact_table: "fact_values",
      measure: { name: "value", aggregation: "sum_or_average_by_business_context" },
      dimensions: ["dim_period", "dim_series", "dim_geo", "dim_source"],
    },
    tables: {
      fact_values: facts,
      dim_period: periodDimension,
      dim_series: seriesDimension,
      dim_geo: geoDimension,
      dim_source: sourceDimension,
      metadata: buildChartExportSheets(prepared, { query, generatedAt: ts }).Metadata.rows,
    },
  };
}

export function exportChartOlapJson(contract, options = {}) {
  const { filename = "chart", query = "" } = options;
  const pkg = buildOlapCubePackage(contract, { query });
  downloadTextBlob(
    JSON.stringify(pkg, null, 2),
    `${safeFilename(filename)}_olap_cube.json`,
    "application/json;charset=utf-8"
  );
}

export function exportChartOlapFactCsv(contract, options = {}) {
  const { filename = "chart", delimiter = ",", query = "" } = options;
  const pkg = buildOlapCubePackage(contract, { query });
  const columns = ["fact_id", "period_id", "series_id", "geo_id", "source_id", "value", "unit", "frequency", "transformation"];
  const csv = rowsToCsv(columns, pkg.tables.fact_values, delimiter, { bom: false });
  downloadTextBlob(csv, `${safeFilename(filename)}_fact_values.csv`, "text/csv;charset=utf-8");
}

/**
 * Technický (dlouhý) formát je určený ke strojovému zpracování, ne k otevření v Excelu: čísla
 * proto zůstávají s desetinnou tečkou a soubor nemá značku kódování. Volba `locale` se sem dřív
 * předávala, ale `rowsToCsv` ji bez `periodHeader` ignorovala — byl to mrtvý kód, který budil
 * dojem, že formát respektuje české oddělovače.
 */
export function exportChartDataLongCsv(contract, options = {}) {
  const { delimiter = DEFAULT_LONG_DELIMITER, filename = "chart" } = options;
  const sheets = buildChartExportSheets(contract);
  const csv = rowsToCsv(sheets.Data_Long.columns, sheets.Data_Long.rows, delimiter, { bom: false });
  downloadTextBlob(csv, `${safeFilename(filename)}_long.csv`, "text/csv;charset=utf-8");
}

/** Simple CSV — user wide table for Excel (cs-CZ: ; and comma decimals). */
export function exportChartDataWideCsv(contract, options = {}) {
  const {
    locale = DEFAULT_WIDE_LOCALE,
    delimiter = DEFAULT_WIDE_DELIMITER,
    filename = "chart",
  } = options;
  const sheets = buildChartExportSheets(contract);
  const periodHeader = sheets.Data_Wide.columns[0] || "Období";
  const csv = rowsToCsv(sheets.Data_Wide.columns, sheets.Data_Wide.rows, delimiter, {
    periodHeader,
    locale,
  });
  downloadTextBlob(csv, `${safeFilename(filename)}.csv`, "text/csv;charset=utf-8");
}

/** @deprecated use exportChartDataLongCsv */
export function exportChartCsvLong(contract, filename = "chart") {
  exportChartDataLongCsv(contract, { filename });
}

/** @deprecated use exportChartDataWideCsv */
export function exportChartCsvWide(contract, filename = "chart") {
  exportChartDataWideCsv(contract, { filename });
}

/** Tab-delimited wide clipboard text for Excel paste (default user export). */
export function buildClipboardTextWide(contract, { locale = DEFAULT_WIDE_LOCALE } = {}) {
  const { headers, rows } = buildUserWideTable(contract);
  const periodHeader = headers[0] || "Období";
  const lines = [headers.join("\t")];
  for (const row of rows) {
    lines.push(
      headers.map((col) => formatWideRowCell(row[col], col, periodHeader, locale)).join("\t")
    );
  }
  return lines.join("\n");
}

/** Advanced tab-delimited long clipboard (technical). */
export function buildClipboardTextLong(contract) {
  const sheets = buildChartExportSheets(contract);
  const { columns, rows } = sheets.Data_Long;
  const lines = [columns.join("\t")];
  for (const row of rows) {
    lines.push(columns.map((col) => row[col] ?? "").join("\t"));
  }
  return lines.join("\n");
}

/** Default clipboard = user wide format. */
export function buildClipboardText(contract, options) {
  return buildClipboardTextWide(contract, options);
}

export async function copyChartDataWideToClipboard(contract, { locale = DEFAULT_WIDE_LOCALE } = {}) {
  const text = buildClipboardTextWide(contract, { locale });
  if (typeof navigator !== "undefined" && navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return true;
  }
  return false;
}

export async function copyChartDataToClipboard(contract, options) {
  return copyChartDataWideToClipboard(contract, options);
}

export async function copyChartDataLongToClipboard(contract) {
  const text = buildClipboardTextLong(contract);
  if (typeof navigator !== "undefined" && navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return true;
  }
  return false;
}

export async function exportChartXlsxViaApi(contract, { api, filename = "chart", query = "" } = {}) {
  if (!api?.post) throw new Error("API client required for XLSX export");
  const sheets = buildChartExportSheets(contract, { query });
  const res = await api.post(
    "/export/chart.xlsx",
    { title: contract.title || filename, sheets, filename: safeFilename(filename) },
    { responseType: "blob" }
  );
  downloadBlob(
    res.data,
    `${safeFilename(filename)}.xlsx`,
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
  );
}

/** @deprecated alias */
export const exportChartXlsx = exportChartXlsxViaApi;

export async function exportChartPng(chartNode, filename = "chart") {
  const { exportChartNodeAsImage } = await import("@/lib/chartImageExport");
  await exportChartNodeAsImage(chartNode, "png", safeFilename(filename));
}

function safeFilename(name) {
  return (name || "chart").replace(/[^A-Za-z0-9_-]+/g, "_").slice(0, 60) || "chart";
}

function downloadTextBlob(text, filename, mime) {
  downloadBlob(new Blob([text], { type: mime }), filename);
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 500);
}

/** Validate export rows — no nested JSON, value_raw numeric. */
export function validateExportRows(rows) {
  const errors = [];
  for (const row of rows || []) {
    if (row.value_raw != null && typeof row.value_raw !== "number") {
      errors.push(`value_raw not numeric: ${row.period}/${row.series_id}`);
    }
    for (const [k, v] of Object.entries(row)) {
      if (v != null && typeof v === "object") {
        errors.push(`nested object in ${k}: ${row.period}/${row.series_id}`);
      }
    }
  }
  return { ok: errors.length === 0, errors };
}

export {
  prepareContractForExport,
  detectFrequencyFromPeriods,
  formatExportNumber,
} from "./chartExportSanitize";
