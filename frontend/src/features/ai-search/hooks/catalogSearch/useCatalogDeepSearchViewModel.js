import { useMemo } from "react";
import { API_FAILURE_CORS_OR_NETWORK } from "@/lib/api";
import { extractDeepSearchPipelineDiagnostics } from "@/lib/catalogDeepSearchClient";
import {
  deepAiDatabaseLabelCz,
  normalizedDeepVerifiedPossible,
  sortDeepCandidatesByFinalRank,
  deepCandidateHasForeignCsuGeoBlock,
  deepCandidateSourceId,
  deepCandidateResultKey,
  deepStatusHasVisibleCandidates,
  deepCandidateForDisplay,
  deepAiHasIndexProblemStatusRaw,
  deepCandidateHasUsablePreview,
} from "@/lib/catalogGlobalSearchHelpers";
import { normalizeCatalogSourceId } from "@/lib/catalogDefinitions";

const DEEP_SEARCH_NON_CATALOG_STATUS_SOURCES = new Set([
  "openai_plan",
  "ai_data_resolver",
  "fred_remote",
  "internal_lane",
  "ecb_fast",
]);

function normalizeQueryVariantForUi(value) {
  if (value && typeof value === "object") {
    const text = String(value.text || value.term || value.label || "").trim();
    if (!text) return null;
    const weight = Number(value.weight);
    return {
      text,
      role: String(value.role || "variant").trim(),
      weight: Number.isFinite(weight) ? weight : null,
    };
  }
  const text = String(value || "").trim();
  return text ? { text, role: "variant", weight: null } : null;
}

