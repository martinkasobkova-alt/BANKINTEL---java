/**
 * Klasické cross-search přes POST /catalog/search — sdílené řazení a merge více zdrojů.
 * Lokální AND filtr v catalogTree.js zůstává jako fallback (viz buildLocalCrossSearchFlatResults).
 */

import { resolveCatalogRowSetId } from "@/lib/catalogPreviewBody";
import { buildFilteredPaths, buildPathIndex, parseSearchKeywords } from "@/lib/catalogTree";

export const CROSS_SEARCH_RESULT_CAP = 1200;
export const CROSS_SEARCH_TIMEOUT_MS = 65000;
/** Speciální hodnota scope — hledat ve všech dostupných katalozích (ne jen ve filtrech). */
export const CLASSIC_SEARCH_SCOPE_ALL = "all";

const SOURCE_ID_ALIASES = {
  ecb: "ecb2",
  oecd: "oecd4",
  data360: "world_bank_data360",
};

export function resolveClassicSearchCatalogDefs(catalogs, scopeId) {
  const scope = String(scopeId || "").trim();
  if (scope === CLASSIC_SEARCH_SCOPE_ALL) {
    return (catalogs || []).filter(Boolean);
  }
  const def = (catalogs || []).find((c) => c.id === scope);
  return def ? [def] : [];
}

export function allowedCatalogIdsForScope(scopeId, _selectedIds, catalogs) {
  return new Set(resolveClassicSearchCatalogDefs(catalogs, scopeId).map((d) => d.id));
}

export function filterHitsByCatalogScope(hits, allowedCatalogIds) {
  const allowed = allowedCatalogIds instanceof Set ? allowedCatalogIds : new Set(allowedCatalogIds || []);
  if (!allowed.size) return [];
  return (hits || []).filter((hit) => allowed.has(String(hit?.catalog_id || "").trim().toLowerCase()));
}

/** Skóre z backendové odpovědi (composite relevance patch). */
export function catalogSearchScore(hit) {
  const raw =
    hit?._search_score ??
    hit?.final_search_score ??
    hit?._final_search_score ??
    0;
  const n = Number(raw);
  return Number.isFinite(n) ? n : 0;
}

/**
 * Primárně _search_score desc; previewable jen jako jemný tie-breaker;
 * katalog a název až na konci.
 */
export function sortCatalogSearchHits(hits) {
  return [...(hits || [])].sort((a, b) => {
    const scoreDiff = catalogSearchScore(b) - catalogSearchScore(a);
    if (scoreDiff !== 0) return scoreDiff;
    const ap = a?.previewable === true ? 1 : 0;
    const bp = b?.previewable === true ? 1 : 0;
    if (ap !== bp) return bp - ap;
    const al = String(a?.catalog_label || "").localeCompare(String(b?.catalog_label || ""), "cs");
    if (al !== 0) return al;
    return String(a?.name || a?.title || "").localeCompare(String(b?.name || b?.title || ""), "cs");
  });
}

export function resolveCatalogDefForSearchHit(hit, catalogs) {
  let raw = String(hit?.catalog_id || hit?.source_type || "").trim().toLowerCase();
  if (SOURCE_ID_ALIASES[raw]) raw = SOURCE_ID_ALIASES[raw];
  return (
    (catalogs || []).find((d) => d.id === raw || d.sourceType === raw || d.sourceType === hit?.source_type) ||
    (catalogs || []).find((d) => d.id === String(hit?.catalog_id || "").trim().toLowerCase())
  );
}

export function catalogSearchHitRow(hit) {
  if (hit?.row && typeof hit.row === "object") return hit.row;
  return hit;
}

/** Backend hit → tvar { def, row, searchScore } pro renderCatalogSetBlock. */
export function catalogSearchHitToFlatEntry(hit, catalogs) {
  const def = resolveCatalogDefForSearchHit(hit, catalogs);
  if (!def) return null;
  const base = catalogSearchHitRow(hit);
  if (!base || typeof base !== "object") return null;
  const mergedRow = {
    ...base,
    set_id: base.set_id || hit.set_id || hit.code,
    dataset_id: base.dataset_id || hit.dataset_id,
  };
  const rawSetId = String(mergedRow.set_id || "").trim();
  const canonicalSetId = resolveCatalogRowSetId(mergedRow);
  const row = {
    ...mergedRow,
    set_id: canonicalSetId,
    ...(rawSetId && rawSetId.toLowerCase() !== canonicalSetId.toLowerCase()
      ? { series_id: rawSetId, sidecar_series_id: rawSetId }
      : {}),
    name: base.name || hit.name || hit.title,
    full_path: base.full_path || base.path || hit.catalog_path || "",
    path: base.path || base.full_path || hit.catalog_path || "",
    item_kind: base.item_kind || base.kind || hit.item_kind || "set",
    kind: base.kind || "set",
    indicator_id: base.indicator_id || hit.indicator_id,
    indicator_name: base.indicator_name || hit.indicator_name,
    previewable: hit.previewable ?? base.previewable,
    query_params:
      base.query_params && typeof base.query_params === "object"
        ? base.query_params
        : hit.query_params,
  };
  return {
    def,
    row,
    searchScore: catalogSearchScore(hit),
    resultSource: "backend",
  };
}

