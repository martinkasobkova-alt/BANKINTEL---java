import { formatScore, scoreVisual } from "@/lib/exploreAnalysisInsights";
import { buildManagerChartSeriesPayload } from "@/lib/managerChartPayload";
import {
  chartTypeRequiresMultipleSeries,
  isPrecomputedRatio,
  SCATTER_CHART_TYPES,
} from "@/lib/managerChartTypes";

export const PUBLIC_SCORE_BREAKDOWN_KEYS = [
  { id: "segment_momentum_score", label: "Momentum segmentu" },
  { id: "demand_outlook_score", label: "Výhled poptávky" },
  { id: "cost_pressure_score", label: "Nákladový tlak" },
  { id: "macro_financial_score", label: "Makro a finance" },
  { id: "market_commodity_score", label: "Trhy a komodity" },
  { id: "data_confidence_score", label: "Důvěra v data" },
];

export const COMPANY_SCORE_BREAKDOWN_KEYS = [
  { id: "company_growth_score", label: "Růst firmy" },
  { id: "profitability_margin_score", label: "Marže" },
  { id: "cost_resilience_score", label: "Odolnost nákladů" },
  { id: "liquidity_financing_score", label: "Likvidita a financování" },
  { id: "company_vs_sector_score", label: "Firma vs sektor" },
  { id: "private_data_confidence_score", label: "Důvěra v firemní data" },
];

const CORRELATION_TYPES = new Set([
  "rolling_correlation",
  "lagged_correlation",
  "rates_vs_credit_or_construction_lag",
]);

const RELATIONSHIP_TYPE_LABELS = {
  growth_gap: "Mezera růstu",
  spread: "Spread",
  ratio: "Poměr",
  indexed_comparison: "Indexované srovnání",
  rolling_correlation: "Korelace (orientační)",
  lagged_correlation: "Zpožděná korelace (orientační)",
  elasticity: "Elasticita (orientační)",
  company_vs_sector_gap: "Firma vs sektor",
  input_cost_vs_output_price_gap: "Vstupy vs výstupy",
  wages_vs_productivity_gap: "Mzdy vs produktivita",
  orders_vs_production_lead: "Objednávky → produkce",
};

const MANAGER_SCORE_LABELS = [
  [8.0, "silně pozitivní"],
  [6.5, "opatrně pozitivní"],
  [5.0, "smíšené / mírně pozitivní"],
  [4.0, "neutrální až rizikové"],
  [2.5, "rizikové"],
  [1.0, "silně negativní"],
];

const MATCH_CONFIDENCE_RANK = { high: 3, medium: 2, low: 1, none: 0 };
const OPTIONAL_ID_SUFFIXES = ["_yoy", "_index", "_rate", "_growth", "_change"];

export function isValidScoreNumber(value) {
  if (value == null || typeof value === "string") return false;
  const n = Number(value);
  return Number.isFinite(n) && n >= 1 && n <= 10;
}

export function labelFromScoreRange(score) {
  const n = Number(score);
  if (!Number.isFinite(n)) return "";
  for (const [threshold, label] of MANAGER_SCORE_LABELS) {
    if (n >= threshold) return label;
  }
  return "silně negativní";
}

export function resolveManagerInterpretationContext(result) {
  const ctx = result?.manager_interpretation_context;
  return ctx && typeof ctx === "object" ? ctx : null;
}

export function hasManagerInterpretation(result) {
  return Boolean(resolveManagerInterpretationContext(result));
}

export function getManagerFinalScoreValue(result) {
  const ctx = resolveManagerInterpretationContext(result);
  if (!ctx) return null;
  const final = ctx.final_score || {};
  const candidates = [final.combined_score, final.score, final.value, final.public_score];
  for (const candidate of candidates) {
    if (isValidScoreNumber(candidate)) return Number(candidate);
  }
  return null;
}

export function getManagerFinalScoreLabel(result) {
  const ctx = resolveManagerInterpretationContext(result);
  const explicit = String(ctx?.final_score?.label || "").trim();
  if (explicit) return explicit;
  const score = getManagerFinalScoreValue(result);
  return score != null ? labelFromScoreRange(score) : "";
}

export function hasValidManagerFinalScore(result) {
  return getManagerFinalScoreValue(result) != null;
}

export function hasValidLegacyScore(result) {
  const analysisScore = result?.analysis_score;
  if (!analysisScore || typeof analysisScore !== "object") return false;
  const candidate = analysisScore.decision_score ?? analysisScore.composite;
  return isValidScoreNumber(candidate);
}

export function resolveScoringDisplay(result) {
  const managerScore = getManagerFinalScoreValue(result);
  const hasManager = managerScore != null;
  const hasLegacy = hasValidLegacyScore(result);

  if (hasManager) {
    return {
      scoring_source: "manager_final_score",
      showManagerPanel: true,
      showLegacyHero: false,
      legacy_score_hidden_due_to_manager_score: hasLegacy,
      managerScore,
      managerLabel: getManagerFinalScoreLabel(result),
    };
  }
  if (hasLegacy) {
    return {
      scoring_source: "legacy_fallback",
      showManagerPanel: Boolean(resolveManagerInterpretationContext(result)),
      showLegacyHero: true,
      legacy_score_hidden_due_to_manager_score: false,
      managerScore: null,
      managerLabel: "",
    };
  }
  return {
    scoring_source: "none",
    showManagerPanel: Boolean(resolveManagerInterpretationContext(result)),
    showLegacyHero: false,
    legacy_score_hidden_due_to_manager_score: false,
    managerScore: null,
    managerLabel: "",
  };
}

