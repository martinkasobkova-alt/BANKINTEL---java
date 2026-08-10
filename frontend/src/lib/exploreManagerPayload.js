/** Normalizace Manager Explorer odpovědi (curated katalog, CZ/EU vrstvy). */

export const INDICATOR_SECTION_DEFS = [
  { key: "recommended_chart_set", label: "Doporučené pro srovnání", highlight: true },
  { key: "sector_indicators", label: "Odvětvové ukazatele (CZ/EU core)" },
  { key: "leading_indicators", label: "Leading indikátory" },
  { key: "cost_indicators", label: "Náklady a ceny" },
  { key: "financial_indicators", label: "Finanční podmínky" },
  { key: "external_indicators", label: "Externí trhy" },
  { key: "forecast_indicators", label: "Výhled IMF WEO" },
  { key: "macro_indicators", label: "Makro (volitelné)" },
];

const CATEGORY_BADGE = {
  sector_core: "Sektor",
  sector_indicators: "Sektor",
  leading: "Leading",
  leading_indicators: "Leading",
  cost: "Náklady",
  cost_indicators: "Náklady",
  financial: "Finance",
  financial_indicators: "Finance",
  financial_markets: "Trhy",
  external: "Externí",
  external_indicators: "Externí",
  forecast: "WEO",
  forecast_indicators: "WEO",
  macro: "Makro",
  macro_indicators: "Makro",
  risk_indicators: "Rizika",
  company: "Firma",
};

const SOURCE_LABELS = {
  arad: "ČNB - ARAD",
  bis: "BIS",
  csu: "CSU",
  data360: "World Bank",
  ecb: "ECB",
  ecb2: "ECB",
  eurostat: "Eurostat",
  fred: "FRED",
  imf: "IMF",
  oecd: "OECD",
  worldbank: "World Bank",
  world_bank: "World Bank",
  world_bank_data360: "World Bank",
  worldbank_pink_sheet: "Komodity",
  user_upload: "Uživatelský upload",
};

export function allSeriesKeys(rows) {
  return (rows || []).map((row) => seriesKey(row));
}

export function sectionFullySelected(items, selectedKeys) {
  const list = items || [];
  if (!list.length) return false;
  return list.every((item) => selectedKeys.has(seriesKey(item)));
}

export function seriesKey(item) {
  const src = String(item?.source || item?.source_type || "").trim().toLowerCase();
  const did = String(item?.dataset_id || item?.set_id || "").trim();
  const fu = item?.filters_used || item?.query_params || {};
  const parts = [fu.cz_nace_oddil, fu.nace_r2, fu.geo, fu.selected_indicator, fu.imf_indicator]
    .map((x) => String(x || "").trim())
    .filter(Boolean);
  const suffix = parts.length ? parts.join("|") : "";
  return suffix ? `${src}:${did}:${suffix}` : `${src}:${did}`;
}

export function categoryBadge(item) {
  const cat = String(item?.category || item?.manager_category || "").trim();
  return CATEGORY_BADGE[cat] || "";
}

export function geoScopeLabel(item) {
  const raw = item?.geo_scope;
  const list = Array.isArray(raw) ? raw : raw ? [raw] : [];
  const fromCountries = Array.isArray(item?.countries) ? item.countries : [];
  const merged = [...list, ...fromCountries].map((g) => String(g || "").trim().toUpperCase()).filter(Boolean);
  return [...new Set(merged)].slice(0, 4).join(", ");
}

export function sourceLabel(item) {
  const raw = String(item?.source || item?.source_type || item?.catalog_id || "").trim().toLowerCase();
  if (!raw) return "Neuvedeno";
  return SOURCE_LABELS[raw] || raw.replace(/[_-]+/g, " ").toUpperCase();
}

function dedupeRows(rows, seen) {
  const out = [];
  for (const row of rows || []) {
    if (!row || typeof row !== "object") continue;
    const key = seriesKey(row);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(row);
  }
  return out;
}

const ANALYSIS_PORTFOLIO_MIN = 8;
const ANALYSIS_PORTFOLIO_MAX = 30;

const MANAGER_SERIES_GROUPS = INDICATOR_SECTION_DEFS.map((item) => item.key);

