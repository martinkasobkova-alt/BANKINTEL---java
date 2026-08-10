import { GEO_LABELS_CS } from "@/lib/macroGeoLabels";
import ISO3166_NUMERIC from "@/lib/iso3166NumericMap.json";

const LABEL_TO_ISO = Object.fromEntries(
  Object.entries(GEO_LABELS_CS).map(([iso, label]) => [label.toLowerCase(), iso])
);

/** Evropské státy (ISO2) pro filtr mapy. */
export const EUROPE_ISO2 = new Set([
  "AL", "AD", "AT", "BY", "BE", "BA", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR",
  "HU", "IS", "IE", "IT", "XK", "LV", "LI", "LT", "LU", "MT", "MD", "MC", "ME", "NL", "MK", "NO",
  "PL", "PT", "RO", "RU", "SM", "RS", "SK", "SI", "ES", "SE", "CH", "TR", "UA", "GB", "VA",
]);

export const MAP_PROJECTIONS = {
  cz: { center: [15.5, 49.75], scale: 3200, width: 800, height: 420 },
  europe: { center: [12, 52], scale: 680, width: 800, height: 420 },
  world: { center: [10, 20], scale: 120, width: 800, height: 420 },
};

/** Ořez mapy podle regionu — bez toho Rusko / zámořská území roztáhnou viewBox na celý svět. */
export const REGION_LON_LAT_BOUNDS = {
  cz: { west: 8, east: 24, south: 47.5, north: 52.5 },
  europe: { west: -25, east: 44, south: 33, north: 71 },
  world: null,
};

export function getRegionLonLatBounds(region) {
  const key = String(region || "world").toLowerCase();
  return REGION_LON_LAT_BOUNDS[key] ?? null;
}

export function coordInRegionBounds(lon, lat, bounds) {
  if (!bounds) return true;
  const λ = Number(lon);
  const φ = Number(lat);
  if (!Number.isFinite(λ) || !Number.isFinite(φ)) return false;
  return λ >= bounds.west && λ <= bounds.east && φ >= bounds.south && φ <= bounds.north;
}

/** ECB / Eurostat agregáty — ne vykreslovat na choropleth mapě zemí. */
export const NON_COUNTRY_GEO = new Set([
  "EU", "EA", "EZ", "U2", "U3", "I9", "UEM", "EUU", "WLD", "WOO", "OECD", "G7", "G20",
]);

export const WORLD_ATLAS_URL = `${process.env.PUBLIC_URL || ""}/world-atlas-countries-110m.json`;

export const WORLD_ATLAS_FALLBACK_URLS = [
  WORLD_ATLAS_URL,
  "/world-atlas-countries-110m.json",
  "https://cdn.jsdelivr.net/npm/world-atlas@2/countries-110m.json",
];

/** Názvy z Natural Earth / world-atlas → ISO2 */
const ATLAS_NAME_TO_ISO = {
  czechia: "CZ",
  "czech rep.": "CZ",
  "czech republic": "CZ",
  slovakia: "SK",
  germany: "DE",
  austria: "AT",
  poland: "PL",
  hungary: "HU",
  france: "FR",
  italy: "IT",
  spain: "ES",
  portugal: "PT",
  netherlands: "NL",
  belgium: "BE",
  sweden: "SE",
  denmark: "DK",
  finland: "FI",
  ireland: "IE",
  greece: "GR",
  romania: "RO",
  bulgaria: "BG",
  croatia: "HR",
  slovenia: "SI",
  estonia: "EE",
  latvia: "LV",
  lithuania: "LT",
  luxembourg: "LU",
  malta: "MT",
  cyprus: "CY",
  "united kingdom": "GB",
  "great britain": "GB",
  "united states of america": "US",
  "united states": "US",
  japan: "JP",
  china: "CN",
  switzerland: "CH",
  norway: "NO",
  ukraine: "UA",
  russia: "RU",
  turkey: "TR",
  brazil: "BR",
  canada: "CA",
  australia: "AU",
  mexico: "MX",
  india: "IN",
  iceland: "IS",
  albania: "AL",
  "bosnia and herz.": "BA",
  "bosnia and herzegovina": "BA",
  macedonia: "MK",
  serbia: "RS",
  montenegro: "ME",
  kosovo: "XK",
  belarus: "BY",
  moldova: "MD",
  armenia: "AM",
  azerbaijan: "AZ",
  georgia: "GE",
  "dem. rep. congo": "CD",
  "dominican rep.": "DO",
  "eq. guinea": "GQ",
  "central african rep.": "CF",
  "s. sudan": "SS",
  "w. sahara": "EH",
  eswatini: "SZ",
  "solomon is.": "SB",
  "fr. s. antarctic lands": "TF",
  "n. cyprus": "CY",
  somaliland: "SO",
};