export function resolveExecutiveScore(ctx) {
  if (!ctx) return null;
  const score = getManagerFinalScoreValue({ manager_interpretation_context: ctx });
  if (score == null) return null;
  const dataConf = ctx.score_breakdown?.data_confidence_score?.score;
  const final = ctx.final_score || {};
  return {
    score,
    label: getManagerFinalScoreLabel({ manager_interpretation_context: ctx }),
    confidence: Number.isFinite(Number(dataConf)) ? Number(dataConf) : null,
    analysisMode: String(ctx.analysis_mode || "public_only"),
    publicScore: Number.isFinite(Number(ctx.public_score?.score)) ? Number(ctx.public_score.score) : null,
    companyScore: Number.isFinite(Number(ctx.company_result?.company_score?.score))
      ? Number(ctx.company_result.company_score.score)
      : null,
    weightNote: String(final.weight_note || "").trim(),
  };
}

export function resolveScoreBreakdownRows(ctx) {
  if (!ctx?.score_breakdown || typeof ctx.score_breakdown !== "object") return [];
  const rows = PUBLIC_SCORE_BREAKDOWN_KEYS.map(({ id, label }) => {
    const part = ctx.score_breakdown[id];
    if (!part || part.score == null) return null;
    return {
      id,
      label,
      score: Number(part.score),
      sublabel: part.label || "",
      inverted: id === "cost_pressure_score",
    };
  }).filter(Boolean);

  const companyBreakdown = ctx.company_result?.breakdown;
  if (companyBreakdown && typeof companyBreakdown === "object") {
    for (const { id, label } of COMPANY_SCORE_BREAKDOWN_KEYS) {
      const part = companyBreakdown[id];
      if (!part || part.score == null) continue;
      rows.push({
        id,
        label,
        score: Number(part.score),
        sublabel: part.label || "",
        isCompany: true,
      });
    }
    if (ctx.company_result?.company_score?.score != null) {
      rows.push({
        id: "company_score",
        label: "Firemní skóre",
        score: Number(ctx.company_result.company_score.score),
        sublabel: ctx.company_result.company_score.label || "",
        isCompany: true,
        highlight: true,
      });
    }
  }
  return rows;
}

const CONFIDENCE_ORDER = { high: 0, medium: 1, low: 2, unknown: 3 };
const STRENGTH_ORDER = { strong: 0, medium: 1, weak: 2, unknown: 3 };

export function resolveAiFallbackMessage(result) {
  if (!result?.fallback) return null;
  const meta = result.detail_synthesis_metadata || result.debug_metadata || {};
  if (meta.final_synthesis_mode === "section_stitch" && meta.final_synthesis_completed) {
    return null;
  }
  const reason = String(result.fallback_reason || result?.debug_metadata?.partial_fallback_reason || "").trim();
  const sectionErrors = meta.section_errors && typeof meta.section_errors === "object" ? meta.section_errors : {};
  const errorKeys = Object.keys(sectionErrors);
  if (reason === "ai_interpretation_required") {
    return "Finální AI interpretaci se nepodařilo dokončit (timeout API, chyba OpenAI nebo přerušení jobu). Zobrazujeme strukturovanou syntézu z načtených dat — zkuste analýzu znovu.";
  }
  if (meta.job_timed_out || errorKeys.includes("final_synthesis")) {
    return "Detailní analýza překročila časový limit AI syntézy. Zobrazujeme částečný report z načtených dat — pro plnou interpretaci spusťte analýzu znovu.";
  }
  if (result.quick_fallback_used) {
    return "AI odpověď byla příliš stručná — doplnili jsme ji strukturovanou syntézou z dat (odvětví, komodity, makro).";
  }
  if (errorKeys.length) {
    return `AI syntéza nebyla dokončena (${errorKeys.slice(0, 3).join(", ")}). Zobrazujeme deterministickou syntézu z načtených vrstev dat.`;
  }
  return "AI interpretace nebyla dostupná — zobrazujeme strukturovanou syntézu z načtených dat (odvětví, komodity, makro). Pro plnou interpretaci zkuste analýzu znovu.";
}

export function resolveTopRelationships(ctx, limit = 5) {
  const rels = Array.isArray(ctx?.derived_relationships) ? ctx.derived_relationships : [];
  return [...rels]
    .filter((rel) => {
      const geoA = String(rel.geo_a || "").trim().toUpperCase();
      const geoB = String(rel.geo_b || "").trim().toUpperCase();
      if (geoA && geoB && geoA !== geoB) return false;
      return true;
    })
    .sort((a, b) => {
      const ca = CONFIDENCE_ORDER[String(a.confidence || "unknown").toLowerCase()] ?? 3;
      const cb = CONFIDENCE_ORDER[String(b.confidence || "unknown").toLowerCase()] ?? 3;
      if (ca !== cb) return ca - cb;
      const sa = STRENGTH_ORDER[String(a.strength || "unknown").toLowerCase()] ?? 3;
      const sb = STRENGTH_ORDER[String(b.strength || "unknown").toLowerCase()] ?? 3;
      return sa - sb;
    })
    .slice(0, limit)
    .map((rel) => ({
      ...rel,
      typeLabel: RELATIONSHIP_TYPE_LABELS[rel.type] || rel.type || "Vztah",
      isCorrelation: CORRELATION_TYPES.has(String(rel.type || "")),
    }));
}

