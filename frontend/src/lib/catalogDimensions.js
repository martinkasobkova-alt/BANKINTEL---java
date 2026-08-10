/**
 * Jednotný model dimenzí pro katalogové preview (source-agnostic).
 * AradView je renderer; dimenze řeší controller nad ním.
 */

import { readOptionsFromDimensionMeta } from "@/lib/sourcePreviewDimensionMeta";

const GEO_KEYS = new Set(["geo", "ref_area", "REF_AREA", "country", "territory", "refarea"]);
const FREQ_KEYS = new Set(["freq", "frequency", "FREQ", "time_freq"]);
const UNIT_KEYS = new Set(["unit", "UNIT", "unit_measure"]);

function inferDimensionType(field) {
  const f = String(field || "").trim().toLowerCase();
  if (GEO_KEYS.has(f) || f.includes("geo") || f.includes("zem") || f.includes("country")) return "geo";
  if (FREQ_KEYS.has(f)) return "frequency";
  if (UNIT_KEYS.has(f)) return "unit";
  if (f.includes("indic") || f.includes("metric") || f.includes("variable")) return "metric";
  if (f.includes("time") || f.includes("period")) return "time";
  return "category";
}

function normalizeDimensionValues(raw, meta) {
  const opts = readOptionsFromDimensionMeta(meta);
  if (opts.length) {
    return opts.map((o) => ({
      id: String(o.value),
      label: String(o.label || o.value),
    }));
  }
  if (Array.isArray(raw)) {
    return raw.map((v) => {
      const id = String(v);
      return { id, label: id };
    });
  }
  return [];
}

/**
 * @param {string} field
 * @param {*} meta
 * @param {*} selected
 * @returns {import('./catalogDimensions').CatalogDimension}
 */
export function buildCatalogDimension(field, meta = {}, selected = null) {
  const id = String(field || "").trim();
  const type = inferDimensionType(id);
  const values = normalizeDimensionValues(meta?.values, meta);
  const label =
    String(meta?.label || meta?.name || id).trim() || id;
  const selectedId =
    selected != null && String(selected).trim() !== ""
      ? String(selected).trim()
      : values[0]?.id ?? null;
  return {
    id,
    label,
    type,
    required: Boolean(meta?.required),
    selected: selectedId,
    values,
    default_value: values[0]?.id ?? null,
    source: String(meta?.source || "").trim() || null,
    is_geo: type === "geo",
    is_time: type === "time",
    is_frequency: type === "frequency",
    is_unit: type === "unit",
    is_metric: type === "metric",
    cardinality: values.length,
    search_enabled: values.length > 12,
  };
}

/**
 * Sestaví stav preview včetně dimenzí z API odpovědi.
 * @param {object} preview — odpověď /catalog/preview nebo /sources/.../preview
 * @param {object} [ctx] — { catalogId, sourceType, rowTitle }
 */
export function buildCatalogPreviewState(preview, ctx = {}) {
  const p = preview && typeof preview === "object" ? preview : {};
  const dimensions = [];
  const selected = {};

  const avail = {
    ...(p.metadata?.dimensions || {}),
    ...(p.available_dimensions || {}),
    ...(p.diagnostic?.available_dimensions || {}),
  };

  const applied =
    p.metadata?.filters_applied ||
    p.requested_filters ||
    p.dimension_filters ||
    {};

  for (const [field, meta] of Object.entries(avail)) {
    const sel = applied[field] ?? applied[field.toUpperCase()];
    const dim = buildCatalogDimension(field, meta, sel);
    dimensions.push(dim);
    if (dim.selected) selected[field] = dim.selected;
  }

  for (const extra of Array.isArray(p.extra_dimensions) ? p.extra_dimensions : []) {
    const field = String(extra?.field || "").trim();
    if (!field || dimensions.some((d) => d.id === field)) continue;
    const dim = buildCatalogDimension(field, { values: extra.values }, applied[field]);
    dimensions.push(dim);
    if (dim.selected) selected[field] = dim.selected;
  }

  for (const sd of Array.isArray(p.selectable_dimensions) ? p.selectable_dimensions : []) {
    const field = String(sd?.field || "").trim();
    if (!field || dimensions.some((d) => d.id === field)) continue;
    const dim = buildCatalogDimension(field, sd, applied[field]);
    dimensions.push(dim);
    if (dim.selected) selected[field] = dim.selected;
  }

  const missing = Array.isArray(p.missing_filters) ? p.missing_filters : [];
  const needsSelection = p.status === "needs_filters" || missing.length > 0;

  return {
    source: ctx.sourceType || p.source?.source_type || "",
    dataset: String(p.dataset_id || p.source?.set_id || ctx.setId || "").trim(),
    dimensions,
    selected_dimensions: selected,
    available_dimensions: avail,
    missing_filters: missing,
    needs_selection: needsSelection,
    chart_config: {
      chart_type: "line",
      chart_data_mode: String(p.chart_data_mode || p.metadata?.chart_data_mode || "history").toLowerCase(),
    },
    rows: Array.isArray(p.rows) ? p.rows : [],
    metadata: {
      unit: p.unit || p.metadata?.unit || p.metadata?.unit_label_cs || "",
      frequency: p.chart_frequency || p.metadata?.chart_frequency || "",
      last_period: extractLastPeriod(p.rows),
      title: ctx.rowTitle || p.title || p.source?.name || "",
      filters_applied: p.metadata?.filters_applied || {},
    },
  };
}

