/**
 * Sestavení těla POST /api/catalog/preview ze řádku katalogu (set_id, query_params, geo).
 * Používá GlobalCatalogSearchPage a CatalogSetPreviewPanel.
 */
function normalizeIds(input) {
  if (!Array.isArray(input)) return [];
  const out = [];
  const seen = new Set();
  for (const raw of input) {
    const id = String(raw ?? "").trim();
    if (!id || seen.has(id)) continue;
    seen.add(id);
    out.push(id);
  }
  return out;
}

const GEO_FILTER_KEYS = new Set([
  "geo",
  "ref_area",
  "refarea",
  "country",
  "territory",
  "location",
  "region",
  "nuts",
  "uzemi",
  "uzemi_kraj",
  "kraj",
]);

function normalizeDimensionKey(value) {
  return String(value ?? "")
    .trim()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
}

function isGeoFilterKey(key) {
  const normalized = normalizeDimensionKey(key);
  if (GEO_FILTER_KEYS.has(normalized)) return true;
  return /^(geo|ref_area|country|territory|location|region|nuts)_(id|code|key)$/.test(normalized);
}

export function buildSourcePreviewParams({
  sourceType = "",
  limit = 1000,
  indicatorId = "",
  indicatorIds = null,
  groupField = "",
  geoValues = null,
  dimensionFilters = null,
} = {}) {
  const params = { limit: Math.max(1, Number(limit) || 1) };
  const many = normalizeIds(indicatorIds);
  const geos = normalizeIds(geoValues);
  const st = String(sourceType || "").trim().toLowerCase();
  const gid = String(indicatorId || "").trim();
  const gf = String(groupField || "").trim().toLowerCase();
  const dims = dimensionFilters && typeof dimensionFilters === "object" && !Array.isArray(dimensionFilters)
    ? Object.fromEntries(
        Object.entries(dimensionFilters)
          .map(([k, v]) => {
            const key = String(k || "").trim();
            if (!key) return null;
            if (key === "indicator_id") return null;
            if (Array.isArray(v)) {
              const vals = normalizeIds(v);
              return vals.length ? [key, vals] : null;
            }
            const val = String(v ?? "").trim();
            return val ? [key, val] : null;
          })
          .filter(Boolean)
      )
    : {};
  const dimEntries = Object.entries(dims);
  const dimCount = dimEntries.length;
  const existingGeoKey = dimEntries.find(([k]) => isGeoFilterKey(k))?.[0] || "";

  // Pro Eurostat i další zdroje (ECB/ARAD/...) respektujeme explicitní
  // dimension_filters. Tím funguje i výběr geo dimenze typu REF_AREA.
  if (dimCount > 0) {
    const mergedDims = { ...dims };
    if (geos.length > 0 && !existingGeoKey) mergedDims.geo = geos;
    params.dimension_filters = JSON.stringify(mergedDims);
    if (st === "eurostat") return params;
  } else if (geos.length > 0) {
    params.dimension_filters = JSON.stringify({ geo: geos });
    if (st === "eurostat") return params;
  }
  if (many.length > 1) {
    params.indicator_ids = many.join(",");
    return params;
  }
  if (!gid) return params;
  if (st !== "eurostat") {
    params.indicator_id = gid;
    return params;
  }
  if (gf === "geo" || gf === "ref_area") {
    params.dimension_filters = JSON.stringify({ [gf]: gid });
  }
  return params;
}