export function formatRelationshipValue(rel) {
  const val = rel?.latest_value;
  if (val == null || !Number.isFinite(Number(val))) return "—";
  const unit = String(rel.unit || "").toLowerCase();
  if (unit === "correlation") return Number(val).toFixed(2);
  if (unit === "ratio") return Number(val).toFixed(2);
  if (unit === "years_of_wage") return `${Number(val).toFixed(1)} roků mzdy`;
  if (unit === "percentage_points") {
    const n = Number(val);
    const formatted = `${n >= 0 ? "+" : ""}${n.toFixed(1).replace(".0", "")} p.b.`;
    return formatted;
  }
  return String(val);
}

export function resolveRelationshipTitle(rel) {
  return String(rel?.display_name || rel?.name || "").trim()
    || String(rel?.relationship_id || "Vztah").replace(/_/g, " ");
}

export function resolveRelationshipSubtitle(rel) {
  const typeLabel = RELATIONSHIP_TYPE_LABELS[rel.type] || rel.type || "Vztah";
  const a = String(rel?.series_a_label || "").trim();
  const b = String(rel?.series_b_label || "").trim();
  if (a && b) return `${a} vs ${b}`;
  const geo = String(rel?.geo_scope_label || "").trim();
  return geo ? `${typeLabel} · ${geo}` : typeLabel;
}

export function resolveRelationshipBadge(rel) {
  const conf = String(rel?.confidence_label_cs || rel?.confidence || "").trim();
  if (String(rel?.confidence || "").toLowerCase() === "low") {
    return conf || "orientační";
  }
  const strength = String(rel?.strength_label_cs || rel?.strength || "").trim();
  return [conf, strength].filter(Boolean).join(" · ") || "—";
}

function czSeriesWord(n) {
  if (n === 1) return "datové řady";
  if (n >= 2 && n <= 4) return "datových řad";
  return "datových řad";
}

/** Místo technického výpisu warningů jen lidský souhrn: kolik dat analýza použila. */
export function resolveDataUsageSummary(ctx) {
  const series = Array.isArray(ctx?.series_summary)
    ? ctx.series_summary
    : Array.isArray(ctx?.fetched_series)
      ? ctx.fetched_series
      : [];
  const total = series.length;
  if (!total) return null;
  const forecastCount = series.filter((r) => r && typeof r === "object" && r.is_forecast).length;
  const proxyCount = series.filter((r) => r && typeof r === "object" && r.is_proxy).length;

  let text = `Analýza vychází z ${total} ${czSeriesWord(total)}.`;
  const extras = [];
  if (forecastCount > 0) {
    extras.push(`${forecastCount} z nich obsahuje prognózu — výhled, ne aktuální stav`);
  }
  if (proxyCount > 0) {
    extras.push(`${proxyCount} je přibližný ukazatel (proxy)`);
  }
  if (extras.length) {
    text += ` ${extras.join("; ")}.`;
  }
  return { total, forecastCount, proxyCount, text };
}

export function resolveAnalysisModeLabel(mode) {
  const key = String(mode || "").toLowerCase();
  if (key === "public_plus_private") return "Veřejná data + agregované firemní signály";
  return "Pouze veřejná data";
}

export function foldId(text) {
  return String(text || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_|_$/g, "");
}

function slugVariants(id) {
  const base = foldId(id);
  if (!base) return [];
  const variants = new Set([base]);
  for (const suffix of OPTIONAL_ID_SUFFIXES) {
    if (base.endsWith(suffix) && base.length > suffix.length + 1) {
      variants.add(base.slice(0, -suffix.length));
    }
  }
  return [...variants];
}

function collectSeriesKeys(series) {
  if (!series || typeof series !== "object") return [];
  const keys = new Set();
  const fields = [
    series.setId,
    series.seriesId,
    series.id,
    series.metricId,
    series.name,
    series.signal,
    series.segmentId,
    series.geo,
    series.labelCs,
    series.labelEn,
  ];
  for (const field of fields) {
    for (const variant of slugVariants(field)) keys.add(variant);
  }
  if (Array.isArray(series.aliases)) {
    for (const alias of series.aliases) {
      for (const variant of slugVariants(alias)) keys.add(variant);
    }
  }
  if (series.source && series.signal) {
    keys.add(foldId(`${series.source}_${series.signal}`));
  }
  if (series.segmentId && series.signal) {
    keys.add(foldId(`${series.segmentId}_${series.signal}`));
  }
  if (series.geo && series.signal) {
    keys.add(foldId(`${series.geo}_${series.signal}`));
  }
  return [...keys];
}

function compositeRefKey(ref) {
  const signal = foldId(ref?.signal);
  const segment = foldId(ref?.segment_id);
  const geo = foldId(ref?.geo);
  const freq = foldId(ref?.freq);
  if (!signal || !segment || !geo) return "";
  return `${signal}__${segment}__${geo}__${freq}`;
}

function buildSeriesLookup(seriesList) {
  const exact = new Map();
  const setId = new Map();
  const sourceSeriesId = new Map();
  const composite = new Map();
  const metadata = new Map();

  for (const series of seriesList) {
    const keys = collectSeriesKeys(series);
    for (const key of keys) {
      if (!key) continue;
      if (!exact.has(key)) exact.set(key, series);
    }
    const payloadSetId = foldId(series.setId);
    if (payloadSetId && !setId.has(payloadSetId)) setId.set(payloadSetId, series);
    const sourceId = foldId(series.seriesId || series.id);
    if (sourceId && !sourceSeriesId.has(sourceId)) sourceSeriesId.set(sourceId, series);

    const compositeKey = compositeRefKey({
      signal: series.signal,
      segment_id: series.segmentId,
      geo: series.geo,
      freq: series.freq,
    });
    if (compositeKey && !composite.has(compositeKey)) composite.set(compositeKey, series);

    const titleKey = foldId(series.labelCs || series.name);
    if (titleKey && !metadata.has(titleKey)) metadata.set(titleKey, series);
  }
  return { exact, setId, sourceSeriesId, composite, metadata };
}

