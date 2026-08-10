import { useCallback, useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import { useNavigate } from "react-router-dom";
import { Loader2, Network, Search, Sparkles, X } from "lucide-react";
import {
  fetchSeriesConceptExplanation,
  fetchSeriesConceptFollowup,
  seriesConceptExplainSourceNote,
} from "@/lib/catalogSeriesConceptExplain";
import { fetchRelatedSeries } from "@/lib/catalogRelatedSeries";
import { enrichChartExplainMeta, fetchChartDataQuality } from "@/lib/chartDataQuality";

/**
 * Minimalistické tlačítko — popup s vysvětlením, příbuznými řadami a doplňujícími dotazy.
 */
export default function SeriesConceptExplainTrigger({
  meta,
  disabled = false,
  className = "",
  iconClassName = "h-3.5 w-3.5",
  buttonClassName = "",
  title = "Co tento ukazatel ukazuje? AI vysvětlí název a význam řady.",
  onFindInCatalogSearch = null,
}) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [related, setRelated] = useState(null);
  const [relatedLoading, setRelatedLoading] = useState(false);
  const [relatedError, setRelatedError] = useState("");
  const [followupQuestion, setFollowupQuestion] = useState("");
  const [followupLoading, setFollowupLoading] = useState(false);
  const [followupError, setFollowupError] = useState("");
  const [followupThread, setFollowupThread] = useState([]);
  const [qualityHints, setQualityHints] = useState(null);

  const metaKey = JSON.stringify(meta || {});

  useEffect(() => {
    setOpen(false);
    setData(null);
    setError("");
    setLoading(false);
    setRelated(null);
    setRelatedLoading(false);
    setRelatedError("");
    setFollowupQuestion("");
    setFollowupLoading(false);
    setFollowupError("");
    setFollowupThread([]);
    setQualityHints(null);
  }, [metaKey]);

  useEffect(() => {
    if (!open) return undefined;
    function onKey(e) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open]);

  const openCatalogSearch = useCallback(
    (query) => {
      const q = String(query || "").trim();
      if (!q) return;
      if (typeof onFindInCatalogSearch === "function") {
        onFindInCatalogSearch(q);
        setOpen(false);
        return;
      }
      setOpen(false);
      navigate(`/search/catalog?q=${encodeURIComponent(q)}`);
    },
    [navigate, onFindInCatalogSearch],
  );

  const runExplain = useCallback(async () => {
    if (disabled || loading || !meta || typeof meta !== "object") return;
    setOpen(true);
    setLoading(true);
    setError("");
    setData(null);
    setFollowupQuestion("");
    setFollowupError("");
    setFollowupThread([]);
    setQualityHints(null);
    try {
      const tasks = [fetchSeriesConceptExplanation(meta)];
      if (meta.data_stale || meta.last_period) {
        tasks.push(fetchChartDataQuality(meta));
      }
      const [result, quality] = await Promise.all(tasks);
      if (quality?.stale) setQualityHints(quality);
      if (result?.ok && result.explanation_cz) {
        setData(result);
      } else {
        setError(String(result?.message_cs || "Vysvětlení se nepodařilo načíst."));
      }
    } catch (e) {
      setError(String(e?.message || "Vysvětlení se nepodařilo načíst."));
    } finally {
      setLoading(false);
    }
  }, [disabled, loading, meta]);

  const runRelated = useCallback(async () => {
    if (relatedLoading || !meta || typeof meta !== "object") return;
    setRelatedLoading(true);
    setRelatedError("");
    setRelated(null);
    try {
      const result = await fetchRelatedSeries(meta);
      if (result?.ok) {
        setRelated(result);
      } else {
        setRelatedError(String(result?.messageCs || "Příbuzné ukazatele se nepodařilo načíst."));
      }
    } catch (e) {
      setRelatedError(String(e?.message || "Příbuzné ukazatele se nepodařilo načíst."));
    } finally {
      setRelatedLoading(false);
    }
  }, [relatedLoading, meta]);

  const runFollowup = useCallback(
    async (questionOverride) => {
      const question = String(questionOverride ?? followupQuestion ?? "").trim();
      if (!question || followupLoading || !meta) return;
      setFollowupLoading(true);
      setFollowupError("");
      try {
        const prior = String(data?.explanation_cz || "").trim();
        const history = followupThread.map((t) => ({ role: t.role, content: t.content }));
        const result = await fetchSeriesConceptFollowup(meta, question, prior, history);
        if (result?.ok && result.answer_cz) {
          setFollowupThread((prev) => [
            ...prev,
            { role: "user", content: question },
            { role: "assistant", content: result.answer_cz },
          ]);
          setFollowupQuestion("");
          if (result.catalog_search_query) {
            setQualityHints((prev) => ({
              ...(prev || {}),
              suggested_search_query: result.catalog_search_query,
            }));
          }
        } else {
          setFollowupError(String(result?.message_cs || "Odpověď se nepodařila načíst."));
        }
      } catch (e) {
        setFollowupError(String(e?.message || "Odpověď se nepodařila načíst."));
      } finally {
        setFollowupLoading(false);
      }
    },
    [followupQuestion, followupLoading, meta, data?.explanation_cz, followupThread],
  );

  const suggestedQuestions = useMemo(() => {
    const fromQuality = qualityHints?.suggested_questions;
    if (Array.isArray(fromQuality) && fromQuality.length) return fromQuality.slice(0, 4);
    if (meta?.data_stale) {
      return ["Proč data končí tak brzy?", "Je to správný ukazatel?", "Kde najdu aktuálnější řadu?"];
    }
    return ["Jak to souvisí s jiným ukazatelem?", "Co znamená poslední hodnota v grafu?"];
  }, [qualityHints, meta?.data_stale]);

  const seriesTitle = String(meta?.title || meta?.name || "Datová řada").trim();

  return (
    <>
      <button
        type="button"
        disabled={disabled}
        onClick={() => void runExplain()}
        className={
          buttonClassName ||
          `inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-violet-200/80 bg-violet-50/90 text-violet-800 shadow-sm hover:bg-violet-100 disabled:opacity-50 ${className}`
        }
        title={title}
        aria-label="Co tento ukazatel ukazuje?"
        data-export-ignore="true"
        data-testid="series-concept-explain-trigger"
      >
        <Sparkles className={iconClassName} aria-hidden />
      </button>

      {open && typeof document !== "undefined"
        ? createPortal(
            <div className="fixed inset-0 z-[260] flex items-center justify-center p-4">
              <button
                type="button"
                className="absolute inset-0 bg-slate-950/40 backdrop-blur-[2px]"
                aria-label="Zavřít"
                onClick={() => setOpen(false)}
              />
              <div
                role="dialog"
                aria-modal="true"
                aria-labelledby="series-concept-explain-title"
                className="relative z-[1] w-full max-w-md rounded-2xl border border-violet-200/80 bg-white shadow-2xl text-left overflow-hidden"
                onClick={(e) => e.stopPropagation()}
              >
                <div className="flex items-start justify-between gap-3 px-4 py-3 border-b border-violet-100 bg-gradient-to-r from-violet-50 to-indigo-50/80">
                  <div className="min-w-0">
                    <div
                      id="series-concept-explain-title"
                      className="text-[10px] font-semibold uppercase tracking-wide text-violet-800"
                    >
                      Co tento ukazatel ukazuje
                    </div>
                    <div className="text-sm font-semibold text-slate-900 leading-snug mt-1 line-clamp-3">
                      {seriesTitle}
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => setOpen(false)}
                    className="shrink-0 rounded-lg p-1.5 text-slate-500 hover:bg-white/80 hover:text-slate-800"
                    aria-label="Zavřít"
                  >
                    <X className="h-4 w-4" />
                  </button>
                </div>

                <div className="px-4 py-3 text-[13px] leading-relaxed text-slate-800 max-h-[min(60vh,420px)] overflow-y-auto">
                  {qualityHints?.stale ? (
                    <div className="mb-3 rounded-lg border border-amber-200 bg-amber-50/90 px-2.5 py-2 text-[12px] text-amber-950">
                      <p className="font-medium">{qualityHints.notice_cs}</p>
                      {qualityHints.detail_cs ? (
                        <p className="mt-1 text-amber-900/90">{qualityHints.detail_cs}</p>
                      ) : null}
                    </div>
                  ) : null}

                  {loading ? (
                    <div className="flex items-center gap-2 text-slate-600 py-1">
                      <Loader2 className="h-4 w-4 animate-spin text-violet-600" aria-hidden />
                      <span>AI připravuje vysvětlení…</span>
                    </div>
                  ) : error ? (
                    <p className="text-rose-800">{error}</p>
                  ) : data?.explanation_cz ? (
                    (() => {
                      const sourceNote = seriesConceptExplainSourceNote(data);
                      return (
                        <>
                          <p>{data.explanation_cz}</p>
                          {data.read_hint_cz ? (
                            <p className="mt-3 pt-3 border-t border-slate-200 text-[12px] text-slate-600">
                              <span className="font-medium text-slate-700">Jak číst graf: </span>
                              {data.read_hint_cz}
                            </p>
                          ) : null}
                          {sourceNote ? (
                            <p className="mt-2 text-[11px] text-slate-500">{sourceNote}</p>
                          ) : null}
                        </>
                      );
                    })()
                  ) : (
                    <p className="text-slate-600">Vysvětlení není k dispozici.</p>
                  )}

                  {!loading ? (
                    <div className="mt-4 pt-3 border-t border-violet-100 space-y-3">
                      <button
                        type="button"
                        onClick={() => void runRelated()}
                        disabled={relatedLoading}
                        className="inline-flex items-center gap-1.5 rounded-lg border border-violet-200/80 bg-violet-50/80 px-2.5 py-1.5 text-[12px] font-medium text-violet-800 hover:bg-violet-100 disabled:opacity-50"
                        data-testid="related-series-button"
                      >
                        {relatedLoading ? (
                          <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />
                        ) : (
                          <Network className="h-3.5 w-3.5" aria-hidden />
                        )}
                        {relatedLoading ? "Hledám příbuzné ukazatele…" : "Načíst příbuzné ukazatele"}
                      </button>

                      {relatedError ? <p className="text-[12px] text-rose-800">{relatedError}</p> : null}

                      {related ? (
                        related.items.length > 0 ? (
                          <div className="space-y-2">
                            <ul className="space-y-1.5">
                              {related.items.map((it, idx) => (
                                <li key={`${it.source}:${it.set_id}:${idx}`}>
                                  <button
                                    type="button"
                                    onClick={() => openCatalogSearch(it.title)}
                                    title={`Vyhledat v katalogu: ${it.title}`}
                                    className="w-full text-left rounded-lg border border-slate-200 bg-slate-50/70 px-2.5 py-1.5 transition hover:border-violet-300 hover:bg-violet-50 cursor-pointer"
                                    data-testid="related-series-item"
                                  >
                                    <div className="text-[12.5px] font-medium text-slate-800 leading-snug line-clamp-2">
                                      {it.title}
                                    </div>
                                    <div className="mt-0.5 flex items-center justify-between gap-2">
                                      <span className="text-[11px] text-slate-500">
                                        {it.source_label || (it.source || "").toUpperCase()}
                                      </span>
                                      <span className="inline-flex items-center gap-0.5 text-[11px] font-medium text-violet-700">
                                        <Search className="h-3 w-3" aria-hidden />
                                        Vyhledat
                                      </span>
                                    </div>
                                  </button>
                                </li>
                              ))}
                            </ul>
                          </div>
                        ) : (
                          <p className="text-[12px] text-slate-600">
                            {related.messageCs || "Žádné dostatečně relevantní příbuzné ukazatele jsme nenašli."}
                          </p>
                        )
                      ) : null}

                      <div className="pt-2 border-t border-violet-100 space-y-2">
                        <div className="text-[10px] font-semibold uppercase tracking-wide text-violet-800">
                          Zeptejte se dál
                        </div>
                        <div className="flex flex-wrap gap-1.5">
                          {suggestedQuestions.map((q) => (
                            <button
                              key={q}
                              type="button"
                              disabled={followupLoading}
                              onClick={() => void runFollowup(q)}
                              className="rounded-full border border-violet-200 bg-white px-2 py-0.5 text-[10px] font-medium text-violet-800 hover:bg-violet-50 disabled:opacity-50"
                            >
                              {q}
                            </button>
                          ))}
                        </div>
                        {followupThread.length ? (
                          <div className="space-y-2 max-h-36 overflow-y-auto">
                            {followupThread.map((turn, idx) => (
                              <div
                                key={`${turn.role}-${idx}`}
                                className={
                                  turn.role === "user"
                                    ? "rounded-lg bg-white border border-violet-100 px-2.5 py-1.5 text-[11px]"
                                    : "rounded-lg bg-violet-100/70 border border-violet-200/80 px-2.5 py-1.5 text-[11px]"
                                }
                              >
                                <span className="font-semibold text-violet-900">
                                  {turn.role === "user" ? "Vy: " : "AI: "}
                                </span>
                                {turn.content}
                              </div>
                            ))}
                          </div>
                        ) : null}
                        {followupError ? <p className="text-[11px] text-rose-800">{followupError}</p> : null}
                        {qualityHints?.suggested_search_query ? (
                          <button
                            type="button"
                            disabled={followupLoading}
                            onClick={() => openCatalogSearch(qualityHints.suggested_search_query)}
                            className="inline-flex items-center gap-1.5 rounded-lg border border-emerald-300 bg-emerald-50 px-2.5 py-1.5 text-[11px] font-medium text-emerald-900 hover:bg-emerald-100"
                          >
                            <Search className="h-3.5 w-3.5" aria-hidden />
                            Vyhledat aktuálnější řadu
                          </button>
                        ) : null}
                        <form
                          className="flex items-center gap-2"
                          onSubmit={(e) => {
                            e.preventDefault();
                            void runFollowup();
                          }}
                        >
                          <input
                            type="text"
                            value={followupQuestion}
                            onChange={(e) => setFollowupQuestion(e.target.value)}
                            placeholder="Proč data končí v roce 2017…"
                            className="min-w-0 flex-1 h-8 rounded-lg border border-violet-200 px-2.5 text-[12px]"
                            disabled={followupLoading}
                          />
                          <button
                            type="submit"
                            disabled={followupLoading || followupQuestion.trim().length < 3}
                            className="h-8 shrink-0 rounded-lg bg-violet-700 px-2.5 text-[11px] font-semibold text-white hover:bg-violet-800 disabled:opacity-50"
                          >
                            {followupLoading ? "…" : "Zeptat"}
                          </button>
                        </form>
                      </div>
                    </div>
                  ) : null}
                </div>
              </div>
            </div>,
            document.body,
          )
        : null}
    </>
  );
}