function normalizeIso(raw) {
  const t = String(raw || "").trim().toUpperCase();
  if (!t) return "";
  if (t === "UK") return "GB";
  if (/^EU\d/.test(t) || /^EA\d/.test(t) || t === "EU" || t === "EA") return "";
  if (t.length === 2 && /^[A-Z]{2}$/.test(t)) return t;
  if (t.length === 3 && GEO_LABELS_CS[t]) return t.slice(0, 2) === "EU" ? "" : t;
  return "";
}

function isoFromAtlasName(name) {
  const key = String(name || "").trim().toLowerCase();
  if (!key) return "";
  if (ATLAS_NAME_TO_ISO[key]) return ATLAS_NAME_TO_ISO[key];
  return "";
}

/** Extrahuje ISO z dlouhého názvu řady (např. „HICP — Česko“ nebo „… (CZ)“). */
function extractEmbeddedGeoToken(raw) {
  const text = String(raw || "").trim();
  if (!text) return "";

  const paren = text.match(/\(([A-Za-z]{2,3})\)\s*$/);
  if (paren) {
    const iso = normalizeIso(paren[1]);
    if (iso) return iso;
  }

  const suffix = text.match(/[:\-–—|]\s*(.+)\s*$/);
  if (suffix) {
    const part = suffix[1].trim();
    const iso = normalizeIso(part) || isoFromAtlasName(part);
    if (iso) return iso;
  }

  const bracket = text.match(/\b([A-Z]{2})\d{0,2}\b/);
  if (bracket && GEO_LABELS_CS[bracket[1]]) return bracket[1];

  const fromAtlas = isoFromAtlasName(text);
  if (fromAtlas) return fromAtlas;

  const lower = text.toLowerCase();
  for (const [iso, name] of Object.entries(GEO_LABELS_CS)) {
    if (iso.length !== 2 || !name) continue;
    const needle = name.toLowerCase();
    if (lower === needle) return iso;
    if (lower.includes(needle)) return iso;
  }

  return "";
}

export function rowLabelToIso(label) {
  const raw = String(label || "").trim();
  if (!raw) return "";
  if (/^[A-Za-z]{2,3}$/.test(raw)) {
    const iso = normalizeIso(raw);
    return iso && !NON_COUNTRY_GEO.has(iso) ? iso : "";
  }
  const fromLabel = LABEL_TO_ISO[raw.toLowerCase()];
  if (fromLabel) {
    const iso = normalizeIso(fromLabel);
    return iso && !NON_COUNTRY_GEO.has(iso) ? iso : "";
  }
  for (const [iso, name] of Object.entries(GEO_LABELS_CS)) {
    if (name.toLowerCase() === raw.toLowerCase()) {
      const n = normalizeIso(iso);
      return n && !NON_COUNTRY_GEO.has(n) ? n : "";
    }
  }
  const embedded = extractEmbeddedGeoToken(raw);
  return embedded && !NON_COUNTRY_GEO.has(embedded) ? embedded : "";
}

export function isGeoGroupField(field) {
  const f = String(field || "").trim().toLowerCase();
  if (!f) return false;
  if (["geo", "ref_area", "country", "territory", "location"].includes(f)) return true;
  return /(^|_)(geo|ref_area|country|kraj|region)(_|$)/.test(f);
}

function resolveSeriesGeoIso(series, index, displayLabels, geoHints) {
  const candidates = [
    Array.isArray(geoHints) ? geoHints[index] : "",
    Array.isArray(displayLabels) ? displayLabels[index] : "",
    series?.geo,
    series?.ref_area,
    series?.country,
    series?.name,
    series?.label,
    series?.id,
    series?.key,
  ];
  for (const c of candidates) {
    const iso = rowLabelToIso(c);
    if (iso) return iso;
  }
  return "";
}

export function seriesDisplayLabel(series) {
  return String(series?.label || series?.name || series?.key || "").trim();
}

export function seriesLabelLooksGeographic(label) {
  return Boolean(rowLabelToIso(label));
}

function resolveSeriesLabel(series, index, displayLabels, geoHints) {
  const fromDisplay = Array.isArray(displayLabels) ? String(displayLabels[index] || "").trim() : "";
  if (fromDisplay) return fromDisplay;
  const fromHint = Array.isArray(geoHints) ? String(geoHints[index] || "").trim() : "";
  if (fromHint) return fromHint;
  return seriesDisplayLabel(series);
}

