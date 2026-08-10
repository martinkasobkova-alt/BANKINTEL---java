/**
 * Sdílený klient pro POST /api/catalog/deep-search (AI pipeline, use_ai=true).
 */

/** Quick AI — router + až 5 zdrojů; backend počká na všechny lane (typ. ~20–75 s). */
export const CATALOG_AI_QUICK_TIMEOUT_MS =
  Number(process.env.REACT_APP_CATALOG_AI_QUICK_TIMEOUT_MS) || 80000;

/** Rozšířené SSE / multi POST — všechny vybrané katalogy. */
export const CATALOG_DEEP_SEARCH_TIMEOUT_MS =
  Number(process.env.REACT_APP_DEEP_SEARCH_TIMEOUT_MS) || 185000;

/** Preflight AI source router — krátký POST bez fulltextu katalogů. */
export const CATALOG_SOURCE_ROUTE_TIMEOUT_MS =
  Number(process.env.REACT_APP_CATALOG_SOURCE_ROUTE_TIMEOUT_MS) || 12000;

/** @param {unknown} route */
export function sourceIdsFromCatalogRoute(route) {
  const raw = route?.selected_sources ?? route?.sources;
  if (!Array.isArray(raw)) return [];
  const seen = new Set();
  const out = [];
  for (const x of raw) {
    const sid = String(x || "").trim().toLowerCase();
    if (!sid || sid === "tradingeconomics" || seen.has(sid)) continue;
    seen.add(sid);
    out.push(sid);
  }
  return out;
}

/**
 * @param {string[]} sourceIds
 * @param {(id: string) => string} labelFn
 * @param {"pending"|"running"} [status]
 */
export function buildCatalogSourceStatusRows(sourceIds, labelFn, status = "pending") {
  return (sourceIds || []).map((sid) => ({
    source: sid,
    label: labelFn(sid),
    status,
    row_count: 0,
    duration_ms: 0,
    message_cs: "",
  }));
}

/** @param {unknown} payload */
export function normalizeDeepSearchResultRows(payload) {
  if (!payload || typeof payload !== "object") {
    return { verified: [], possible: [], candidates: [] };
  }
  const verifiedRaw = Array.isArray(payload.verified) ? payload.verified : [];
  const possibleRaw = Array.isArray(payload.possible) ? payload.possible : [];
  const verified = verifiedRaw.filter((x) => x && typeof x === "object");
  const possible = possibleRaw.filter((x) => x && typeof x === "object");
  if (verified.length > 0 || possible.length > 0) {
    return { verified, possible, candidates: possible };
  }

  const grouped = payload.grouped_results;
  if (grouped && typeof grouped === "object") {
    const gv = Array.isArray(grouped.verified) ? grouped.verified.filter((x) => x && typeof x === "object") : [];
    const gc = Array.isArray(grouped.candidates) ? grouped.candidates.filter((x) => x && typeof x === "object") : [];
    const gb = Array.isArray(grouped.beta) ? grouped.beta.filter((x) => x && typeof x === "object") : [];
    if (gv.length > 0 || gc.length > 0 || gb.length > 0) {
      return { verified: gv, possible: [...gc, ...gb], candidates: gc };
    }
  }

  const resultsRaw = Array.isArray(payload.results) ? payload.results : [];
  const rows = resultsRaw.filter((x) => x && typeof x === "object");
  if (!rows.length) return { verified: [], possible: [], candidates: [] };

  const v = [];
  const p = [];
  for (const r of rows) {
    const st = typeof r.status === "string" ? r.status.toLowerCase() : "";
    const tier = typeof r.result_tier === "string" ? r.result_tier.toLowerCase() : "";
    const isPoss =
      st === "candidate" ||
      st === "unverified" ||
      st === "beta" ||
      tier === "candidate" ||
      tier === "possible" ||
      tier === "unverified" ||
      tier === "beta";
    if (isPoss) p.push(r);
    else v.push(r);
  }
  return { verified: v, possible: p, candidates: p };
}

/**
 * @param {{ query: string, sources: Iterable<string>, limitPerSource?: number, mode?: string, searchProfile?: string }} opts
 */
export function buildCatalogDeepSearchBody({
  query,
  sources,
  limitPerSource = 20,
  mode = "multi",
  searchProfile,
}) {
  const dq = String(query || "").trim();
  const profile = String(searchProfile || mode || "multi").trim().toLowerCase();
  const isQuick = profile === "quick_ai" || profile === "quick" || profile === "classic_ai";
  const sourcesSorted = [...(sources || [])]
    .map((s) => String(s || "").trim().toLowerCase())
    .filter((sid) => sid && sid !== "tradingeconomics")
    .sort();
  return {
    q: dq,
    query: dq,
    sources: sourcesSorted,
    use_ai: true,
    // Relevance still uses the LLM planner and reranker. Narrative prose must not block results.
    use_ai_story: false,
    mode: isQuick ? "quick_ai" : mode === "single" ? "single" : "multi",
    quick_ai: isQuick,
    request_id: isQuick ? `fe-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}` : undefined,
    limit_per_source: isQuick
      ? Math.max(3, Math.min(Number(limitPerSource) || 10, 12))
      : Math.max(1, Math.min(Number(limitPerSource) || 20, 40)),
  };
}

/** @param {unknown} payload */
export function extractDeepSearchPipelineDiagnostics(payload) {
  const diag =
    payload && typeof payload === "object" && payload.search_diagnostics && typeof payload.search_diagnostics === "object"
      ? payload.search_diagnostics
      : {};
  const intent = diag.intent && typeof diag.intent === "object" ? diag.intent : {};
  const pipeline =
    diag.pipeline_telemetry && typeof diag.pipeline_telemetry === "object" ? diag.pipeline_telemetry : {};
  const resolvedGeo = diag.resolved_geo && typeof diag.resolved_geo === "object" ? diag.resolved_geo : {};
  return {
    queryDomain: String(intent.query_domain || "").trim(),
    resolvedGeo,
    searchedSources: Array.isArray(diag.searched_sources)
      ? diag.searched_sources.map((s) => String(s || "").trim()).filter(Boolean)
      : [],
    durationMsTotal: Number(pipeline.duration_ms_total) || 0,
    retrievalQueryCap: Number(pipeline.max_retrieval_queries_per_source) || 0,
    timeoutCount: Number(pipeline.timeout_count) || 0,
    fallbackCount: Number(pipeline.fallback_count) || 0,
    gptModel: String(pipeline.gpt_model || diag.used_model || "").trim(),
  };
}
