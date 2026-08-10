import React, { useCallback, useMemo, useState } from "react";
import { FolderOpen, Loader2, MessageCircle, RefreshCw, Search } from "lucide-react";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import ExploreInlineCatalogPicker from "@/components/explore/ExploreInlineCatalogPicker";
import { EXPLORE_CATALOG_SOURCES, filterExploreCatalogSources } from "@/lib/exploreCatalogSources";
import { localizeDecisionImpact } from "@/lib/exploreAnalysisInsights";

const CATALOG_SOURCES = EXPLORE_CATALOG_SOURCES;

function seriesRefKey(item) {
  const src = String(item?.source_type || item?.source || "").trim().toLowerCase();
  const sid = String(item?.set_id || item?.dataset_id || "").trim();
  const qp = item?.query_params || item?.filters_used || {};
  return `${src}:${sid}:${JSON.stringify(qp)}`;
}

function catalogRowToRef(row, source) {
  return {
    source_type: String(row?.source_type || row?.catalog_id || source || "").trim().toLowerCase(),
    set_id: String(row?.set_id || row?.series_id || row?.dataset_id || "").trim(),
    title: String(row?.title || row?.name || row?.indicator_name || row?.set_id || "").trim(),
    query_params:
      row?.query_params && typeof row.query_params === "object" && !Array.isArray(row.query_params)
        ? { ...row.query_params }
        : {},
  };
}

