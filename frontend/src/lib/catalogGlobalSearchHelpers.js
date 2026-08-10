/**
 * Pure helper funkce pro GlobalCatalogSearchPage.
 *
 * Žádné React hooky, žádný component state, žádné DOM side-effecty.
 * Extrahováno z GlobalCatalogSearchPage.jsx beze změny logiky.
 */
import { CATALOGS, catalogDefMatchesSourceId, normalizeCatalogSourceId } from "@/lib/catalogDefinitions";

// ---------------------------------------------------------------------------
// Normalizace a utility
// ---------------------------------------------------------------------------

export function normalizeSelectedIndicators(input) {
  if (!Array.isArray(input)) return [];
  const out = [];
  const seen = new Set();
  for (const raw of input) {
    const id = String(raw ?? "").trim();
    if (!id || seen.has(id)) continue;
    seen.add(id);
    out.push(id);
  }
  return out;
}

export function logCatalogPreviewDebug(event, details = {}) {
  if (typeof console === "undefined" || typeof console.debug !== "function") return;
  if (process.env.NODE_ENV !== "development") return;
  try {
    const detailText = JSON.stringify(details);
    console.debug(`[catalog-preview] ${event}${detailText ? ` ${detailText}` : ""}`);
  } catch {
    try {
      console.debug(`[catalog-preview] ${event}`);
    } catch {
      // no-op
    }
  }
}

/** Výstup hloubkového Eurostat AI / preview — nesmí se zobrazit přímo v produkčním UI (jen v „Technické detaily"). */
export function eurostatDeepAiMessageLooksInternalDebug(text) {
  const s = String(text ?? "");
  if (!s.trim()) return false;
  const u = s.toUpperCase();
  if (u.includes("EUROSTAT_DEEP_SEARCH")) return true;
  if (u.includes("REACT_APP_") || u.includes("NODE_ENV")) return true;
  if (/náhled\s+dat/i.test(s) && /hloubkov[a-zá]*\s+AI/i.test(s) && /vypnut/i.test(s)) return true;
  if (/Zapněte\s+/i.test(s) && /ALLOW_DATA/i.test(s)) return true;
  const euro = /eurostat/i.test(s);
  if (euro && /DATA\s*API/i.test(s)) return true;
  if (euro && (/paměti?\s+serveru/i.test(s) || /\bOOM\b/i.test(s) || /\bmemory\b/i.test(s))) return true;
  if (
    euro &&
    (/GATEWAY\s+TIMEOUT/i.test(s) || /READ\s+(BODY\s+)?TIMEOUT/i.test(s) || /UPSTREAM\s+TIMEOUT/i.test(s))
  ) {
    return true;
  }
  return false;
}

// ---------------------------------------------------------------------------
// Deep-search databáze / labely
// ---------------------------------------------------------------------------

/** Hodnoty `id` odpovídají backend `catalog_definitions` / `normalize_likely_sources_list`. */
const AI_QUICK_DATABASE_OPTIONS = [
  { id: "arad", label: "ČNB - ARAD" },
  { id: "csu", label: "ČSÚ" },
  { id: "eurostat", label: "Eurostat" },
  { id: "ecb2", label: "ECB" },
  { id: "fred", label: "FRED" },
  { id: "data360", label: "World Bank" },
  { id: "bis", label: "BIS" },
  { id: "imf", label: "IMF" },
  { id: "oecd4", label: "OECD" },
];

export function deepAiDatabaseLabelCz(id) {
  const raw = String(id || "").trim().toLowerCase();
  const normalized = normalizeCatalogSourceId(raw);
  if (raw === "internal") return "Interní data";
  if (raw === "ai_data_resolver" || normalized === "ai_data_resolver") return "AI resolver";
  const hit = AI_QUICK_DATABASE_OPTIONS.find((o) => normalizeCatalogSourceId(o.id) === normalized);
  if (hit) return hit.label;
  const def = CATALOGS.find((c) => catalogDefMatchesSourceId(c, normalized));
  return def?.label || raw.toUpperCase() || "zdroj";
}

export function deepAiSourceStatusText(meta) {
  const st = String(meta?.status || "").trim().toLowerCase();
  const count = Number(meta?.candidate_count ?? meta?.row_count ?? meta?.count ?? 0) || 0;
  if (st === "running") return "hledám";
  if (st === "warming") return "připravuji index";
  if (st === "not_ready") return "připravuji index";
  if (st === "timeout") return "timeout";
  if (st === "error") return "chyba";
  if (st === "skipped") return "přeskočeno";
  if (st === "selected") return "vybrána přesná řada";
  if (st === "needs_clarification") return "potřebuje upřesnit";
  if (st === "rejected") return "výběr odmítnut";
  if (st === "unavailable") return "nedostupné";
  if (st === "pending") return "čeká";
  if (count > 0) return `${count} kandidátů`;
  if (st === "empty") return "0 kandidátů";
  return st || "čeká";
}