function extractLastPeriod(rows) {
  const list = Array.isArray(rows) ? rows : [];
  if (!list.length) return "";
  const last = list[list.length - 1];
  return String(
    last?.period ?? last?.x ?? last?.TIME_PERIOD ?? last?.date ?? ""
  ).trim();
}

/** Extrahuje selected_dimensions pro uložení do dashboard widget config. */
export function extractSelectedDimensionsForStorage(preview, previewState = null) {
  const state = previewState || buildCatalogPreviewState(preview);
  const out = { ...(state.selected_dimensions || {}) };

  const geo = extractGeoSelection(preview);
  if (geo.length) {
    out.geo = geo.length === 1 ? geo[0] : geo;
    out.REF_AREA = out.geo;
  }

  const ind = preview?.selected_indicator;
  if (ind != null && String(ind).trim()) out.selected_indicator = String(ind).trim();

  const many = Array.isArray(preview?.selected_indicators) ? preview.selected_indicators : [];
  if (many.length > 1) out.selected_indicators = many.map((x) => String(x).trim()).filter(Boolean);

  const applied = preview?.metadata?.filters_applied || preview?.dimension_filters || {};
  for (const [k, v] of Object.entries(applied)) {
    if (v == null || v === "") continue;
    out[k] = v;
  }

  // Náhled může úspěšně načíst data až po serverovém doplnění filtrů (Eurostat HICP, ECB …).
  // `requested_filters` pak obsahuje skutečně použité parametry i když metadata.filters_applied chybí.
  const requested = preview?.requested_filters;
  if (requested && typeof requested === "object") {
    for (const [k, v] of Object.entries(requested)) {
      if (v == null || v === "" || out[k] != null) continue;
      out[k] = v;
    }
  }

  return out;
}

export function extractGeoSelection(preview) {
  const fromApplied = preview?.metadata?.filters_applied;
  const fromRequested = preview?.requested_filters;
  const raw =
    (fromApplied && (fromApplied.geo || fromApplied.REF_AREA || fromApplied.ref_area)) ||
    (fromRequested && (fromRequested.geo || fromRequested.REF_AREA || fromRequested.ref_area));
  if (!raw) return [];
  const list = Array.isArray(raw) ? raw : [raw];
  return [...new Set(list.map((x) => String(x || "").trim().toUpperCase()).filter(Boolean))].slice(0, 8);
}

export function catalogPreviewNeedsDimensionChoice(preview) {
  if (!preview) return true;
  if (preview.status === "needs_filters") return true;
  if (Array.isArray(preview.missing_filters) && preview.missing_filters.length) return true;
  const rows = Array.isArray(preview.rows) ? preview.rows : [];
  if (rows.length < 2 && !preview.error) return true;
  return false;
}

const SPLIT_DIM_SKIP_FIELDS = new Set(["source", "source_type", "variable"]);

function normalizeSplitDimensionEntry(entry) {
  if (!entry) return null;
  if (typeof entry === "string") return { field: entry.trim(), values: [] };
  if (typeof entry !== "object") return null;
  const field = String(entry.field || entry.name || entry.id || "").trim();
  if (!field) return null;
  const values = Array.isArray(entry.values)
    ? entry.values.map((v) => String(v)).filter(Boolean)
    : [];
  return { field, values };
}

/**
 * Sloučí dimenze z API odpovědi (data) a uložené konfigurace widgetu (config).
 * Config často obsahuje plný seznam hodnot z katalogového náhledu; data po filtraci ne.
 */
export function mergeAvailableSplitDimensions(data, config) {
  const sources = [
    ...(Array.isArray(data?.available_split_dimensions) ? data.available_split_dimensions : []),
    ...(Array.isArray(data?.extra_dimensions) ? data.extra_dimensions : []),
    ...(Array.isArray(config?.available_split_dimensions) ? config.available_split_dimensions : []),
  ];
  const filterFields =
    config?.dimension_filters && typeof config.dimension_filters === "object"
      ? Object.keys(config.dimension_filters)
      : [];
  for (const field of filterFields) {
    if (field) sources.push({ field, values: [] });
  }
  const configuredSplit = String(config?.chart_series_dim || "").trim();
  if (configuredSplit) sources.unshift({ field: configuredSplit, values: [] });

  const byField = new Map();
  for (const raw of sources) {
    const dim = normalizeSplitDimensionEntry(raw);
    if (!dim) continue;
    const key = dim.field.toLowerCase();
    if (SPLIT_DIM_SKIP_FIELDS.has(key)) continue;
    const prev = byField.get(key) || { field: dim.field, values: new Set() };
    for (const v of dim.values) prev.values.add(v);
    byField.set(key, prev);
  }

  return [...byField.values()]
    .map(({ field, values }) => ({
      field,
      values: [...values].sort((a, b) => a.localeCompare(b, "cs")),
    }))
    .filter((d) => d.field && d.values.length >= 2)
    .slice(0, 12);
}

export function buildCompareConfigFromPreview(def, preview, row) {
  const built = {
    catalog: def?.id,
    set_id: String(row?.set_id ?? "").trim(),
    source_type: "external_catalog",
    selected_indicator: String(preview?.selected_indicator || "").trim(),
    group_field: String(preview?.group_field || "").trim(),
    query_params: {},
    selected_dimensions: extractSelectedDimensionsForStorage(preview),
  };
  const geo = extractGeoSelection(preview);
  if (geo.length) {
    built.query_params = { geo, REF_AREA: geo.length === 1 ? geo[0] : geo };
  }
  return built;
}