export function mergeExploreManagerPayloads(curatedPayload, discoveryPayload) {
  const curated = curatedPayload && typeof curatedPayload === "object" ? curatedPayload : {};
  const discovery = discoveryPayload && typeof discoveryPayload === "object" ? discoveryPayload : {};
  const merged = { ...curated, ...discovery };

  for (const key of MANAGER_SERIES_GROUPS) {
    const seen = new Set();
    merged[key] = dedupeRows(
      [
        ...(Array.isArray(discovery[key]) ? discovery[key] : []),
        ...(Array.isArray(curated[key]) ? curated[key] : []),
      ],
      seen,
    );
  }
  return merged;
}

function analysisFamilyKey(item) {
  return [
    item?.source || item?.source_type,
    item?.manager_series_tier || item?.macro_manager_tier,
    item?.manager_category || item?.category,
    item?.indicator_role || item?.summarize_role,
    item?.from_related_segment ? "related" : "primary",
  ]
    .map((value) => String(value || "").trim().toLowerCase())
    .filter(Boolean)
    .join("|") || "other";
}

/**
 * Builds a bounded but diverse analysis portfolio. Explicit planner selections are
 * immutable; remaining capacity is filled round-robin across structured indicator
 * families and only then by the original relevance order.
 */
export function selectAnalysisPortfolio(rows, limit = ANALYSIS_PORTFOLIO_MIN) {
  const candidates = Array.isArray(rows) ? rows.filter((row) => row && typeof row === "object") : [];
  const cap = Math.max(1, Number(limit) || ANALYSIS_PORTFOLIO_MIN);
  const selected = [];
  const seen = new Set();
  const add = (row) => {
    const key = seriesKey(row);
    if (!key || seen.has(key) || selected.length >= cap) return;
    seen.add(key);
    selected.push(row);
  };

  // A manual/default selection outside the generated preset is explicit. Preset
  // defaults are ranking hints, so they still pass through the diversity pass.
  candidates
    .filter((row) => Boolean(row.default_selected) && !row.from_preset)
    .forEach(add);
  if (selected.length >= cap) return selected;

  const familyQueues = new Map();
  for (const row of candidates) {
    if (seen.has(seriesKey(row))) continue;
    const family = analysisFamilyKey(row);
    if (!familyQueues.has(family)) familyQueues.set(family, []);
    familyQueues.get(family).push(row);
  }
  for (const queue of familyQueues.values()) add(queue.shift());
  for (const row of candidates) add(row);
  return selected;
}

function arrayValue(value) {
  return Array.isArray(value) ? value : [];
}

function distinctNonBlank(values) {
  return new Set(
    (values || [])
      .map((value) => String(value || "").trim().toLowerCase())
      .filter(Boolean),
  );
}

/**
 * Computes a bounded analysis budget from structured request complexity and the
 * candidate portfolio. This deliberately avoids phrase- or sector-specific
 * rules: the same calculation applies to every Manager Explorer question.
 */
export function computeAnalysisPortfolioBudget(data, rows) {
  const payload = data && typeof data === "object" ? data : {};
  const candidates = Array.isArray(rows) ? rows.filter((row) => row && typeof row === "object") : [];
  const plan = payload.analysis_plan && typeof payload.analysis_plan === "object"
    ? payload.analysis_plan
    : {};
  const stats = plan.selection_stats && typeof plan.selection_stats === "object"
    ? plan.selection_stats
    : {};
  const queryUnderstanding = payload.query_understanding && typeof payload.query_understanding === "object"
    ? payload.query_understanding
    : {};
  const ecosystem = payload.sector_ecosystem && typeof payload.sector_ecosystem === "object"
    ? payload.sector_ecosystem
    : {};

  const familyCount = distinctNonBlank(candidates.map(analysisFamilyKey)).size;
  const sourceCount = distinctNonBlank(candidates.map((row) => row.source || row.source_type)).size;
  const categoryCount = distinctNonBlank(candidates.map((row) => row.manager_category || row.category)).size;
  const relatedCount = Math.max(
    arrayValue(plan.related_segments).length,
    arrayValue(ecosystem.linked_sectors).length,
    candidates.filter((row) => Boolean(row.from_related_segment)).length > 0 ? 1 : 0,
  );
  const countryCount = Math.max(
    arrayValue(plan?.country_context?.country_codes).length,
    arrayValue(queryUnderstanding?.resolved_geo?.countries).length,
    String(queryUnderstanding.country || "")
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean).length,
  );
  const comparisonCount = Math.max(
    arrayValue(payload?.multi_sector_comparison?.sectors).length,
    arrayValue(queryUnderstanding.compare_countries).length,
  );
  const broadAnalysis = Boolean(stats.broad_analysis);
  const explicitCount = candidates.filter(
    (row) => Boolean(row.default_selected) && !row.from_preset,
  ).length;

  let target = ANALYSIS_PORTFOLIO_MIN;
  target += Math.min(8, Math.max(0, familyCount - 1));
  target += Math.min(4, Math.max(0, sourceCount - 1));
  target += Math.min(4, Math.max(0, categoryCount - 1));
  target += Math.min(4, Math.max(0, countryCount - 1) * 2);
  target += Math.min(4, relatedCount);
  target += Math.min(4, Math.max(0, comparisonCount - 1) * 2);
  if (broadAnalysis) target += 4;

  const boundedTarget = Math.max(
    explicitCount,
    Math.min(ANALYSIS_PORTFOLIO_MAX, Math.max(ANALYSIS_PORTFOLIO_MIN, target)),
  );
  return Math.min(candidates.length, boundedTarget);
}

