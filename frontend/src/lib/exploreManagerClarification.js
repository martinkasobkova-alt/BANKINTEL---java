/** Manager Explorer — needs_clarification odpovědi z /explore/sector. */

export function isExploreNeedsClarification(payload) {
  if (!payload || typeof payload !== "object") return false;
  if (String(payload.status || "").trim() === "needs_clarification") return true;
  if (payload.needs_clarification === true) return true;
  const qu = payload?.query_understanding;
  if (qu?.clarification_needed === true && qu?.needs_user_confirmation === true) return true;
  return payload.ok === false && Boolean(payload.clarification);
}

export function isProductionTypeClarification(payload) {
  const qu = payload?.query_understanding || payload?.clarification;
  const clar = payload?.clarification;
  const ambiguity = String(qu?.ambiguity_type || clar?.ambiguity_type || "").trim();
  return ambiguity === "production_type_missing";
}

export function exploreClarificationFallbackSegment(payload) {
  const qu = payload?.query_understanding || payload?.clarification;
  const clar = payload?.clarification;
  return String(qu?.fallback_segment || clar?.fallback_segment || "manufacturing_general").trim();
}

export function exploreClarificationMessage(payload) {
  const clar = payload?.clarification;
  return (
    String(
      clar?.clarifying_question ||
        clar?.clarification_reason ||
        payload?.error ||
        "Dotaz potřebuje upřesnění — vyberte typ výroby nebo segment."
    ).trim() || "Dotaz potřebuje upřesnění."
  );
}

export function exploreClarificationOptions(payload) {
  const clar = payload?.clarification;
  const fromClar = Array.isArray(clar?.suggested_clarification_options)
    ? clar.suggested_clarification_options
    : [];
  if (fromClar.length) return fromClar;
  const fromQu = Array.isArray(payload?.query_understanding?.suggested_clarification_options)
    ? payload.query_understanding.suggested_clarification_options
    : [];
  return fromQu;
}

const CLARIFICATION_SEGMENT_FALLBACK_CS = {
  manufacturing_general: "Zpracovatelský průmysl",
  automotive: "Automobilový průmysl",
  machinery_engineering: "Strojírenství",
  chemicals_materials: "Chemický průmysl a materiály",
  agriculture_food: "Zemědělství a potravinářství",
  rubber_plastics: "Gumárenský a plastikářský průmysl",
  metals_mining: "Kovy, hutnictví a těžba",
  energy: "Energetika",
  unknown_general_view: "Zpracovatelský průmysl",
  other_manual: "Jiné / upřesnit ručně",
};

export function resolveSectorLabelForClarificationOption(option, sectorLabelById = null) {
  const segmentId = String(option?.segment_id || "").trim();
  const label = String(option?.label || "").trim();
  if (segmentId && sectorLabelById && typeof sectorLabelById.get === "function") {
    const mapped = String(sectorLabelById.get(segmentId) || "").trim();
    if (mapped) return mapped;
  }
  if (segmentId && CLARIFICATION_SEGMENT_FALLBACK_CS[segmentId]) {
    return CLARIFICATION_SEGMENT_FALLBACK_CS[segmentId];
  }
  if (segmentId === "unknown_general_view" || segmentId === "other_manual") {
    return null;
  }
  return label;
}

export function countryCodesFromQueryUnderstanding(qu) {
  if (!qu || typeof qu !== "object") return [];
  const resolved = qu.resolved_geo && typeof qu.resolved_geo === "object" ? qu.resolved_geo : {};
  const explicitCountries = Array.isArray(qu.country)
    ? qu.country
    : String(qu.country || "").split(/[;,]/);
  const raw = [
    ...(Array.isArray(resolved.countries) ? resolved.countries : []),
    ...(Array.isArray(qu.country_codes) ? qu.country_codes : []),
    ...explicitCountries,
    qu.primary_country_code,
    qu.geo_intent?.country_code,
    ...(Array.isArray(qu.geo_intent?.country_codes) ? qu.geo_intent.country_codes : []),
  ];
  return [...new Set(raw.map((c) => String(c || "").trim().toUpperCase()).filter(Boolean))];
}

export function buildGeoPayloadFromCountryCodes(codes) {
  const list = [...new Set((codes || []).map((c) => String(c || "").trim().toUpperCase()).filter(Boolean))];
  if (!list.length) return null;
  return { country: list.join(", "), geo_mode: "countries", continent: null };
}

export function buildGeoPayloadFromQueryUnderstanding(qu) {
  return buildGeoPayloadFromCountryCodes(countryCodesFromQueryUnderstanding(qu));
}