function fuzzyTitleMatch(requestId, seriesList) {
  const needle = foldId(requestId).replace(/_/g, " ");
  if (!needle || needle.length < 4) return null;
  let best = null;
  let bestScore = 0;
  for (const series of seriesList) {
    const hay = foldId(series.labelCs || series.name);
    if (!hay) continue;
    if (hay.includes(needle) || needle.includes(hay)) {
      const score = Math.min(hay.length, needle.length);
      if (score > bestScore) {
        best = series;
        bestScore = score;
      }
    }
  }
  return best;
}

export function matchSeriesRefToChartSeries(seriesRef, seriesId, seriesList) {
  const ref = seriesRef && typeof seriesRef === "object" ? seriesRef : { series_id: seriesId };
  const id = String(ref.series_id || seriesId || "").trim();
  if (!Array.isArray(seriesList) || !seriesList.length) {
    return { series: null, confidence: "none", method: "none", attempted_matches: [] };
  }

  const lookup = buildSeriesLookup(seriesList);
  const attempted = [];
  const acceptedFuzzy = Boolean(ref.accepted_fuzzy_match || ref.accept_fuzzy_match);

  const hit = (series, confidence, method) => ({
    series,
    confidence,
    method,
    attempted_matches: attempted,
    accepted_fuzzy_match: acceptedFuzzy,
  });

  const payloadSetId = String(ref.chart_payload_set_id || "").trim();
  if (payloadSetId) {
    attempted.push({ key: payloadSetId, tier: "chart_payload_set_id" });
    const key = foldId(payloadSetId);
    if (lookup.setId.has(key)) return hit(lookup.setId.get(key), "high", "chart_payload_set_id");
  }

  const sourceSeriesId = String(ref.source_series_id || "").trim();
  if (sourceSeriesId) {
    attempted.push({ key: sourceSeriesId, tier: "source_series_id" });
    const key = foldId(sourceSeriesId);
    if (lookup.sourceSeriesId.has(key)) return hit(lookup.sourceSeriesId.get(key), "high", "source_series_id");
  }

  if (id) {
    for (const variant of slugVariants(id)) {
      attempted.push({ key: variant, tier: "exact_series_id" });
      if (lookup.exact.has(variant)) {
        return hit(
          lookup.exact.get(variant),
          variant === foldId(id) ? "high" : "medium",
          variant === foldId(id) ? "exact_series_id" : "normalized_series_id"
        );
      }
    }
  }

  const compositeKey = compositeRefKey(ref);
  if (compositeKey) {
    attempted.push({ key: compositeKey, tier: "signal_segment_geo_freq" });
    if (lookup.composite.has(compositeKey)) {
      return hit(lookup.composite.get(compositeKey), "high", "signal_segment_geo_freq");
    }
  }

  if (id) {
    for (const variant of slugVariants(id)) {
      attempted.push({ key: variant, tier: "metadata_slug" });
      if (lookup.metadata.has(variant)) {
        return hit(lookup.metadata.get(variant), "medium", "metadata_slug");
      }
    }
  }

  const fuzzyNeedle = ref.label || id;
  const fuzzy = fuzzyTitleMatch(fuzzyNeedle, seriesList);
  if (fuzzy) {
    attempted.push({ key: foldId(fuzzyNeedle), tier: "fuzzy_title" });
    return hit(fuzzy, "low", "fuzzy_title");
  }

  return { series: null, confidence: "none", method: "none", attempted_matches: attempted };
}

export function matchSeriesIdToChartSeries(seriesId, seriesList, seriesRef = null) {
  return matchSeriesRefToChartSeries(seriesRef, seriesId, seriesList);
}

export function validateRequiredSeriesMatchConfidence(requiredMatches, spec = {}) {
  const matches = Array.isArray(requiredMatches) ? requiredMatches : [];
  if (!matches.length) return { ok: false, reason: MULTI_SERIES_LOW_CONFIDENCE_REASON };

  const chartType = String(spec?.chart_type || "").trim().toLowerCase();
  const requiresMultiple = chartTypeRequiresMultipleSeries(chartType, spec);
  const globalAcceptedFuzzy = Boolean(spec?.accepted_fuzzy_match);

  const hasHigh = matches.some((m) => m.confidence === "high");
  const hasLowOnly = matches.some(
    (m) => m.confidence === "low" && !m.accepted_fuzzy_match && !globalAcceptedFuzzy
  );

  if (requiresMultiple) {
    if (!hasHigh) return { ok: false, reason: MULTI_SERIES_LOW_CONFIDENCE_REASON };
    if (hasLowOnly) return { ok: false, reason: MULTI_SERIES_LOW_CONFIDENCE_REASON };
  } else if (matches.every((m) => m.confidence === "low") && !globalAcceptedFuzzy) {
    return { ok: false, reason: MULTI_SERIES_LOW_CONFIDENCE_REASON };
  }

  return { ok: true, reason: "ok" };
}

export function countValidChartPoints(rows) {
  if (!Array.isArray(rows)) return 0;
  return rows.filter((row) => row && row.x && Number.isFinite(Number(row.y))).length;
}

