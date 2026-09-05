import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import api, { API_FAILURE_CORS_OR_NETWORK, API_ROOT_URL, catalogDeepSearchRowDedupeKey, formatApiErrorFromAxios, normalizeApiFailure } from "@/lib/api";
import { buildCatalogDeepSearchBody, normalizeDeepSearchResultRows } from "@/lib/catalogDeepSearchClient";
import { resolveAiSearchSources } from "@/lib/catalogLikelySources";
import { AI_SEARCH_SCOPE_EXTENDED } from "@/hooks/catalogSearch/useCatalogSearchState";
import { runCatalogDeepSearchSseStream } from "./useCatalogSseStream";

function followupRecoveryRows(deepData) {
  if (!deepData || typeof deepData !== "object") return [];
  const grouped = deepData.grouped_results && typeof deepData.grouped_results === "object"
    ? deepData.grouped_results
    : {};
  const candidates = [
    ...(Array.isArray(deepData.verified) ? deepData.verified : []),
    ...(Array.isArray(deepData.possible) ? deepData.possible : []),
    ...(Array.isArray(grouped.verified) ? grouped.verified : []),
    ...(Array.isArray(grouped.candidates) ? grouped.candidates : []),
  ];
  const rows = [];
  const seen = new Set();
  for (const candidate of candidates) {
    if (!candidate || typeof candidate !== "object") continue;
    const source = String(candidate.source_type || candidate.catalog_id || candidate.source || "").trim().toLowerCase();
    const setId = String(candidate.set_id || candidate.series_id || candidate.id || "").trim();
    if (!source || !setId) continue;
    const key = `${source}|${setId}`;
    if (seen.has(key)) continue;
    seen.add(key);
    rows.push({
      source_type: source,
      catalog_id: source,
      set_id: setId,
      title: String(candidate.title || candidate.name || candidate.label || setId).trim(),
      query_params: candidate.query_params && typeof candidate.query_params === "object" ? candidate.query_params : {},
    });
    if (rows.length >= 50) break;
  }
  return rows;
}

function followupChartContext(chartPayload) {
  if (!chartPayload || typeof chartPayload !== "object") return {};
  const rows = Array.isArray(chartPayload.rows) ? chartPayload.rows : [];
  const periods = rows.map((row) => String(row?.x ?? row?.period ?? "").trim()).filter(Boolean).sort();
  const series = Array.isArray(chartPayload.series) ? chartPayload.series : [];
  return {
    title: String(chartPayload.title || "").trim(),
    start_period: periods[0] || "",
    end_period: periods[periods.length - 1] || "",
    frequency: String(chartPayload.frequency || "").trim(),
    series_labels: series.map((item) => String(item?.name || item?.label || "").trim()).filter(Boolean).slice(0, 12),
    geographies: series.map((item) => String(item?.territory || item?.geo || "").trim()).filter(Boolean).slice(0, 12),
    sources: series.map((item) => String(item?.source || item?.catalog_id || "").trim()).filter(Boolean).slice(0, 12),
  };
}

function mergeResearchResult(baseData, researchResult) {
  const base = baseData && typeof baseData === "object" ? { ...baseData } : {};
  if (!researchResult || typeof researchResult !== "object") return base;
  base.followup_research_result = researchResult;
  const actions = Array.isArray(researchResult.chart_actions) ? researchResult.chart_actions : [];
  const annotations = actions.filter((action) => String(action?.type || "") === "annotate_period");
  if (annotations.length && base.followup_chart_payload && typeof base.followup_chart_payload === "object") {
    const existing = Array.isArray(base.followup_chart_payload.web_annotations)
      ? base.followup_chart_payload.web_annotations
      : [];
    const replacedLayers = new Set(
      annotations
        .filter((item) => item?.replace_layer === true)
        .map((item) => String(item?.layer_id || "").trim())
        .filter(Boolean),
    );
    const byKey = new Map();
    [...existing.filter((item) => !replacedLayers.has(String(item?.layer_id || "").trim())), ...annotations].forEach((item) => {
      const key = `${item?.from || ""}|${item?.to || ""}|${item?.label || ""}`;
      if (key !== "||") byKey.set(key, item);
    });
    base.followup_chart_payload = {
      ...base.followup_chart_payload,
      web_annotations: Array.from(byKey.values()).slice(-24),
      web_citations: Array.isArray(researchResult.citations) ? researchResult.citations : [],
    };
  }
  return base;
}

