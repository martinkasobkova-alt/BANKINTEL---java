/** ECB / explore kódy → IMF WEO (3 písmena). */
const ECB_TO_IMF_COUNTRY = {
  CZ: "CZE",
  DE: "DEU",
  US: "USA",
  JP: "JPN",
  PL: "POL",
  FR: "FRA",
  IT: "ITA",
  ES: "ESP",
  AT: "AUT",
  SK: "SVK",
  HU: "HUN",
  GB: "GBR",
  NL: "NLD",
  BE: "BEL",
  CH: "CHE",
  SE: "SWE",
  NO: "NOR",
  DK: "DNK",
  FI: "FIN",
  IE: "IRL",
  PT: "PRT",
  GR: "GRC",
  RO: "ROU",
  BG: "BGR",
  HR: "HRV",
  SI: "SVN",
  LT: "LTU",
  LV: "LVA",
  EE: "EST",
  CN: "CHN",
  IN: "IND",
  BR: "BRA",
  MX: "MEX",
  KR: "KOR",
  AU: "AUS",
  CA: "CAN",
};

/** ECB / explore kódy → World Bank (ISO3). */
const ECB_TO_WB_COUNTRY = { ...ECB_TO_IMF_COUNTRY };

const ISO3_TO_ISO2 = Object.fromEntries(
  Object.entries(ECB_TO_IMF_COUNTRY).map(([iso2, iso3]) => [iso3, iso2]),
);

/** ISO-3 → ISO-2 (CZE → CZ); prázdný string, pokud kód neznáme. */
export function mapIso3CountryToIso2(code) {
  return ISO3_TO_ISO2[String(code || "").trim().toUpperCase()] || "";
}

const IMF_AGGREGATE_CODES = new Set(["U2", "U6", "EU", "EA", "EZ", "G998", "G001", "W00"]);

export function isImfAggregateCompareCode(code) {
  return IMF_AGGREGATE_CODES.has(String(code || "").trim().toUpperCase());
}

export function mapCompareCountryToImfCode(code) {
  const normalized = String(code || "").trim().toUpperCase();
  if (!normalized || isImfAggregateCompareCode(normalized)) return "";
  if (ECB_TO_IMF_COUNTRY[normalized]) return ECB_TO_IMF_COUNTRY[normalized];
  if (normalized.length === 3) return normalized;
  return normalized;
}

export function mapCompareCountryToWorldBankCode(code) {
  const normalized = String(code || "").trim().toUpperCase();
  if (!normalized) return "";
  if (ECB_TO_WB_COUNTRY[normalized]) return ECB_TO_WB_COUNTRY[normalized];
  if (normalized.length === 3) return normalized;
  return normalized;
}

/** Doplní query_params pro Data360 preview — posílá geo, REF_AREA normalizuje backend. */
export function applyData360GeoQueryParams(queryParams, selectedGeo) {
  const base = queryParams && typeof queryParams === "object" ? { ...queryParams } : {};
  const geo = Array.isArray(selectedGeo)
    ? [...new Set(selectedGeo.map((x) => String(x || "").trim().toUpperCase()).filter(Boolean))]
    : [];
  if (!geo.length) {
    return base;
  }
  base.geo = geo;
  delete base.REF_AREA;
  delete base.ref_area;
  return base;
}

export function filterCompareCountriesForSource(countries, sourceType) {
  const src = String(sourceType || "").trim().toLowerCase();
  return (countries || []).filter((row) => {
    const code = String(row?.code || "").trim().toUpperCase();
    if (!code) return false;
    if (src === "imf" && isImfAggregateCompareCode(code)) return false;
    return true;
  });
}