const MULTI_SERIES_LOW_CONFIDENCE_REASON = "low_confidence_required_series_match";

export { chartTypeRequiresMultipleSeries, SCATTER_CHART_TYPES } from "@/lib/managerChartTypes";

export function resolveRequiredOptionalSeries(spec) {
  const explicitRequired = Array.isArray(spec?.required_series_ids) ? spec.required_series_ids : [];
  const explicitOptional = Array.isArray(spec?.optional_series_ids) ? spec.optional_series_ids : [];
  const allIds = Array.isArray(spec?.series_ids) ? spec.series_ids.map(String) : [];
  const optionalFromExplicit = explicitOptional.map(String);

  if (explicitRequired.length) {
    return {
      required_series_ids: explicitRequired.map(String),
      optional_series_ids: optionalFromExplicit,
    };
  }

  const chartType = String(spec?.chart_type || "").trim().toLowerCase();
  if (chartType === "score_breakdown") {
    return { required_series_ids: [], optional_series_ids: optionalFromExplicit };
  }

  if (spec?.relationship_id && allIds.length > 1) {
    return {
      required_series_ids: allIds.slice(),
      optional_series_ids: optionalFromExplicit,
    };
  }

  if (chartTypeRequiresMultipleSeries(chartType, spec)) {
    return {
      required_series_ids: allIds.slice(),
      optional_series_ids: optionalFromExplicit,
    };
  }

  if (chartType === "ratio_line" && !isPrecomputedRatio(spec) && allIds.length >= 2) {
    return {
      required_series_ids: allIds.slice(0, 2),
      optional_series_ids: allIds.slice(2).concat(optionalFromExplicit),
    };
  }

  if (allIds.length) {
    return {
      required_series_ids: [allIds[0]],
      optional_series_ids: allIds.slice(1).concat(optionalFromExplicit),
    };
  }

  return { required_series_ids: [], optional_series_ids: optionalFromExplicit };
}

function validObservationKeys(rows) {
  return new Set(
    (Array.isArray(rows) ? rows : [])
      .filter((row) => row && row.x != null && row.x !== "" && Number.isFinite(Number(row.y)))
      .map((row) => String(row.x).trim())
  );
}

export function countCommonObservations(seriesList) {
  if (!Array.isArray(seriesList) || !seriesList.length) return 0;
  const keySets = seriesList.map((series) => validObservationKeys(series?.rows));
  if (!keySets.length) return 0;
  let common = keySets[0];
  for (let i = 1; i < keySets.length; i += 1) {
    common = new Set([...common].filter((key) => keySets[i].has(key)));
  }
  return common.size;
}

export function countScatterPairedPoints(seriesList) {
  if (!Array.isArray(seriesList) || seriesList.length < 2) return 0;
  const primary = seriesList[0]?.rows || [];
  const secondary = seriesList[1]?.rows || [];
  const secondaryByX = new Map(
    secondary
      .filter((row) => row && row.x != null && row.x !== "" && Number.isFinite(Number(row.y)))
      .map((row) => [String(row.x).trim(), Number(row.y)])
  );
  let pairs = 0;
  for (const row of primary) {
    if (!row || row.x == null || row.x === "" || !Number.isFinite(Number(row.y))) continue;
    const y2 = secondaryByX.get(String(row.x).trim());
    if (Number.isFinite(y2)) pairs += 1;
  }
  return pairs;
}

function buildChartValidation(chartItem, partial = {}) {
  const spec = chartItem?.spec || {};
  const requiredIds = Array.isArray(chartItem?.required_series_ids) ? chartItem.required_series_ids : [];
  const requiredSeries = Array.isArray(chartItem?.required_series) ? chartItem.required_series : [];
  const matchedRequiredIds = Array.isArray(chartItem?.matched_required_series_ids)
    ? chartItem.matched_required_series_ids
    : requiredSeries.map((s) => s.seriesId || s.setId || s.name).filter(Boolean);
  const chartType = String(spec.chart_type || "").trim();
  return {
    ok: false,
    reason: "",
    chart_id: String(spec.chart_id || spec.title || "").trim(),
    chart_type: chartType,
    missing_series_ids: Array.isArray(chartItem?.missing_series_ids) ? chartItem.missing_series_ids : [],
    matched_series_ids: matchedRequiredIds,
    required_series_count: requiredIds.length,
    matched_required_series_count: requiredSeries.length,
    chart_type_requires_multiple_series: chartTypeRequiresMultipleSeries(chartType, spec),
    common_observation_count: 0,
    match_confidence: chartItem?.match_confidence || "none",
    point_count: 0,
    attempted_matches: chartItem?.attempted_matches || [],
    ...partial,
  };
}