// ---------------------------------------------------------------------------
// Deep-search výsledky — normalizace / řazení / mapping
// ---------------------------------------------------------------------------

/**
 * Ověřené / kandidátské řady z deep-search payloadu (retro vs. contract s `results`).
 * `deep_search_request_echo` se zde neřeší — neobsahuje řádky výsledků.
 */
export function normalizedDeepVerifiedPossible(payload) {
  if (!payload || typeof payload !== "object") return { verified: [], possible: [] };
  const verifiedRaw = Array.isArray(payload.verified) ? payload.verified : [];
  const possibleRaw = Array.isArray(payload.possible) ? payload.possible : [];
  const verified = verifiedRaw.filter((x) => x && typeof x === "object");
  const possible = possibleRaw.filter((x) => x && typeof x === "object");
  if (verified.length > 0 || possible.length > 0) return { verified, possible };

  const grouped = payload.grouped_results;
  if (grouped && typeof grouped === "object") {
    const gv = Array.isArray(grouped.verified) ? grouped.verified.filter((x) => x && typeof x === "object") : [];
    const gc = Array.isArray(grouped.candidates) ? grouped.candidates.filter((x) => x && typeof x === "object") : [];
    if (gv.length > 0 || gc.length > 0) {
      return { verified: gv, possible: gc };
    }
  }

  const resultsRaw = Array.isArray(payload.results) ? payload.results : [];
  const rows = resultsRaw.filter((x) => x && typeof x === "object");
  if (!rows.length) return { verified: [], possible: [] };

  const v = [];
  const p = [];
  for (const r of rows) {
    const st = typeof r.status === "string" ? r.status.toLowerCase() : "";
    const tier = typeof r.result_tier === "string" ? r.result_tier.toLowerCase() : "";
    const isPoss =
      st === "candidate" ||
      st === "unverified" ||
      tier === "candidate" ||
      tier === "possible" ||
      tier === "unverified";
    if (isPoss) p.push(r);
    else v.push(r);
  }
  return { verified: v, possible: p };
}

export function sortDeepCandidatesByFinalRank(rows) {
  const arr = Array.isArray(rows) ? rows.filter((x) => x && typeof x === "object") : [];
  return [...arr].sort((a, b) => {
    const ar = Number(a.final_rank ?? a.rank);
    const br = Number(b.final_rank ?? b.rank);
    const ah = Number.isFinite(ar) && ar > 0;
    const bh = Number.isFinite(br) && br > 0;
    if (ah && bh && ar !== br) return ar - br;
    if (ah !== bh) return ah ? -1 : 1;
    const af = Number(a.final_score ?? a._search_score ?? a.final_search_score ?? a.score ?? a.confidence ?? 0);
    const bf = Number(b.final_score ?? b._search_score ?? b.final_search_score ?? b.score ?? b.confidence ?? 0);
    if (Number.isFinite(af) && Number.isFinite(bf) && af !== bf) return bf - af;
    const av = String(a.status || a.preview_status || "").toLowerCase() === "verified" ? 1 : 0;
    const bv = String(b.status || b.preview_status || "").toLowerCase() === "verified" ? 1 : 0;
    if (av !== bv) return bv - av;
    return String(a.set_id || a.series_id || a.name || "").localeCompare(String(b.set_id || b.series_id || b.name || ""), "cs");
  });
}

export function deepCandidateHasUsablePreview(row) {
  if (!row || typeof row !== "object") return false;
  const semanticLevel = String(row.semantic_match_level || "").trim().toLowerCase();
  if (semanticLevel === "mismatch" || semanticLevel === "negative_mismatch" || row.topic_match === false) return false;
  if (row.negative_intent_match === true || row.metric_match === false || row.domain_match === false) return false;
  const previewStatus = String(row.preview_status || "").trim().toLowerCase();
  const tier = String(row.result_tier || "").trim().toLowerCase();
  if (previewStatus === "catalog_match" || tier === "catalog_match") return false;
  const statusVerified = String(row.status || "").trim().toLowerCase() === "verified";
  const previewVerified = previewStatus === "verified";
  const available =
    row.preview_available === true ||
    String(row.preview_available || "").trim().toLowerCase() === "true" ||
    Number(row.preview_row_count || 0) > 0;
  return (statusVerified || previewVerified) && available;
}

