const GEO_LABELS_CS = {
  GLOBAL: "Globální",
  WORLD: "Globální",
  EU: "Evropská unie",
  U6: "Evropská unie (EU27)",
  EA: "Eurozóna",
  U2: "Eurozóna (EA20)",
  CZ: "Česko",
  SK: "Slovensko",
  DE: "Německo",
  AT: "Rakousko",
  PL: "Polsko",
  HU: "Maďarsko",
  FR: "Francie",
  IT: "Itálie",
  ES: "Španělsko",
  PT: "Portugalsko",
  NL: "Nizozemsko",
  BE: "Belgie",
  SE: "Švédsko",
  DK: "Dánsko",
  FI: "Finsko",
  IE: "Irsko",
  GR: "Řecko",
  RO: "Rumunsko",
  BG: "Bulharsko",
  HR: "Chorvatsko",
  SI: "Slovinsko",
  EE: "Estonsko",
  LV: "Lotyšsko",
  LT: "Litva",
  LU: "Lucembursko",
  MT: "Malta",
  CY: "Kypr",
  GB: "Velká Británie",
  UK: "Velká Británie",
  US: "Spojené státy",
  JP: "Japonsko",
  CH: "Švýcarsko",
  NO: "Norsko",
  CN: "Čína",
  IN: "Indie",
  KR: "Jižní Korea",
  AU: "Austrálie",
  CA: "Kanada",
  MX: "Mexiko",
  BR: "Brazílie",
  AR: "Argentina",
  RU: "Rusko",
  TR: "Turecko",
  UA: "Ukrajina",
  NZ: "Nový Zéland",
  ZA: "Jihoafrická republika",
};

export { GEO_LABELS_CS };

export function looksLikeIsoCode(text) {
  const t = String(text || "").trim();
  if (!t) return true;
  return t.length <= 3 && /^[A-Za-z]{2,3}$/.test(t) && t === t.toUpperCase();
}

export function macroGeoLabelCs(code, fallbackLabel = "") {
  const c = String(code || "").trim().toUpperCase();
  const fb = String(fallbackLabel || "").trim();
  if (fb && !looksLikeIsoCode(fb) && fb.toUpperCase() !== c) return fb;
  return GEO_LABELS_CS[c] || (fb && !looksLikeIsoCode(fb) ? fb : "") || "";
}

export function isAbbreviationCountry(country) {
  if (!country) return true;
  const code = String(country.code || "").trim().toUpperCase();
  const label = macroGeoLabelCs(code, country.label_cs);
  if (!label) return true;
  if (label.toUpperCase() === code) return true;
  if (looksLikeIsoCode(label)) return true;
  return false;
}

export function countryDisplayLabel(country) {
  if (!country) return "";
  return macroGeoLabelCs(country.code, country.label_cs);
}

/** Kratší popisek pro úzký mobilní sloupec „Země“. */
export function countryDisplayLabelCompact(country) {
  if (!country) return "";
  const code = String(country.code || "").trim().toUpperCase();
  const compactByCode = {
    U6: "EU27",
    U2: "EA20",
    EU: "EU",
    EA: "EA",
    GLOBAL: "Svět",
    WORLD: "Svět",
  };
  if (compactByCode[code]) return compactByCode[code];
  const full = countryDisplayLabel(country);
  if (full.length <= 12) return full;
  if (code.length >= 2 && code.length <= 3 && GEO_LABELS_CS[code]) {
    const mapped = GEO_LABELS_CS[code];
    if (mapped.length <= 12) return mapped;
  }
  return full.length > 13 ? `${full.slice(0, 12)}…` : full;
}

export function geoDisplayLabel(row) {
  if (!row) return "";
  return macroGeoLabelCs(row.geo, row.geo_label || row.label_cs);
}