export function validateResolvedManagerChart(chartItem, { allowLowConfidence = false } = {}) {
  const spec = chartItem?.spec || {};
  const chartId = String(spec.chart_id || spec.title || "").trim();
  const chartType = String(spec.chart_type || "").trim().toLowerCase();
  const isScoreBreakdown = Boolean(chartItem?.isScoreBreakdown || chartType === "score_breakdown");
  const requiresMultiple = chartTypeRequiresMultipleSeries(chartType, spec);
  const derivedSeriesIds = resolveRequiredOptionalSeries(spec);
  const requiredIds = Array.isArray(chartItem?.required_series_ids) && chartItem.required_series_ids.length
    ? chartItem.required_series_ids
    : derivedSeriesIds.required_series_ids;
  let requiredSeries = Array.isArray(chartItem?.required_series) ? chartItem.required_series : [];
  if (!requiredSeries.length && Array.isArray(chartItem?.series) && chartItem.series.length) {
    requiredSeries = chartItem.series;
  }
  const optionalSeries = Array.isArray(chartItem?.optional_series) ? chartItem.optional_series : [];
  const allMatchedSeries = [...requiredSeries, ...optionalSeries];
  const missing = Array.isArray(chartItem?.missing_series_ids) ? chartItem.missing_series_ids : [];
  const matchedRequiredIds = requiredSeries.map((s) => s.seriesId || s.setId || s.name).filter(Boolean);
  const matchConfidence = chartItem?.match_confidence || "none";

  if (isScoreBreakdown) {
    const breakdownOk = Array.isArray(chartItem?.breakdownRows) && chartItem.breakdownRows.length > 0;
    return buildChartValidation(chartItem, {
      ok: breakdownOk,
      reason: breakdownOk ? "score_breakdown_ok" : "score_breakdown_empty",
      chart_id: chartId || "score_breakdown",
      chart_type: "score_breakdown",
      missing_series_ids: [],
      matched_series_ids: [],
      required_series_count: 0,
      matched_required_series_count: 0,
      chart_type_requires_multiple_series: false,
      match_confidence: "high",
      point_count: 0,
    });
  }

  if (!chartId) {
    return buildChartValidation(chartItem, {
      reason: "missing_chart_identity",
      chart_id: "",
      match_confidence: "none",
    });
  }

  if (!chartType) {
    return buildChartValidation(chartItem, {
      reason: "missing_chart_type",
      match_confidence: matchConfidence,
    });
  }

  if (missing.length > 0) {
    return buildChartValidation(chartItem, {
      reason: "missing_required_series",
      matched_series_ids: matchedRequiredIds,
      matched_required_series_count: requiredSeries.length,
      chart_type_requires_multiple_series: requiresMultiple,
      match_confidence: matchConfidence,
    });
  }

  if (!requiredSeries.length && requiredIds.length > 0) {
    return buildChartValidation(chartItem, {
      reason: "no_matched_series",
      matched_series_ids: [],
      matched_required_series_count: 0,
      chart_type_requires_multiple_series: requiresMultiple,
      match_confidence: matchConfidence,
    });
  }

  if (requiresMultiple && requiredSeries.length < 2) {
    return buildChartValidation(chartItem, {
      reason: "insufficient_required_series",
      matched_series_ids: matchedRequiredIds,
      matched_required_series_count: requiredSeries.length,
      chart_type_requires_multiple_series: true,
      match_confidence: matchConfidence,
    });
  }

  const validationSeries = requiresMultiple ? requiredSeries : (requiredSeries.length ? requiredSeries : allMatchedSeries);
  const pointCount = validationSeries.reduce((sum, s) => sum + countValidChartPoints(s.rows), 0);

  const rawPointCount = validationSeries.reduce(
    (sum, s) => sum + (Array.isArray(s.rows) ? s.rows.length : 0),
    0
  );
  const allNull =
    rawPointCount > 0
    && validationSeries.every((s) =>
      (s.rows || []).every((row) => row.y == null || Number.isNaN(Number(row.y)))
    );
  if (allNull) {
    return buildChartValidation(chartItem, {
      reason: "all_values_null",
      matched_series_ids: matchedRequiredIds,
      matched_required_series_count: requiredSeries.length,
      chart_type_requires_multiple_series: requiresMultiple,
      match_confidence: matchConfidence,
      point_count: pointCount,
    });
  }

  const minPointsPerSeries = SCATTER_CHART_TYPES.has(chartType) ? 1 : 2;
  const seriesWithInsufficientPoints = validationSeries.filter(
    (s) => countValidChartPoints(s.rows) < minPointsPerSeries
  );
  if (seriesWithInsufficientPoints.length > 0) {
    return buildChartValidation(chartItem, {
      reason: "insufficient_points",
      matched_series_ids: matchedRequiredIds,
      matched_required_series_count: requiredSeries.length,
      chart_type_requires_multiple_series: requiresMultiple,
      match_confidence: matchConfidence,
      point_count: pointCount,
    });
  }

  let commonObservationCount = 0;
  if (SCATTER_CHART_TYPES.has(chartType)) {
    commonObservationCount = countScatterPairedPoints(requiredSeries);
    if (commonObservationCount < 3) {
      return buildChartValidation(chartItem, {
        reason: "insufficient_scatter_pairs",
        matched_series_ids: matchedRequiredIds,
        matched_required_series_count: requiredSeries.length,
        chart_type_requires_multiple_series: true,
        common_observation_count: commonObservationCount,
        match_confidence: matchConfidence,
        point_count: pointCount,
      });
    }
  } else if (requiresMultiple && requiredSeries.length >= 2) {
    commonObservationCount = countCommonObservations(requiredSeries);
    if (commonObservationCount < 2) {
      return buildChartValidation(chartItem, {
        reason: "insufficient_common_observations",
        matched_series_ids: matchedRequiredIds,
        matched_required_series_count: requiredSeries.length,
        chart_type_requires_multiple_series: true,
        common_observation_count: commonObservationCount,
        match_confidence: matchConfidence,
        point_count: pointCount,
      });
    }
  } else if (!requiresMultiple && pointCount < 2) {
    return buildChartValidation(chartItem, {
      reason: "insufficient_points",
      matched_series_ids: matchedRequiredIds,
      matched_required_series_count: requiredSeries.length,
      chart_type_requires_multiple_series: false,
      match_confidence: matchConfidence,
      point_count: pointCount,
    });
  }

  if (matchConfidence === "low" && !allowLowConfidence) {
    return buildChartValidation(chartItem, {
      reason: "low_confidence_match",
      matched_series_ids: matchedRequiredIds,
      matched_required_series_count: requiredSeries.length,
      chart_type_requires_multiple_series: requiresMultiple,
      common_observation_count: commonObservationCount,
      match_confidence: "low",
      point_count: pointCount,
    });
  }

  const requiredMatches = (chartItem?.seriesMatches || []).filter((m) => m.required !== false);
  const confidenceCheck = validateRequiredSeriesMatchConfidence(requiredMatches, spec);
  if (!confidenceCheck.ok) {
    return buildChartValidation(chartItem, {
      reason: confidenceCheck.reason,
      matched_series_ids: matchedRequiredIds,
      matched_required_series_count: requiredSeries.length,
      chart_type_requires_multiple_series: requiresMultiple,
      common_observation_count: commonObservationCount,
      match_confidence: matchConfidence,
      point_count: pointCount,
    });
  }

  return buildChartValidation(chartItem, {
    ok: true,
    reason: "ok",
    matched_series_ids: matchedRequiredIds,
    matched_required_series_count: requiredSeries.length,
    chart_type_requires_multiple_series: requiresMultiple,
    common_observation_count: commonObservationCount,
    match_confidence: matchConfidence,
    point_count: pointCount,
  });
}