export function buildWidgetConceptExplainMeta({ widget, data, heading, unit, frequency }) {
  const cfg = widget?.config && typeof widget.config === "object" ? widget.config : {};
  const widgetEngine = String(widget?.engine_type || widget?.type || "").toLowerCase();
  let sourceType = String(cfg.catalog || cfg.source_type || "").trim().toLowerCase();
  if (!sourceType || sourceType === "external_catalog") {
    const match = String(data?.dataset || "").match(/^catalog:([^:]+)/i);
    if (match?.[1]) sourceType = match[1].toLowerCase();
  }
  if (!sourceType && widgetEngine.endsWith("_view")) {
    sourceType = widgetEngine.replace(/_view$/, "");
  }
  const sid = String(cfg.source_id || cfg.set_id || cfg.dataset_id || data?.dataset_id || "").trim();
  const indicator = String(
    cfg.selected_indicator || cfg.series_value || cfg.indicator || data?.indicator || "",
  ).trim();
  const base = {
    source_type: sourceType || undefined,
    catalog_id: sourceType || undefined,
    set_id: sid || undefined,
    dataset_id: sid || undefined,
    title: String(heading || data?.title || "Datová řada").trim(),
    name: String(heading || data?.title || "").trim() || undefined,
    unit: unit || data?.unit || cfg.unit,
    frequency: frequency || data?.frequency || cfg.frequency,
    selected_indicator_name: indicator || undefined,
    indicator_name: indicator || undefined,
    catalog_path: cfg.catalog_path || cfg.full_path || undefined,
    query_params: cfg.query_params || cfg.dimension_filters || undefined,
    country_label: cfg.country || cfg.geo || cfg.country_label || undefined,
  };
  const rows = Array.isArray(data?.rows) ? data.rows : [];
  const enriched = rows.length
    ? enrichChartExplainMeta(base, rows, {
        timeField: data?.x_field || data?.time_field,
        valueField: data?.y_field || data?.value_field,
      })
    : base;

  // Multi-series graf (napr. "Srovnat s...") - AI chat nad grafem se jinak vzdy ptal jen na
  // hlavni/vychozi radu, i kdyz se uzivatel zeptal na nazev pridane srovnavaci rady. Sem se
  // proto priklada identita KAZDE rady v grafu, aby si volajici (ChartAnalystTrigger) mohl podle
  // textu otazky vybrat spravnou radu misto hlavni.
  if (data?.multi_series && Array.isArray(data?.series) && data.series.length > 1) {
    const availableSeries = data.series
      .map((s) => {
        const label = String(s?.label || s?.name || "").trim();
        if (!label) return null;
        return {
          title: label,
          name: label,
          source_type: String(s?.source_type || s?.catalog || "").trim().toLowerCase() || undefined,
          set_id: String(s?.set_id || "").trim() || undefined,
          selected_indicator_name: String(s?.selected_indicator || "").trim() || undefined,
        };
      })
      .filter(Boolean);
    if (availableSeries.length > 1) {
      return { ...enriched, available_series: availableSeries };
    }
  }
  return enriched;
}

export function buildExploreChartConceptExplainMeta(series, rows) {
  if (!series || typeof series !== "object") return null;
  const title = String(series.name || "").trim();
  if (!title) return null;
  const base = {
    source_type: String(series.source || "").trim().toLowerCase() || undefined,
    catalog_id: String(series.source || "").trim().toLowerCase() || undefined,
    set_id: String(series.setId || "").trim() || undefined,
    dataset_id: String(series.setId || "").trim() || undefined,
    title,
    name: title,
    unit: series.unit || undefined,
    country_label: series.primaryCountryCode || series.geoDisplayLabel || undefined,
  };
  const chartRows = rows || series.rows;
  if (!Array.isArray(chartRows) || !chartRows.length) return base;
  return enrichChartExplainMeta(base, chartRows);
}
