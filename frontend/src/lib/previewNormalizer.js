/**
 * Normalizace odpovědi POST /api/catalog/preview pro vykreslení grafu.
 *
 * Backend může vracet rows/fields/columns v různých tvarech — tato utilita sjednotí
 * payload pro CatalogChartPreview a AradView. Volá se z GlobalCatalogSearchPage.
 */
function toPlainObject(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  return value;
}

export function formatPreviewMessage(value, fallback = "Nahled dat se nepodarilo nacist.") {
  if (value == null || value === false) return "";
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return String(value).trim();
  }
  if (Array.isArray(value)) {
    return value.map((item) => formatPreviewMessage(item, "")).filter(Boolean).join("\n");
  }
  if (typeof value === "object") {
    const direct =
      value.detail_cs ||
      value.message_cs ||
      value.messageCs ||
      value.detail ||
      value.message ||
      value.error ||
      value.title;
    if (direct && direct !== value) {
      const formatted = formatPreviewMessage(direct, "");
      if (formatted) return formatted;
    }
    try {
      return JSON.stringify(value);
    } catch {
      return fallback;
    }
  }
  return fallback;
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

function inferRows(raw) {
  const candidates = [
    raw?.rows,
    raw?.data,
    raw?.observations,
    raw?.values,
    raw?.preview?.rows,
    raw?.preview?.data,
  ];
  for (const cand of candidates) {
    if (!Array.isArray(cand)) continue;
    if (!cand.length) return [];
    if (typeof cand[0] === "object" && cand[0] !== null && !Array.isArray(cand[0])) {
      return cand;
    }
  }
  return [];
}

function inferColumns(raw, rows) {
  const direct = asArray(raw?.columns);
  if (direct.length) {
    return direct
      .map((col) => {
        if (typeof col === "string") return { key: col, label: col };
        if (col && typeof col === "object") {
          const key = String(col.key || col.field || col.id || "").trim();
          const label = String(col.label || col.name || key).trim();
          return key ? { key, label: label || key } : null;
        }
        return null;
      })
      .filter(Boolean);
  }
  const fields = asArray(raw?.fields);
  if (fields.length) {
    return fields
      .map((f) => {
        const key = String(f || "").trim();
        return key ? { key, label: key } : null;
      })
      .filter(Boolean);
  }
  const row0 = rows[0];
  if (row0 && typeof row0 === "object" && !Array.isArray(row0)) {
    return Object.keys(row0).map((k) => ({ key: k, label: k }));
  }
  return [];
}

function inferMetadata(raw, rows, columns) {
  const metaBase = toPlainObject(raw?.metadata);
  const sourceMeta = toPlainObject(raw?.source_metadata);
  const filtersApplied =
    toPlainObject(raw?.filters_applied) ||
    toPlainObject(metaBase?.filters_applied) ||
    toPlainObject(sourceMeta?.filters_applied);
  const fallbackRowCount = Array.isArray(rows) ? rows.length : 0;
  const rowCountRaw = raw?.total_count ?? metaBase?.row_count ?? fallbackRowCount;
  const rowCount = Number.isFinite(Number(rowCountRaw)) ? Number(rowCountRaw) : fallbackRowCount;
  return {
    ...metaBase,
    dimensions: metaBase.dimensions || sourceMeta.dimensions || {},
    filters_applied: filtersApplied,
    row_count: rowCount,
    warning: raw?.warning ?? metaBase?.warning ?? null,
    response_keys: Object.keys(toPlainObject(raw)),
    column_count: columns.length,
  };
}

export function normalizePreviewPayload(raw, sourceTypeHint = "") {
  const safeRaw = toPlainObject(raw);
  const rows = inferRows(safeRaw);
  const columns = inferColumns(safeRaw, rows);
  const metadata = inferMetadata(safeRaw, rows, columns);
  const source = toPlainObject(safeRaw.source);
  const normalized = {
    ...safeRaw,
    source: source.source_type ? source : { ...source, source_type: sourceTypeHint || source.source_type || "" },
    dataset_id:
      String(safeRaw.dataset_id || source.set_id || safeRaw.set_id || source.dataset_id || "").trim() || undefined,
    title: String(safeRaw.title || source.name || safeRaw.name || "").trim() || undefined,
    columns,
    rows,
    metadata,
  };
  return normalized;
}

export function previewShapeDebug(payload) {
  const safe = toPlainObject(payload);
  const keys = Object.keys(safe);
  return {
    keys,
    hasRows: Array.isArray(safe.rows),
    hasData: Array.isArray(safe.data),
    hasObservations: Array.isArray(safe.observations),
    hasColumns: Array.isArray(safe.columns),
    hasFields: Array.isArray(safe.fields),
  };
}

export function buildUnknownPreviewShapeMessage(payload) {
  const dbg = previewShapeDebug(payload);
  return `Neocekavany tvar odpovedi nahledu. Klice: ${dbg.keys.join(", ") || "(zadne)"}`;
}

export function unwrapApiErrorPayload(payload) {
  if (payload && typeof payload === "object" && payload.detail && typeof payload.detail === "object") {
    return payload.detail;
  }
  return payload && typeof payload === "object" ? payload : {};
}

export function buildPreviewPayloadFromStructuredError(rawErrorPayload, source = {}) {
  const err = unwrapApiErrorPayload(rawErrorPayload);
  const message = formatPreviewMessage(err, "Nahled dat se nepodarilo nacist.");
  const datasetId = String(err?.dataset_id || source?.set_id || "").trim();
  const requestedFilters =
    err?.requested_filters && typeof err.requested_filters === "object" ? err.requested_filters : {};
  const availableDimensions =
    err?.available_dimensions && typeof err.available_dimensions === "object" ? err.available_dimensions : {};
  const missingFilters = Array.isArray(err?.missing_filters) ? err.missing_filters : [];
  const droppedFilters =
    err?.dropped_filters && typeof err.dropped_filters === "object" ? err.dropped_filters : {};
  const warnings = Array.isArray(err?.warnings) ? err.warnings : [];
  return normalizePreviewPayload(
    {
      status: String(err?.status || "").trim() || "error",
      error: String(err?.error || "").trim() || "UPSTREAM_ERROR",
      source: {
        source_type: source?.source_type || "",
        set_id: source?.set_id || datasetId || "",
        name: source?.name || "",
      },
      dataset_id: datasetId || undefined,
      rows: [],
      fields: [],
      columns: [],
      message,
      requested_filters: requestedFilters,
      available_dimensions: availableDimensions,
      missing_filters: missingFilters,
      dropped_filters: droppedFilters,
      warnings,
      request_id: String(err?.request_id || "").trim(),
      upstream_status: err?.upstream_status,
      upstream_body_preview: err?.upstream_body_preview,
      metadata: {
        filters_applied: requestedFilters,
        warning: message,
        diagnostic: {
          dataset_id: datasetId || undefined,
          request_id: String(err?.request_id || "").trim() || undefined,
          requested_filters: requestedFilters,
          missing_filters: missingFilters,
          dropped_filters: droppedFilters,
          available_dimensions: availableDimensions,
          upstream_status: err?.upstream_status,
          upstream_body_preview: err?.upstream_body_preview,
          error_code: String(err?.error || "").trim() || undefined,
        },
      },
    },
    source?.source_type || "",
  );
}