function aggregateMatchConfidence(matches) {
  if (!matches.length) return "none";
  const minRank = Math.min(...matches.map((m) => MATCH_CONFIDENCE_RANK[m.confidence] ?? 0));
  if (minRank >= 3) return "high";
  if (minRank >= 2) return "medium";
  if (minRank >= 1) return "low";
  return "none";
}

/**
 * Mapuje manager_recommended_charts na chart series + validační metadata.
 */
export function resolveManagerRecommendedChartItems(recommendedCharts, chartSeries, breakdownRows = null) {
  const specs = Array.isArray(recommendedCharts) ? recommendedCharts : [];
  const seriesList = Array.isArray(chartSeries) ? chartSeries : [];
  if (!specs.length) return [];

  return specs
    .filter((spec) => spec && typeof spec === "object")
    .sort((a, b) => Number(a.priority || 99) - Number(b.priority || 99))
    .map((spec) => {
      const isScoreBreakdown = spec.chart_type === "score_breakdown";
      const { required_series_ids: requiredIds, optional_series_ids: optionalIds } = resolveRequiredOptionalSeries(spec);
      const requiredMatches = [];
      const optionalMatches = [];
      const requiredSeries = [];
      const optionalSeries = [];
      const missingSeriesIds = [];
      const missingOptionalSeriesIds = [];
      const attemptedMatches = [];

      if (isScoreBreakdown) {
        return {
          spec,
          series: [],
          required_series: [],
          optional_series: [],
          seriesMatches: [],
          isScoreBreakdown: true,
          breakdownRows: Array.isArray(breakdownRows) ? breakdownRows : [],
          required_series_ids: [],
          optional_series_ids: [],
          matched_required_series_ids: [],
          missing_series_ids: [],
          missing_optional_series_ids: [],
          match_confidence: "high",
          attempted_matches: [],
        };
      }

      const seriesRefById = (sid) => {
        const refs = Array.isArray(spec.series_refs) ? spec.series_refs : [];
        return refs.find((ref) => String(ref?.series_id || "") === String(sid)) || { series_id: sid };
      };

      const matchSeriesIds = (ids, bucketMatches, bucketSeries, missingBucket, required = true) => {
        for (const sid of ids) {
          const ref = seriesRefById(sid);
          const match = matchSeriesRefToChartSeries(ref, sid, seriesList);
          attemptedMatches.push({ series_id: sid, required, ...match });
          if (match.series) {
            bucketMatches.push({ series_id: sid, required, ...match });
            if (!bucketSeries.includes(match.series)) bucketSeries.push(match.series);
          } else if (required) {
            missingBucket.push(String(sid));
          } else {
            missingOptionalSeriesIds.push(String(sid));
          }
        }
      };

      matchSeriesIds(requiredIds, requiredMatches, requiredSeries, missingSeriesIds, true);
      matchSeriesIds(optionalIds, optionalMatches, optionalSeries, missingOptionalSeriesIds, false);

      const matchedSeries = [...requiredSeries, ...optionalSeries];
      const seriesMatches = [...requiredMatches, ...optionalMatches];
      const matchedRequiredSeriesIds = requiredMatches
        .map((row) => row.series_id)
        .filter(Boolean);

      return {
        spec,
        series: matchedSeries,
        required_series: requiredSeries,
        optional_series: optionalSeries,
        seriesMatches,
        isScoreBreakdown: false,
        required_series_ids: requiredIds,
        optional_series_ids: optionalIds,
        matched_required_series_ids: matchedRequiredSeriesIds,
        missing_series_ids: missingSeriesIds,
        missing_optional_series_ids: missingOptionalSeriesIds,
        match_confidence: aggregateMatchConfidence(requiredMatches),
        attempted_matches: attemptedMatches,
      };
    });
}

/** Skrýt per-section score ring, pokud platí hlavní manager final_score. */
export function shouldSuppressSectionPrimaryScore(result) {
  return hasValidManagerFinalScore(result);
}

