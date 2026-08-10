import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Loader2, Search, Sparkles, X } from "lucide-react";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import VoiceInputButton from "@/components/common/VoiceInputButton";
import ChatMarkdownText from "@/components/common/ChatMarkdownText";
import { CATALOGS, STOCK_MARKET_CATALOGS } from "@/lib/catalogDefinitions";
import { buildCatalogPreviewBody } from "@/lib/catalogPreviewBody";
import { isCatalogRowPreviewEligible } from "@/lib/catalogRowPreviewEligible";
import {
  fetchSeriesConceptExplanation,
  fetchSeriesConceptFollowup,
  seriesConceptExplainSourceNote,
} from "@/lib/catalogSeriesConceptExplain";
import { fetchRelatedSeries } from "@/lib/catalogRelatedSeries";
import { fetchChartDataQuality } from "@/lib/chartDataQuality";
import {
  resultAfterAutomaticApply,
  shouldAutoApplyChartActions,
  uniqueResearchCitations,
} from "@/lib/chartActionExecution";
import {
  addSeriesFallbackResult,
  buildAddSeriesSelectionContext,
  candidateHasQuerySupport,
  compactCurrencyPairFromText,
  contextualAddSeriesSearchQueries,
  rankAddSeriesSuggestions,
  suggestionMatchesSelectionContext,
  suggestionDedupeKey,
  uniqueNonEmpty,
} from "@/lib/chartAddSeriesSearch";
import { deepSearchRowsForPicker } from "@/lib/catalogChartPickerSearch";

// Akcie (yahoo_finance/alphavantage) jsou schvalne mimo CATALOGS (viz catalogDefinitions.js),
// takze jakekoli hledani zdroje/katalogu tady musi prochazet OBA seznamy, jinak "stocks"/
// "yahoo_finance" nikdy nevyjde jako preferovany/rozpoznany zdroj a graf s akciovym primarnim
// widgetem nedostane bonus pro dalsi akcie (viz addSeriesSuggestionScore sourceHints).
const ALL_CATALOGS = [...CATALOGS, ...STOCK_MARKET_CATALOGS];

const SUGGESTIONS = [
  "Co daná řada říká?",
  "Najdi související řady",
  "Najdi největší drawdown a ukaž ho v grafu",
  "Nakresli trendovou linku",
  "Spočítej Sharpe ratio",
  "Najdi vztahy mezi řadami",
  "Vyznač v grafu období krizí podle ověřených webových zdrojů",
  "Připrav analytickou kostku",
];

function actionLabel(action) {
  const type = String(action?.type || "");
  if (type === "draw_trend_line") return "Nakreslit trendovou linku";
  if (type === "highlight_period") return `Zvýraznit období ${action.from || ""} - ${action.to || ""}`;
  if (type === "annotate_period") return `Vyznačit ${action.label || "událost"}: ${action.from || ""} - ${action.to || ""}`;
  if (type === "add_derived_series") return `Odvozená řada: ${action.label || action.series_id || ""}`;
  if (type === "add_catalog_series") return `Přidat řadu: ${action.name || action.title || action.set_id || ""}`;
  if (type === "open_catalog_search") return `Vyhledat v katalogu: ${action.query || ""}`;
  if (type === "open_cube_view") return "Otevřít analytickou kostku";
  return type || "Akce grafu";
}

function actionKey(action, idx) {
  return `${action?.type || "action"}-${action?.series_id || ""}-${action?.from || ""}-${action?.to || ""}-${idx}`;
}

function wantsConceptExplanation(question) {
  const q = String(question || "").toLowerCase();
  return /co\s+(dana|dan[aá]|tato|ten|ukazatel|[řr]ada).*(rik[aá]|[řr][ií]k[aá]|ukazuje|znamena|znamen[aá])/.test(q)
    || /vysv[eě]tl|popi[sš].*(ukazatel|[řr]ad)/.test(q);
}

function wantsRelatedSeries(question) {
  const q = String(question || "").toLowerCase();
  return /p[řr][ií]buz|souvisej[ií]c|podobn/.test(q)
    || /p[řr]id(ej|at).*(ukazatel|[řr]ad)/.test(q)
    || /najdi.*(ukazatel|[řr]ad).*(katalog|souvisej|p[řr][ií]buz|podobn)/.test(q);
}