export function deepCandidateMayOpenUnverifiedPreview(row) {
  if (!row || typeof row !== "object") return false;
  const semanticLevel = String(row.semantic_match_level || "").trim().toLowerCase();
  if (semanticLevel === "mismatch" || semanticLevel === "negative_mismatch") return false;
  if (row.negative_intent_match === true) return false;
  if (row.geo_match === false || row.topic_match === false || row.metric_match === false || row.domain_match === false) {
    return false;
  }
  const previewChecked =
    row.preview_checked === true ||
    String(row.preview_checked || "").trim().toLowerCase() === "true";
  const previewAvailable =
    row.preview_available === true ||
    String(row.preview_available || "").trim().toLowerCase() === "true" ||
    Number(row.preview_row_count || 0) > 0;
  if (previewChecked && !previewAvailable) return false;
  const previewStatus = String(row.preview_status || "").trim().toLowerCase();
  const previewState = String(row.preview_state || "").trim().toLowerCase();
  if (previewStatus === "preview_available_semantic_mismatch") return false;
  if (["no_data", "sync_failed", "unsupported", "timeout", "all_zero"].includes(previewState)) return false;
  return true;
}

export function deepCandidateHasForeignCsuGeoBlock(row) {
  if (!row || typeof row !== "object") return false;
  const src = String(row.catalog_id || row.source_type || row.source || "").trim().toLowerCase();
  if (src !== "csu") return false;
  if (row.geo_match === false) return true;
  const demotion = String(row.demotion_reason || row.demotion_reasons || "").toLowerCase();
  if (demotion.includes("geo_mismatch_foreign_country")) return true;
  if (String(row.semantic_match_level || "").toLowerCase() === "mismatch") return true;
  const geo = row.detected_query_geo && typeof row.detected_query_geo === "object" ? row.detected_query_geo : {};
  const codes = Array.isArray(geo.country_codes) ? geo.country_codes : geo.country_code ? [geo.country_code] : [];
  return codes.some((code) => String(code || "").trim().toUpperCase() && String(code || "").trim().toUpperCase() !== "CZ");
}

export function deepCandidateSourceId(row) {
  return normalizeCatalogSourceId(row?.catalog_id || row?.source_type || row?.source || "");
}

export function deepCandidateResultKey(cand) {
  const src = deepCandidateSourceId(cand);
  const sid = String(cand?.set_id ?? cand?.series_id ?? "").trim();
  return `${src}::${sid}`;
}

export function catalogRowFromDeepCandidate(cand) {
  const sid = cand.set_id != null ? String(cand.set_id) : "";
  const title = cand.name || sid;
  const fp = cand.full_path || "";
  const geoRaw = cand.geo != null ? String(cand.geo).trim().toUpperCase() : "";
  const ck =
    cand.kind != null && String(cand.kind).trim()
      ? String(cand.kind).trim()
      : null;
  const sourceType = normalizeCatalogSourceId(cand.source_type || cand.source || cand.catalog_id || "");
  const internalVisual =
    ck === "internal_visual_asset" || /^internal_visual:/i.test(sid) || sourceType === "internal_visual";
  const euroCatalogRef =
    sourceType === "eurostat" && cand.catalog_node_ref && typeof cand.catalog_node_ref === "object"
      ? { ...cand.catalog_node_ref }
      : undefined;
  return {
    kind: ck || "set",
    item_kind: cand.item_kind != null ? String(cand.item_kind) : ck || undefined,
    set_id: sid,
    name: title,
    full_path: fp,
    path: fp || title || sid,
    depth: 0,
    parentPath: "",
    catalog_id: cand.catalog_id || sourceType,
    source_type: sourceType,
    source: cand.source || sourceType,
    period: cand.period || "",
    territory: cand.territory || "",
    last_update: cand.last_update || "",
    fromDeepAi: true,
    eurostat_geo: geoRaw,
    catalog_node_ref: euroCatalogRef,
    query_params:
      cand.query_params && typeof cand.query_params === "object" ? { ...cand.query_params } : undefined,
    actions: Array.isArray(cand.actions) ? cand.actions : undefined,
    contact_user_id: cand.contact_user_id != null ? String(cand.contact_user_id).trim() : "",
    previewPayload: cand.preview_payload && typeof cand.preview_payload === "object" ? cand.preview_payload : null,
    visualActions: cand.visual_actions && typeof cand.visual_actions === "object" ? cand.visual_actions : null,
    internalVisualAsset: internalVisual,
  };
}

// ---------------------------------------------------------------------------
// Deep-search source status / viditelnost
// ---------------------------------------------------------------------------