export function useCatalogDeepSearchViewModel({
  deepData,
  deepSearchExcludedCatalogIds,
  deepStreamAwaitingFinal,
  deepLaneResults,
  useAiAssistant,
  deepLoading,
  deepConversation,
  deepSourceStatuses,
  selectedExternalCatalogs,
  deepError,
}) {
  const deepSearchExcludedKey = useMemo(
    () => [...deepSearchExcludedCatalogIds].sort().join("|"),
    [deepSearchExcludedCatalogIds],
  );
  const deepSearchExcludedSet = useMemo(
    () => new Set(deepSearchExcludedKey ? deepSearchExcludedKey.split("|") : []),
    [deepSearchExcludedKey],
  );

  const { verified: deepVerifiedList, possible: deepPossibleList, lowRelevance: deepLowRelevanceList } = useMemo(
    () => {
      const normalized = normalizedDeepVerifiedPossible(deepData);
      const rows = [...normalized.verified, ...normalized.possible].map(deepCandidateForDisplay);
      const mainRows = rows.filter((row) => !deepCandidateHasForeignCsuGeoBlock(row));
      const lowRelevance = sortDeepCandidatesByFinalRank(rows.filter(deepCandidateHasForeignCsuGeoBlock));
      const excluded = deepSearchExcludedSet;
      const filterBySource = (list) =>
        excluded.size > 0
          ? list.filter((row) => !excluded.has(deepCandidateSourceId(row)))
          : list;
      return {
        verified: filterBySource(
          sortDeepCandidatesByFinalRank(mainRows.filter((row) => String(row?.status || "").toLowerCase() === "verified")),
        ),
        possible: filterBySource(
          sortDeepCandidatesByFinalRank(mainRows.filter((row) => String(row?.status || "").toLowerCase() !== "verified")),
        ),
        lowRelevance: filterBySource(lowRelevance),
      };
    },
    [deepData, deepSearchExcludedSet],
  );
  const topDeepRecommendations = useMemo(
    () => {
      if (deepData?.status === "no_valid_result") return [];
      return sortDeepCandidatesByFinalRank(
        [...deepVerifiedList, ...deepPossibleList].filter(deepCandidateHasUsablePreview),
      ).slice(0, 8);
    },
    [deepData?.status, deepVerifiedList, deepPossibleList],
  );
  const topDeepRecommendationKeys = useMemo(
    () => new Set(topDeepRecommendations.map(deepCandidateResultKey)),
    [topDeepRecommendations],
  );
  const deepDiscardedCandidatesList = useMemo(
    () => {
      const rows = Array.isArray(deepData?.discarded_candidates) ? deepData.discarded_candidates : [];
      const excluded = deepSearchExcludedSet;
      const visibleKeys = new Set(
        [...deepVerifiedList, ...deepPossibleList, ...deepLowRelevanceList].map(deepCandidateResultKey),
      );
      const out = rows
        .map(deepCandidateForDisplay)
        .filter((row) => row && typeof row === "object")
        .filter((row) => !visibleKeys.has(deepCandidateResultKey(row)))
        .filter((row) => (excluded.size > 0 ? !excluded.has(deepCandidateSourceId(row)) : true));
      return sortDeepCandidatesByFinalRank(out).slice(0, 40);
    },
    [deepData, deepVerifiedList, deepPossibleList, deepLowRelevanceList, deepSearchExcludedSet],
  );
  const deepVerifiedListBelowTop = useMemo(
    () => deepVerifiedList.filter((c) => !topDeepRecommendationKeys.has(deepCandidateResultKey(c))),
    [deepVerifiedList, topDeepRecommendationKeys],
  );
  const deepPossibleListBelowTop = useMemo(
    () => deepPossibleList.filter((c) => !topDeepRecommendationKeys.has(deepCandidateResultKey(c))),
    [deepPossibleList, topDeepRecommendationKeys],
  );
  const deepLanePreviewEntries = useMemo(() => {
    if (!deepStreamAwaitingFinal || !deepLaneResults || typeof deepLaneResults !== "object") return [];
    return Object.entries(deepLaneResults)
      .map(([source, rows]) => {
        const list = Array.isArray(rows) ? rows.filter((r) => r && typeof r === "object") : [];
        if (!list.length) return null;
        return {
          source: String(source || "").toLowerCase(),
          count: list.length,
          rows: list.slice(0, 5),
          titles: list
            .slice(0, 3)
            .map((r) => String(r.name || r.title || r.set_id || r.series_id || "").trim())
            .filter(Boolean),
        };
      })
      .filter(Boolean);
  }, [deepLaneResults, deepStreamAwaitingFinal]);
  const showDeepLaneProvisional = deepStreamAwaitingFinal && deepLanePreviewEntries.length > 0;
  const deepWarningsForUi = useMemo(() => {
    const out = [];
    const seen = new Set();
    const add = (arr) => {
      if (!Array.isArray(arr)) return;
      for (const w of arr) {
        const s = typeof w === "string" ? w.trim() : String(w ?? "").trim();
        if (!s || seen.has(s)) continue;
        seen.add(s);
        out.push(s);
      }
    };
    add(deepData?.catalog_index_warnings);
    add(deepData?.warnings);
    return out;
  }, [deepData]);
  const deepSuggestedQueriesList = useMemo(
    () => (Array.isArray(deepData?.suggested_queries) ? deepData.suggested_queries : []),
    [deepData],
  );
  const DEEP_DISPLAY_MAX_PER_SOURCE = 8;
  const groupedDeepVerified = useMemo(() => {
    const m = new Map();
    for (const c of deepVerifiedListBelowTop) {
      const k =
        String(c.catalog_id ?? "ostatní")
          .trim()
          .toLowerCase() || "ostatní";
      const arr = m.get(k);
      if (arr) arr.push(c);
      else m.set(k, [c]);
    }
    return [...m.entries()]
      .map(([k, rows]) => [k, sortDeepCandidatesByFinalRank(rows).slice(0, DEEP_DISPLAY_MAX_PER_SOURCE)])
      .sort((a, b) => {
        const ar = Number(a[1]?.[0]?.final_rank);
        const br = Number(b[1]?.[0]?.final_rank);
        if (Number.isFinite(ar) && Number.isFinite(br) && ar !== br) return ar - br;
        return String(a[0]).localeCompare(String(b[0]), "cs");
      });
  }, [deepVerifiedListBelowTop]);
  const groupedDeepPossible = useMemo(() => {
    const m = new Map();
    for (const c of deepPossibleListBelowTop) {
      const k =
        String(c.catalog_id ?? "ostatní")
          .trim()
          .toLowerCase() || "ostatní";
      const arr = m.get(k);
      if (arr) arr.push(c);
      else m.set(k, [c]);
    }
    return [...m.entries()]
      .map(([k, rows]) => [k, sortDeepCandidatesByFinalRank(rows).slice(0, DEEP_DISPLAY_MAX_PER_SOURCE)])
      .sort((a, b) => {
        const ar = Number(a[1]?.[0]?.final_rank);
        const br = Number(b[1]?.[0]?.final_rank);
        if (Number.isFinite(ar) && Number.isFinite(br) && ar !== br) return ar - br;
        return String(a[0]).localeCompare(String(b[0]), "cs");
      });
  }, [deepPossibleListBelowTop]);
  const followupSeriesOptions = useMemo(() => {
    const out = [];
    const seen = new Set();
    const addRows = (rows) => {
      for (const r of rows || []) {
        const setId = String(r?.set_id || r?.series_id || "").trim();
        const src = String(r?.source_type || r?.catalog_id || "").trim().toLowerCase();
        if (!setId || !src) continue;
        const territory = String(r?.territory || r?.country_or_region || "").trim().toUpperCase();
        const key = territory ? `${src}:${setId}:${territory}` : `${src}:${setId}`;
        if (seen.has(key)) continue;
        seen.add(key);
        out.push({
          key,
          source_type: src,
          catalog_id: String(r?.catalog_id || src).trim().toLowerCase(),
          set_id: setId,
          title: String(r?.title || r?.name || setId).trim(),
          territory,
          query_params: r?.query_params && typeof r.query_params === "object" ? { ...r.query_params } : {},
        });
        if (out.length >= 10) return;
      }
    };
    // Použít surová data bez filtru zdrojů — follow-up nesmí zmizet jen proto, že uživatel skryl zdroj.
    const normalized = normalizedDeepVerifiedPossible(deepData);
    addRows(normalized.verified.map(deepCandidateForDisplay).filter(deepCandidateHasUsablePreview));
    addRows(normalized.possible.map(deepCandidateForDisplay).filter(deepCandidateHasUsablePreview));
    return out;
  }, [deepData]);
  const followupSuggestedActions = useMemo(() => {
    const raw = deepData?.ai_result_layer?.follow_up_suggestions;
    if (!Array.isArray(raw)) return [];
    return raw
      .filter((x) => x && typeof x === "object")
      .map((x) => ({
        id: String(x.id || "").trim() || String(x.kind || "").trim(),
        kind: String(x.kind || "").trim().toLowerCase(),
        label_cz: String(x.label_cz || "").trim(),
        prompt_cz: String(x.prompt_cz || "").trim(),
      }))
      .filter((x) => x.id && x.kind && x.label_cz && x.prompt_cz)
      .slice(0, 8);
  }, [deepData]);
  /** Jeden panel místo duplicitního „AI follow-up“ + „needs clarification“. */
  const deepClarificationPayload = useMemo(() => {
    if (deepData?.followup_computation_result || deepData?.followup_chart_payload) return null;
    const sn = deepData?.structured_no_answer;
    if (!sn || typeof sn !== "object") return null;
    if (String(sn.code || "").trim() !== "needs_clarification") return null;
    const message = String(sn.message || sn.message_cs || "").trim();
    if (!message) return null;
    const options = Array.isArray(sn.options_cz)
      ? sn.options_cz.map((x) => String(x || "").trim()).filter((x) => x.length >= 2)
      : [];
    return { message, options };
  }, [deepData?.structured_no_answer, deepData?.followup_computation_result, deepData?.followup_chart_payload]);
  const showDeepFollowupPanel = Boolean(
    useAiAssistant &&
      !deepLoading &&
      deepData?.ok !== false &&
      (deepClarificationPayload || deepConversation?.id || deepData?.conversation?.id || deepVerifiedList.length > 0 || deepPossibleList.length > 0),
  );
  const deepFollowupClarificationMode = Boolean(deepClarificationPayload);
  // Pokud payload má multi_series=true, je připravený přímo pro AradView.
  // Jinak (starý fallback formát) pivot na {rows, keys} pro základní LineChart.
  const followupChartPayload = useMemo(() => {
    const explicit = deepData?.followup_chart_payload;
    if (explicit && typeof explicit === "object") return explicit;
    const computation = deepData?.followup_computation_result;
    const nested = computation?.chart_payload;
    if (nested && typeof nested === "object") return nested;
    if (
      String(computation?.operation || "") === "multi" &&
      Array.isArray(computation?.rows) &&
      Array.isArray(computation?.diagnostics?.series)
    ) {
      return {
        title: "Složený graf",
        multi_series: true,
        chart_data_mode: "series",
        chart_type: "line",
        group_field: "series",
        rows: computation.rows,
        series: computation.diagnostics.series,
      };
    }
    return null;
  }, [deepData]);
  const followupComposedChartData = useMemo(() => {
    const payload = followupChartPayload;
    if (!payload || typeof payload !== "object") return { rows: [], keys: [], isMultiSeries: false };
    // AradView formát — vrátíme as-is
    if (payload.multi_series === true && Array.isArray(payload.rows) && Array.isArray(payload.series)) {
      return { rows: payload.rows, keys: payload.series.map((s) => s?.key).filter(Boolean), isMultiSeries: true, aradData: payload };
    }
    // Legacy formát {series: [{name, data}]}
    if (!Array.isArray(payload.series)) return { rows: [], keys: [], isMultiSeries: false };
    const map = new Map();
    const keys = [];
    for (const s of payload.series.slice(0, 4)) {
      if (!s || typeof s !== "object" || !Array.isArray(s.data)) continue;
      const key = String(s.name || "").trim() || `series_${keys.length + 1}`;
      keys.push(key);
      for (const p of s.data) {
        if (!p || typeof p !== "object") continue;
        const x = String(p.x ?? "").trim();
        if (!x) continue;
        const ex = map.get(x) || { x };
        const y = Number(p.y);
        if (!Number.isNaN(y)) ex[key] = y;
        map.set(x, ex);
      }
    }
    return { rows: [...map.values()], keys, isMultiSeries: false };
  }, [followupChartPayload]);
  const deepEcbCandidatesList = useMemo(
    () =>
      Array.isArray(deepData?.ecb_series_build_candidates) ? deepData.ecb_series_build_candidates : [],
    [deepData],
  );
  const deepAiAnalysisPayload = useMemo(() => {
    if (!deepData || typeof deepData !== "object") return null;
    if (deepData.ai_analysis_payload && typeof deepData.ai_analysis_payload === "object") {
      return deepData.ai_analysis_payload;
    }
    const cp = deepData.chart_payload;
    if (cp && typeof cp === "object" && cp.ai_analysis_payload && typeof cp.ai_analysis_payload === "object") {
      return cp.ai_analysis_payload;
    }
    return null;
  }, [deepData]);
  const deepAiAnalysisDatasets = useMemo(() => {
    const ds = deepAiAnalysisPayload?.datasets;
    return Array.isArray(ds) ? ds.filter((x) => x && typeof x === "object") : [];
  }, [deepAiAnalysisPayload]);
  const deepPrimaryChart = useMemo(() => {
    const cp = deepData?.chart_payload;
    if (!cp || typeof cp !== "object") return { rows: [], title: "" };
    const series = Array.isArray(cp.series) ? cp.series : [];
    const firstSeries = series.find((s) => s && typeof s === "object" && Array.isArray(s.data) && s.data.length > 0);
    if (!firstSeries) return { rows: [], title: String(cp.title || "") };
    const points = Array.isArray(firstSeries.data) ? firstSeries.data : [];
    const norm = [];
    for (const p of points) {
      if (!p || typeof p !== "object") continue;
      const rawX = p.x ?? p.date ?? p.period ?? p.time ?? p.label;
      const rawY = p.y ?? p.value;
      if (rawX == null || rawX === "" || rawY == null) continue;
      const y = Number(rawY);
      if (!Number.isFinite(y)) continue;
      norm.push({ x: String(rawX), y });
    }
    if (!norm.length) return { rows: [], title: String(cp.title || "") };
    // Large ARAD series can have thousands of points; keep chart responsive.
    const maxPoints = 1600;
    if (norm.length > maxPoints) {
      const step = Math.ceil(norm.length / maxPoints);
      return {
        rows: norm.filter((_, i) => i % step === 0 || i === norm.length - 1),
        title: String(cp.title || firstSeries.name || ""),
      };
    }
    return { rows: norm, title: String(cp.title || firstSeries.name || "") };
  }, [deepData]);
  const deepStatusesForUi = useMemo(() => {
    const fromApiRaw = deepData?.source_statuses;
    let apiArr = Array.isArray(fromApiRaw) ? fromApiRaw : [];
    if (
      apiArr.length === 0 &&
      deepData &&
      typeof deepData === "object" &&
      deepData.sources &&
      typeof deepData.sources === "object" &&
      !Array.isArray(deepData.sources)
    ) {
      try {
        apiArr = Object.entries(deepData.sources).map(([src, info]) => {
          const inf = info && typeof info === "object" ? info : {};
          const rc = inf.count;
          const dm = inf.duration_ms;
          return {
            source: String(src || "").toLowerCase(),
            label: deepAiDatabaseLabelCz(src),
            status: String(inf.status ?? "—"),
            row_count: typeof rc === "number" ? rc : Number(rc) || 0,
            duration_ms: typeof dm === "number" ? dm : Number(dm) || 0,
            message_cs: inf.error != null ? String(inf.error) : "",
          };
        });
      } catch  {
        apiArr = [];
      }
    }
    const localArr = Array.isArray(deepSourceStatuses) ? deepSourceStatuses : [];
    return deepLoading ? localArr : apiArr.length > 0 ? apiArr : localArr;
  }, [deepLoading, deepData, deepSourceStatuses]);

  const deepPipelineDiagnostics = useMemo(
    () => extractDeepSearchPipelineDiagnostics(deepData),
    [deepData],
  );

  const deepAiSearchPlanForUi = useMemo(() => {
    const plan = deepData?.search_plan || deepData?.plan;
    if (!plan || typeof plan !== "object") return null;
    const profile = plan.semantic_profile && typeof plan.semantic_profile === "object" ? plan.semantic_profile : {};
    const normalizedCz = String(plan.normalized_query_cz || profile.normalized_query_cz || "").trim();
    const englishQuery = String(plan.english_query || profile.english_query || "").trim();
    const topic = String(plan.topic || profile.topic || "").trim();
    const queryShape = String(plan.query_shape || profile.query_shape || "").trim();
    const confidenceRaw = Number(profile.confidence ?? plan.confidence);
    const confidence = Number.isFinite(confidenceRaw) ? confidenceRaw : null;
    const indicators = Array.isArray(plan.indicators || profile.indicators)
      ? (plan.indicators || profile.indicators).map((x) => String(x || "").trim()).filter(Boolean).slice(0, 8)
      : [];
    const queryVariants = Array.isArray(plan.query_variants || profile.query_variants)
      ? (plan.query_variants || profile.query_variants).map(normalizeQueryVariantForUi).filter(Boolean).slice(0, 12)
      : [];
    const termLabels = (rows) =>
      Array.isArray(rows)
        ? rows.map((row) => String(row?.label || row || "").trim()).filter(Boolean).slice(0, 8)
        : [];
    const metricTerms = termLabels(plan.metric_terms || profile.metric_terms);
    const domainTerms = termLabels(plan.domain_terms || profile.domain_terms);
    const openaiStatus = (deepStatusesForUi || []).find(
      (st) => String(st?.source || "").toLowerCase() === "openai_plan",
    );
    if (
      !normalizedCz &&
      !englishQuery &&
      !topic &&
      !queryShape &&
      !queryVariants.length &&
      !metricTerms.length &&
      !domainTerms.length &&
      !openaiStatus
    ) {
      return null;
    }
    return {
      normalizedCz,
      englishQuery,
      topic,
      queryShape,
      confidence,
      indicators,
      queryVariants,
      metricTerms,
      domainTerms,
      openaiStatus,
    };
  }, [deepData, deepStatusesForUi]);

  const inferredDeepSearchSources = useMemo(() => {
    const raw = deepData?.deep_search_request_echo?.sources;
    if (Array.isArray(raw) && raw.length > 0) {
      return raw.map(normalizeCatalogSourceId).filter(Boolean);
    }
    return [...selectedExternalCatalogs].map(normalizeCatalogSourceId).filter(Boolean).sort();
  }, [deepData, selectedExternalCatalogs]);

  const deepSourceStatusLookup = useMemo(() => {
    const m = {};
    for (const s of deepStatusesForUi) {
      const rawKey = String(s.source || "").trim().toLowerCase();
      if (DEEP_SEARCH_NON_CATALOG_STATUS_SOURCES.has(rawKey)) continue;
      const k = normalizeCatalogSourceId(rawKey);
      if (k) m[k] = s;
    }
    return m;
  }, [deepStatusesForUi]);

  const deepSourceCandidateDiagnostics = useMemo(() => {
    if (!deepData || deepLoading) return [];
    const nonCatalogStatusSources = new Set([
      "openai_plan",
      "ai_data_resolver",
      "fred_remote",
      "internal_lane",
      "ecb_fast",
    ]);
    const visibleRows = [...deepVerifiedList, ...deepPossibleList, ...deepLowRelevanceList];
    const visibleSources = new Set(visibleRows.map(deepCandidateSourceId).filter(Boolean));
    const out = [];
    for (const st of deepStatusesForUi) {
      const rawSid = String(st?.source || "").trim().toLowerCase();
      const sid = normalizeCatalogSourceId(rawSid);
      const count = Number(st?.candidate_count ?? st?.row_count ?? st?.count ?? 0) || 0;
      const stNorm = String(st?.status || "").trim().toLowerCase();
      if (!sid || nonCatalogStatusSources.has(rawSid) || nonCatalogStatusSources.has(sid)) continue;
      if (stNorm === "not_indexed" && count <= 0) {
        if (sid === "imf") {
          out.push("IMF zatím nemá napojený globální index pro AI hledání — použijte katalog IMF (beta) nebo vyberte jiný zdroj.");
        } else if (sid === "oecd" || sid === "bis") {
          out.push(`${deepAiDatabaseLabelCz(sid)} zatím nemá napojený globální index pro AI hledání — otevřete dedikovaný katalog.`);
        }
        continue;
      }
      if (count <= 0 || deepStatusHasVisibleCandidates(sid, visibleSources)) continue;
      if (sid === "csu" && deepLowRelevanceList.some((row) => deepCandidateSourceId(row) === "csu")) {
        out.push("ČSÚ kandidáti jsou skryti z hlavního seznamu, protože neodpovídají požadované zahraniční zemi.");
      } else {
        out.push(
          `${deepAiDatabaseLabelCz(sid)} našel v indexu ${count} shod, ale do zobrazitelného seznamu se nedostaly (filtrování, AI ověření nebo náhled).`,
        );
      }
    }
    return [...new Set(out)];
  }, [deepData, deepLoading, deepStatusesForUi, deepVerifiedList, deepPossibleList, deepLowRelevanceList]);

  const deepClarificationSuppressMessages = useMemo(() => {
    if (!deepFollowupClarificationMode) return new Set();
    const out = new Set();
    const add = (text) => {
      const s = String(text || "").trim().toLowerCase();
      if (s) out.add(s);
    };
    add(deepClarificationPayload?.message);
    const sn = deepData?.structured_no_answer;
    if (sn && typeof sn === "object") {
      add(sn.message);
      add(sn.message_cs);
    }
    add(deepData?.message);
    add(deepData?.message_cs);
    const resolver = deepData?.ai_data_resolver;
    if (resolver && typeof resolver === "object") {
      add(resolver.clarification_question_cz);
    }
    add("Dotaz je potřeba upřesnit, aby bylo možné vybrat správnou datovou řadu.");
    return out;
  }, [deepFollowupClarificationMode, deepClarificationPayload, deepData]);

  const deepCombinedWarnings = useMemo(() => {
    const seen = new Set();
    const out = [];
    for (const w of [...deepWarningsForUi, ...deepSourceCandidateDiagnostics]) {
      const s = String(w || "").trim();
      if (!s || seen.has(s)) continue;
      if (deepClarificationSuppressMessages.has(s.toLowerCase())) continue;
      seen.add(s);
      out.push(s);
    }
    return out;
  }, [deepWarningsForUi, deepSourceCandidateDiagnostics, deepClarificationSuppressMessages]);

  const deepStatusesForDisplay = useMemo(() => {
    const visibleStatuses = ["timeout", "error", "not_ready", "warming", "running"];
    return deepStatusesForUi.filter((st) => {
      if (DEEP_SEARCH_NON_CATALOG_STATUS_SOURCES.has(String(st?.source || "").toLowerCase())) return false;
      const rawStatus = String(st?.status || "").trim().toLowerCase();
      if (!deepLoading) return visibleStatuses.includes(rawStatus);
      return rawStatus !== "skipped";
    });
  }, [deepLoading, deepStatusesForUi]);

  const deepSearchSourceSummaries = useMemo(() => {
    if (!deepData || deepLoading) return [];
    const normalized = normalizedDeepVerifiedPossible(deepData);
    const allRows = [...normalized.verified, ...normalized.possible].map(deepCandidateForDisplay);
    const hitCounts = new Map();
    for (const row of allRows) {
      const id = deepCandidateSourceId(row);
      if (!id) continue;
      hitCounts.set(id, (hitCounts.get(id) || 0) + 1);
    }
    const sourceIds = new Set([...inferredDeepSearchSources, ...hitCounts.keys()]);
    for (const st of deepStatusesForUi) {
      const rawSid = String(st?.source || "").trim().toLowerCase();
      const sid = normalizeCatalogSourceId(rawSid);
      if (sid && !DEEP_SEARCH_NON_CATALOG_STATUS_SOURCES.has(rawSid) && !DEEP_SEARCH_NON_CATALOG_STATUS_SOURCES.has(sid)) {
        sourceIds.add(sid);
      }
    }
    return [...sourceIds]
      .filter(Boolean)
      .sort((a, b) => a.localeCompare(b, "cs"))
      .map((id) => {
        const st = deepSourceStatusLookup[id];
        const rawStatus = String(st?.status || "").trim().toLowerCase();
        return {
          id,
          label: deepAiDatabaseLabelCz(id),
          hits: hitCounts.get(id) || 0,
          status: rawStatus,
          upstream_unavailable: rawStatus === "error" || rawStatus === "timeout",
          local_index_missing: rawStatus === "not_ready" || rawStatus === "warming",
          message_cs: String(st?.message_cs || st?.message || ""),
        };
      });
  }, [deepData, deepLoading, inferredDeepSearchSources, deepStatusesForUi, deepSourceStatusLookup]);

  const deepSearchAllSourcesHidden = useMemo(() => {
    if (!deepData || deepLoading || deepSearchExcludedSet.size === 0) return false;
    const normalized = normalizedDeepVerifiedPossible(deepData);
    const allRows = [...normalized.verified, ...normalized.possible].map(deepCandidateForDisplay);
    const visible = allRows.filter((row) => !deepSearchExcludedSet.has(deepCandidateSourceId(row)));
    return allRows.length > 0 && visible.length === 0;
  }, [deepData, deepLoading, deepSearchExcludedSet]);

  const showPartialNoValidCandidates = Boolean(
    deepData &&
      deepData.ok !== false &&
      (deepData.status === "partial_no_valid_candidates" ||
        (deepData.partial && deepData.progressive && deepVerifiedList.length === 0 && deepPossibleList.length === 0 && deepLowRelevanceList.length > 0)),
  );

  const anySelectedAiIndexProblem = useMemo(() => {
    if (!deepData || deepLoading) return false;
    for (const sid of inferredDeepSearchSources) {
      const meta = deepSourceStatusLookup[sid];
      if (meta && deepAiHasIndexProblemStatusRaw(meta.status)) return true;
    }
    return false;
  }, [deepData, deepLoading, inferredDeepSearchSources, deepSourceStatusLookup]);

  const eurostatRetryableIndexStatus = useMemo(() => {
    if (!deepData || deepLoading) return false;
    const meta = deepSourceStatusLookup.eurostat;
    const st = String(meta?.status || "").trim().toLowerCase();
    return ["not_ready", "warming", "timeout"].includes(st);
  }, [deepData, deepLoading, deepSourceStatusLookup]);

  /** Jediný vybraný zdroj v problému → prominentní banner místo zavádějícího „nic v indexu“. */
  const singleAiSourceProblemBanner = useMemo(() => {
    if (!deepData || deepLoading || deepData.ok === false) return null;
    if (inferredDeepSearchSources.length !== 1) return null;
    const sid = inferredDeepSearchSources[0];
    const meta = deepSourceStatusLookup[sid];
    const st = String(meta?.status || "").trim().toLowerCase();
    if (!meta || !deepAiHasIndexProblemStatusRaw(st)) return null;
    return { sid, status: st };
  }, [deepData, deepLoading, inferredDeepSearchSources, deepSourceStatusLookup]);

  const showMultiAiIndexProblemNoHitsNote = Boolean(
    !deepLoading &&
      deepData &&
      deepData.ok !== false &&
      inferredDeepSearchSources.length > 1 &&
      deepVerifiedList.length === 0 &&
      deepPossibleList.length === 0 &&
      anySelectedAiIndexProblem,
  );

  const showGenericAiIndexNoHitsMessage = Boolean(
    !deepLoading &&
      deepData &&
      deepData.ok !== false &&
      typeof deepData.no_verified_row_message === "string" &&
      deepData.no_verified_row_message.trim().length > 0 &&
      !anySelectedAiIndexProblem &&
      deepVerifiedList.length === 0 &&
      deepPossibleList.length === 0,
  );

  /** Jedna uživatelsky čitelná věta — neslibuj hlavně CORS, když jde často o timeout/proxy. */
  const deepErrorFriendly = useMemo(() => {
    const d = String(deepError || "").trim();
    if (!d) return "";
    if (
      d === API_FAILURE_CORS_OR_NETWORK ||
      d.includes("CORS_ORIGINS") ||
      /Nelze se spojit|Access-Control-Allow-Origin|ERR_NETWORK|blocked by CORS/i.test(d)
    ) {
      return "AI vyhledávání se nepodařilo dokončit. Zkuste vybrat méně databází nebo kratší dotaz.";
    }
    return d;
  }, [deepError]);

  return {
    deepVerifiedList,
    deepPossibleList,
    deepLowRelevanceList,
    deepDiscardedCandidatesList,
    topDeepRecommendations,
    deepVerifiedListBelowTop,
    deepPossibleListBelowTop,
    deepLanePreviewEntries,
    showDeepLaneProvisional,
    deepWarningsForUi,
    deepSuggestedQueriesList,
    groupedDeepVerified,
    groupedDeepPossible,
    followupSeriesOptions,
    followupSuggestedActions,
    deepClarificationPayload,
    showDeepFollowupPanel,
    deepFollowupClarificationMode,
    followupChartPayload,
    followupComposedChartData,
    deepEcbCandidatesList,
    deepAiAnalysisPayload,
    deepAiAnalysisDatasets,
    deepPrimaryChart,
    deepStatusesForUi,
    deepPipelineDiagnostics,
    deepAiSearchPlanForUi,
    inferredDeepSearchSources,
    deepSourceStatusLookup,
    deepSourceCandidateDiagnostics,
    deepClarificationSuppressMessages,
    deepCombinedWarnings,
    deepStatusesForDisplay,
    deepSearchSourceSummaries,
    deepSearchAllSourcesHidden,
    showPartialNoValidCandidates,
    anySelectedAiIndexProblem,
    eurostatRetryableIndexStatus,
    singleAiSourceProblemBanner,
    showMultiAiIndexProblemNoHitsNote,
    showGenericAiIndexNoHitsMessage,
    deepErrorFriendly,
  };
}
