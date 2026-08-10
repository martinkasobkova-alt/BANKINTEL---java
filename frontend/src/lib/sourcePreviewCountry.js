import { asGeoCode, geoDisplayLabel, readGeoOptionsFromDimensionMeta, toNormalizedToken } from "./sourcePreviewGeo";

/** Eurostat FIGARO / env_ac: country axes without classic `geo` dimension. */
export const FIGARO_FLOW_COUNTRY_DIM_KEYS = ["c_orig", "c_dest", "c_imp", "c_exp"];

/** Výchozí země určení u FIGARO — uživatel nemění, vždy EU agregát. */
export const EUROSTAT_FIGARO_DEST_DEFAULT = "EU27_2020";

const EU_DEST_PICK_ORDER = [
  "EU27_2020",
  "EU27_2007",
  "EU27",
  "EU28",
  "EU",
  "EA20",
  "EA19",
  "EA",
];

/** EU27 členské státy + běžné agregáty v FIGARO c_orig (ne celý svět z metadat). */
export const EUROSTAT_EU_MEMBER_CODES = new Set([
  "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "EL",
  "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
]);

export const EUROSTAT_EU_EXTRA_CODES = new Set([
  ...EU_DEST_PICK_ORDER,
  "CH", "NO", "IS", "LI", "UK", "GB",
]);

export const EUROSTAT_FIGARO_ORIGIN_PICK_ORDER = [
  "CZ", "DE", "SK", "PL", "AT", "HU", "FR", "IT", "ES", "NL", "BE", "SE", "DK", "FI",
  "EU27_2020", "EU27", "EA20", "EA",
];

const FIGARO_WORLD_CODE_HINTS = new Set([
  "WRL_REST", "WORLD", "WLD", "WRL", "TOTAL", "T", "ALL", "TOT", "_T", "OTH", "EXTRA_EU",
]);

export const CLASSIC_COUNTRY_DIM_KEYS = ["geo", "ref_area", "country"];

export const PRIMARY_COUNTRY_DIM_ORDER = [
  ...CLASSIC_COUNTRY_DIM_KEYS,
  "c_orig",
  "c_imp",
  "c_exp",
];

export const COUNTRY_DIM_LABELS_CS = {
  geo: "Země",
  ref_area: "Země",
  country: "Země",
  c_orig: "Země původu",
  c_dest: "Země určení",
  c_imp: "Země dovozu",
  c_exp: "Země vývozu",
};

export function isCountryDimensionKey(key) {
  const lk = toNormalizedToken(key);
  return (
    CLASSIC_COUNTRY_DIM_KEYS.includes(lk) || FIGARO_FLOW_COUNTRY_DIM_KEYS.includes(lk)
  );
}

export function countryDimensionLabel(key) {
  const lk = toNormalizedToken(key);
  return COUNTRY_DIM_LABELS_CS[lk] || String(key || "").trim() || "Země";
}

export function resolvePrimaryCountryDimensionKey(availableDimensions, filters = {}) {
  const dimKeys = Object.keys(availableDimensions || {}).map((k) => String(k || "").trim()).filter(Boolean);
  const dimSet = new Set(dimKeys.map((k) => toNormalizedToken(k)));
  const explicitFilterKey = Object.keys(filters || {}).find((key) => {
    if (!isCountryDimensionKey(key)) return false;
    const value = filters?.[key];
    return Array.isArray(value)
      ? value.some((item) => String(item ?? "").trim() !== "")
      : value != null && String(value).trim() !== "";
  });
  if (explicitFilterKey) return explicitFilterKey;
  for (const candidate of PRIMARY_COUNTRY_DIM_ORDER) {
    if (!dimSet.has(candidate)) continue;
    const exact = dimKeys.find((k) => toNormalizedToken(k) === candidate);
    return exact || candidate;
  }
  for (const candidate of FIGARO_FLOW_COUNTRY_DIM_KEYS) {
    const val = filters?.[candidate] ?? filters?.[candidate.toUpperCase()];
    if (val != null && String(val).trim() !== "") return candidate;
  }
  return "geo";
}