const CATALOG_SOURCE_EQUIVALENTS = {
  ecb: new Set(["ecb", "ecb2"]),
  ecb2: new Set(["ecb", "ecb2"]),
  fred: new Set(["fred", "fred_remote"]),
  fred_remote: new Set(["fred", "fred_remote"]),
  imf: new Set(["imf", "imf2"]),
  imf2: new Set(["imf", "imf2"]),
  oecd: new Set(["oecd", "oecd2", "oecd3", "oecd4"]),
  oecd2: new Set(["oecd", "oecd2", "oecd3", "oecd4"]),
  oecd3: new Set(["oecd", "oecd2", "oecd3", "oecd4"]),
  oecd4: new Set(["oecd", "oecd2", "oecd3", "oecd4"]),
  worldbank: new Set(["data360", "world_bank_data360"]),
  data360: new Set(["data360", "world_bank_data360"]),
  world_bank_data360: new Set(["data360", "world_bank_data360"]),
};

function catalogSourcesEquivalent(a, b) {
  const aa = normalizeCatalogSourceId(a);
  const bb = normalizeCatalogSourceId(b);
  if (!aa || !bb) return false;
  if (aa === bb) return true;
  const famA = CATALOG_SOURCE_EQUIVALENTS[aa];
  if (famA && famA.has(bb)) return true;
  const famB = CATALOG_SOURCE_EQUIVALENTS[bb];
  return Boolean(famB && famB.has(aa));
}

export function deepStatusHasVisibleCandidates(statusId, visibleSourceIds) {
  const sid = String(statusId || "").trim().toLowerCase();
  if (!sid) return false;
  for (const vid of visibleSourceIds) {
    if (catalogSourcesEquivalent(sid, vid)) return true;
  }
  return false;
}

export function deepCandidateForDisplay(row) {
  if (!row || typeof row !== "object") return row;
  if (!deepCandidateHasForeignCsuGeoBlock(row)) return row;
  return {
    ...row,
    status: String(row.status || "").toLowerCase() === "verified" ? "candidate" : row.status,
    result_tier: "mismatch",
    match_quality_cz: String(row.match_quality_cz || "").toLowerCase() === "vysoká" ? "možná" : row.match_quality_cz,
    semantic_match_level: row.semantic_match_level || "mismatch",
    demotion_reason: row.demotion_reason || "geo_mismatch_foreign_country",
  };
}

// ---------------------------------------------------------------------------
// Deep AI index status / banner messages
// ---------------------------------------------------------------------------

/** Stav zdroje, kdy nesmíme tvrdit „v indexu nic není" — jde o nehotový index / infra, ne absenci datové řady. */
const DEEP_AI_INDEX_PROBLEM_STATUSES = new Set([
  "not_ready",
  "warming",
  "not_indexed",
  "timeout",
  "error",
  "temporarily_unavailable",
]);

export function deepAiHasIndexProblemStatusRaw(st) {
  return DEEP_AI_INDEX_PROBLEM_STATUSES.has(String(st || "").trim().toLowerCase());
}

/** Hlavičková zpráva pro single-source režim (Eurostat rozlišení not_ready vs timeout). */
export function deepAiIndexProblemBannerMessageCz(sourceId, status) {
  const sid = String(sourceId || "").trim().toLowerCase();
  const st = String(status || "").trim().toLowerCase();
  if (!st || !deepAiHasIndexProblemStatusRaw(st)) return "";

  if (sid === "eurostat" && (st === "not_ready" || st === "warming")) {
    return (
      "Eurostat se ještě připravuje / index se načítá. Zkuste znovu za chvíli nebo klikněte obnovit."
    );
  }
  if (sid === "eurostat" && st === "timeout") {
    return (
      "Vyhledávání v Eurostatu trvalo příliš dlouho. Zkuste dotaz zúžit nebo použijte klasické procházení katalogu."
    );
  }
  if (st === "not_indexed") {
    return "Tento zdroj zatím není napojený na AI vyhledávání.";
  }
  if (st === "error") {
    return "Vyhledávání v tomto zdroji selhalo. Ostatní zdroje můžete použít dál.";
  }
  if (st === "temporarily_unavailable") {
    return "Vyhledávání v tomto zdroji není momentálně k dispozici. Zkuste to později.";
  }
  if (st === "not_ready") {
    return (
      "AI index pro tento zdroj zatím není připravený. Nejprve obnovte příslušný katalog nebo použijte klasické procházení níže."
    );
  }
  if (st === "timeout") {
    return "Vyhledávání v tomto zdroji trvalo příliš dlouho. Zkuste dotaz zúžit nebo použijte klasické procházení katalogu.";
  }
  return "";
}
