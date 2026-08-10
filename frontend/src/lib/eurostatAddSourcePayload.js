function normalizeSetId(input) {
  if (typeof input === "string") return input.trim();
  return String(input?.set_id ?? "").trim();
}

function qpTokens(value) {
  if (Array.isArray(value)) {
    return value.map((v) => String(v ?? "").trim()).filter(Boolean);
  }
  return String(value ?? "")
    .split(",")
    .map((v) => v.trim())
    .filter(Boolean);
}

/**
 * Keep Eurostat query params usable for time-series charts.
 * - removes internal/preview-only hints
 * - avoids fixed single-point `time=...`
 * - removes `lastTimePeriod`, which is a latest-period switch, not a row count
 */
export function sanitizeEurostatQueryParams(inputQueryParams) {
  const qp = {};
  const src = inputQueryParams && typeof inputQueryParams === "object" ? inputQueryParams : {};
  for (const [k, val] of Object.entries(src)) {
    if (val == null) continue;
    const key = String(k || "").trim();
    if (!key) continue;
    const hasValue = Array.isArray(val) ? String(val[0] ?? "").trim() : String(val).trim();
    if (!hasValue) continue;
    qp[key] = val;
  }
  delete qp.query_mode;
  const timeTokens = qpTokens(qp.time);
  if (timeTokens.length <= 1) delete qp.time;

  for (const key of Object.keys(qp)) {
    const normalized = key.toLowerCase().replace(/[^a-z0-9]+/g, "");
    if (normalized === "lasttimeperiod") delete qp[key];
  }
  const geoTokens = qpTokens(qp.geo).map((v) => String(v || "").trim().toUpperCase()).filter(Boolean);
  if (geoTokens.length > 1) {
    qp.geo = geoTokens;
  } else if (geoTokens.length === 1) {
    qp.geo = geoTokens[0];
  }
  return qp;
}

function pickCatalogNodeRef(input, setId) {
  const fromCandidate = input?.catalog_node_ref;
  if (fromCandidate && typeof fromCandidate === "object") {
    return {
      ...fromCandidate,
      source: "eurostat",
      set_id: String(fromCandidate.set_id || setId),
      add_source_endpoint: "/api/eurostat/catalog/add-source",
    };
  }
  const fullPath = String(input?.full_path || "").trim();
  if (!setId) return null;
  return {
    source: "eurostat",
    set_id: setId,
    node_path: fullPath,
    add_source_endpoint: "/api/eurostat/catalog/add-source",
  };
}

/**
 * Shared payload builder for both manual Eurostat catalog and AI search entries.
 * AI mode can carry `catalog_node_ref` and optional resolved query params from the candidate.
 */
export function buildEurostatAddSourceBody(input, opts = {}) {
  const setId = normalizeSetId(input);
  const body = { set_id: setId };
  const geo = String(input?.eurostat_geo || "").trim().toUpperCase();
  if (geo) body.geo = geo;
  const geoScope = String(input?.geo_scope || input?.eurostat_geo_scope || "").trim();
  if (geoScope) body.geo_scope = geoScope;
  if (input?.preview_mode === true) body.preview_mode = true;
  if (input?.full_export === true) body.full_export = true;
  if (input?.query_params && typeof input.query_params === "object") {
    const qp = sanitizeEurostatQueryParams(input.query_params);
    if (Object.keys(qp).length) body.query_params = qp;
  }
  if (!opts?.fromAiSearch) return body;

  body.from_ai_search = true;
  const nodeRef = pickCatalogNodeRef(input, setId);
  if (nodeRef) body.catalog_node_ref = nodeRef;
  return body;
}