export function resolveFigaroDestinationCode(availableDimensions) {
  if (!dimKeysInclude(availableDimensions, "c_dest")) return "";
  const options = readCountryOptionsFromDimensions(availableDimensions, "c_dest");
  const codes = new Set(options.map((o) => asGeoCode(o.value)).filter(Boolean));
  for (const candidate of EU_DEST_PICK_ORDER) {
    if (codes.has(candidate)) return candidate;
  }
  return EUROSTAT_FIGARO_DEST_DEFAULT;
}

/** Nastaví c_dest na EU — volá se při každém výběru země původu (bez UI pro určení). */
export function applyEurostatFigaroFixedFilters(queryParams, availableDimensions) {
  const destCode = resolveFigaroDestinationCode(availableDimensions);
  if (!destCode) return { ...(queryParams || {}) };
  const out = { ...(queryParams || {}) };
  const destKey =
    Object.keys(availableDimensions || {}).find((k) => toNormalizedToken(k) === "c_dest") || "c_dest";
  out[destKey] = destCode;
  return out;
}

export function isFigaroWorldAggregate(code, label = "") {
  const c = asGeoCode(code);
  if (!c) return true;
  if (FIGARO_WORLD_CODE_HINTS.has(c)) return true;
  const lab = String(label || "").toLowerCase();
  if (
    lab.includes("all countries") ||
    lab.includes("all countries of the world") ||
    lab.includes("rest of the world") ||
    lab.includes("celý svět") ||
    lab.includes("zbytek světa")
  ) {
    return true;
  }
  return false;
}

export function isEurostatFigaroPickerCountry(code) {
  const c = asGeoCode(code);
  if (!c || isFigaroWorldAggregate(c)) return false;
  if (EUROSTAT_EU_EXTRA_CODES.has(c)) return true;
  if (EUROSTAT_EU_MEMBER_CODES.has(c)) return true;
  return false;
}

/** Ořízne seznam z Eurostat metadat (c_orig) na EU/EEA — bez Argentiny, „celého světa“ atd. */
export function filterFigaroOriginCountryOptions(options) {
  const list = Array.isArray(options) ? options : [];
  const filtered = list.filter((opt) => {
    const value = asGeoCode(opt?.value);
    if (!value) return false;
    if (Number(opt?.rowCount || 0) > 0) return isEurostatFigaroPickerCountry(value) || EUROSTAT_EU_MEMBER_CODES.has(value);
    return isEurostatFigaroPickerCountry(value);
  });
  const orderIndex = new Map(EUROSTAT_FIGARO_ORIGIN_PICK_ORDER.map((c, i) => [c, i]));
  return [...filtered].sort((a, b) => {
    const ai = orderIndex.has(a.value) ? orderIndex.get(a.value) : 999;
    const bi = orderIndex.has(b.value) ? orderIndex.get(b.value) : 999;
    if (ai !== bi) return ai - bi;
    return String(a.label || a.value).localeCompare(String(b.label || b.value), "cs");
  });
}

export function pickDefaultFigaroOriginCountries(options, max = 1) {
  const pool = filterFigaroOriginCountryOptions(options);
  const allowed = new Set(pool.map((o) => o.value));
  const out = [];
  for (const code of EUROSTAT_FIGARO_ORIGIN_PICK_ORDER) {
    if (!allowed.has(code)) continue;
    out.push(code);
    if (out.length >= max) return out;
  }
  const withData = pool.filter((o) => Number(o.rowCount || 0) > 0);
  for (const opt of withData) {
    if (out.includes(opt.value)) continue;
    out.push(opt.value);
    if (out.length >= max) return out;
  }
  if (pool[0]?.value) out.push(pool[0].value);
  if (!out.length && allowed.has("CZ")) return ["CZ"];
  return out.slice(0, max);
}

export function readCountryOptionsFromDimensions(availableDimensions, primaryKey) {
  const key = String(primaryKey || "").trim();
  if (!key || !availableDimensions?.[key]) return [];
  const raw = readGeoOptionsFromDimensionMeta(availableDimensions[key]);
  if (toNormalizedToken(key) === "c_orig") {
    return filterFigaroOriginCountryOptions(raw);
  }
  return raw;
}

