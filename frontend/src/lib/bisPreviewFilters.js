/** Map SourcePreview dimension filters to BIS SDMX query params for /catalog/preview. */

const GEO_KEYS = new Set(["geo", "ref_area", "country"]);
const TIME_KEYS = new Set(["time_period", "time", "period", "date", "obdobi"]);

export function extractGeoValuesFromDimensionFilters(filters) {
  if (!filters || typeof filters !== "object" || Array.isArray(filters)) return [];
  const entry = Object.entries(filters).find(([k]) => GEO_KEYS.has(String(k || "").trim().toLowerCase()));
  if (!entry) return [];
  const [, raw] = entry;
  if (Array.isArray(raw)) {
    return [...new Set(raw.map((x) => String(x || "").trim().toUpperCase()).filter(Boolean))];
  }
  const one = String(raw ?? "").trim().toUpperCase();
  return one ? [one] : [];
}

/**
 * @param {Record<string, unknown>} queryParams
 * @param {Record<string, unknown>|null|undefined} dimensionFilters
 */
export function applyBisDimensionFiltersToQueryParams(queryParams, dimensionFilters) {
  const out = {
    ...(queryParams && typeof queryParams === "object" && !Array.isArray(queryParams) ? queryParams : {}),
  };
  delete out.startPeriod;
  delete out.endPeriod;
  if (!dimensionFilters || typeof dimensionFilters !== "object" || Array.isArray(dimensionFilters)) {
    return out;
  }
  for (const [rawKey, rawVal] of Object.entries(dimensionFilters)) {
    const key = String(rawKey || "").trim();
    if (!key || key === "indicator_id") continue;
    const val = Array.isArray(rawVal)
      ? String(rawVal[0] ?? "").trim()
      : String(rawVal ?? "").trim();
    if (!val) continue;
    const lk = key.toLowerCase();
    if (TIME_KEYS.has(lk)) {
      out.startPeriod = val;
      out.endPeriod = val;
      continue;
    }
    if (GEO_KEYS.has(lk)) {
      out.REF_AREA = val;
      continue;
    }
    out[key] = val;
  }
  return out;
}