export default function ExploreSectionFollowup({
  section,
  exploreMeta,
  priorResult,
  onSectionUpdate,
  onRequestFullRefresh,
  defaultOpen = false,
  triggerLabel = "",
}) {
  const [open, setOpen] = useState(Boolean(defaultOpen));
  const [question, setQuestion] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [thread, setThread] = useState([]);
  const [pickedRefs, setPickedRefs] = useState(() => new Set());
  const [pickedRefByKey, setPickedRefByKey] = useState(() => ({}));
  const [catalogQuery, setCatalogQuery] = useState("");
  const [catalogSource, setCatalogSource] = useState("eurostat");
  const [catalogHits, setCatalogHits] = useState([]);
  const [catalogLoading, setCatalogLoading] = useState(false);
  const [catalogBrowseOpen, setCatalogBrowseOpen] = useState(false);

  const priorContext = String(priorResult?.followup_context?.data_context || "").trim();
  const questionUnderstanding =
    priorResult?.question_understanding && typeof priorResult.question_understanding === "object"
      ? priorResult.question_understanding
      : priorResult?.followup_context?.question_understanding && typeof priorResult.followup_context.question_understanding === "object"
        ? priorResult.followup_context.question_understanding
        : {};
  const analysisScoreSnapshot =
    priorResult?.analysis_score && typeof priorResult.analysis_score === "object"
      ? priorResult.analysis_score
      : priorResult?.followup_context?.analysis_score_snapshot && typeof priorResult.followup_context.analysis_score_snapshot === "object"
        ? priorResult.followup_context.analysis_score_snapshot
        : {};
  const optionalSeries = useMemo(() => {
    const rows = Array.isArray(exploreMeta?.optionalSeries) ? exploreMeta.optionalSeries : [];
    const seen = new Set();
    const out = [];
    for (const row of rows) {
      const ref = catalogRowToRef(row, row?.source);
      if (!ref.set_id || !ref.source_type) continue;
      const key = seriesRefKey(ref);
      if (seen.has(key)) continue;
      seen.add(key);
      out.push(ref);
    }
    return out.slice(0, 40);
  }, [exploreMeta?.optionalSeries]);

  const pickedSeriesList = useMemo(() => {
    const all = [...optionalSeries];
    const fromCatalog = catalogHits
      .map((row) => catalogRowToRef(row, catalogSource))
      .filter((r) => pickedRefs.has(seriesRefKey(r)));
    for (const r of fromCatalog) {
      if (!all.some((x) => seriesRefKey(x) === seriesRefKey(r))) all.push(r);
    }
    for (const ref of Object.values(pickedRefByKey)) {
      if (!ref || typeof ref !== "object") continue;
      const key = seriesRefKey(ref);
      if (!pickedRefs.has(key)) continue;
      if (!all.some((x) => seriesRefKey(x) === key)) all.push(ref);
    }
    return all.filter((r) => pickedRefs.has(seriesRefKey(r)));
  }, [optionalSeries, catalogHits, catalogSource, pickedRefs, pickedRefByKey]);

  const searchCatalog = async () => {
    const q = String(catalogQuery || "").trim();
    if (q.length < 2) {
      setError("Hledaný výraz musí mít alespoň 2 znaky.");
      return;
    }
    setCatalogLoading(true);
    setError("");
    try {
      const { data } = await api.post("/catalog/search", {
        source: catalogSource,
        query: q,
        limit: 12,
      });
      const rows = Array.isArray(data?.results) ? data.results : Array.isArray(data?.hits) ? data.hits : [];
      setCatalogHits(rows);
    } catch (e) {
      setError(formatApiErrorFromAxios(e));
      setCatalogHits([]);
    } finally {
      setCatalogLoading(false);
    }
  };

  const toggleRef = (ref) => {
    const key = seriesRefKey(ref);
    setPickedRefs((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
    setPickedRefByKey((prev) => {
      const next = { ...prev };
      if (next[key]) delete next[key];
      else next[key] = ref;
      return next;
    });
  };

  const submitFollowup = useCallback(async () => {
    const q = String(question || "").trim();
    if (q.length < 2) {
      setError("Dotaz musí mít alespoň 2 znaky.");
      return;
    }
    setLoading(true);
    setError("");
    try {
      const explainMode = /\b(proc|proč|why)\b/i.test(q);
      const { data } = await api.post("/explore/summarize/followup", {
        followup_question: q,
        section_id: section?.id || "all",
        section_title: section?.title || "",
        section_text: section?.text || "",
        question: String(exploreMeta?.question || priorResult?.followup_context?.original_question || ""),
        sector: exploreMeta?.sector || priorResult?.followup_context?.sector || null,
        countries: exploreMeta?.countries || priorResult?.followup_context?.country || null,
        prior_data_context: priorContext,
        question_understanding: questionUnderstanding,
        analysis_score_snapshot: analysisScoreSnapshot,
        response_mode: explainMode ? "explain" : "answer",
        explain_target: explainMode ? section?.id || "decision_score" : null,
        additional_series: pickedSeriesList.map((r) => ({
          source_type: r.source_type,
          set_id: r.set_id,
          title: r.title,
          query_params: r.query_params || {},
        })),
      });
      if (!data?.ok) {
        setError(String(data?.error || "Doplňující odpověď se nepodařila."));
        return;
      }
      const answer = String(data?.followup_answer || "").trim();
      if (answer) {
        setThread((prev) => [
          ...prev,
          { role: "user", content: q },
          {
            role: "assistant",
            content: answer,
            keyPoints: data?.key_points,
            supportingDrivers: data?.supporting_drivers,
          },
        ]);
      }
      const updated = String(data?.updated_section_text || "").trim();
      if (updated && onSectionUpdate) {
        onSectionUpdate(section.id, updated, answer);
      }
      setQuestion("");
    } catch (e) {
      setError(formatApiErrorFromAxios(e));
    } finally {
      setLoading(false);
    }
  }, [
    question,
    section,
    exploreMeta,
    priorResult,
    priorContext,
    questionUnderstanding,
    analysisScoreSnapshot,
    pickedSeriesList,
    onSectionUpdate,
  ]);

  const catalogSources = useMemo(
    () =>
      filterExploreCatalogSources(CATALOG_SOURCES, {
        geoMode: exploreMeta?.geoMode,
        continent: exploreMeta?.continent,
        countryCodes: exploreMeta?.countryCodes,
      }),
    [exploreMeta?.geoMode, exploreMeta?.continent, exploreMeta?.countryCodes]
  );

  React.useEffect(() => {
    if (!catalogSources.some((s) => s.id === catalogSource)) {
      setCatalogSource(catalogSources[0]?.id || "imf");
    }
  }, [catalogSources, catalogSource]);

  const allowedCatalogIds = useMemo(() => catalogSources.map((s) => s.id), []);

  return (
    <div className="mt-3 pt-3 border-t border-border/50">
      <button
        type="button"
        className="inline-flex items-center gap-1.5 text-[11px] font-medium text-teal-800 hover:text-teal-950"
        onClick={() => setOpen((v) => !v)}
      >
        <MessageCircle className="h-3.5 w-3.5" />
        {open ? "Skrýt doplňující dotaz" : (triggerLabel || "Doptat se AI / doplnit data")}
      </button>

      {open ? (
        <div className="mt-3 space-y-3 rounded-lg border border-border/60 bg-muted/15 px-3 py-3">
          {thread.length > 0 ? (
            <div className="space-y-2 max-h-48 overflow-y-auto">
              {thread.map((msg, idx) => (
                <div
                  key={`${msg.role}-${idx}`}
                  className={`rounded-lg px-2.5 py-2 text-[12px] leading-relaxed ${
                    msg.role === "user"
                      ? "bg-teal-50/80 text-slate-800 ml-4"
                      : "bg-card border border-border/50 text-slate-900 mr-2"
                  }`}
                >
                  {msg.content}
                  {Array.isArray(msg.supportingDrivers) && msg.supportingDrivers.length > 0 ? (
                    <ul className="mt-2 space-y-1 text-[11px] text-slate-700 list-disc pl-4">
                      {msg.supportingDrivers.slice(0, 3).map((row, idx) => (
                        <li key={`${row?.driver || "driver"}-${idx}`}>
                          {String(row?.driver || "Driver")} {row?.decision_impact ? `· ${localizeDecisionImpact(row.decision_impact)}` : ""}
                        </li>
                      ))}
                    </ul>
                  ) : null}
                </div>
              ))}
            </div>
          ) : null}

          <label className="block space-y-1">
            <span className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
              Váš doplňující dotaz
            </span>
            <textarea
              className="w-full min-h-[72px] rounded-lg border border-border bg-card px-2.5 py-2 text-sm resize-y"
              placeholder="např. Jak by rostoucí sazby hypoték ovlivnily návratnost FVE u firem?"
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
            />
          </label>

          <div className="space-y-2">
            <div className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
              Doplnit řady z katalogu
            </div>
            <div className="flex flex-wrap gap-2">
              <select
                className="h-8 rounded-md border border-border bg-card text-xs px-2"
                value={catalogSource}
                onChange={(e) => setCatalogSource(e.target.value)}
              >
                {catalogSources.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.label}
                  </option>
                ))}
              </select>
              <input
                type="text"
                className="flex-1 min-w-[8rem] h-8 rounded-md border border-border bg-card text-xs px-2"
                placeholder="Hledat v katalogu…"
                value={catalogQuery}
                onChange={(e) => setCatalogQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    void searchCatalog();
                  }
                }}
              />
              <button
                type="button"
                className="h-8 px-2.5 rounded-md border border-border bg-card text-xs inline-flex items-center gap-1"
                disabled={catalogLoading}
                onClick={() => void searchCatalog()}
              >
                {catalogLoading ? <Loader2 className="h-3 w-3 animate-spin" /> : <Search className="h-3 w-3" />}
                Hledat
              </button>
              <button
                type="button"
                className={`h-8 px-2.5 rounded-md border text-xs inline-flex items-center gap-1 ${
                  catalogBrowseOpen
                    ? "border-teal-600 bg-teal-100 text-teal-950"
                    : "border-teal-200 bg-teal-50 text-teal-900 hover:bg-teal-100/80"
                }`}
                onClick={() => setCatalogBrowseOpen((v) => !v)}
              >
                <FolderOpen className="h-3 w-3" />
                {catalogBrowseOpen ? "Skrýt katalog" : "Celý katalog"}
              </button>
            </div>
            {catalogBrowseOpen ? (
              <ExploreInlineCatalogPicker
                catalogId={catalogSource}
                onCatalogIdChange={(id) => {
                  setCatalogSource(id);
                  setCatalogHits([]);
                }}
                allowedCatalogIds={allowedCatalogIds}
                pickedKeys={pickedRefs}
                onToggleRef={toggleRef}
                seriesRefKey={seriesRefKey}
                onClose={() => setCatalogBrowseOpen(false)}
              />
            ) : null}
            {catalogHits.length > 0 ? (
              <ul className="max-h-36 overflow-y-auto space-y-1">
                {catalogHits.map((row) => {
                  const ref = catalogRowToRef(row, catalogSource);
                  const key = seriesRefKey(ref);
                  if (!ref.set_id) return null;
                  return (
                    <li key={key}>
                      <label className="flex items-start gap-2 rounded-md border border-border/50 bg-card/80 px-2 py-1.5 cursor-pointer text-[11px]">
                        <input
                          type="checkbox"
                          className="mt-0.5"
                          checked={pickedRefs.has(key)}
                          onChange={() => toggleRef(ref)}
                        />
                        <span className="min-w-0">
                          <span className="font-medium text-slate-800 block truncate">{ref.title}</span>
                          <span className="text-muted-foreground">{ref.source_type}</span>
                        </span>
                      </label>
                    </li>
                  );
                })}
              </ul>
            ) : null}
            {optionalSeries.length > 0 ? (
              <details className="text-[11px]">
                <summary className="cursor-pointer text-muted-foreground hover:text-slate-700">
                  Řady z průvodce ({optionalSeries.length})
                </summary>
                <ul className="mt-2 max-h-32 overflow-y-auto space-y-1">
                  {optionalSeries.map((ref) => {
                    const key = seriesRefKey(ref);
                    return (
                      <li key={key}>
                        <label className="flex items-start gap-2 rounded-md border border-border/40 px-2 py-1 cursor-pointer">
                          <input
                            type="checkbox"
                            className="mt-0.5"
                            checked={pickedRefs.has(key)}
                            onChange={() => toggleRef(ref)}
                          />
                          <span className="truncate">{ref.title}</span>
                        </label>
                      </li>
                    );
                  })}
                </ul>
              </details>
            ) : null}
            {pickedSeriesList.length > 0 ? (
              <p className="text-[10px] text-teal-800">
                Vybráno {pickedSeriesList.length} doplňkových řad pro tento dotaz.
              </p>
            ) : null}
          </div>

          {error ? <p className="text-[11px] text-rose-800">{error}</p> : null}

          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              className="h-8 px-3 rounded-lg bg-teal-700 hover:bg-teal-800 text-white text-xs font-medium inline-flex items-center gap-1.5"
              disabled={loading}
              onClick={() => void submitFollowup()}
            >
              {loading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <MessageCircle className="h-3.5 w-3.5" />}
              Zeptat se AI
            </button>
            {onRequestFullRefresh && pickedSeriesList.length > 0 ? (
              <button
                type="button"
                className="h-8 px-3 rounded-lg border border-border bg-card text-xs font-medium inline-flex items-center gap-1.5"
                disabled={loading || exploreMeta?.refreshing}
                onClick={() => onRequestFullRefresh(pickedSeriesList)}
              >
                <RefreshCw className="h-3.5 w-3.5" />
                Přepočítat celou analýzu s novými řadami
              </button>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  );
}
