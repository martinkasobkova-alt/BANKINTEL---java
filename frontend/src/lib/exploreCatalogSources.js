/** ISO2 evropské země (shoda s backend catalog_geo_intent.EUROPEAN_COUNTRY_CODES). */
export const EUROPEAN_ISO2 = new Set([
  "AL", "AD", "AM", "AT", "AZ", "BY", "BE", "BA", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "GE", "DE", "GR",
  "HU", "IS", "IE", "IT", "XK", "LV", "LI", "LT", "LU", "MT", "MD", "MC", "ME", "NL", "MK", "NO", "PL", "PT", "RO",
  "RU", "SM", "RS", "SK", "SI", "ES", "SE", "CH", "TR", "UA", "GB", "VA",
]);

const EUROPEAN_CATALOG_IDS = new Set(["ecb2", "eurostat"]);
const CZECH_CATALOG_IDS = new Set(["csu", "arad"]);

/**
 * Geo-aware filtr katalogů pro Explorer follow-up.
 * CZ: ČSÚ + ARAD + evropské zdroje; mimo Evropu schová ECB/Eurostat.
 */
export function filterExploreCatalogSources(sources, { geoMode, continent, countryCodes } = {}) {
  const list = Array.isArray(sources) ? sources : [];
  if (geoMode === "none" || !geoMode) return list;

  const codes = (countryCodes || []).map((c) => String(c || "").trim().toUpperCase()).filter(Boolean);
  const czOnly = codes.length > 0 && codes.every((c) => c === "CZ");
  const allEuropean = codes.length > 0 && codes.every((c) => EUROPEAN_ISO2.has(c));

  if (geoMode === "continent") {
    if (continent === "europe") return list;
    return list.filter((s) => !EUROPEAN_CATALOG_IDS.has(String(s.id || "").toLowerCase()));
  }

  if (!codes.length) return list;

  return list.filter((s) => {
    const id = String(s.id || "").toLowerCase();
    if (czOnly && CZECH_CATALOG_IDS.has(id)) return true;
    if (!czOnly && CZECH_CATALOG_IDS.has(id)) return false;
    if (allEuropean) return true;
    return !EUROPEAN_CATALOG_IDS.has(id);
  });
}

export const EXPLORE_CATALOG_SOURCES = [
  { id: "eurostat", label: "Eurostat (EU/NACE)" },
  { id: "ecb2", label: "ECB" },
  { id: "csu", label: "ČSÚ (CZ)" },
  { id: "arad", label: "ČNB - ARAD" },
  { id: "imf", label: "IMF WEO" },
  { id: "data360", label: "World Bank" },
];