/**
 * Původní lokální cross-search (AND tokenů v catalogTree) — fallback when backend fails.
 */
export function buildLocalCrossSearchFlatResults({
  catalogs,
  selectedIds,
  indexedRowsByCat,
  query,
  cap = CROSS_SEARCH_RESULT_CAP,
}) {
  const keywords = parseSearchKeywords(query);
  if (keywords.length === 0) return [];
  const selected = selectedIds instanceof Set ? selectedIds : new Set(selectedIds || []);
  const out = [];
  const seenKeys = new Set();
  for (const def of catalogs || []) {
    if (!selected.has(def.id)) continue;
    const allRows = indexedRowsByCat?.[def.id];
    if (!Array.isArray(allRows) || !allRows.length) continue;
    const rowIndex = buildPathIndex(allRows);
    const filteredPaths = buildFilteredPaths(allRows, rowIndex, keywords);
    if (!filteredPaths) continue;
    for (const r of allRows) {
      if (r.kind !== "set" || !filteredPaths.has(r.path)) continue;
      const dedupeKey = `${def.id}::${String(r.set_id || r.id || "").trim().toLowerCase()}`;
      if (seenKeys.has(dedupeKey)) continue;
      seenKeys.add(dedupeKey);
      out.push({ def, row: r, searchScore: 0, resultSource: "local" });
    }
  }
  out.sort((a, b) => {
    const la = a.def.label.localeCompare(b.def.label, "cs");
    if (la !== 0) return la;
    return String(a.row.name || "").localeCompare(String(b.row.name || ""), "cs");
  });
  return out.slice(0, cap);
}

function limitPerSourceForCount(count) {
  const n = Math.max(1, Number(count) || 1);
  return n === 1 ? 40 : 24;
}

/**
 * Paralelní POST /catalog/search pro vybrané katalogy.
 * @returns {Promise<{hits: Array, sourceSummaries: Array, allFailed: boolean, query: string}>}
 */
export async function runCatalogCrossSearch(apiClient, { query, catalogDefs, limitPerSource, timeoutMs }) {
  const q = String(query || "").trim();
  const defs = (catalogDefs || []).filter(Boolean);
  if (q.length < 2 || !defs.length) {
    return { hits: [], sourceSummaries: [], allFailed: false, query: q };
  }
  const lim = limitPerSource || limitPerSourceForCount(defs.length);
  const timeout = timeoutMs || CROSS_SEARCH_TIMEOUT_MS;

  const settled = await Promise.allSettled(
    defs.map((def) =>
      apiClient.post(
        "/catalog/search",
        { source: def.id, query: q, limit: lim },
        { timeout },
      ),
    ),
  );

  const mergedHits = [];
  const sourceSummaries = [];
  let successCount = 0;
  const allowedIds = new Set(defs.map((d) => d.id));

  settled.forEach((result, idx) => {
    const def = defs[idx];
    if (!def) return;
    if (result.status !== "fulfilled") {
      sourceSummaries.push({
        id: def.id,
        label: def.label,
        hits: 0,
        failed: true,
        upstream_unavailable: true,
      });
      return;
    }
    const raw = result.value?.data && typeof result.value.data === "object" ? result.value.data : {};
    const hits = filterHitsByCatalogScope(
      Array.isArray(raw.results) ? raw.results : [],
      allowedIds,
    );
    successCount += 1;
    hits.forEach((hit) => {
      if (!hit || typeof hit !== "object") return;
      mergedHits.push({
        ...hit,
        catalog_id: String(hit.catalog_id || def.id),
        catalog_label: String(hit.catalog_label || def.label),
      });
    });
    sourceSummaries.push({
      id: def.id,
      label: def.label,
      hits: hits.length,
      failed: false,
      upstream_unavailable: Boolean(raw.upstream_unavailable),
      local_index_missing: Boolean(raw.local_index_missing),
      index_status: String(raw.index_status || "").trim() || null,
      message_cs: String(raw.message_cs || "").trim() || null,
      elapsed_ms: Number.isFinite(Number(raw.elapsed_ms)) ? Number(raw.elapsed_ms) : null,
    });
  });

  return {
    hits: sortCatalogSearchHits(mergedHits).slice(0, CROSS_SEARCH_RESULT_CAP),
    sourceSummaries,
    partialIndexMissing: sourceSummaries.some((s) => s.local_index_missing),
    allFailed: successCount === 0,
    query: q,
  };
}