export function useDeepSearchRunner({
  aiQuery,
  selected,
  useAiAssistant,
  deepSourceOrder,
  deepSourceLabel,
  chunkTimeoutMs,
  totalTimeoutMs,
  onNewSearch,
}) {
  const [deepLoading, setDeepLoading] = useState(false);
  const [deepError, setDeepError] = useState("");
  const [deepErrorTechnical, setDeepErrorTechnical] = useState("");
  const [deepData, setDeepData] = useState(null);
  const [deepSourceStatuses, setDeepSourceStatuses] = useState([]);
  const [deepActiveSourceIds, setDeepActiveSourceIds] = useState([]);
  const [deepFollowupLoading, setDeepFollowupLoading] = useState(false);
  const [deepFollowupError, setDeepFollowupError] = useState("");
  const [deepFollowupResult, setDeepFollowupResult] = useState(null);
  const [deepChatFilteredIds, setDeepChatFilteredIds] = useState(null);
  const [deepConversation, setDeepConversation] = useState(null);
  const [deepLaneResults, setDeepLaneResults] = useState({});
  const [deepStreamAwaitingFinal, setDeepStreamAwaitingFinal] = useState(false);
  const deepSearchRequestSeqRef = useRef(0);
  const deepAbortRef = useRef(null);
  const deepEventSourceRef = useRef(null);
  const skipQueryChangeAbortRef = useRef(false);
  const prevAiQueryTrimRef = useRef("");
  const selectedSourcesKey = useMemo(
    () =>
      [...(selected instanceof Set ? selected : [])]
        .map((id) => String(id || "").trim().toLowerCase())
        .filter(Boolean)
        .sort()
        .join(","),
    [selected],
  );

  const mergeCandidateRows = useCallback((prev, rows, source) => {
    const incoming = Array.isArray(rows) ? rows.filter((r) => r && typeof r === "object") : [];
    if (!incoming.length) return prev;
    const base = prev && typeof prev === "object" ? { ...prev } : {};
    const grouped = base.grouped_results && typeof base.grouped_results === "object" ? { ...base.grouped_results } : {};
    const currentCandidates = Array.isArray(grouped.candidates)
      ? [...grouped.candidates]
      : Array.isArray(base.possible)
        ? [...base.possible]
        : [];
    const beforeCount = currentCandidates.length;
    const seen = new Set(currentCandidates.map(catalogDeepSearchRowDedupeKey));
    for (const row of incoming) {
      const key = catalogDeepSearchRowDedupeKey(row);
      if (!key || key === "|" || seen.has(key)) continue;
      seen.add(key);
      currentCandidates.push({ ...row, progressive: true });
    }
    try {
      const sourcesVisible = [...new Set(currentCandidates.map((r) => String(r?.catalog_id || r?.source_type || r?.source || "").toLowerCase()).filter(Boolean))];
      console.debug("[deep-search candidates merge]", {
        incomingSource: source || "",
        incomingCount: incoming.length,
        beforeCount,
        afterCount: currentCandidates.length,
        sourcesVisible,
        topCandidates: currentCandidates.slice(0, 10).map((r) => ({
          source: r.catalog_id || r.source_type || r.source,
          set_id: r.set_id || r.series_id,
          title: r.name || r.title,
          score: r.final_score ?? r.score,
          geo_match: r.geo_match,
          semantic_match_level: r.semantic_match_level,
          demotion_reason: r.demotion_reason,
          final_rank: r.final_rank,
        })),
      });
    } catch  {
      /* debug only */
    }
    grouped.candidates = currentCandidates;
    grouped.verified = Array.isArray(grouped.verified) ? grouped.verified : Array.isArray(base.verified) ? base.verified : [];
    grouped.beta = Array.isArray(grouped.beta) ? grouped.beta : [];
    return {
      ...base,
      ok: base.ok === false ? false : true,
      partial: true,
      progressive: true,
      last_progressive_source: source || base.last_progressive_source,
      grouped_results: grouped,
      possible: currentCandidates,
      verified: Array.isArray(base.verified) ? base.verified : grouped.verified,
    };
  }, []);

  const sortStatuses = useCallback(
    (arr) =>
      [...(arr || [])].sort((a, b) => {
        const ai = deepSourceOrder.indexOf(String(a.source || ""));
        const bi = deepSourceOrder.indexOf(String(b.source || ""));
        const av = ai === -1 ? 88 : ai;
        const bv = bi === -1 ? 88 : bi;
        if (av !== bv) return av - bv;
        return String(a.source || "").localeCompare(String(b.source || ""));
      }),
    [deepSourceOrder],
  );

  useEffect(() => {
    if (!useAiAssistant) return undefined;
    const nextTrim = String(aiQuery || "").trim();
    if (skipQueryChangeAbortRef.current) {
      skipQueryChangeAbortRef.current = false;
      prevAiQueryTrimRef.current = nextTrim;
      return undefined;
    }
    if (prevAiQueryTrimRef.current === nextTrim) {
      return undefined;
    }
    prevAiQueryTrimRef.current = nextTrim;
    const c = deepAbortRef.current;
    if (!c) return undefined;
    try {
      c.abort();
    } catch  {
      /* noop */
    }
    deepAbortRef.current = null;
    try {
      deepEventSourceRef.current?.close?.();
    } catch  {
      /* noop */
    }
    deepEventSourceRef.current = null;
    deepSearchRequestSeqRef.current += 1;
    setDeepLoading(false);
    setDeepError("");
    setDeepErrorTechnical("");
    setDeepSourceStatuses([]);
    setDeepActiveSourceIds([]);
    setDeepFollowupLoading(false);
    setDeepFollowupError("");
    setDeepFollowupResult(null);
    setDeepConversation(null);
    return undefined;
  }, [aiQuery, useAiAssistant, selectedSourcesKey]);

  const runDeepSearch = useCallback(
    async (overrideQuery, options = {}) => {
      if (!useAiAssistant) return;
      skipQueryChangeAbortRef.current = true;
      const dq = typeof overrideQuery === "string" && overrideQuery.trim().length >= 2 ? overrideQuery.trim() : aiQuery.trim();
      if (dq.length < 2) return;
      void options; // The unified pipeline always uses multi-source retrieval.
      const sourcesForRun = resolveAiSearchSources(
        dq,
        selected,
        AI_SEARCH_SCOPE_EXTENDED,
      );
      if (sourcesForRun.length < 1) return;
      deepAbortRef.current?.abort?.();
      const seq = ++deepSearchRequestSeqRef.current;
      const ctrl = new AbortController();
      deepAbortRef.current = ctrl;
      const { signal } = ctrl;

      const postDeepChunk = async (sourcesArr, searchProfile, timeoutMs = chunkTimeoutMs, limitPerSource = 20) => {
        const body = buildCatalogDeepSearchBody({
          query: dq,
          sources: sourcesArr,
          searchProfile,
          limitPerSource,
        });
        try {
          const { data } = await api.post("/catalog/deep-search", body, {
            timeout: timeoutMs,
            signal,
          });
          return data;
        } catch (e) {
          const nf = normalizeApiFailure(e);
          if (nf.isCanceled) return null;
          const status = Number(e?.response?.status || 0);
          const detail = e?.response?.data?.detail || e?.response?.data?.message_cs || nf.message;
          if (status === 403) {
            return {
              ok: false,
              partial: false,
              verified: [],
              possible: [],
              source_statuses: [],
              catalog_index_warnings: [],
              message_cs: String(detail || "Tato funkce je dostupná pro předplatitele časopisu Bankovnictví."),
              error: "access_denied",
            };
          }
          if (e?.response?.data && typeof e.response.data === "object") return e.response.data;
          return {
            ok: false,
            partial: Boolean(e?.response?.status === 503),
            verified: [],
            possible: [],
            source_statuses: [],
            catalog_index_warnings: [],
            message_cs: nf.message || formatApiErrorFromAxios(e),
            error: "request_failed",
          };
        }
      };

      try {
        setDeepLoading(true);
        setDeepError("");
        setDeepErrorTechnical("");
        setDeepData(null);
        setDeepConversation(null);
        setDeepFollowupResult(null);
        setDeepFollowupError("");
        setDeepChatFilteredIds(null);
        setDeepSourceStatuses([]);
        setDeepActiveSourceIds([]);

        if (seq !== deepSearchRequestSeqRef.current || signal.aborted) return;

        setDeepSourceStatuses(
          sortStatuses(
            sourcesForRun.map((sid) => ({
              source: sid,
              label: deepSourceLabel(sid),
              status: "pending",
              row_count: 0,
              duration_ms: 0,
              message_cs: "",
            })),
          ),
        );
        setDeepActiveSourceIds([...sourcesForRun]);

        if (seq !== deepSearchRequestSeqRef.current || signal.aborted) return;

        const streamParams = new URLSearchParams();
        streamParams.set("q", dq);
        streamParams.set("sources", sourcesForRun.join(","));
        streamParams.set("mode", "multi");
        streamParams.set("use_ai", "1");
        setDeepLaneResults({});
        setDeepStreamAwaitingFinal(true);
        let sseFirstEventReceived = false;
        let ssePartialRowsReceived = false;
        const streamResult = await runCatalogDeepSearchSseStream({
          url: `${API_ROOT_URL.replace(/\/$/, "")}/catalog/deep-search/stream?${streamParams.toString()}`,
          signal,
          timeoutMs: totalTimeoutMs,
          isRequestCurrent: () => seq === deepSearchRequestSeqRef.current && !signal.aborted,
          onSourceStatus: (msg) => {
            sseFirstEventReceived = true;
            if (msg?.event === "stream_open") return;
            const sid = String(msg.source || "").toLowerCase();
            if (!sid || sid === "pipeline") return;
            setDeepSourceStatuses((prev) => {
              const arr = Array.isArray(prev) ? [...prev] : [];
              const idx = arr.findIndex((x) => String(x.source || "").toLowerCase() === sid);
              const nextStatus =
                msg.event === "source_started"
                  ? "running"
                  : String(msg.status || "ok").toLowerCase() || "ok";
              const row = {
                ...(idx >= 0 ? arr[idx] : {}),
                ...msg,
                source: sid,
                label: msg.label || deepSourceLabel(sid),
                status: nextStatus,
              };
              if (idx >= 0) arr[idx] = row;
              else arr.push(row);
              return sortStatuses(arr);
            });
          },
          onPartial: (msg) => {
            sseFirstEventReceived = true;
            if (msg?.event === "source_candidates_missing_payload") {
              setDeepData((prev) => {
                const base = prev && typeof prev === "object" ? { ...prev } : {};
                const warnings = Array.isArray(base.catalog_index_warnings) ? [...base.catalog_index_warnings] : [];
                warnings.push(`Zdroj ${String(msg.source || "").toUpperCase()} hlásí kandidáty, ale neposlal jejich řádky.`);
                return {
                  ...base,
                  ok: true,
                  partial: true,
                  progressive: true,
                  source_candidates_missing_payload: {
                    source: msg.source,
                    row_count: msg.row_count,
                    warning: msg.warning,
                  },
                  catalog_index_warnings: [...new Set(warnings)],
                };
              });
              return;
            }
            if (!msg || (msg.event !== "source_candidates" && msg.event !== "ranking_update")) return;
            if (Array.isArray(msg.candidates) && msg.candidates.length > 0) {
              ssePartialRowsReceived = true;
            }
            const sid = String(msg.source || "").toLowerCase();
            if (sid && msg.event === "source_candidates") {
              const count = Number(msg.count ?? (Array.isArray(msg.candidates) ? msg.candidates.length : 0)) || 0;
              setDeepSourceStatuses((prev) => {
                const arr = Array.isArray(prev) ? [...prev] : [];
                const idx = arr.findIndex((x) => String(x.source || "").toLowerCase() === sid);
                const row = {
                  ...(idx >= 0 ? arr[idx] : {}),
                  source: sid,
                  label: deepSourceLabel(sid),
                  status: "ok",
                  candidate_count: count,
                  row_count: Math.max(Number(idx >= 0 ? arr[idx]?.row_count || 0 : 0), count),
                };
                if (idx >= 0) arr[idx] = row;
                else arr.push(row);
                return sortStatuses(arr);
              });
            }
            setDeepData((prev) => mergeCandidateRows(prev, msg.candidates, msg.source));
          },
          onLane: (msg) => {
            sseFirstEventReceived = true;
            ssePartialRowsReceived = true;
            const sid = String(msg?.source || "").toLowerCase();
            const rows = Array.isArray(msg?.rows) ? msg.rows : [];
            if (!sid) return;
            setDeepLaneResults((prev) => ({ ...(prev && typeof prev === "object" ? prev : {}), [sid]: rows }));
            setDeepSourceStatuses((prev) => {
              const arr = Array.isArray(prev) ? [...prev] : [];
              const idx = arr.findIndex((x) => String(x.source || "").toLowerCase() === sid);
              const count = Number(msg?.count ?? rows.length) || 0;
              const row = {
                ...(idx >= 0 ? arr[idx] : {}),
                source: sid,
                label: deepSourceLabel(sid),
                status: "ok",
                candidate_count: count,
                row_count: Math.max(Number(idx >= 0 ? arr[idx]?.row_count || 0 : 0), count),
              };
              if (idx >= 0) arr[idx] = row;
              else arr.push(row);
              return sortStatuses(arr);
            });
          },
          onFinal: (msg) => {
            sseFirstEventReceived = true;
            if (!msg || typeof msg !== "object") return;
            setDeepStreamAwaitingFinal(false);
            setDeepLaneResults({});
            setDeepData((prev) => {
              const { verified, possible } = normalizeDeepSearchResultRows(msg);
              const next = {
                ...(prev && typeof prev === "object" ? prev : {}),
                ok: true,
                verified,
                possible,
                grouped_results: {
                  verified,
                  candidates: possible,
                  beta: [],
                },
                partial: false,
                progressive: false,
                // Classic-search web fallback (backend: no_valid_result branch). The SSE "final"
                // event copies the whole result map, so these arrive on `msg`; forward them so the
                // intermediate deepData already carries them (the terminal setDeepData from the
                // search_finished payload passes them too, but this keeps the state consistent).
                web_sources: Array.isArray(msg.web_sources) ? msg.web_sources : (prev?.web_sources || []),
                web_sources_total: Number.isFinite(msg.web_sources_total)
                  ? msg.web_sources_total
                  : (prev?.web_sources_total || 0),
                web_research_status: msg.web_research_status || prev?.web_research_status || "not_attempted",
                search_diagnostics:
                  msg.diagnostics && typeof msg.diagnostics === "object"
                    ? msg.diagnostics
                    : prev?.search_diagnostics,
              };
              return next;
            });
          },
        });
        setDeepStreamAwaitingFinal(false);
        if (seq !== deepSearchRequestSeqRef.current || signal.aborted) return;
        const sseTelemetry = streamResult && typeof streamResult === "object" ? streamResult.telemetry || {} : {};
        const streamPayload = streamResult && typeof streamResult === "object" && "payload" in streamResult ? streamResult.payload : streamResult;
        let multiPayload = streamPayload;
        let sseSkippedFallback = false;
        if (!multiPayload) {
          const hadProgress =
            sseFirstEventReceived || sseTelemetry.sse_first_event_received || ssePartialRowsReceived;
          const reason = hadProgress
            ? ssePartialRowsReceived
              ? "sse_closed_after_partial_results"
              : "sse_closed_after_progress_events"
            : "sse_closed_before_first_event";
          if (hadProgress) {
            sseSkippedFallback = true;
            const retryMessage = ssePartialRowsReceived
              ? "Stream vyhledávání byl přerušen před dokončením. Ponechávám průběžné výsledky; pro nový pokus spusťte hledání znovu."
              : "Stream vyhledávání byl přerušen před dokončením. Spusťte hledání znovu.";
            setDeepData((prev) => ({
              ...(prev && typeof prev === "object" ? prev : {}),
              ok: true,
              partial: true,
              progressive: ssePartialRowsReceived,
              sse_telemetry: {
                ...sseTelemetry,
                fallback_post_started: false,
                fallback_post_skipped_reason: `${reason}_active_stream_not_restarted`,
              },
              catalog_index_warnings: [
                ...((prev && typeof prev === "object" && Array.isArray(prev.catalog_index_warnings))
                  ? prev.catalog_index_warnings
                  : []),
                retryMessage,
              ],
            }));
            setDeepError(retryMessage);
          } else {
            sseTelemetry.fallback_post_started = true;
            multiPayload = await postDeepChunk(sourcesForRun, "multi", totalTimeoutMs, 20);
          }
          if (multiPayload && typeof multiPayload === "object") {
            multiPayload.sse_telemetry = {
              ...sseTelemetry,
              fallback_post_started: true,
              fallback_post_skipped_reason: reason,
            };
          }
        } else if (multiPayload && typeof multiPayload === "object") {
          multiPayload.sse_telemetry = {
            ...sseTelemetry,
            fallback_post_started: false,
            fallback_post_skipped_reason: "sse_final_received",
          };
        }
        if (seq !== deepSearchRequestSeqRef.current || signal.aborted) return;
        if (multiPayload) {
          if (multiPayload.ok === false) {
            const msg = String(multiPayload.message_cs || multiPayload.message || "").trim();
            setDeepError(msg || "AI vyhledávání se nepodařilo dokončit.");
            setDeepData(null);
            setDeepConversation(null);
          } else {
            setDeepData((prev) => {
              const next = multiPayload && typeof multiPayload === "object" ? { ...multiPayload } : multiPayload;
              if (!next || typeof next !== "object") return next;
              if (prev && typeof prev === "object" && Array.isArray(prev.catalog_index_warnings)) {
                const mergedWarnings = [
                  ...prev.catalog_index_warnings,
                  ...(Array.isArray(next.catalog_index_warnings) ? next.catalog_index_warnings : []),
                ];
                if (mergedWarnings.length) {
                  next.catalog_index_warnings = [...new Set(mergedWarnings)];
                }
              }
              const hasRows =
                (Array.isArray(next.verified) && next.verified.length > 0) ||
                (Array.isArray(next.possible) && next.possible.length > 0) ||
                (next.grouped_results &&
                  typeof next.grouped_results === "object" &&
                  ((Array.isArray(next.grouped_results.candidates) && next.grouped_results.candidates.length > 0) ||
                    (Array.isArray(next.grouped_results.verified) && next.grouped_results.verified.length > 0)));
              if (!hasRows && prev && typeof prev === "object") {
                const prevPossible = Array.isArray(prev.possible) ? prev.possible : [];
                const prevVerified = Array.isArray(prev.verified) ? prev.verified : [];
                const prevGrouped =
                  prev.grouped_results && typeof prev.grouped_results === "object"
                    ? prev.grouped_results
                    : null;
                const prevGroupedCand = Array.isArray(prevGrouped?.candidates) ? prevGrouped.candidates : [];
                if (prevPossible.length + prevVerified.length + prevGroupedCand.length > 0) {
                  return {
                    ...next,
                    verified: prevVerified,
                    possible: prevPossible.length ? prevPossible : prevGroupedCand,
                    partial: true,
                    progressive: true,
                    catalog_index_warnings: [
                      ...(Array.isArray(next.catalog_index_warnings) ? next.catalog_index_warnings : []),
                      ...(Array.isArray(prev.catalog_index_warnings) ? prev.catalog_index_warnings : []),
                    ],
                  };
                }
              }
              return next;
            });
            setDeepFollowupResult(null);
            if (multiPayload?.conversation && typeof multiPayload.conversation === "object") {
              setDeepConversation(multiPayload.conversation);
            }
            if (Array.isArray(multiPayload.source_statuses)) {
              setDeepSourceStatuses(sortStatuses(multiPayload.source_statuses));
            }
          }
        } else if (!signal.aborted && !sseSkippedFallback) {
          setDeepError("AI vyhledávání se nepodařilo dokončit. Zkuste vybrat méně databází nebo kratší dotaz.");
        }
      } catch (e) {
        if (seq !== deepSearchRequestSeqRef.current) return;
        const nf = normalizeApiFailure(e);
        if (nf.isCanceled) return;
        const netHint =
          nf.isCorsOrNetwork ||
          nf.message === API_FAILURE_CORS_OR_NETWORK ||
          /blocked by CORS|ERR_NETWORK/i.test(String(nf.message || ""));
        const friendly = netHint
          ? "AI vyhledávání se nepodařilo dokončit. Zkuste vybrat méně databází nebo kratší dotaz."
          : nf.message || formatApiErrorFromAxios(e);
        setDeepError(friendly);
        setDeepErrorTechnical(nf.details || nf.message || String(e?.message || e));
        setDeepData(null);
        setDeepConversation(null);
      } finally {
        deepAbortRef.current = null;
        if (seq === deepSearchRequestSeqRef.current) {
          setDeepLoading(false);
        }
      }
    },
    [
      aiQuery,
      selected,
      useAiAssistant,
      sortStatuses,
      mergeCandidateRows,
      deepSourceLabel,
      chunkTimeoutMs,
      totalTimeoutMs,
    ],
  );

  const runDeepSearchExtended = useCallback(
    async (overrideQuery) => runDeepSearch(overrideQuery, { extended: true }),
    [runDeepSearch],
  );

  const runDeepFollowup = useCallback(
    async ({ message, actionHint, followupPlan, availableSeriesRefs } = {}) => {
      const msg = String(message || "").trim();
      if (msg.length < 2) return null;
      const convId = String((deepData?.conversation || deepConversation || {}).id || "").trim();
      if (!convId) {
        setDeepFollowupError("Konverzace ještě není připravená. Nejdřív spusťte AI návrh řad.");
        return null;
      }
      setDeepFollowupLoading(true);
      setDeepFollowupError("");
      try {
        const body = {
          conversation_id: convId,
          message: msg,
          action_hint: actionHint ? String(actionHint).trim().toLowerCase() : undefined,
          followup_plan:
            followupPlan && typeof followupPlan === "object" ? followupPlan : undefined,
          available_series_refs: Array.isArray(availableSeriesRefs) ? availableSeriesRefs : [],
          selected_series_refs: [],
          chart_context: followupChartContext(deepData?.followup_chart_payload),
          recovery_context: {
            root_query: String(aiQuery || "").trim(),
            mode: "multi",
            sources: selectedSourcesKey ? selectedSourcesKey.split(",").filter(Boolean) : [],
            use_ai: Boolean(useAiAssistant),
            found_summary: followupRecoveryRows(deepData),
          },
        };
        const { data } = await api.post("/catalog/deep-search/followup", body, {
          timeout: totalTimeoutMs,
        });
        if (data?.conversation && typeof data.conversation === "object") {
          setDeepConversation(data.conversation);
        }
        const convForData =
          data?.conversation && typeof data.conversation === "object"
            ? data.conversation
            : deepConversation || (deepData?.conversation && typeof deepData.conversation === "object" ? deepData.conversation : null);
        if (data?.deep_search_result && typeof data.deep_search_result === "object") {
          const nextData = { ...data.deep_search_result };
          if (convForData) nextData.conversation = convForData;
          if (data?.computation_result && typeof data.computation_result === "object") {
            nextData.followup_computation_result = data.computation_result;
            if (data.computation_result.chart_payload && typeof data.computation_result.chart_payload === "object") {
              nextData.followup_chart_payload = data.computation_result.chart_payload;
            }
          }
          setDeepData(mergeResearchResult(nextData, data?.research_result));
        } else if (data?.computation_result && typeof data.computation_result === "object") {
          setDeepData((prev) => {
            const base = prev && typeof prev === "object" ? { ...prev } : {};
            if (convForData) base.conversation = convForData;
            base.followup_computation_result = data.computation_result;
            if (data.computation_result.chart_payload && typeof data.computation_result.chart_payload === "object") {
              base.followup_chart_payload = data.computation_result.chart_payload;
            }
            return mergeResearchResult(base, data?.research_result);
          });
        } else if (data?.research_result && typeof data.research_result === "object") {
          setDeepData((prev) => {
            const base = mergeResearchResult(prev, data.research_result);
            if (convForData) base.conversation = convForData;
            return base;
          });
        }
        setDeepFollowupResult(data?.computation_result && typeof data.computation_result === "object" ? data.computation_result : null);
        if (data?.action === "chat_over_results") {
          const fids = Array.isArray(data?.filtered_set_ids) ? data.filtered_set_ids : null;
          setDeepChatFilteredIds(fids);
        }
        if (data?.ok === false) {
          setDeepFollowupError(String(data?.error || "Follow-up se nepodařilo dokončit."));
        }
        return data;
      } catch (e) {
        const nf = normalizeApiFailure(e);
        const friendly = nf.message || formatApiErrorFromAxios(e);
        setDeepFollowupError(friendly);
        return { ok: false, error: friendly };
      } finally {
        setDeepFollowupLoading(false);
      }
    },
    [aiQuery, deepData, deepConversation, selectedSourcesKey, totalTimeoutMs, useAiAssistant],
  );

  const applySuggestedDeepSearch = useCallback(
    async (query) => {
      const dq = String(query || "").trim();
      if (dq.length < 2) return;
      skipQueryChangeAbortRef.current = true;
      // Nový dotaz = nové téma - chat nad PŘEDCHOZÍMI výsledky (`followupMessages` ve stránce)
      // by jinak zůstal viset nad výsledky, které už uživatel nevidí. `runDeepSearch()` bez
      // argumentu (tlačítko "zkusit znovu") a `runDeepSearchExtended()` (rozšířit STEJNÝ dotaz)
      // tudy neprochází, takže chat u pouhého opakování/rozšíření zůstává schválně netknutý.
      onNewSearch?.(dq);
      await runDeepSearch(dq);
    },
    [runDeepSearch, onNewSearch],
  );

  const cancelDeepSearch = useCallback(() => {
    try {
      deepAbortRef.current?.abort?.();
    } catch  {
      /* noop */
    }
    try {
      deepEventSourceRef.current?.close?.();
    } catch  {
      /* noop */
    }
    deepAbortRef.current = null;
    deepEventSourceRef.current = null;
    deepSearchRequestSeqRef.current += 1;
    setDeepLoading(false);
    setDeepActiveSourceIds([]);
    setDeepLaneResults({});
    setDeepStreamAwaitingFinal(false);
  }, []);

  return {
    deepLoading,
    deepError,
    deepErrorTechnical,
    deepData,
    deepSourceStatuses,
    deepActiveSourceIds,
    deepFollowupLoading,
    deepFollowupError,
    deepFollowupResult,
    deepChatFilteredIds,
    deepConversation,
    deepLaneResults,
    deepStreamAwaitingFinal,
    runDeepSearch,
    runDeepSearchExtended,
    applySuggestedDeepSearch,
    cancelDeepSearch,
    runDeepFollowup,
    setDeepData,
    setDeepError,
    setDeepErrorTechnical,
    setDeepSourceStatuses,
    setDeepConversation,
    setDeepFollowupResult,
    setDeepFollowupError,
    clearDeepChatFilter: () => setDeepChatFilteredIds(null),
  };
}