export function partitionManagerRecommendedCharts(recommendedCharts, chartSeries, breakdownRows = null) {
  const resolved = resolveManagerRecommendedChartItems(recommendedCharts, chartSeries, breakdownRows);
  const valid = [];
  const invalid = [];

  const nonBreakdown = resolved.filter((item) => !item.isScoreBreakdown);
  const hasAnyNonLow = nonBreakdown.some(
    (item) => item.match_confidence !== "low" && item.required_series?.length > 0
  );

  for (const item of resolved) {
    const validation = validateResolvedManagerChart(item, {
      allowLowConfidence: !hasAnyNonLow,
    });
    if (!validation.ok) {
      invalid.push({ ...item, validation });
      continue;
    }

    if (item.isScoreBreakdown) {
      valid.push({ ...item, validation });
      continue;
    }

    const built = buildManagerChartSeriesPayload(item);
    if (!built.ok) {
      invalid.push({
        ...item,
        validation: {
          ...validation,
          ok: false,
          reason: built.reason,
          transform_reason: built.reason,
          data_quality_notes: built.data_quality_notes || [],
        },
        renderBuild: built,
      });
      continue;
    }

    valid.push({ ...item, validation, renderPayload: built.payload });
  }

  return { resolved, valid, invalid };
}

export function resolveManagerChartDisplayPlan(result, chartSeries) {
  const recommended = result?.manager_recommended_charts;
  const hasRecommended = Array.isArray(recommended) && recommended.length > 0;
  const ctx = resolveManagerInterpretationContext(result);
  const breakdownRows = ctx ? resolveScoreBreakdownRows(ctx) : [];
  const fallbackAvailable = Array.isArray(chartSeries) && chartSeries.length > 0;

  if (!hasRecommended) {
    return {
      mode: fallbackAvailable ? "fallback" : "none",
      validCharts: [],
      invalidCharts: [],
      showUnmappedMessage: false,
      useFallbackChartPayload: fallbackAvailable,
    };
  }

  const { valid, invalid } = partitionManagerRecommendedCharts(recommended, chartSeries, breakdownRows);

  if (valid.length > 0) {
    return {
      mode: "manager",
      validCharts: valid,
      invalidCharts: invalid,
      showUnmappedMessage: invalid.length > 0,
      useFallbackChartPayload: false,
    };
  }

  return {
    mode: "fallback",
    validCharts: [],
    invalidCharts: invalid,
    showUnmappedMessage: true,
    useFallbackChartPayload: fallbackAvailable,
  };
}

export function shouldUseManagerCharts(result) {
  return resolveManagerChartDisplayPlan(result, []).mode === "manager";
}

export function resolveManagerVerdict(result) {
  const raw = result?.manager_verdict && typeof result.manager_verdict === "object" ? result.manager_verdict : null;
  if (raw?.verdict_headline) {
    return {
      headline: String(raw.verdict_headline || "").trim(),
      businessConclusion: String(raw.business_conclusion || "").trim(),
      topReasons: Array.isArray(raw.top_reasons) ? raw.top_reasons.filter(Boolean) : [],
      keyRisks: Array.isArray(raw.key_risks) ? raw.key_risks.map((x) => String(x || "").trim()).filter(Boolean) : [],
      recommendation: String(raw.recommendation || "").trim(),
      decisionTriggers: Array.isArray(raw.decision_triggers)
        ? raw.decision_triggers.map((x) => String(x || "").trim()).filter(Boolean)
        : [],
      watchNext: Array.isArray(raw.watch_next) ? raw.watch_next.map((x) => String(x || "").trim()).filter(Boolean) : [],
      briefLimitations: String(raw.brief_limitations || "").trim(),
      hasStructuredVerdict: true,
    };
  }
  return {
    headline: "",
    businessConclusion: String(result?.assistant_answer_cz || result?.short_answer || "").trim(),
    topReasons: [],
    keyRisks: [],
    recommendation: "",
    decisionTriggers: [],
    watchNext: [],
    briefLimitations: String(result?.limitations_cz || result?.limitations || "").trim(),
    hasStructuredVerdict: false,
  };
}

const PLACEHOLDER_SECTION_RE = /chyb[ií]|nebyla dostupn|nepodařilo|bez dat|žádná pojmenovaná/i;

export function isPlaceholderExploreSectionText(text) {
  const raw = String(text || "").trim();
  if (!raw || raw.length < 16) return true;
  return PLACEHOLDER_SECTION_RE.test(raw);
}

export function resolveManagerRunTraceDisplay(result) {
  const trace = result?.manager_run_trace && typeof result.manager_run_trace === "object" ? result.manager_run_trace : null;
  const flags = trace?.consistency_flags && typeof trace.consistency_flags === "object" ? trace.consistency_flags : {};
  const qu = trace?.query_understanding && typeof trace.query_understanding === "object" ? trace.query_understanding : {};
  const finalReport = trace?.final_report && typeof trace.final_report === "object" ? trace.final_report : {};
  const consistencyError = flags.consistency_error === true || trace?.consistency_error === true;
  const chartsMismatch = flags.charts_match_primary_segment === false;
  return {
    trace,
    flags,
    consistencyError,
    chartsMismatch,
    primarySegment: String(qu.primary_segment || result?.primary_segment || "").trim() || null,
    mainSectionSegment: String(finalReport.main_section_segment || "").trim() || null,
    keyNumbersSegments: Array.isArray(finalReport.key_numbers_segments) ? finalReport.key_numbers_segments : [],
    chartSegments: Array.isArray(finalReport.chart_segments) ? finalReport.chart_segments : [],
  };
}

export { buildManagerChartSeriesPayload } from "@/lib/managerChartPayload";

export { formatScore, scoreVisual };
