/** Stejné pořadí a názvy jako textové sekce analýzy (backend CHART_SECTION_META). */
export const EXPLORE_CHART_SECTION_DEFS = [
  { id: "company", title: "Firemní data", seriesKey: "company_series_used" },
  { id: "sector", title: "Odvětví", seriesKey: "sector_series_used" },
  { id: "commodities", title: "Komodity", seriesKey: "commodity_series_used" },
  { id: "financial_markets", title: "Finanční trhy a sentiment", seriesKey: "financial_markets_series_used" },
  { id: "macro", title: "Makro", seriesKey: "macro_series_used" },
  { id: "demographics", title: "Demografie", seriesKey: "demographics_series_used" },
  { id: "fx", title: "Směnné kurzy", seriesKey: "fx_series_used" },
  { id: "neighbors", title: "Sousedé", seriesKey: "neighbor_series_used" },
  { id: "partners", title: "Partneři", seriesKey: "partner_series_used" },
  { id: "eu", title: "Region / EU", seriesKey: "eu_series_used" },
  { id: "global", title: "Globální kontext", seriesKey: "global_series_used" },
];

/** Kurzové overlay (EUR/USD…) dává smysl jen u FX řad / sekce Kurzy — ne u odvětví, makra apod. */
export function chartSupportsFxCompare(series, sectionId = "") {
  const sid = String(sectionId || series?.chartSectionId || series?.chart_section_id || "")
    .trim()
    .toLowerCase();
  if (sid === "fx") return true;
  const role = String(series?.summarizeRole || series?.summarize_role || "").trim().toLowerCase();
  const scope = String(series?.contextScope || series?.context_scope || "").trim().toLowerCase();
  return role === "fx" || scope === "fx";
}

export function resolveChartSectionId(series) {
  const role = String(series?.summarizeRole || series?.summarize_role || "").trim().toLowerCase();
  const scope = String(series?.contextScope || series?.context_scope || "").trim().toLowerCase();
  const preset = String(series?.chartSectionId || series?.chart_section_id || "").trim().toLowerCase();
  if (preset) return preset;
  if (role === "company" || scope === "company") return "company";
  if (role === "sector") return "sector";
  if (role === "commodity" || scope === "commodity") return "commodities";
  if (role === "financial_markets" || role === "market" || scope === "financial_markets" || scope === "market") {
    return "financial_markets";
  }
  if (role === "demographics" || scope === "demographics") return "demographics";
  if (role === "fx" || scope === "fx") return "fx";
  if (role === "partner" || scope === "partner") return "partners";
  if (role === "neighbor" || scope === "neighbor") return "neighbors";
  if (role === "eu" || role === "continent" || scope === "eu" || scope === "continent") return "eu";
  if (role === "global" || scope === "global") return "global";
  return "macro";
}

function chartSeriesFromPayloadSection(section) {
  if (!section || typeof section !== "object" || !Array.isArray(section.series)) return [];
  return section.series
    .map((s) => ({
      name: String(s.name || "").trim(),
      rows: Array.isArray(s.data)
        ? s.data
            .filter((p) => p && typeof p === "object")
            .map((p) => ({ x: String(p.x ?? "").trim(), y: Number(p.y) }))
            .filter((p) => p.x && !Number.isNaN(p.y))
        : [],
      source: String(s.source || "").trim(),
      sourceLabel: String(s.source_label || "").trim(),
      setId: String(s.set_id || "").trim(),
      summarizeRole: String(s.summarize_role || "").trim(),
      contextScope: String(s.context_scope || "").trim(),
      chartSectionId: String(s.chart_section_id || "").trim(),
      compareCapable: Boolean(s.compare_capable),
      compareRef: s.compare_ref && typeof s.compare_ref === "object" ? s.compare_ref : null,
      primaryCountryCode: String(s.primary_country_code || "").trim(),
      geoDisplayLabel: String(s.geo_display_label || "").trim(),
      chartNote: String(s.chart_note || "").trim(),
    }))
    .filter((s) => s.name && s.rows.length >= 2);
}

/**
 * Seskupí grafy podle sekcí; ukazuje jen řady s použitelnou časovou řadou.
 */
export function buildExploreChartSectionGroups(result, chartSeries) {
  const fromBackend = Array.isArray(result?.chart_sections) ? result.chart_sections : null;
  if (fromBackend?.length) {
    return fromBackend
      .map((section) => {
        const def = EXPLORE_CHART_SECTION_DEFS.find((d) => d.id === section.id) || {
          id: section.id,
          title: section.title,
          seriesKey: null,
        };
        const charts = chartSeriesFromPayloadSection(section);
        if (!charts.length) return null;
        return { id: def.id, title: def.title || section.title, charts };
      })
      .filter(Boolean);
  }

  const byId = Object.fromEntries(EXPLORE_CHART_SECTION_DEFS.map((d) => [d.id, []]));
  for (const chart of chartSeries || []) {
    const sid = resolveChartSectionId(chart);
    if (!byId[sid]) byId[sid] = [];
    byId[sid].push(chart);
  }

  return EXPLORE_CHART_SECTION_DEFS.map((def) => {
    const charts = byId[def.id] || [];
    if (!charts.length) return null;
    return { id: def.id, title: def.title, charts };
  }).filter(Boolean);
}