export function extractCountryCodesFromFilters(filters) {
  if (!filters || typeof filters !== "object" || Array.isArray(filters)) return [];
  for (const candidate of PRIMARY_COUNTRY_DIM_ORDER) {
    const entry = Object.entries(filters).find(([k]) => toNormalizedToken(k) === candidate);
    if (!entry) continue;
    const [, raw] = entry;
    if (Array.isArray(raw)) {
      return [...new Set(raw.map((x) => asGeoCode(x)).filter(Boolean))];
    }
    const one = asGeoCode(raw);
    if (one) return [one];
  }
  return [];
}

export function applyEurostatCountrySelection(queryParams, countryCodes, availableDimensions, extraFilters = {}) {
  const out = {
    ...(queryParams && typeof queryParams === "object" ? queryParams : {}),
    ...(extraFilters && typeof extraFilters === "object" ? extraFilters : {}),
  };
  const codes = [...new Set((countryCodes || []).map((x) => asGeoCode(x)).filter(Boolean))];
  if (!codes.length) return out;
  const key = resolvePrimaryCountryDimensionKey(availableDimensions, out);
  const lk = toNormalizedToken(key);
  delete out.geo_scope;
  if (lk === "geo" || lk === "ref_area" || lk === "country") {
    out.geo = codes.length === 1 ? codes[0] : codes;
  } else {
    out[key] = codes.length === 1 ? codes[0] : codes;
    if (!dimKeysInclude(availableDimensions, "geo")) {
      delete out.geo;
    }
  }
  return applyEurostatFigaroFixedFilters(out, availableDimensions);
}

function dimKeysInclude(availableDimensions, key) {
  const lk = toNormalizedToken(key);
  return Object.keys(availableDimensions || {}).some((k) => toNormalizedToken(k) === lk);
}

export function formatFigaroCountryContext(filters, filterDisplayLabels, availableDimensions, primaryKey) {
  const primary = String(primaryKey || "").trim();
  const codes = extractCountryCodesFromFilters(filters);
  const primaryCode = codes[0] || "";
  const primaryLabel =
    filterDisplayLabels?.[primary] ||
    geoDisplayLabel(primaryCode, "", buildCountryLabelLookup(availableDimensions, primary));
  const destCode = resolveFigaroDestinationCode(availableDimensions);
  const destLabel = destCode
    ? geoDisplayLabel(destCode, "Evropská unie (EU)", buildCountryLabelLookup(availableDimensions, "c_dest"))
    : "Evropská unie (EU)";
  return {
    primaryKey: primary,
    primaryCode,
    primaryLabel: primaryLabel || primaryCode,
    destCode,
    destLabel,
    isFigaro: FIGARO_FLOW_COUNTRY_DIM_KEYS.includes(toNormalizedToken(primary)),
  };
}

export function buildCountryLabelLookup(availableDimensions, ...extraKeys) {
  const lookup = {};
  const keys = [...PRIMARY_COUNTRY_DIM_ORDER, ...FIGARO_FLOW_COUNTRY_DIM_KEYS, ...extraKeys].filter(Boolean);
  const seen = new Set();
  for (const key of keys) {
    const norm = toNormalizedToken(key);
    if (seen.has(norm)) continue;
    seen.add(norm);
    for (const opt of readCountryOptionsFromDimensions(availableDimensions, key)) {
      if (opt.value && opt.label) lookup[opt.value] = opt.label;
    }
  }
  return lookup;
}

export function isFigaroLikeDataset(availableDimensions, datasetId = "") {
  const dims = Object.keys(availableDimensions || {}).map((k) => toNormalizedToken(k));
  if (dims.includes("geo")) return false;
  const sid = String(datasetId || "").trim().toLowerCase();
  if (sid.startsWith("env_ac_") || sid.startsWith("naio_10_")) return true;
  return FIGARO_FLOW_COUNTRY_DIM_KEYS.some((k) => dims.includes(k));
}
