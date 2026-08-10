import React from "react";
import { Sparkles, X as XIcon } from "lucide-react";
import { Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import AradView from "@/components/widgets/AradView";
import VoiceInputButton from "@/components/common/VoiceInputButton";
import ChatMarkdownText from "@/components/common/ChatMarkdownText";

import {
  deepAiDatabaseLabelCz,
  deepAiSourceStatusText,
  deepCandidateResultKey,
  deepCandidateSourceId,
} from "@/lib/catalogGlobalSearchHelpers";

export default function CatalogDeepSearchResultsPanel({
  deepData,
  deepVerifiedList,
  deepPossibleList,
  deepLowRelevanceList,
  deepDiscardedCandidatesList,
  deepSuggestedQueriesList,
  showPartialNoValidCandidates,
  singleAiSourceProblemBanner,
  showMultiAiIndexProblemNoHitsNote,
  showGenericAiIndexNoHitsMessage,
  deepSearchSourceSummaries,
  showDeepFollowupPanel,
  deepFollowupClarificationMode,
  deepClarificationPayload,
  followupInputRef,
  followupInput,
  deepLoading,
  deepSearchExcludedCatalogIds,
  deepSearchAllSourcesHidden,
  deepPrimaryChart,
  followupMessages,
  deepChatFilteredIds,
  followupChartPayload,
  followupComposedChartData,
  deepFollowupLoading,
  deepFollowupError,
  deepAiAnalysisDatasets,
  eurostatRetryableIndexStatus,
  deepStatusesForUi,
  topDeepRecommendations,
  groupedDeepVerified,
  showDeepLaneProvisional,
  deepLanePreviewEntries,
  groupedDeepPossible,
  renderCatalogSetBlock,
  CandidateResultCard,
  LoadingDots,
  onClarificationOption,
  onClarificationInputChange,
  onSubmitClarification,
  onDeepSearchSourceToggle,
  onDeepSearchSourceReset,
  onScrollToCandidates,
  onFollowupInputChange,
  onSubmitFollowupInput,
  onSubmitFollowupAction,
  onRetryDeepSearch,
  onStructuredNoAnswerOption,
  onSuggestedQuery,
  onClearChatFilter,
}) {
  const hasContent =
    deepData?.ok !== false &&
    deepData &&
    ((deepVerifiedList || []).length > 0 ||
      (deepPossibleList || []).length > 0 ||
      (deepLowRelevanceList || []).length > 0 ||
      (deepDiscardedCandidatesList || []).length > 0 ||
      (deepSuggestedQueriesList || []).length > 0 ||
      deepData.no_verified_row_message ||
      showPartialNoValidCandidates ||
      singleAiSourceProblemBanner ||
      showMultiAiIndexProblemNoHitsNote ||
      showGenericAiIndexNoHitsMessage);

  if (!hasContent) return null;

  const aiResultLayer = deepData?.ai_result_layer;
  const finalAnswer = deepData?.final_answer;
  const structuredNoAnswer = deepData?.structured_no_answer;
  // Filtr aktivovaný AI ekonomem ("zobraz jen eurostat" apod.)
  const chatFilterSet = Array.isArray(deepChatFilteredIds) && deepChatFilteredIds.length > 0
    ? new Set(deepChatFilteredIds)
    : null;
  const _filterByChatIds = (arr) =>
    chatFilterSet ? (arr || []).filter((c) => chatFilterSet.has(String(c?.set_id || c?.series_id || ""))) : (arr || []);
  const _filterGroupedByChatIds = (grouped) =>
    chatFilterSet
      ? (grouped || []).map(([key, rows]) => [key, _filterByChatIds(rows)]).filter(([, rows]) => rows.length > 0)
      : (grouped || []);

  return (
    <div className="space-y-4 pt-2">
      {(deepSearchSourceSummaries || []).length > 0 ||
      (showDeepFollowupPanel && deepFollowupClarificationMode) ? (
        <div
          className="rounded-xl border border-border/80 bg-card px-4 py-3 space-y-3"
          data-testid="catalog-deep-interaction-panel"
        >
          {showDeepFollowupPanel && deepFollowupClarificationMode ? (
            <div className="space-y-3" data-testid="catalog-deep-followup-panel">
              <div className="flex flex-wrap items-center gap-2 text-[11px]">
                <span className="font-semibold uppercase tracking-wide text-foreground/85">
                  Upřesněte dotaz
                </span>
              </div>
              <>
                <p className="text-sm text-foreground leading-snug">{deepClarificationPayload.message}</p>
                {deepClarificationPayload.options.length > 0 ? (
                  <div className="flex flex-wrap gap-2 pt-1" data-testid="catalog-deep-clarification-options">
                    {deepClarificationPayload.options.map((optText, i) => (
                      <button
                        key={i}
                        type="button"
                        className="inline-flex items-center rounded-lg border border-emerald-300/80 bg-emerald-50/70 px-3 py-1.5 text-xs font-medium text-emerald-900 hover:bg-emerald-100 canvas-dark:border-emerald-700/45 canvas-dark:bg-emerald-900/25 canvas-dark:text-emerald-100"
                        onClick={() => onClarificationOption(optText)}
                        disabled={deepLoading}
                      >
                        {optText}
                      </button>
                    ))}
                  </div>
                ) : null}
                <div className="flex flex-col sm:flex-row gap-2">
                  <input
                    ref={followupInputRef}
                    type="text"
                    className="h-9 px-3 border border-border rounded-xl text-sm bg-card flex-1"
                    placeholder="Nebo napište upřesnění (např. ROE bankovní sektor Česko)…"
                    value={followupInput}
                    onChange={(e) => onClarificationInputChange(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" && !e.shiftKey) {
                        e.preventDefault();
                        void onSubmitClarification(followupInput);
                      }
                    }}
                  />
                  <button
                    type="button"
                    className="h-9 px-4 rounded-xl border border-emerald-700/25 bg-emerald-50 hover:bg-emerald-100/90 text-emerald-950 text-sm"
                    disabled={deepLoading || String(followupInput || "").trim().length < 2}
                    onClick={() => void onSubmitClarification(followupInput)}
                  >
                    {deepLoading ? "Hledám…" : "Upřesnit a hledat"}
                  </button>
                </div>
              </>
            </div>
          ) : null}

          {showDeepFollowupPanel && deepFollowupClarificationMode && (deepSearchSourceSummaries || []).length > 0 ? (
            <div className="border-t border-border/60" aria-hidden="true" />
          ) : null}

          {(deepSearchSourceSummaries || []).length > 0 ? (
            <div className="space-y-2" data-testid="deep-search-source-chips">
              <div className="text-[11px] font-medium text-foreground/90">
                Zobrazované zdroje — kliknutím skryjete nebo znovu zapnete katalog ve výsledcích
              </div>
              <div className="flex flex-wrap gap-1.5 items-center">
                {deepSearchSourceSummaries.map((sum) => {
                  const id = String(sum?.id || "").trim();
                  const excluded = id ? deepSearchExcludedCatalogIds.has(id) : false;
                  return (
                    <button
                      key={`deep-src-summary-${id || "unknown"}`}
                      type="button"
                      onClick={() => onDeepSearchSourceToggle(id)}
                      className={`text-[10px] px-2 py-1 rounded-md border inline-flex items-center gap-1 ${
                        excluded
                          ? "border-border bg-muted/40 text-muted-foreground line-through"
                          : sum.hits > 0
                            ? "border-emerald-300/75 bg-emerald-50/80 text-emerald-900"
                            : sum.local_index_missing
                              ? "border-amber-300/80 bg-amber-50 text-amber-950"
                              : sum.upstream_unavailable
                                ? "border-rose-300/80 bg-rose-50 text-rose-900"
                                : "border-border bg-muted/40 text-muted-foreground"
                      }`}
                      title={
                        excluded
                          ? `${sum.label} je skrytý ve výsledcích — kliknutím znovu zobrazit`
                          : sum.message_cs ||
                            (sum.hits > 0
                              ? `${sum.label}: ${sum.hits} řad ve výsledcích — kliknutím skrýt`
                              : `${sum.label}: bez řad ve výsledcích — kliknutím skrýt štítek`)
                      }
                      data-testid={`deep-search-source-chip-${id || "unknown"}`}
                    >
                      <span>
                        {sum.label}
                        {sum.hits > 0 ? `: ${sum.hits}` : ""}
                      </span>
                      <XIcon className="h-2.5 w-2.5" />
                    </button>
                  );
                })}
                {deepSearchExcludedCatalogIds.size > 0 ? (
                  <button
                    type="button"
                    className="text-[10px] px-2 py-1 rounded-md border border-border bg-card hover:bg-muted/50 text-foreground"
                    onClick={onDeepSearchSourceReset}
                    data-testid="deep-search-source-chip-reset"
                  >
                    Zobrazit vše
                  </button>
                ) : null}
              </div>
              {deepSearchAllSourcesHidden ? (
                <p className="text-[11px] text-muted-foreground">
                  Všechny zdroje jsou právě skryté. Klikněte na „Zobrazit vše" nebo znovu zapněte některý štítek.
                </p>
              ) : null}
            </div>
          ) : null}
        </div>
      ) : null}

      {aiResultLayer &&
      typeof aiResultLayer === "object" &&
      String(aiResultLayer.answer_cz || "").trim() ? (
        <div
          className="rounded-2xl border border-emerald-200/80 canvas-dark:border-emerald-700/45 bg-gradient-to-br from-emerald-50/90 via-card to-card px-4 py-3.5 shadow-sm space-y-2"
          data-testid="catalog-deep-ai-result-layer"
        >
          <div className="text-[11px] font-semibold uppercase tracking-wider text-emerald-900 canvas-dark:text-emerald-100">
            {String(aiResultLayer.headline_cz || "Shrnutí výsledku")}
          </div>
          <ChatMarkdownText
            text={String(aiResultLayer.answer_cz || "").trim()}
            className="text-[13px] sm:text-sm text-foreground"
          />
          {String(aiResultLayer.next_steps_cz || "").trim() ? (
            <p className="text-[12px] text-muted-foreground leading-snug">
              {String(aiResultLayer.next_steps_cz).trim()}
            </p>
          ) : null}
          {(deepPossibleList || []).length > 0 ? (
            <button
              type="button"
              className="inline-flex items-center gap-1.5 text-[12px] font-medium text-emerald-900 underline underline-offset-2 hover:text-emerald-950"
              onClick={onScrollToCandidates}
            >
              Další návrhy (kandidáti) ↓
            </button>
          ) : null}
        </div>
      ) : null}

      {(String(deepData?.first_response_summary_cz || "").trim() ||
        (finalAnswer && typeof finalAnswer === "object")) ? (
        <div className="rounded-xl border border-sky-200/80 bg-sky-50/40 px-4 py-3 space-y-2">
          {deepPrimaryChart.rows.length > 0 ? (
            <div className="rounded-lg border border-border/70 bg-card px-2.5 py-2">
              <div className="text-[11px] font-medium text-foreground/85 mb-1.5 truncate" title={deepPrimaryChart.title}>
                {deepPrimaryChart.title || "Graf výsledku"}
              </div>
              <div className="h-52 w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={deepPrimaryChart.rows} margin={{ top: 6, right: 10, left: 0, bottom: 4 }}>
                    <XAxis dataKey="x" tick={{ fontSize: 11 }} interval="preserveStartEnd" />
                    <YAxis tick={{ fontSize: 11 }} />
                    <Tooltip />
                    <Line type="monotone" dataKey="y" stroke="hsl(160 55% 36%)" strokeWidth={2} dot={false} isAnimationActive={false} />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </div>
          ) : null}
          <div className="text-[11px] font-semibold uppercase tracking-wide text-sky-900">AI odpověď z dat</div>
          {String(deepData.first_response_summary_cz || "").trim() ? (
            <ChatMarkdownText
              text={String(deepData.first_response_summary_cz || "").trim()}
              className="text-sm text-foreground"
            />
          ) : null}
          {finalAnswer && typeof finalAnswer === "object" && String(finalAnswer.short_answer || "").trim() ? (
            <p className="text-sm text-foreground leading-snug">{String(finalAnswer.short_answer || "").trim()}</p>
          ) : null}
          {finalAnswer && typeof finalAnswer === "object" && finalAnswer.key_numbers && typeof finalAnswer.key_numbers === "object" ? (
            <div className="text-xs text-foreground/90">
              {typeof finalAnswer.key_numbers.requested_year === "number" &&
              typeof finalAnswer.key_numbers.requested_year_value === "number" ? (
                <div>
                  Rok {finalAnswer.key_numbers.requested_year}:{" "}
                  <span className="font-mono">{Number(finalAnswer.key_numbers.requested_year_value).toFixed(4)}</span>
                  {String(finalAnswer.key_numbers.unit || "").trim() ? ` ${String(finalAnswer.key_numbers.unit).trim()}` : ""}
                </div>
              ) : typeof finalAnswer.key_numbers.latest_value === "number" ? (
                <div>
                  Poslední hodnota:{" "}
                  <span className="font-mono">{Number(finalAnswer.key_numbers.latest_value).toFixed(4)}</span>
                  {String(finalAnswer.key_numbers.unit || "").trim() ? ` ${String(finalAnswer.key_numbers.unit).trim()}` : ""}
                </div>
              ) : null}
            </div>
          ) : null}
        </div>
      ) : null}

      {showDeepFollowupPanel && !deepFollowupClarificationMode ? (
        <div
          className="overflow-hidden rounded-2xl border border-sky-200 bg-white shadow-sm"
          data-testid="catalog-deep-followup-panel"
        >
          <div className="flex items-start justify-between gap-3 border-b border-sky-100 bg-sky-50 px-4 py-3">
            <div className="min-w-0">
              <div className="text-[10px] font-semibold uppercase tracking-wide text-sky-800">Datový asistent</div>
              <div className="mt-1 text-sm font-semibold leading-snug text-slate-900">
                Co chcete udělat s nalezenými řadami?
              </div>
            </div>
          </div>
          <div className="flex min-h-0 flex-col gap-3 px-4 py-3 text-[13px] text-slate-800">
          {(followupMessages || []).length > 0 ? (
            <div className="max-h-64 overflow-y-auto space-y-2">
              {followupMessages.map((m, idx) => {
                const isAssistant = String(m.role || "") === "assistant";
                const text = String(m.content || "").trim();
                if (!text) return null;
                return isAssistant ? (
                    <div key={String(m.id || idx)} className="flex items-start gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-2 text-xs text-slate-800">
                    <span className="shrink-0 font-semibold text-sky-800">AI:</span>
                    <ChatMarkdownText text={text} className="min-w-0 flex-1" />
                  </div>
                ) : (
                  <div key={String(m.id || idx)} className="ml-auto max-w-[88%] rounded-xl bg-sky-700 px-3 py-1.5 text-xs font-medium text-white">
                    {text}
                  </div>
                );
              })}
              {deepFollowupLoading ? (
                <div className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-xs text-slate-800">
                  <span className="font-semibold text-sky-800 mr-1.5">AI:</span>
                  <LoadingDots text="přemýšlí" />
                </div>
              ) : null}
            </div>
          ) : deepFollowupLoading ? (
            <div className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-xs text-slate-800">
              <span className="font-semibold text-sky-800 mr-1.5">AI:</span>
              <LoadingDots text="přemýšlí" />
            </div>
          ) : null}
          {followupChartPayload && typeof followupChartPayload === "object" ? (
            <div className="rounded-xl border border-sky-200 bg-white px-3 py-3 space-y-2 shadow-sm">
              <div className="text-xs font-semibold text-foreground/90">
                {String(followupChartPayload.title || "Složený graf")}
              </div>
              {followupComposedChartData.isMultiSeries && followupComposedChartData.aradData ? (
                <div className="h-[440px] min-h-[320px] w-full">
                  <AradView
                    data={followupComposedChartData.aradData}
                    widget={{ width: "full" }}
                  />
                </div>
              ) : (
                <div className="h-48 w-full">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart
                      data={followupComposedChartData.rows}
                      margin={{ left: 8, right: 12, top: 8, bottom: 8 }}
                    >
                      <XAxis dataKey="x" hide={false} tick={{ fontSize: 11 }} />
                      <YAxis tick={{ fontSize: 11 }} />
                      <Tooltip />
                      {followupComposedChartData.keys.map((k, i) => (
                        <Line key={`${k}-${i}`} type="monotone" dataKey={k} name={k} dot={false} strokeWidth={2} stroke={["#10b981", "#3b82f6", "#f59e0b", "#ef4444"][i % 4]} />
                      ))}
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              )}
            </div>
          ) : null}
          {deepData?.followup_research_result && typeof deepData.followup_research_result === "object" ? (
            <div className="space-y-2 rounded-xl border border-sky-100 bg-sky-50/50 px-3 py-2.5">
              {Array.isArray(deepData.followup_research_result.external_items) && deepData.followup_research_result.external_items.length ? (
                <div className="space-y-1.5">
                  <div className="text-[10px] font-semibold uppercase tracking-wide text-sky-900">Oficiální externí data</div>
                  {deepData.followup_research_result.external_items.slice(0, 6).map((item, idx) => (
                    <a
                      key={`${item?.url || "dataset"}-${idx}`}
                      href={String(item?.url || "")}
                      target="_blank"
                      rel="noreferrer noopener"
                      className="block rounded-lg border border-sky-100 bg-white px-2.5 py-2 hover:border-sky-300"
                    >
                      <div className="text-xs font-medium text-slate-900">{item?.title}</div>
                      <div className="mt-0.5 text-[10px] text-slate-600">{item?.publisher}{item?.format ? ` · ${item.format}` : ""}</div>
                    </a>
                  ))}
                </div>
              ) : null}
              {Array.isArray(deepData.followup_research_result.citations) && deepData.followup_research_result.citations.length ? (
                <div>
                  <div className="mb-1 text-[10px] font-semibold uppercase tracking-wide text-sky-900">Zdroje anotací</div>
                  <div className="flex flex-wrap gap-x-3 gap-y-1">
                    {deepData.followup_research_result.citations.slice(0, 8).map((citation, idx) => (
                      <a
                        key={`${citation?.url || "source"}-${idx}`}
                        href={String(citation?.url || "")}
                        target="_blank"
                        rel="noreferrer noopener"
                        className="max-w-full truncate text-[11px] font-medium text-sky-800 underline decoration-sky-300 underline-offset-2"
                      >
                        {citation?.title || citation?.publisher || citation?.url}
                      </a>
                    ))}
                  </div>
                </div>
              ) : null}
            </div>
          ) : null}
          {deepFollowupError ? <p className="text-xs text-rose-700">{deepFollowupError}</p> : null}
          <div className="flex flex-col gap-2 sm:flex-row">
            <div className="relative min-w-0 flex-1">
            <input
              ref={followupInputRef}
              type="text"
              className="h-11 w-full min-w-0 rounded-xl border border-sky-200 bg-white px-3 pr-12 text-sm outline-none focus:border-sky-500"
              placeholder="Zeptejte se nebo zadejte úpravu grafu..."
              value={followupInput}
              onChange={(e) => onFollowupInputChange(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  void onSubmitFollowupInput();
                }
              }}
            />
              <VoiceInputButton value={followupInput} onChange={onFollowupInputChange} disabled={deepFollowupLoading || deepLoading} className="absolute right-1.5 top-1/2 h-8 w-8 -translate-y-1/2" />
            </div>
            <button
              type="button"
              className="inline-flex h-11 min-w-[104px] items-center justify-center gap-1.5 rounded-xl bg-sky-700 px-4 text-sm font-semibold text-white hover:bg-sky-800 disabled:opacity-50"
              disabled={
                deepFollowupLoading ||
                deepLoading ||
                String(followupInput || "").trim().length < 2
              }
              onClick={onSubmitFollowupAction}
            >
              {deepLoading || deepFollowupLoading ? (
                <LoadingDots text="Zpracovávám" />
              ) : (
                <>
                  <Sparkles className="h-3.5 w-3.5" />
                  Zeptat
                </>
              )}
            </button>
          </div>
          </div>
        </div>
      ) : null}

      {(deepAiAnalysisDatasets || []).length > 0 ? (
        <div className="rounded-xl border border-border/80 bg-card px-4 py-3 space-y-2">
          <div className="text-[11px] font-semibold uppercase tracking-wide text-foreground/85">Použité datové řady</div>
          {deepAiAnalysisDatasets.map((ds, idx) => {
            const row = ds && typeof ds === "object" ? ds : {};
            const values = Array.isArray(row.values) ? row.values.filter((v) => v && typeof v === "object") : [];
            return (
              <div key={`${String(row.series_id || row.dataset_id || "series")}-${idx}`} className="rounded-lg border border-border/70 bg-muted/15 px-3 py-2 space-y-1 text-xs">
                <div><strong>Zdroj:</strong> {row.source_name || "—"}</div>
                <div><strong>Dataset:</strong> {row.dataset_name || "—"}</div>
                <div><strong>ID datasetu:</strong> {row.dataset_id || "—"}</div>
                <div><strong>ID řady:</strong> {row.series_id || "—"}</div>
                <div><strong>Ukazatel:</strong> {row.indicator || "—"}</div>
                <div><strong>Země:</strong> {row.country || "—"}</div>
                <div><strong>Jednotka:</strong> {row.unit || "—"}</div>
                <div><strong>Frekvence:</strong> {row.frequency || "—"}</div>
                <div><strong>Období:</strong> {row.time_range || "—"}</div>
                <div>
                  <strong>Odkaz na zdroj:</strong>{" "}
                  {row.source_url ? (
                    <a
                      href={row.source_url}
                      target="_blank"
                      rel="noreferrer"
                      className="underline text-emerald-900"
                    >
                      {row.source_url}
                    </a>
                  ) : "—"}
                </div>

                <details className="mt-1">
                  <summary className="cursor-pointer select-none text-[11px] font-medium text-emerald-900">
                    Zobrazit hodnoty datové řady
                  </summary>
                  <div className="mt-2 overflow-x-auto">
                    <table className="w-full text-left text-[11px] border-collapse">
                      <thead>
                        <tr className="border-b border-border/60">
                          <th className="py-1 pr-3 font-semibold">date</th>
                          <th className="py-1 pr-3 font-semibold">value</th>
                        </tr>
                      </thead>
                      <tbody>
                        {values.length === 0 ? (
                          <tr>
                            <td className="py-1 text-muted-foreground" colSpan={2}>Žádné hodnoty.</td>
                          </tr>
                        ) : (
                          values.map((v, vIdx) => (
                            <tr key={`${idx}-${vIdx}`} className="border-b border-border/30">
                              <td className="py-1 pr-3">{String(v.date || "")}</td>
                              <td className="py-1 pr-3">{v.value ?? ""}</td>
                            </tr>
                          ))
                        )}
                      </tbody>
                    </table>
                  </div>
                </details>
              </div>
            );
          })}
        </div>
      ) : null}

      {deepData?.status === "no_valid_result" ? (
        <div className="rounded-xl border border-amber-300/80 bg-amber-50 px-4 py-3 space-y-2 text-sm text-amber-950">
          <div className="text-[11px] font-semibold uppercase tracking-wide">
            Pro požadovanou zemi není dostupná vhodná řada
          </div>
          <p>{String(deepData.message_cs || deepData.message || "Nepodařilo se najít vhodnou datovou řadu pro požadovanou zemi ve vybraných zdrojích.")}</p>
          {eurostatRetryableIndexStatus ? (
            <button
              type="button"
              className="inline-flex items-center rounded-lg border border-amber-300/80 px-3 py-1.5 text-xs font-medium bg-amber-100/70 hover:bg-amber-100"
              onClick={() => void onRetryDeepSearch()}
            >
              Zkusit Eurostat znovu
            </button>
          ) : null}
          {(deepStatusesForUi || []).length > 0 ? (
            <details className="text-[11px]">
              <summary className="cursor-pointer select-none font-medium">Stav zdrojů</summary>
              <div className="mt-2 space-y-1">
                {deepStatusesForUi.map((st) => (
                  <div key={`no-valid-${String(st.source || st.label || "")}`}>
                    <span className="font-semibold">{st.label || deepAiDatabaseLabelCz(st.source)}:</span>{" "}
                    <span>{deepAiSourceStatusText(st)}</span>
                    {String(st.message_cs || st.message || "").trim() ? (
                      <span className="text-amber-900/80"> — {String(st.message_cs || st.message).trim()}</span>
                    ) : null}
                  </div>
                ))}
              </div>
            </details>
          ) : null}
        </div>
      ) : null}

      {showPartialNoValidCandidates ? (
        <div className="rounded-xl border border-amber-300/80 bg-amber-50 px-4 py-3 space-y-2 text-sm text-amber-950">
          <div className="text-[11px] font-semibold uppercase tracking-wide">
            Průběžně zatím bez validních kandidátů
          </div>
          <p>
            Vyhledávání skončilo dříve, než dorazily finální výsledky. Relevantní kandidáti pro požadovanou zemi
            nebyli zatím potvrzeni.
          </p>
          <p className="text-[12px] text-amber-900/90">
            Průběžně zatím nebyla nalezena validní datová řada pro požadovanou zemi.
          </p>
          <button
            type="button"
            className="inline-flex items-center rounded-lg border border-amber-300/80 px-3 py-1.5 text-xs font-medium bg-amber-100/70 hover:bg-amber-100"
            onClick={() => void onRetryDeepSearch()}
          >
            Zkusit znovu
          </button>
        </div>
      ) : null}

      {structuredNoAnswer &&
      typeof structuredNoAnswer === "object" &&
      String(structuredNoAnswer.code || "").trim() !== "needs_clarification" &&
      String(structuredNoAnswer.message || structuredNoAnswer.message_cs || "").trim() ? (
        <div className="rounded-xl border border-border/80 bg-muted/30 px-4 py-3 space-y-2">
          <div className="text-[11px] font-semibold uppercase tracking-wide text-foreground/85">
            {String(structuredNoAnswer.code || "no_answer").replace(/_/g, " ")}
          </div>
          <p className="text-sm text-foreground leading-snug">
            {String(structuredNoAnswer.message || structuredNoAnswer.message_cs || "").trim()}
          </p>
          {String(structuredNoAnswer.message_en || "").trim() ? (
            <p className="text-[12px] text-muted-foreground leading-snug">
              {String(structuredNoAnswer.message_en).trim()}
            </p>
          ) : null}
          {Array.isArray(structuredNoAnswer.options_cz) &&
          structuredNoAnswer.options_cz.length ? (
            <div className="flex flex-wrap gap-2 pt-1" data-testid="catalog-deep-clarification-options">
              {structuredNoAnswer.options_cz.map((opt, i) => {
                const optText = String(opt || "").trim();
                if (optText.length < 2) return null;
                return (
                  <button
                    key={i}
                    type="button"
                    className="inline-flex items-center rounded-lg border border-emerald-300/80 bg-emerald-50/70 px-3 py-1.5 text-xs font-medium text-emerald-900 hover:bg-emerald-100 canvas-dark:border-emerald-700/45 canvas-dark:bg-emerald-900/25 canvas-dark:text-emerald-100"
                    onClick={() => onStructuredNoAnswerOption(optText)}
                  >
                    {optText}
                  </button>
                );
              })}
            </div>
          ) : null}
        </div>
      ) : null}

      {chatFilterSet ? (
        <div className="flex items-center gap-2 rounded-lg border border-sky-200 bg-sky-50/60 px-3 py-2 text-xs text-sky-900">
          <span className="font-semibold">AI filtr aktivní</span>
          <span className="text-sky-700">— zobrazuji {chatFilterSet.size} {chatFilterSet.size === 1 ? "řadu" : chatFilterSet.size < 5 ? "řady" : "řad"} vybraných ekonomem</span>
          <button
            type="button"
            className="ml-auto rounded border border-sky-300 px-2 py-0.5 hover:bg-sky-100"
            onClick={onClearChatFilter}
          >
            Zobrazit vše
          </button>
        </div>
      ) : null}

      {_filterByChatIds(topDeepRecommendations).length > 0 ? (
        <div className="space-y-3 rounded-xl border border-emerald-200/80 bg-emerald-50/35 px-4 py-3">
          <div>
            <div className="text-xs font-semibold uppercase tracking-wider text-emerald-900">
              Top doporučené řady
            </div>
            <p className="text-[11px] text-muted-foreground mt-0.5">
              Klikněte na náhled nebo přidání přímo u řady — stejně jako u výsledků níže.
            </p>
          </div>
          <div className="catalog-search-hits-grid">
            {_filterByChatIds(topDeepRecommendations).map((cand, idx) => (
              <CandidateResultCard
                key={`deep-top-${deepCandidateResultKey(cand)}-${idx}`}
                cand={cand}
                idx={idx}
                showRank
                renderCatalogSetBlock={renderCatalogSetBlock}
                keyPrefix="deep-top"
              />
            ))}
          </div>
        </div>
      ) : null}

      {_filterGroupedByChatIds(groupedDeepVerified).length > 0 ? (
        <div className="space-y-4">
          <div className="text-xs font-semibold uppercase tracking-wider text-emerald-800 canvas-dark:text-emerald-100">
            Ověřené řady — náhled potvrzen (podle databáze)
          </div>
          {_filterGroupedByChatIds(groupedDeepVerified).map(([catalogKey, rows]) => (
            <div key={`deep-vgrp-${catalogKey}`} className="space-y-2">
              <div className="text-[11px] font-semibold text-foreground/90 border-b border-emerald-100/90 pb-1">
                {deepAiDatabaseLabelCz(catalogKey)}
              </div>
              <div className="catalog-search-hits-grid">
                {rows.map((cand, idx) => (
                  <CandidateResultCard
                    key={`deep-v-${deepCandidateResultKey(cand)}-${idx}`}
                    cand={cand}
                    idx={idx}
                    variant="verified"
                    renderCatalogSetBlock={renderCatalogSetBlock}
                    keyPrefix="deep-v"
                  />
                ))}
              </div>
            </div>
          ))}
        </div>
      ) : null}

      {showDeepLaneProvisional ? (
        <div className="space-y-4" data-testid="deep-search-lane-provisional">
          <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-sky-900">
            <LoadingDots text="Načítám další zdroje" />
          </div>
          {(deepLanePreviewEntries || []).map((lane) => (
            <div key={`deep-lane-${lane.source}`} className="space-y-2" data-testid={`deep-search-lane-preview-${lane.source}`}>
              <div className="flex items-center gap-2 text-[11px] font-semibold text-foreground/70 border-b border-sky-200/60 pb-1">
                <span className="inline-flex items-center rounded-md bg-sky-100 px-2 py-0.5 text-sky-900">
                  {deepAiDatabaseLabelCz(lane.source)}
                </span>
                <span className="text-muted-foreground font-normal">{lane.count} kandidátů</span>
              </div>
              <div className="space-y-1.5">
                {lane.rows.map((row, idx) => {
                  const title = String(row.name || row.title || row.set_id || "").trim();
                  const path = String(row.full_path || "").trim();
                  if (!title) return null;
                  return (
                    <div key={`lane-${lane.source}-${row.set_id || idx}`} className="rounded-md border border-border/50 bg-background px-3 py-2 text-xs">
                      <div className="font-medium text-foreground truncate">{title}</div>
                      {path ? <div className="text-muted-foreground truncate mt-0.5">{path}</div> : null}
                    </div>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      ) : null}

      {_filterGroupedByChatIds(groupedDeepPossible).length > 0 ? (
        <details id="catalog-deep-candidates-section" className="scroll-mt-4" open>
          <summary className="cursor-pointer select-none text-xs font-semibold uppercase tracking-wider text-amber-900 mb-3">
            Další nalezené řady dle katalogu ({_filterGroupedByChatIds(groupedDeepPossible).reduce((s, [, r]) => s + r.length, 0)})
          </summary>
          <div className="space-y-4">
          {_filterGroupedByChatIds(groupedDeepPossible).map(([catalogKey, rows]) => (
            <div key={`deep-ugrp-${catalogKey}`} className="space-y-2">
              <div className="text-[11px] font-semibold text-foreground/90 border-b border-amber-300/70 canvas-dark:border-amber-600/50 pb-1">
                {deepAiDatabaseLabelCz(catalogKey)}
              </div>
              <div className="catalog-search-hits-grid">
                {rows.map((cand, idx) => (
                  <CandidateResultCard
                    key={`deep-u-${deepCandidateResultKey(cand)}-${idx}`}
                    cand={cand}
                    idx={idx}
                    variant="possible"
                    renderCatalogSetBlock={renderCatalogSetBlock}
                    keyPrefix="deep-u"
                  />
                ))}
              </div>
            </div>
          ))}
          </div>
        </details>
      ) : null}

      {(deepLowRelevanceList || []).length > 0 ? (
        <details className="rounded-xl border border-slate-200 bg-slate-50/70 px-4 py-3 text-xs text-slate-800">
          <summary className="cursor-pointer select-none font-semibold uppercase tracking-wide">
            Vyřazené / nízká relevance ({deepLowRelevanceList.length})
          </summary>
          <div className="mt-2 catalog-search-hits-grid">
            {deepLowRelevanceList.map((cand, idx) => (
              <CandidateResultCard
                key={`deep-low-${deepCandidateSourceId(cand)}-${String(cand.set_id || cand.series_id || idx)}`}
                cand={cand}
                idx={idx}
                variant="candidate"
                renderCatalogSetBlock={renderCatalogSetBlock}
                keyPrefix="deep-low"
              />
            ))}
          </div>
        </details>
      ) : null}

      {(deepDiscardedCandidatesList || []).length > 0 ? (
        <details className="rounded-xl border border-amber-200 bg-amber-50/70 px-4 py-3 text-xs text-amber-950">
          <summary className="cursor-pointer select-none font-semibold uppercase tracking-wide">
            Skrytí kandidáti z indexu ({deepDiscardedCandidatesList.length})
          </summary>
          <p className="mt-1 text-[11px] text-amber-900/90">
            Tyto řady index našel, ale finální filtrování nebo AI ověření je nezařadilo mezi hlavní výsledky.
          </p>
          <div className="mt-2 catalog-search-hits-grid">
            {deepDiscardedCandidatesList.map((cand, idx) => (
              <CandidateResultCard
                key={`deep-discarded-${deepCandidateSourceId(cand)}-${String(cand.set_id || cand.series_id || idx)}`}
                cand={cand}
                idx={idx}
                variant="candidate"
                renderCatalogSetBlock={renderCatalogSetBlock}
                keyPrefix="deep-discarded"
              />
            ))}
          </div>
        </details>
      ) : null}

      {(deepSuggestedQueriesList || []).length ? (
        <div className="rounded-xl border border-border bg-muted/32 px-4 py-3 text-sm text-foreground">
          <span className="font-medium">Doporučený další dotaz:</span>
          <ul className="list-disc pl-5 mt-2 space-y-1 text-foreground/90">
            {deepSuggestedQueriesList.map((s, i) => (
              <li key={i}>
                <button
                  type="button"
                  className="underline underline-offset-2 hover:text-emerald-900"
                  onClick={() => onSuggestedQuery(s)}
                >
                  {s}
                </button>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