export function parseExploreManagerPayload(data) {
  const seen = new Set();
  const sections = [];
  let recommended = [];

  for (const def of INDICATOR_SECTION_DEFS) {
    const raw = Array.isArray(data?.[def.key]) ? data[def.key] : [];
    const items = dedupeRows(raw, seen).map((item) => ({
      ...item,
      source: String(item?.source || item?.source_type || item?.catalog_id || "").trim(),
      chart_recommended: def.key === "recommended_chart_set" || Boolean(item.chart_recommended),
    }));
    if (def.key === "recommended_chart_set") {
      recommended = items;
    }
    if (items.length) {
      sections.push({ id: def.key, label: def.label, highlight: Boolean(def.highlight), items });
    }
  }

  const sectorIndicators = sections.find((s) => s.id === "sector_indicators")?.items || [];
  const macroIndicators = sections.find((s) => s.id === "macro_indicators")?.items || [];
  const allRows = sections.flatMap((s) => s.items);

  const analysisBudget = computeAnalysisPortfolioBudget(data, allRows);
  const preselectKeys = selectAnalysisPortfolio(allRows, analysisBudget).map((item) => seriesKey(item));

  const missing = data?.decision_context?.missing_data || data?.missing_data || [];
  const sectorEcosystem = data?.sector_ecosystem && typeof data.sector_ecosystem === "object"
    ? data.sector_ecosystem
    : null;
  const linkedSectors = Array.isArray(sectorEcosystem?.linked_sectors) ? sectorEcosystem.linked_sectors : [];
  const needsFilterIndicators = Array.isArray(data?.needs_filter_indicators) ? data.needs_filter_indicators : [];
  const meta = {
    sectorLabel:
      String(data?.matched_manager_sector_name_cs || data?.matched_preset_label || "").trim() || null,
    sectorId: String(data?.matched_manager_sector_id || "").trim() || null,
    subsegment:
      String(data?.matched_subsegment_name_cs || "").trim() || null,
    geoScope: String(data?.geo_scope || "").trim() || null,
    topSources: Array.isArray(data?.top_sources_used) ? data.top_sources_used : [],
    discoverySource: String(data?.discovery_source || "").trim() || null,
    missingData: Array.isArray(missing) ? missing : [],
    entsoeCoverage: data?.entsoe_coverage && typeof data.entsoe_coverage === "object" ? data.entsoe_coverage : null,
    dependentSectors: Array.isArray(data?.dependent_sectors) ? data.dependent_sectors : [],
    outlook: data?.outlook && typeof data.outlook === "object" ? data.outlook : null,
    analysisMode: String(sectorEcosystem?.analysis_mode || "").trim() || null,
    primarySector: sectorEcosystem?.primary_sector && typeof sectorEcosystem.primary_sector === "object"
      ? sectorEcosystem.primary_sector
      : null,
    linkedSectors,
    driverFocus: String(sectorEcosystem?.driver_focus || "").trim() || null,
    dataGaps: Array.isArray(sectorEcosystem?.data_gaps) ? sectorEcosystem.data_gaps : [],
    sectorEcosystem,
    multiSectorComparison:
      data?.multi_sector_comparison && typeof data.multi_sector_comparison === "object"
        ? data.multi_sector_comparison
        : null,
    driverExposureRanking:
      data?.driver_exposure_ranking && typeof data.driver_exposure_ranking === "object"
        ? data.driver_exposure_ranking
        : null,
    needsFilterIndicators,
  };

  return {
    sections,
    recommended,
    sectorIndicators,
    macroIndicators,
    allRows,
    preselectKeys: [...preselectKeys],
    analysisBudget,
    meta,
  };
}