function normalizeQueryText(value) {
  return String(value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();
}

function looksLikeMarketPair(question) {
  return /\b[A-Z]{3}\s*[/-]\s*[A-Z]{3}\b/i.test(String(question || ""))
    || compactCurrencyPairFromText(question) != null;
}

function wantsVisibleSeriesRelationship(question, chartContract) {
  const seriesCount = Array.isArray(chartContract?.series) ? chartContract.series.length : 0;
  if (seriesCount < 2 || wantsRelatedSeries(question)) return false;
  const q = normalizeQueryText(question);
  return /\b(vztah|vztahy|korelace|korelaci|korelovat|souvislost|rozdil|rozdily|porovnej|porovnani)\b/.test(q)
    && /\b(rad|rada|rady|radami|serie|serii|ukazatel|ukazateli|zeme|zememi)\b/.test(q);
}

function wantsAddSeriesToChart(question) {
  const q = normalizeQueryText(question);
  return (
    (/\b(pridej|pridat|pridas|dopln|doplnit|doplnis|vloz|vlozit|vlozis|add)\b/.test(q)
      && /\b(graf|grafu|radu|rada|serie|serii|ukazatel|vyvoj)\b/.test(q))
    || (/\b(dokazes|dokazal|dokazala|umis|muzes|mohl|mohla|jde|slo)\b/.test(q)
      && /\b(pridat|pridej|doplnit|vlozit)\b/.test(q)
      && /\b(graf|grafu|radu|rada|serie|serii|ukazatel|vyvoj)\b/.test(q))
    || /\b(chci|ukaz|ukazat|najdi|hledej|zobraz|zobrazit)\b/.test(q) && (looksLikeMarketPair(question) || /\b(radu|rada|serie|serii|kurz|fx|exchange|eur|czk|usd)\b/.test(q))
  );
}

function wantsChartAnnotationEdit(question) {
  const q = normalizeQueryText(question);
  return /\b(oddelej|odstran|smaz|vymaz|skryj|zrus|zrusit|remove|delete|clear|hide)\b/.test(q)
    && /\b(anotac|vyznac|zvyrazn|udalost|obdobi|interval|vrstv|popisk|label|period|annotation|event|vlad|government|kabinet)\b/.test(q);
}

function catalogAliasMatches(text, alias) {
  const a = normalizeQueryText(alias).replace(/[^a-z0-9]+/g, " ").trim();
  if (a.length < 3) return false;
  const q = ` ${normalizeQueryText(text).replace(/[^a-z0-9]+/g, " ")} `;
  if (a.includes(" ")) return q.includes(` ${a} `);
  return new RegExp(`\\b${a}[a-z0-9]*\\b`, "i").test(q);
}

function requestedCatalogIdsFromText(text) {
  const ids = new Set();
  for (const def of ALL_CATALOGS) {
    const aliases = [
      def.id,
      def.sourceType,
      def.label,
      String(def.label || "").replace(/\s*-\s*/g, " "),
    ];
    if (def.id === "ecb2") aliases.push("ecb");
    if (def.id === "oecd4") aliases.push("oecd");
    if (def.id === "data360") aliases.push("world bank", "worldbank", "svetova banka", "svetove banky");
    if (def.id === "arad") aliases.push("arad", "cnb arad", "cnb", "cnb aradu");
    if (def.id === "csu") aliases.push("csu", "cesky statisticky urad");
    if (aliases.some((alias) => catalogAliasMatches(text, alias))) {
      ids.add(def.id);
      // Kandidati z deep-search maji akcie oznacene obecnym source_type/catalog "stocks"
      // (viz catalogDefForSuggestion), ne "yahoo_finance"/"alphavantage" - bez tohoto by
      // sourceHints v addSeriesSuggestionScore nikdy netrefil skutecny akciovy kandidat.
      if (def.id === "yahoo_finance" || def.id === "alphavantage") ids.add("stocks");
    }
  }
  if (catalogAliasMatches(text, "akcie") || catalogAliasMatches(text, "akcii") || catalogAliasMatches(text, "stocks")) {
    ids.add("stocks");
  }
  return Array.from(ids);
}

/** Významová slova z názvu řady - bez krátkých/čistě číselných tokenů (roky, kódy). */
function significantLabelTokens(label) {
  return String(label || "")
    .toLowerCase()
    .split(/[^a-z0-9áčďéěíňóřšťúůýž]+/i)
    .filter((t) => t.length >= 3 && !/^\d+$/.test(t));
}

/**
 * Katalogové názvy řad mívají tvar "Země · Ukazatel (jednotka)" (např. "France · HDP nominální
 * (USD)") - vyžadovat shodu VŠECH slov z celého názvu by selhalo na otázku, která zmíní jen zemi
 * ("co říká řada France?"), protože "hdp"/"nominální"/"usd" v otázce nejsou. Rozdělíme proto název
 * i na jednotlivé segmenty (země / ukazatel) a zkusíme shodu na každém zvlášť - celý název i každý
 * segment jsou samostatní kandidáti.
 */
function labelMatchCandidates(label) {
  const trimmed = String(label || "").trim();
  if (!trimmed) return [];
  const segments = trimmed
    .split(/[·:—–]/)
    .map((s) => s.trim())
    .filter(Boolean);
  return [trimmed, ...segments];
}

/**
 * Multi-series graf ("Srovnat s...") - vysvětlení/follow-up/příbuzné řady se dřív VŽDY ptaly na
 * hlavní/výchozí řadu (`conceptMeta`), i když se uživatel textově zeptal na jinou, přidanou řadu
 * (`meta.available_series`, viz buildWidgetConceptExplainMeta). Pokud text otázky obsahuje
 * všechna významová slova z názvu (nebo jen jednoho segmentu názvu) jiné řady z grafu (uživatel
 * běžně píše zkráceně, např. "gold dec" pro "Gold Dec 26", nebo jen zemi z "France · HDP..."),
 * přepneme identitu (title/source_type/set_id/indikátor) na tu řadu - jinak se chová stejně jako
 * dřív (jen hlavní řada).
 */
function pickConceptMetaForQuestion(meta, question) {
  const list = Array.isArray(meta?.available_series) ? meta.available_series : [];
  const q = String(question || "").trim().toLowerCase();
  if (list.length < 2 || !q) return meta;
  let best = null;
  let bestScore = 0;
  for (const candidate of list) {
    const label = String(candidate?.title || candidate?.name || "").trim();
    if (label.length < 3) continue;
    for (const part of labelMatchCandidates(label)) {
      const tokens = significantLabelTokens(part);
      let score = 0;
      if (tokens.length > 0) {
        if (!tokens.every((t) => q.includes(t))) continue;
        score = tokens.length;
      } else if (q.includes(part.toLowerCase())) {
        score = 1;
      } else {
        continue;
      }
      if (score > bestScore) {
        best = candidate;
        bestScore = score;
      }
    }
  }
  if (!best) return meta;
  return {
    ...meta,
    title: best.title || meta.title,
    name: best.name || best.title || meta.name,
    source_type: best.source_type || meta.source_type,
    catalog_id: best.source_type || meta.catalog_id,
    set_id: best.set_id || meta.set_id,
    dataset_id: best.set_id || meta.dataset_id,
    selected_indicator_name: best.selected_indicator_name || undefined,
    indicator_name: best.selected_indicator_name || undefined,
  };
}

function catalogIdsFromChartContract(chartContract, conceptMeta = null) {
  const source = chartContract?.source || {};
  const meta = chartContract?.metadata || {};
  const concept = conceptMeta || {};
  const values = [
    source?.id,
    source?.name,
    source?.source,
    source?.source_type,
    source?.catalog,
    meta?.source,
    meta?.source_id,
    meta?.source_type,
    meta?.catalog,
    meta?.catalog_id,
    concept?.source,
    concept?.source_id,
    concept?.source_type,
    concept?.catalog,
    concept?.catalog_id,
    concept?.provider,
  ].filter(Boolean);
  const ids = new Set();
  for (const value of values) {
    for (const id of requestedCatalogIdsFromText(value)) ids.add(id);
    const direct = String(value || "").trim().toLowerCase();
    if (direct === "stocks") ids.add("stocks");
    if (ALL_CATALOGS.some((def) => String(def.id || "").toLowerCase() === direct)) ids.add(direct);
    const bySourceType = ALL_CATALOGS.find((def) => String(def.sourceType || "").toLowerCase() === direct);
    if (bySourceType?.id) ids.add(bySourceType.id);
    if (direct === "yahoo_finance" || direct === "alphavantage") ids.add("stocks");
  }
  return Array.from(ids);
}

function suggestionMatchesCatalogIds(item, sourceIds) {
  const allowed = new Set((Array.isArray(sourceIds) ? sourceIds : []).map((id) => String(id || "").toLowerCase()));
  if (!allowed.size) return true;
  const def = catalogDefForSuggestion(item);
  const ids = [
    def?.id,
    def?.sourceType,
    item?.catalog_id,
    item?.source,
    item?.catalog,
    item?.source_type,
  ].map((id) => String(id || "").toLowerCase()).filter(Boolean);
  return ids.some((id) => allowed.has(id));
}

function stripSeriesCorrectionPrefix(question) {
  return String(question || "")
    .trim()
    .replace(/^(?:ta\s+)?(?:r[̌ř]?ada|serie|seri[eí]|ukazatel|indikator)\s+(?:je|ma\s+byt|má\s+být|=|:)?\s*/i, "")
    .trim();
}

function extractAddSeriesQuery(question) {
  const original = String(question || "").trim();
  const normalized = normalizeQueryText(original);
  if (!original) return "";
  const corrected = stripSeriesCorrectionPrefix(original);
  if (corrected && corrected !== original) return corrected;
  const afterAddIntent = original.match(/(?:pridat|přidat|pridej|přidej|pridas|přidáš|doplnit|dopln|vlozit|vlož(?:it)?|add)\s+(.+)$/i);
  if (afterAddIntent?.[1]) {
    const cleaned = afterAddIntent[1]
      .replace(/\b(do|k|ke|na|v)\s+(grafu|graf|chartu|chart)\b/gi, " ")
      .replace(/\b(grafu|graf|chartu|chart)\b/gi, " ")
      .replace(/\s+/g, " ")
      .trim();
    if (cleaned) return cleaned;
  }
  const dropWords = new Set([
    "dokazes",
    "dokazal",
    "dokazala",
    "dokazat",
    "umis",
    "umel",
    "umela",
    "muzes",
    "muze",
    "mohl",
    "mohla",
    "prosim",
    "mi",
    "sem",
    "tam",
    "to",
    "tu",
    "by",
    "bys",
    "pridej",
    "pridat",
    "pridas",
    "dopln",
    "doplnit",
    "doplnis",
    "vloz",
    "vlozit",
    "vlozis",
    "add",
    "chci",
    "ukaz",
    "ukazat",
    "najdi",
    "hledej",
    "zobraz",
    "zobrazit",
    "do",
    "k",
    "ke",
    "na",
    "v",
    "graf",
    "grafu",
    "grafem",
    "radu",
    "rada",
    "serie",
    "serii",
    "ukazatel",
    "indikator",
    "vyvoj",
    "kurz",
  ]);
  const compact = original
    .split(/\s+/)
    .filter((word) => !dropWords.has(normalizeQueryText(word)))
    .join(" ")
    .replace(/\s+/g, " ")
    .trim();
  if (compact) return compact;
  return normalized
    .replace(/\b(pridej|pridat|dopln|doplnit|vloz|vlozit|add|do|grafu|graf|radu|rada|serie|serii|ukazatel|indikator|vyvoj)\b/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

async function fetchAddSeriesSuggestions(query, selectionContext) {
  const byKey = new Map();
  const queries = contextualAddSeriesSearchQueries(query, selectionContext);
  const primaryQuery = queries[0] || query;
  const sourceIds = Array.isArray(selectionContext?.sourceIds) ? selectionContext.sourceIds : [];
  const preferredSourceIds = sourceIds.length
    ? sourceIds
    : (Array.isArray(selectionContext?.preferredSourceIds) ? selectionContext.preferredSourceIds : []);
  try {
    const { data } = await api.post(
      "/catalog/deep-search",
      {
        q: primaryQuery,
        query: primaryQuery,
        sources: preferredSourceIds.length ? preferredSourceIds : undefined,
        use_ai: true,
        mode: preferredSourceIds.length === 1 ? "single" : "multi",
        limit_per_source: preferredSourceIds.length === 1 ? 18 : 6,
      },
      { timeout: 25_000 },
    );
    for (const row of deepSearchRowsForPicker(data)) {
      const item = {
        ...row,
        source: row?.source || row?.source_type || row?.catalog_id,
        catalog: row?.catalog || row?.source_type || row?.catalog_id,
        title: row?.title || row?.name || row?.dataset_name,
        _query_used: primaryQuery,
        _search_engine: "deep-search",
      };
      const key = suggestionDedupeKey(item);
      if (!key || key === "::" || byKey.has(key)) continue;
      byKey.set(key, item);
    }
  } catch {
    // Keep autocomplete as a fallback, but never auto-add without the safety checks below.
  }
  const responses = await Promise.all(queries.map(async (q) => {
    try {
      const { data } = await api.get("/catalog/suggest", { params: { q, limit: 12 } });
      return { q, data };
    } catch {
      return null;
    }
  }));
  for (const response of responses) {
    if (!response) continue;
    const { q, data } = response;
      const suggestions = Array.isArray(data?.suggestions) ? data.suggestions : [];
      for (const item of suggestions) {
        const key = suggestionDedupeKey(item);
        if (!key || key === "::" || byKey.has(key)) continue;
        byKey.set(key, { ...item, _query_used: q });
      }
  }
  return Array.from(byKey.values());
}

function shouldTreatAsAddSeriesFollowup(question, turns) {
  const q = String(question || "").trim();
  if (!q) return false;
  if (wantsAddSeriesToChart(q)) return true;
  const normalized = normalizeQueryText(q);
  const latest = Array.isArray(turns) && turns.length ? turns[turns.length - 1] : null;
  const latestActions = latest?.result?.chart_actions || [];
  const latestWasAddAttempt =
    wantsAddSeriesToChart(latest?.question || "") ||
    latestActions.some((a) => String(a?.type || "") === "open_catalog_search" || String(a?.type || "") === "add_catalog_series");
  if (!latestWasAddAttempt) return false;
  if (/^(ta\s+)?(rada|serie|serii|ukazatel|indikator)\s+(je|ma\s+byt|=|:)?\s+/.test(normalized)) return true;
  const words = normalized.split(/\s+/).filter(Boolean);
  return words.length > 0 && words.length <= 4 && !/[?]/.test(q);
}

function chartIntentHistory(turns) {
  return (Array.isArray(turns) ? turns : []).flatMap((t) => {
    const answer = t?.result?.answer_cz ? [{ role: "assistant", content: t.result.answer_cz }] : [];
    return [{ role: "user", content: t?.question || "" }, ...answer];
  });
}

function chartConversationState(turns) {
  return (Array.isArray(turns) ? turns : []).slice(-6).map((turn) => ({
    question: String(turn?.question || "").slice(0, 500),
    answer_cz: String(turn?.result?.answer_cz || "").slice(0, 700),
    research_mode: String(turn?.result?.research_mode || "").slice(0, 80),
    chart_actions: (Array.isArray(turn?.result?.chart_actions) ? turn.result.chart_actions : [])
      .slice(0, 8)
      .map((action) => ({
        type: String(action?.type || "").slice(0, 80),
        label: String(action?.label || "").slice(0, 180),
        from: String(action?.from || "").slice(0, 32),
        to: String(action?.to || "").slice(0, 32),
        description_cz: String(action?.description_cz || "").slice(0, 360),
        series_id: String(action?.series_id || "").slice(0, 160),
      })),
  }));
}

function chartConversationStorageKey(chartContract, conceptMeta) {
  const source = chartContract?.source || {};
  const identity = JSON.stringify({
    title: chartContract?.title || conceptMeta?.title || "",
    source: source?.name || chartContract?.metadata?.source || conceptMeta?.source || "",
    dataset: source?.dataset || chartContract?.metadata?.dataset || conceptMeta?.set_id || "",
    series: (Array.isArray(chartContract?.series) ? chartContract.series : []).map((series) => [
      series?.id || series?.key || "",
      series?.label || "",
    ]),
  });
  let hash = 2166136261;
  for (let i = 0; i < identity.length; i += 1) {
    hash ^= identity.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return `bankintel:chart-chat:v2:${(hash >>> 0).toString(36)}`;
}

function persistentChartTurn(turn) {
  const result = turn?.result;
  return {
    id: String(turn?.id || ""),
    question: String(turn?.question || "").slice(0, 500),
    error: String(turn?.error || "").slice(0, 500),
    result: result && typeof result === "object"
      ? {
          ok: result.ok,
          answer_cz: result.answer_cz,
          methodology_cz: result.methodology_cz,
          research_mode: result.research_mode,
          chart_actions: Array.isArray(result.chart_actions) ? result.chart_actions.slice(0, 24) : [],
          related_items: Array.isArray(result.related_items) ? result.related_items.slice(0, 12) : [],
          external_items: Array.isArray(result.external_items) ? result.external_items.slice(0, 12) : [],
          citations: Array.isArray(result.citations) ? result.citations.slice(0, 16) : [],
          warnings: Array.isArray(result.warnings) ? result.warnings.slice(0, 8) : [],
          action_execution: result.action_execution,
          plan: result.plan,
        }
      : null,
  };
}

function loadChartConversation(storageKey) {
  if (typeof sessionStorage === "undefined") return [];
  try {
    const parsed = JSON.parse(sessionStorage.getItem(storageKey) || "[]");
    return Array.isArray(parsed) ? parsed.slice(-12) : [];
  } catch {
    return [];
  }
}

function saveChartConversation(storageKey, turns) {
  if (typeof sessionStorage === "undefined") return;
  try {
    sessionStorage.setItem(
      storageKey,
      JSON.stringify((Array.isArray(turns) ? turns : []).slice(-12).map(persistentChartTurn)),
    );
  } catch {
    // Conversation persistence is a convenience; a full or blocked storage must not break the chat.
  }
}

async function resolveChartIntent({ question, chartContract, turns }) {
  try {
    const { data } = await api.post("/chart-agent/intent", {
      question,
      chart_contract: chartContract,
      conversation_history: chartIntentHistory(turns),
      conversation_state: chartConversationState(turns),
    });
    return data && typeof data === "object" ? data : null;
  } catch {
    return null;
  }
}

function catalogDefForSuggestion(item) {
  // Deep-search vraci akcie s obecnym source_type="stocks"; skutecny konektor
  // (yahoo_finance/alphavantage) je jen v raw.preview_source_type. CATALOGS ho
  // nema vubec (akcie jsou schvalne v samostatnem STOCK_MARKET_CATALOGS), takze
  // bez tohohle pole tu def vzdy vyjde null a overeni tise selze pro kazdou akcii.
  const candidates = [
    item?.raw?.preview_source_type,
    item?.source,
    item?.catalog,
    item?.source_type,
    item?.catalog_id,
  ]
    .map((v) => String(v || "").trim().toLowerCase())
    .filter(Boolean);
  if (!candidates.length) return null;
  const allCatalogs = [...CATALOGS, ...STOCK_MARKET_CATALOGS];
  for (const source of candidates) {
    const match = allCatalogs.find((def) =>
      String(def.id || "").toLowerCase() === source ||
      String(def.sourceType || "").toLowerCase() === source
    );
    if (match) return match;
  }
  return null;
}

function suggestionToRow(item) {
  const setId = String(item?.set_id || item?.series_id || item?.code || "").trim();
  const indicatorId = String(item?.indicator_id || item?.selected_indicator || "").trim();
  return {
    ...item,
    kind: item?.kind || item?.item_kind || "set",
    item_kind: item?.item_kind || item?.kind || "set",
    set_id: setId,
    name: String(item?.title || item?.name || item?.dataset_name || setId || indicatorId || "").trim(),
    title: String(item?.title || item?.name || item?.dataset_name || setId || indicatorId || "").trim(),
    selected_indicator: indicatorId,
    indicator_id: indicatorId,
  };
}

async function verifyCatalogSuggestion(item) {
  const def = catalogDefForSuggestion(item);
  const row = suggestionToRow(item);
  if (!def || !isCatalogRowPreviewEligible(def, row)) return null;
  try {
    const body = buildCatalogPreviewBody(def, row);
    const { data } = await api.post("/catalog/preview", body, { timeout: 20_000 });
    const rows =
      (Array.isArray(data?.rows) && data.rows) ||
      (Array.isArray(data?.data) && data.data) ||
      (Array.isArray(data?.records) && data.records) ||
      [];
    if (!rows.length) return null;
    return { item, def, row, preview: data };
  } catch {
    return null;
  }
}

function cleanCatalogQuery(text) {
  return String(text || "")
    .replace(/\s*\([AQMDS]\)\s*/gi, " ")
    .split("·")[0]
    .replace(/\s+/g, " ")
    .trim();
}

function addCatalogActionFromSuggestion(item, query = "") {
  const row = suggestionToRow(item);
  // Akcie maji v deep-search vysledcich jen obecny "stocks" bucket v source/catalog/source_type -
  // skutecny konektor (yahoo_finance/alphavantage) najde jen catalogDefForSuggestion (cte
  // raw.preview_source_type). Bez tohohle se do /homepage/render-widget poslal catalog:"stocks",
  // ktery backend nezna -> preview tise selze -> uzivatel vidi nesmyslnou hlasku "vyzaduje
  // upresneni dimenze" i pro obycejnou akcii bez zadnych dimenzi.
  const catalog = catalogDefForSuggestion(item)?.id
    || String(item?.source || item?.catalog || item?.source_type || item?.catalog_id || "").trim().toLowerCase();
  const setId = String(row.set_id || "").trim();
  const selectedIndicator = String(row.selected_indicator || row.indicator_id || "").trim();
  if (!catalog || (!setId && !selectedIndicator)) return null;
  const name = String(item?.title || item?.name || item?.dataset_name || setId || selectedIndicator || query).trim();
  return {
    type: "add_catalog_series",
    query,
    catalog,
    source: catalog,
    set_id: setId,
    selected_indicator: selectedIndicator,
    indicator_id: selectedIndicator,
    name,
    title: name,
  };
}

function chartContractHasPrivateData(contract) {
  if (!contract || typeof contract !== "object") return false;
  const meta = contract.metadata && typeof contract.metadata === "object" ? contract.metadata : {};
  if (meta.contains_private_series || String(meta.privacy_mode || "").toLowerCase() === "strict_private") return true;
  const isPrivate = (obj) => {
    if (!obj || typeof obj !== "object") return false;
    const m = obj.metadata && typeof obj.metadata === "object" ? obj.metadata : {};
    const privacy = String(obj.privacy || obj.privacy_level || obj.privacy_mode || m.privacy || m.privacy_level || m.privacy_mode || "").toLowerCase();
    const sourceType = String(obj.source_type || obj.kind || m.source_type || m.kind || "").toLowerCase();
    return ["private", "confidential", "strict_private"].includes(privacy)
      || ["user_upload", "uploaded_data_chart", "user_upload_chart", "file_upload", "private", "company"].includes(sourceType)
      || Boolean(obj.user_upload_id || obj.upload_id || obj.file_upload_id || m.user_upload_id);
  };
  return (contract.series || []).some(isPrivate) || (contract.data || []).some(isPrivate);
}

function compareOlapPeriodDesc(a, b) {
  const ap = String(a?.period_id || "");
  const bp = String(b?.period_id || "");
  const ay = Number((ap.match(/\d{4}/) || [])[0]);
  const by = Number((bp.match(/\d{4}/) || [])[0]);
  if (Number.isFinite(ay) && Number.isFinite(by) && ay !== by) return by - ay;
  return bp.localeCompare(ap, "cs");
}

function buildOlapAiContext(olapPackage) {
  const tables = olapPackage?.tables || {};
  const factRows = Array.isArray(tables.fact_values) ? tables.fact_values : [];
  if (!factRows.length) return null;
  const series = Array.isArray(tables.dim_series) ? tables.dim_series : [];
  const periods = Array.isArray(tables.dim_period) ? tables.dim_period : [];
  const geos = Array.isArray(tables.dim_geo) ? tables.dim_geo : [];
  const sources = Array.isArray(tables.dim_source) ? tables.dim_source : [];
  const seriesById = new Map(series.map((row) => [String(row.series_id || ""), row]));
  const latestFacts = [...factRows].sort(compareOlapPeriodDesc).slice(0, 40).map((row) => {
    const dimSeries = seriesById.get(String(row.series_id || "")) || {};
    return {
      period_id: row.period_id,
      series_id: row.series_id,
      series_label: dimSeries.series_label || row.series_id,
      geo_id: row.geo_id,
      value: row.value,
      unit: row.unit,
      frequency: row.frequency,
      transformation: row.transformation,
    };
  });
  return {
    type: olapPackage?.type || "bankoapp_olap_cube",
    title: olapPackage?.title || "",
    schema: olapPackage?.schema || null,
    table_counts: {
      fact_values: factRows.length,
      dim_period: periods.length,
      dim_series: series.length,
      dim_geo: geos.length,
      dim_source: sources.length,
    },
    dim_series: series.slice(0, 30),
    latest_fact_rows: latestFacts,
  };
}

export function ChartAnalystPanel({
  chartContract,
  olapPackage = null,
  olapActive = false,
  conceptMeta = null,
  disabled = false,
  onApplyActions,
  onFindInCatalogSearch,
  onClose,
  embedded = false,
  fillHeight = false,
  heading = "AI nad grafem",
  subheading = "Zeptejte se na význam řady, výpočet, vztah nebo související data",
  emptyText = "AI pracuje s hodnotami a metadaty aktuálního grafu, ne s obrázkem grafu.",
  suggestions = SUGGESTIONS,
  headerExtra = null,
  resolveChartContractForQuestion = null,
  /**
   * Přepíše automaticky spočítaný klíč konverzace (jinak se počítá z chartContract - název +
   * zdroj + dataset + seznam řad). Použije se u "AI nad dashboardem": tam se sada widgetů běžně
   * mění (přidání/odebrání grafu), takže klíč odvozený od chartContract by po každé změně vedl na
   * jinou konverzaci - "AI nad dashboardem" místo toho posílá stabilní klíč vázaný na ID stránky.
   */
  conversationKey = "",
  /**
   * Standardně `ask()` odmítne cokoliv, dokud graf/dashboard nemá čitelná data (hasData) ani
   * conceptMeta. "AI nad dashboardem" ale musí jít použít i na prázdném dashboardu - jediné, co
   * tam dává smysl, je požádat o přidání prvního grafu (viz onApplyActions), a to čitelná data
   * nevyžaduje.
   */
  allowEmptyData = false,
}) {
  const conversationStorageKey = useMemo(
    () => (conversationKey ? `bankintel:chart-chat:v2:custom:${conversationKey}` : chartConversationStorageKey(chartContract, conceptMeta)),
    [conversationKey, chartContract, conceptMeta],
  );
  const [question, setQuestion] = useState("");
  const [loading, setLoading] = useState(false);
  const [turns, setTurns] = useState(() => loadChartConversation(conversationStorageKey));
  const conversationStorageKeyRef = useRef(conversationStorageKey);
  const [appliedTurnIds, setAppliedTurnIds] = useState(() => new Set());
  const [applyNotice, setApplyNotice] = useState("");
  const [applyNoticeQuery, setApplyNoticeQuery] = useState("");
  const [seriesSearchQuery, setSeriesSearchQuery] = useState("");

  const hasData = Array.isArray(chartContract?.data) && chartContract.data.length > 0;
  const hasConcept = Boolean(conceptMeta && typeof conceptMeta === "object");
  const olapAiContext = useMemo(() => buildOlapAiContext(olapPackage), [olapPackage]);
  const latestTurn = turns.length ? turns[turns.length - 1] : null;

  useEffect(() => {
    if (conversationStorageKeyRef.current !== conversationStorageKey) {
      conversationStorageKeyRef.current = conversationStorageKey;
      setTurns(loadChartConversation(conversationStorageKey));
      setAppliedTurnIds(new Set());
      return;
    }
    saveChartConversation(conversationStorageKey, turns);
  }, [conversationStorageKey, turns]);

  const openCatalogSearch = useCallback(
    (query) => {
      const q = String(query || "").trim();
      if (!q) return;
      if (typeof onFindInCatalogSearch === "function") {
        onFindInCatalogSearch(q);
      }
    },
    [onFindInCatalogSearch],
  );

  const resolveConceptExplanation = useCallback(async (q = "", contract = null) => {
    const effectiveMeta = pickConceptMetaForQuestion(conceptMeta, q);
    const tasks = [fetchSeriesConceptExplanation(effectiveMeta, contract)];
    if (effectiveMeta?.data_stale || effectiveMeta?.last_period) {
      tasks.push(fetchChartDataQuality(effectiveMeta));
    }
    const [result, quality] = await Promise.all(tasks);
    if (!result?.ok || !result.explanation_cz) {
      throw new Error(String(result?.message_cs || "Vysvětlení se nepodařilo načíst."));
    }
    const note = seriesConceptExplainSourceNote(result);
    const warnings = quality?.stale && quality.notice_cs ? [quality.notice_cs] : [];
    return {
      answer_cz: result.explanation_cz,
      methodology_cz: result.read_hint_cz ? `Jak číst graf: ${result.read_hint_cz}` : note || "",
      warnings,
      chart_actions: [],
      is_concept_explanation: true,
    };
  }, [conceptMeta]);

  const resolveConceptFollowup = useCallback(
    async (q, priorAnswer, priorTurns, contract = null) => {
      const effectiveMeta = pickConceptMetaForQuestion(conceptMeta, q);
      const history = chartIntentHistory(priorTurns);
      const result = await fetchSeriesConceptFollowup(effectiveMeta, q, priorAnswer, history, contract);
      if (!result?.ok || !result.answer_cz) {
        throw new Error(String(result?.message_cs || "Odpověď se nepodařila načíst."));
      }
      const note = seriesConceptExplainSourceNote(result);
      return {
        answer_cz: result.answer_cz,
        methodology_cz: note || "",
        warnings: [],
        chart_actions: result.find_in_data && result.catalog_search_query
          ? [{ type: "open_catalog_search", query: result.catalog_search_query }]
          : [],
        is_concept_explanation: true,
      };
    },
    [conceptMeta],
  );

  const resolveRelatedSeries = useCallback(async (q = "") => {
    const effectiveMeta = pickConceptMetaForQuestion(conceptMeta, q);
    const result = await fetchRelatedSeries(effectiveMeta);
    if (!result?.ok) {
      throw new Error(String(result?.messageCs || "Příbuzné ukazatele se nepodařilo načíst."));
    }
    const items = Array.isArray(result.items) ? result.items : [];
    return {
      answer_cz: items.length
        ? `Našel jsem ${items.length} souvisejících řad. Můžete je otevřít v katalogu a přidat do porovnání grafu.`
        : result.messageCs || "Nenašel jsem dostatečně relevantní související řady.",
      methodology_cz: "Hledání souvisejících řad používá metadata aktuálního ukazatele, ne obrázek grafu.",
      chart_actions: [],
      related_items: items,
    };
  }, [conceptMeta]);

  const resolveAddSeriesToChartSafe = useCallback(async (q, selectionContext, chartContractForResearch) => {
    const query = extractAddSeriesQuery(q);
    // Poradi zalezi: `query` je uz vycisteny LLM/extractAddSeriesQuery vystup ("Alphabet Inc.
    // (Google) revenue"), zatimco selectionContext.originalQuestion je syrova puvodni veta
    // ("pridej tm google"). Ta drive stala PRVNI, takze i po uspesnem rozpoznani ukazatele se
    // fallback "Vyhledat v katalogu" odkaz posilal se surovou vetou mista s cistym nazvem.
    const fallbackQuery = String(query || q || selectionContext?.originalQuestion || "").trim();
    const fallbackSourceIds = Array.isArray(selectionContext?.sourceIds) && selectionContext.sourceIds.length
      ? selectionContext.sourceIds
      : (Array.isArray(selectionContext?.preferredSourceIds) ? selectionContext.preferredSourceIds : []);
    const fallbackOptions = { fallbackQuery, sourceIds: fallbackSourceIds };
    if (!query || query.length < 2) {
      return {
        answer_cz: "Napište, jakou řadu mám do grafu přidat. Zkuste název ukazatele, zkratku nebo zdroj.",
        methodology_cz: "Požadavek jsem rozpoznal jako úpravu grafu, ale chybí hledaný výraz.",
        chart_actions: [],
      };
    }
    let items = [];
    try {
      items = rankAddSeriesSuggestions(await fetchAddSeriesSuggestions(query, selectionContext), query, selectionContext);
    } catch {
      return addSeriesFallbackResult(query, [], "katalogovy naseptavac ted nevratil pouzitelnou odpoved", fallbackOptions);
    }
    const contextConstrained = ["inherit_chart", "explicit"].includes(String(selectionContext?.mode || ""))
      && Array.isArray(selectionContext?.terms)
      && selectionContext.terms.length > 0;
    const contextMatchedItems = contextConstrained
      ? items.filter((item) => suggestionMatchesSelectionContext(item, selectionContext))
      : items;
    const compatibleItems = contextMatchedItems.length ? contextMatchedItems : items;
    const sourceConstrainedItems = Array.isArray(selectionContext?.sourceIds) && selectionContext.sourceIds.length
      ? compatibleItems.filter((item) => suggestionMatchesCatalogIds(item, selectionContext.sourceIds))
      : compatibleItems;
    if (contextConstrained && !compatibleItems.length) {
      return addSeriesFallbackResult(
        query,
        [],
        "nenasel jsem radu odpovidajici zemi nebo entite zadane v dotazu ci aktivnim grafu",
        fallbackOptions,
      );
    }
    if (compatibleItems.length && !sourceConstrainedItems.length) {
      return addSeriesFallbackResult(
        query,
        [],
        "nalezene kandidaty neodpovidaji zdroji uvedenemu v dotazu",
        fallbackOptions,
      );
    }
    const queryEvidence = uniqueNonEmpty([
      query,
      ...(Array.isArray(selectionContext?.searchQueries) ? selectionContext.searchQueries : []),
    ]);
    const supportedItems = sourceConstrainedItems.filter((item) => candidateHasQuerySupport(item, queryEvidence));
    if (sourceConstrainedItems.length && !supportedItems.length) {
      return addSeriesFallbackResult(
        query,
        sourceConstrainedItems,
        "nalezene kandidaty nemaji dostatecnou vazbu na pozadovany ukazatel",
        fallbackOptions,
      );
    }
    let verified = null;
    for (const item of supportedItems.slice(0, 10)) {
      // Validate before proposing an auto-add; suggest can contain labels without usable data.
      // eslint-disable-next-line no-await-in-loop
      verified = await verifyCatalogSuggestion(item);
      if (verified) break;
    }
    const action = verified ? addCatalogActionFromSuggestion(verified.item, query) : null;
    if (!action) {
      try {
        const { data: external } = await api.post("/chart-agent/ask", {
          question: `Najdi oficiální externí dataset pro chybějící řadu: ${query}`,
          chart_contract: chartContractForResearch,
          analysis_context: "external_dataset_discovery",
          mode: "research_only",
        });
        if (Array.isArray(external?.external_items) && external.external_items.length) {
          return external;
        }
      } catch {
        // The internal catalog result below remains the safe fallback when web research is unavailable.
      }
      return addSeriesFallbackResult(query, compatibleItems, "", fallbackOptions);
    }
    return {
      answer_cz: `Našel jsem a ověřil řadu "${action.title || action.set_id}" a připravil její přidání do grafu.`,
      methodology_cz: "Požadavek jsem vyhodnotil jako úpravu grafu. Řadu jsem vybral z katalogového našeptávače a před přidáním ověřil přes /catalog/preview.",
      chart_actions: [action],
      related_items: compatibleItems,
    };
  }, []);

  const applyRelatedItem = useCallback(
    async (item) => {
      const query = String(item?.title || item?.name || item?.set_id || "").trim();
      const verified = await verifyCatalogSuggestion(item);
      const action = verified ? addCatalogActionFromSuggestion(verified.item, query) : null;
      if (action && typeof onApplyActions === "function") {
        setApplyNotice("");
        setApplyNoticeQuery("");
        const ok = await onApplyActions([action]);
        if (ok === false) {
          setApplyNotice(
            `Řadu „${query}“ se nepodařilo přidat do grafu — pravděpodobně vyžaduje upřesnění (kategorii/dimenzi). Vyberte konkrétní variantu v katalogu.`,
          );
          setApplyNoticeQuery(cleanCatalogQuery(query));
        }
        return;
      }
      // Verify se nepovedlo → řadu nelze přidat přímo. Nebloudit do hledání (končilo na 0
      // výsledcích), ale ukázat hlášku v chatu + nabídnout „Vybrat v katalogu" (čistý dotaz).
      setApplyNotice(
        `Řadu „${query}“ nelze přidat přímo — pravděpodobně vyžaduje upřesnění (kategorii/dimenzi). Vyberte konkrétní variantu v katalogu.`,
      );
      setApplyNoticeQuery(cleanCatalogQuery(query));
    },
    [onApplyActions],
  );

  const ask = useCallback(
    async (nextQuestion) => {
      const q = String(nextQuestion ?? question ?? "").trim();
      if (!q || loading || disabled || (!hasData && !hasConcept && !allowEmptyData)) return;
      setLoading(true);
      const turnId = `turn-${Date.now()}-${turns.length}`;
      setTurns((prev) => [...prev, { id: turnId, question: q, result: null, error: "" }]);
      try {
        let data;
        const effectiveChartContract =
          typeof resolveChartContractForQuestion === "function"
            ? resolveChartContractForQuestion(q, chartContract) || chartContract
            : chartContract;
        const effectiveHasData = Array.isArray(effectiveChartContract?.data) && effectiveChartContract.data.length > 0;
        const effectiveHasPrivateData = chartContractHasPrivateData(effectiveChartContract);
        const intent = await resolveChartIntent({ question: q, chartContract: effectiveChartContract, turns });
        const intentSource = String(intent?.source || "").trim().toLowerCase();
        const useLocalIntentFallback = intentSource !== "llm";
        const analyzeVisibleRelationships = wantsVisibleSeriesRelationship(q, effectiveChartContract);
        const intentName = wantsChartAnnotationEdit(q)
          ? "analyze_chart"
          : useLocalIntentFallback && analyzeVisibleRelationships
          ? "analyze_chart"
          : String(intent?.intent || "").trim();
        const intentQuestion = String(intent?.rewritten_question || q).trim() || q;
        const catalogQuery = String(intent?.catalog_query || "").trim();
        const useLocalAddSeriesFallback =
          intentName !== "add_series" &&
          useLocalIntentFallback &&
          !wantsChartAnnotationEdit(q) &&
          shouldTreatAsAddSeriesFollowup(q, turns);
        if (intentName === "add_series" || useLocalAddSeriesFallback) {
          try {
            const requestedSourceIds = requestedCatalogIdsFromText(q);
            const selectionContext = {
              ...buildAddSeriesSelectionContext(intent, effectiveChartContract),
              sourceIds: requestedSourceIds,
              preferredSourceIds: requestedSourceIds.length
                ? requestedSourceIds
                : catalogIdsFromChartContract(effectiveChartContract, conceptMeta),
              originalQuestion: q,
              // Poradi zalezi: primaryQuery pro deep-search bere prvni polozku odsud.
              // LLM-vycistene catalog_queries/catalog_query (napr. "Alphabet Inc. stock
              // price") musi byt PRED syrovou puvodni otazkou q (napr. "pridej mi do
              // grafu taky graf googlu") - jinak se do vyhledavace posle prikazova veta
              // mista nazvu rady a hledani selze i kdyz LLM spravne poznalo, co chce.
              searchQueries: uniqueNonEmpty([
                ...(Array.isArray(intent?.catalog_queries) ? intent.catalog_queries : []),
                intent?.catalog_query,
                q,
              ]),
            };
            data = await resolveAddSeriesToChartSafe(catalogQuery || q, selectionContext, effectiveChartContract);
          } catch {
            const fallbackQuery = extractAddSeriesQuery(catalogQuery || q) || q;
            data = addSeriesFallbackResult(fallbackQuery, [], "automatické ověření řady selhalo");
          }
        } else if (
          (intentName === "explain_current_series" || (useLocalIntentFallback && wantsConceptExplanation(q))) &&
          hasConcept &&
          !effectiveHasPrivateData
        ) {
          const priorConceptTurn = [...turns].reverse().find((t) => t?.result?.is_concept_explanation);
          data = priorConceptTurn
            ? await resolveConceptFollowup(q, priorConceptTurn.result.answer_cz, turns, effectiveChartContract)
            : await resolveConceptExplanation(q, effectiveChartContract);
        } else if (
          (intentName === "find_related_series" || (useLocalIntentFallback && wantsRelatedSeries(q))) &&
          hasConcept
        ) {
          data = await resolveRelatedSeries(q);
        } else if (effectiveHasData) {
          const history = chartIntentHistory(turns);
          const response = await api.post("/chart-agent/ask", {
            question: intentQuestion,
            chart_contract: effectiveChartContract,
            olap_cube: olapAiContext,
            analysis_context: olapActive && olapAiContext ? "olap_cube" : "chart",
            chart_source_meta: conceptMeta || null,
            mode: "propose_or_execute",
            conversation_history: history,
            conversation_state: chartConversationState(turns),
          });
          data = response.data;
        } else {
          throw new Error("Pro výpočet nad grafem nejsou k dispozici čitelná data.");
        }
        const actions = Array.isArray(data?.chart_actions) ? data.chart_actions : [];
        if (shouldAutoApplyChartActions(actions) && typeof onApplyActions === "function") {
          const applied = await onApplyActions(actions);
          if (applied !== false) {
            setAppliedTurnIds((current) => new Set([...current, turnId]));
            data = resultAfterAutomaticApply(data);
          }
        }
        setTurns((prev) => prev.map((t) => (t.id === turnId ? { ...t, result: data, error: "" } : t)));
        setQuestion("");
      } catch (e) {
        const msg = formatApiErrorFromAxios(e) || "Analýza grafu se nepodařila.";
        setTurns((prev) => prev.map((t) => (t.id === turnId ? { ...t, error: msg } : t)));
      } finally {
        setLoading(false);
      }
    },
    [allowEmptyData, chartContract, conceptMeta, disabled, hasConcept, hasData, loading, olapActive, olapAiContext, onApplyActions, question, resolveAddSeriesToChartSafe, resolveChartContractForQuestion, resolveConceptExplanation, resolveConceptFollowup, resolveRelatedSeries, turns],
  );

  const applyActions = useCallback(
    async (turn) => {
      if (!turn?.result?.chart_actions?.length || typeof onApplyActions !== "function") return;
      setApplyNotice("");
      setApplyNoticeQuery("");
      const ok = await onApplyActions(turn.result.chart_actions || []);
      if (ok === false) {
        const addAct = (turn.result.chart_actions || []).find(
          (a) => a?.type === "add_catalog_series" || a?.type === "open_catalog_search",
        );
        setApplyNotice(
          "Řadu se nepodařilo přidat do grafu — pravděpodobně vyžaduje upřesnění (kategorii/dimenzi). Vyberte konkrétní variantu v katalogu.",
        );
        setApplyNoticeQuery(cleanCatalogQuery(addAct?.name || addAct?.title || addAct?.query || ""));
        return;
      }
      setAppliedTurnIds((prev) => {
        const next = new Set(prev);
        next.add(turn.id);
        return next;
      });
      setTurns((current) => current.map((item) => (
        item.id === turn.id
          ? { ...item, result: resultAfterAutomaticApply(item.result) }
          : item
      )));
    },
    [onApplyActions],
  );

  // Klasické chat okno: zprávy scrollují nahoře, input dole → drž nejnovější zprávu v dohledu.
  const threadRef = useRef(null);
  useEffect(() => {
    const el = threadRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [turns, loading]);

  const rootClass = embedded
    ? fillHeight
      ? "flex h-full min-h-0 flex-col overflow-hidden rounded-xl border border-sky-200 bg-white text-left shadow-sm"
      : "flex max-h-[min(72dvh,760px)] min-h-[220px] flex-col overflow-hidden rounded-xl border border-sky-200 bg-white text-left shadow-sm"
    : "relative z-[1] w-full max-w-lg overflow-hidden rounded-2xl border border-sky-200 bg-white text-left shadow-2xl";
  const bodyClass = embedded
    ? "flex min-h-0 flex-1 flex-col gap-2 overflow-hidden px-3 py-2 text-[12px] text-slate-800"
    : "flex min-h-0 flex-col gap-3 px-4 py-3 text-[13px] text-slate-800";
  const threadMaxClass = embedded ? "min-h-[120px] flex-1 overscroll-contain pb-3" : "max-h-[min(44vh,360px)] pb-2";

  return (
    <div
      role={embedded ? "region" : "dialog"}
      aria-modal={embedded ? undefined : "true"}
      aria-labelledby="chart-analyst-title"
      className={rootClass}
      onClick={(e) => e.stopPropagation()}
      data-testid={embedded ? "chart-analyst-inline-panel" : "chart-analyst-modal-panel"}
    >
      <div className={`${embedded ? "px-3 py-2" : "px-4 py-3"} flex items-start justify-between gap-3 border-b border-sky-100 bg-sky-50`}>
        <div className="min-w-0">
          <div id="chart-analyst-title" className="text-[10px] font-semibold uppercase tracking-wide text-sky-800">
            {heading}
          </div>
          <div className={`${embedded ? "text-[13px]" : "mt-1 text-sm"} font-semibold leading-snug text-slate-900`}>
            {subheading}
          </div>
        </div>
        {headerExtra ? <div className="shrink-0">{headerExtra}</div> : null}
        {onClose ? (
          <button
            type="button"
            onClick={onClose}
            className="shrink-0 rounded-lg p-1.5 text-slate-500 hover:bg-white hover:text-slate-800"
            aria-label="Zavřít"
          >
            <X className="h-4 w-4" />
          </button>
        ) : null}
      </div>

      <div className={bodyClass}>
        {turns.length ? (
          <div ref={threadRef} className={`${threadMaxClass} space-y-2 overflow-y-auto pr-1`}>
            {turns.map((turn) => {
              const result = turn.result;
              const hasActions = Array.isArray(result?.chart_actions) && result.chart_actions.length > 0;
              const applied = appliedTurnIds.has(turn.id) || result?.action_execution === "applied";
              const citations = uniqueResearchCitations(result?.citations);
              return (
                <div key={turn.id} className="space-y-2">
                  <div className="ml-auto max-w-[88%] rounded-xl bg-sky-700 px-3 py-1.5 text-xs font-medium text-white">
                    {turn.question}
                  </div>
                  {turn.error ? (
                    <p className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-rose-800">{turn.error}</p>
                  ) : result ? (
                    <div className="space-y-2 rounded-xl border border-slate-200 bg-white px-3 py-2.5">
                      <ChatMarkdownText text={result.answer_cz} className="break-words" />
                      {result.methodology_cz && !applied ? (
                        <p className="rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-2 text-[11px] text-slate-600">
                          {result.methodology_cz}
                        </p>
                      ) : null}
                      {result.privacy_audit?.contains_private_series ? (
                        <p className="rounded-lg border border-emerald-200 bg-emerald-50 px-2.5 py-2 text-[11px] text-emerald-900">
                          Strict private: nahraná data se používají jen pro lokální výpočet na backendu. Externí AI plánování
                          dostává pouze anonymizovaná metadata bez raw hodnot.
                        </p>
                      ) : null}
                      {!applied && Array.isArray(result.warnings) && result.warnings.length ? (
                        <div className="rounded-lg border border-amber-200 bg-amber-50 px-2.5 py-2 text-[11px] text-amber-950">
                          {result.warnings.map((w, idx) => (
                            <div key={`${w}-${idx}`}>{w}</div>
                          ))}
                        </div>
                      ) : null}
                      {citations.length ? (
                        <details className="rounded-lg border border-sky-100 bg-sky-50/60 px-2.5 py-2">
                          <summary className="cursor-pointer text-[10px] font-semibold uppercase tracking-wide text-sky-900">
                            Zdroje ({citations.length})
                          </summary>
                          <div className="mt-1 space-y-1">
                            {citations.slice(0, 8).map((citation, idx) => (
                              <a
                                key={`${citation?.url || "source"}-${idx}`}
                                href={String(citation?.url || "")}
                                target="_blank"
                                rel="noreferrer noopener"
                                className="block truncate text-[11px] font-medium text-sky-800 underline decoration-sky-300 underline-offset-2 hover:text-sky-950"
                                title={String(citation?.title || citation?.url || "")}
                              >
                                {citation?.title || citation?.publisher || citation?.url}
                              </a>
                            ))}
                          </div>
                        </details>
                      ) : null}
                      {Array.isArray(result.external_items) && result.external_items.length ? (
                        <div className="space-y-1.5">
                          <div className="text-[10px] font-semibold uppercase tracking-wide text-sky-900">Oficiální externí data</div>
                          {result.external_items.slice(0, 6).map((item, idx) => (
                            <a
                              key={`${item?.url || "dataset"}-${idx}`}
                              href={String(item?.url || "")}
                              target="_blank"
                              rel="noreferrer noopener"
                              className="block rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-2 hover:border-sky-300 hover:bg-sky-50"
                            >
                              <div className="text-[12px] font-medium text-slate-900">{item?.title}</div>
                              <div className="mt-0.5 text-[10px] text-slate-600">
                                {item?.publisher}{item?.format ? ` · ${item.format}` : ""}
                              </div>
                              {item?.coverage_cz ? <div className="mt-1 text-[11px] text-slate-700">{item.coverage_cz}</div> : null}
                            </a>
                          ))}
                        </div>
                      ) : null}
                      {Array.isArray(result.related_items) && result.related_items.length ? (
                        <div className="space-y-1.5">
                          {result.related_items.slice(0, 6).map((it, idx) => (
                            <button
                              key={`${it.source || ""}:${it.set_id || ""}:${idx}`}
                              type="button"
                              onClick={() => void applyRelatedItem(it)}
                              className="w-full rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-left transition hover:border-sky-300 hover:bg-sky-50"
                            >
                              <div className="line-clamp-2 text-[12px] font-medium leading-snug text-slate-800">
                                {it.title}
                              </div>
                              <div className="mt-0.5 flex items-center justify-between gap-2">
                                <span className="text-[10px] text-slate-500">
                                  {it.source_label || String(it.source || "").toUpperCase()}
                                </span>
                                <span className="inline-flex items-center gap-1 text-[10px] font-semibold text-sky-800">
                                  <Search className="h-3 w-3" aria-hidden />
                                  Vyhledat / přidat
                                </span>
                              </div>
                            </button>
                          ))}
                        </div>
                      ) : null}
                      {hasActions && applied ? (
                        <div className="rounded-lg border border-emerald-200 bg-emerald-50 px-2.5 py-2 text-[11px] font-semibold text-emerald-900">
                          Provedeno v grafu
                        </div>
                      ) : hasActions ? (
                        <div className="space-y-2">
                          <div className="text-[10px] font-semibold uppercase tracking-wide text-sky-800">
                            Navržené akce v grafu
                          </div>
                          <ul className="space-y-1">
                            {result.chart_actions.slice(0, 5).map((action, idx) => (
                              <li key={actionKey(action, idx)} className="rounded-md bg-slate-50 px-2 py-1 text-[11px] text-slate-700">
                                {actionLabel(action)}
                              </li>
                            ))}
                          </ul>
                          <button
                            type="button"
                            disabled={applied || typeof onApplyActions !== "function"}
                            onClick={() => applyActions(turn)}
                            className="inline-flex h-8 items-center rounded-lg bg-sky-700 px-3 text-xs font-semibold text-white hover:bg-sky-800 disabled:opacity-50"
                          >
                            {applied ? "Aplikováno" : "Použít v grafu"}
                          </button>
                        </div>
                      ) : null}
                    </div>
                  ) : (
                    <div className="flex items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-slate-600">
                      <Loader2 className="h-4 w-4 animate-spin text-sky-700" />
                      Počítám nad aktuálními daty grafu...
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        ) : (
          <p className="flex-1 text-[12px] text-slate-600">
            {emptyText}
          </p>
        )}

        {loading && latestTurn?.result ? (
          <div className="flex items-center gap-2 text-slate-600">
            <Loader2 className="h-4 w-4 animate-spin text-sky-700" />
            Počítám další odpověď...
          </div>
        ) : null}

        {turns.length ? (
          <form
            className="shrink-0 rounded-lg border border-sky-200 bg-sky-50 p-2"
            aria-label="Vyhledat jinou řadu do grafu"
            onSubmit={(e) => {
              e.preventDefault();
              const query = seriesSearchQuery.trim();
              if (!query || loading) return;
              setSeriesSearchQuery("");
              // "Hledat jinou řadu" je součást chatu NAD grafem - dřív při běžném hledání
              // odnavigovala pryč z grafu na plnou katalogovou stránku (přes
              // onFindInCatalogSearch), což rozbíjelo kontext panelu. Musí zůstat v chatu
              // stejně jako pole "Doplňující otázka" dole.
              void ask(query);
            }}
          >
            <label htmlFor="chart-analyst-series-search" className="mb-1 block text-[10px] font-semibold uppercase tracking-wide text-sky-900">
              Hledat jinou řadu
            </label>
            <div className="flex items-center gap-2">
              <div className="relative min-w-0 flex-1">
                <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-400" aria-hidden />
                <input
                  id="chart-analyst-series-search"
                  value={seriesSearchQuery}
                  onChange={(e) => setSeriesSearchQuery(e.target.value)}
                  placeholder="Název ukazatele, země nebo zdroj..."
                  className="h-8 w-full rounded-lg border border-sky-200 bg-white pl-8 pr-3 text-[12px] outline-none focus:border-sky-500"
                  disabled={loading}
                />
              </div>
              <button
                type="submit"
                disabled={loading || !seriesSearchQuery.trim()}
                className="inline-flex h-8 shrink-0 items-center gap-1.5 rounded-lg bg-sky-700 px-3 text-xs font-semibold text-white hover:bg-sky-800 disabled:opacity-50"
              >
                {loading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Search className="h-3.5 w-3.5" aria-hidden />}
                Hledat
              </button>
            </div>
          </form>
        ) : null}

        <div className="flex shrink-0 flex-wrap gap-1.5">
          {(suggestions || SUGGESTIONS).map((s) => (
            <button
              key={s}
              type="button"
              disabled={loading}
              onClick={() => void ask(s)}
              className="shrink-0 rounded-full border border-sky-200 bg-white px-2 py-0.5 text-[10px] font-medium text-sky-800 hover:bg-sky-50 disabled:opacity-50"
            >
              {s}
            </button>
          ))}
        </div>

        {applyNotice ? (
          <div className="shrink-0 space-y-1.5 rounded-lg border border-amber-200 bg-amber-50 px-2.5 py-2 text-[11px] text-amber-900">
            <div>{applyNotice}</div>
            {applyNoticeQuery && typeof onFindInCatalogSearch === "function" ? (
              <button
                type="button"
                onClick={() => {
                  openCatalogSearch(applyNoticeQuery);
                  setApplyNotice("");
                  setApplyNoticeQuery("");
                }}
                className="inline-flex items-center gap-1 rounded-md border border-amber-300 bg-white px-2 py-0.5 text-[11px] font-semibold text-amber-900 hover:bg-amber-100"
              >
                <Search className="h-3 w-3" aria-hidden />
                Vybrat v katalogu
              </button>
            ) : null}
          </div>
        ) : null}
        <form
          className="flex shrink-0 items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            void ask();
          }}
        >
          <input
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder={turns.length ? "Doplňující otázka..." : "Např. co daná řada říká?"}
            className={`${embedded ? "h-8 text-[13px]" : "h-9 text-sm"} min-w-0 flex-1 rounded-lg border border-sky-200 px-3 outline-none focus:border-sky-500`}
            disabled={loading}
          />
          <VoiceInputButton
            value={question}
            onChange={setQuestion}
            disabled={loading}
            className={embedded ? "h-8 w-8" : "h-9 w-9"}
          />
          <button
            type="submit"
            disabled={loading || !question.trim()}
            className={`${embedded ? "h-8" : "h-9"} inline-flex items-center gap-1.5 rounded-lg bg-sky-700 px-3 text-xs font-semibold text-white hover:bg-sky-800 disabled:opacity-50`}
          >
            {loading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
            Zeptat
          </button>
        </form>
      </div>
    </div>
  );
}

export default function ChartAnalystTrigger({
  chartContract,
  olapPackage = null,
  olapActive = false,
  conceptMeta = null,
  disabled = false,
  onApplyActions,
  onFindInCatalogSearch,
  onOpenInline,
}) {
  const [open, setOpen] = useState(false);
  const hasData = Array.isArray(chartContract?.data) && chartContract.data.length > 0;
  const hasConcept = Boolean(conceptMeta && typeof conceptMeta === "object");

  const handleOpen = useCallback(() => {
    if (typeof onOpenInline === "function") {
      onOpenInline();
      return;
    }
    setOpen(true);
  }, [onOpenInline]);

  return (
    <>
      <button
        type="button"
        disabled={disabled || (!hasData && !hasConcept)}
        onClick={handleOpen}
        className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-violet-200 bg-violet-50 text-violet-800 shadow-sm hover:bg-violet-100 disabled:opacity-50"
        title="AI nad grafem: vysvětlení řady, související řady a výpočty"
        aria-label="AI nad grafem"
        data-export-ignore="true"
        data-testid="chart-analyst-trigger"
      >
        <Sparkles className="h-3.5 w-3.5" aria-hidden />
      </button>

      {open && typeof document !== "undefined"
        ? createPortal(
            <div className="fixed inset-0 z-[270] flex items-center justify-center p-4">
              <button
                type="button"
                className="absolute inset-0 bg-slate-950/40 backdrop-blur-[2px]"
                aria-label="Zavřít"
                onClick={() => setOpen(false)}
              />
              <ChartAnalystPanel
                chartContract={chartContract}
                olapPackage={olapPackage}
                olapActive={olapActive}
                conceptMeta={conceptMeta}
                disabled={disabled}
                onApplyActions={onApplyActions}
                onFindInCatalogSearch={onFindInCatalogSearch}
                onClose={() => setOpen(false)}
              />
            </div>,
            document.body,
          )
        : null}
    </>
  );
}