/** Více řad po zemích (CZ, DE, …) — vhodné pro mapu. */
export function multiSeriesLooksGeographic(seriesMeta, displayLabels, geoHints) {
  const list = Array.isArray(seriesMeta) ? seriesMeta : [];
  if (list.length < 2) return false;
  const geoCount = list.filter((s, i) =>
    seriesLabelLooksGeographic(resolveSeriesLabel(s, i, displayLabels, geoHints))
  ).length;
  return geoCount >= 2;
}

/** Poslední hodnota každé řady → body pro GeoMapChart (x = štítek země, y = hodnota). */
export function buildGeoMapRowsFromMultiSeries(chartRows, seriesMeta, options = {}) {
  const rows = Array.isArray(chartRows) ? chartRows : [];
  const series = Array.isArray(seriesMeta) ? seriesMeta : [];
  const displayLabels = options.displayLabels;
  const geoHints = options.geoHints;
  if (!series.length) return [];

  const pickValue = (key) => {
    for (let i = rows.length - 1; i >= 0; i -= 1) {
      const val = Number(rows[i]?.[key]);
      if (Number.isFinite(val)) return val;
    }
    return null;
  };

  const out = [];
  series.forEach((s, index) => {
    const label = resolveSeriesLabel(s, index, displayLabels, geoHints);
    const key = s?.key;
    if (!key) return;
    const iso = resolveSeriesGeoIso(s, index, displayLabels, geoHints);
    if (!iso) return;
    const val = pickValue(key);
    if (val === null) return;
    out.push({ x: label || iso, geo: iso, y: val, series_label: label || iso });
  });
  return out;
}

/** Jedna řada s kategoriemi zemí (latest bar) nebo multi-series — sjednocený vstup pro mapu. */
export function buildGeoMapRowsFromChartRows(chartRows, seriesMeta, options = {}) {
  const series = Array.isArray(seriesMeta) ? seriesMeta : [];
  if (series.length > 0) {
    return buildGeoMapRowsFromMultiSeries(chartRows, series, options);
  }
  const rows = Array.isArray(chartRows) ? chartRows : [];
  const out = [];
  for (const row of rows) {
    const label = String(row?.x ?? row?.period ?? row?.label ?? "").trim();
    const iso = rowLabelToIso(row?.geo ?? row?.ref_area ?? row?.geo_label ?? label);
    const val = Number(row?.y);
    if (!iso || !Number.isFinite(val)) continue;
    out.push({ x: label || iso, geo: iso, y: val });
  }
  return out;
}

/** @returns {Map<string, number>} ISO2 -> value */
export function buildGeoValueMap(rows) {
  const map = new Map();
  if (!Array.isArray(rows)) return map;
  for (const row of rows) {
    const iso = rowLabelToIso(row?.geo ?? row?.x ?? row?.geo_label ?? row?.series_label);
    const val = Number(row?.y);
    if (!iso || !Number.isFinite(val)) continue;
    map.set(iso, val);
  }
  return map;
}

export function geographyIso2(geo) {
  const props = geo?.properties || {};
  const numericRaw = String(geo?.id ?? props.id ?? "").trim();
  if (numericRaw) {
    const numKey = String(Number(numericRaw));
    if (numKey !== "NaN" && ISO3166_NUMERIC[numKey]) {
      return normalizeIso(ISO3166_NUMERIC[numKey]);
    }
  }
  const fromProp = normalizeIso(
    props.ISO_A2 || props.iso_a2 || props.ISO_A2_EH || props.ADM0_A3 || props.adm0_a3
  );
  if (fromProp && fromProp.length === 2) return fromProp;
  const nameKey = String(props.name || props.NAME || props.admin || "").trim().toLowerCase();
  if (nameKey && ATLAS_NAME_TO_ISO[nameKey]) return ATLAS_NAME_TO_ISO[nameKey];
  return "";
}

export function geographyMatchesRegion(geo, region) {
  const iso = geographyIso2(geo);
  if (!iso) return region === "world";
  if (region === "world") return true;
  if (region === "cz") {
    return ["CZ", "SK", "AT", "DE", "PL"].includes(iso);
  }
  if (region === "europe") {
    return EUROPE_ISO2.has(iso);
  }
  return true;
}

export function colorScale(value, min, max, baseRgb = [31, 140, 219]) {
  if (!Number.isFinite(value)) return "#e2e8f0";
  if (!Number.isFinite(min) || !Number.isFinite(max) || min === max) {
    return `rgb(${baseRgb.join(",")})`;
  }
  const t = Math.max(0, Math.min(1, (value - min) / (max - min)));
  const light = [226, 232, 240];
  const r = Math.round(light[0] + (baseRgb[0] - light[0]) * t);
  const g = Math.round(light[1] + (baseRgb[1] - light[1]) * t);
  const b = Math.round(light[2] + (baseRgb[2] - light[2]) * t);
  return `rgb(${r},${g},${b})`;
}
