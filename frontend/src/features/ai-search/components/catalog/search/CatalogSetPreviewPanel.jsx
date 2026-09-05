import { ChevronDown, Download, Play, RefreshCw, Sparkles, X as XIcon } from "lucide-react";
import CatalogLiveChartPreview from "@/components/catalog/search/CatalogLiveChartPreview";
import { DataLoadInline } from "@/components/ui/DataLoadIndicator";
import { resolveImfFreqOptions, imfFreqLabel } from "@/lib/imfCatalogFreq";
import { normalizeSelectedIndicators } from "@/lib/catalogGlobalSearchHelpers";
import { canRenderAradCatalogChart } from "@/lib/mapCatalogPreviewToArad";
import { formatPreviewMessage } from "@/lib/previewNormalizer";
import { EurostatDeepAiTechnicalDetails } from "@/features/ai-search/pages/globalCatalogSearchDeepResultUi";

export default function CatalogSetPreviewPanel({
  isOpen,
  isEmbeddedPreview,
  isPreviewOverlay,
  isDetailPanel,
  isCompactFlat,
  isDeepSearch,
  previewData,
  previewFallbackNotice,
  isImfPreviewRow,
  displayRow,
  row,
  def,
  previewLoading,
  setImfPreviewFreq,
  fetchPreview,
  imfFreqActive,
  previewState,
  syncingPreview,
  sourceIsRunning,
  sourceIsQueued,
  previewSource,
  previewError,
  canShowSyncButton,
  syncAndReloadPreview,
  isSubscriber,
  addSeriesToPersonalDashboard,
  addingToDash,
  personalDashLoading,
  saveWidgetLoading,
  canPersonalDashboard,
  canSaveWidget,
  canExportData,
  personalDashMsg,
  saveWidgetMsg,
  downloadOpen,
  setDownloadOpen,
  downloadingFmt,
  exportFeLoading,
  exportDataLockMsg,
  downloadCurrent,
  closePreview,
  catalogRowTitle,
  previewErrorForSourcePreview,
  seriesDetailOpen,
  catalogChartExpanded,
  handleSeriesDetailChartDisplayState,
  previewCompareList,
  setPreviewCompareList,
  previewSelectedIndicators,
  extractGeoValuesFromDimensionFilters,
  setPreviewSelectedIndicators,
  allowGroupMultiSelection,
  setUseAiAssistant,
  setAiQuery,
  applySuggestedDeepSearch,
  catalogAiSectionRef,
  eurostatDeepAiPreviewErrorSanitized,
  previewable,
}) {
  const previewErrorText = formatPreviewMessage(previewError);
  const previewErrorForSourcePreviewText =
    formatPreviewMessage(previewErrorForSourcePreview) || previewErrorText;
  const previewRows = Array.isArray(previewData?.rows) ? previewData.rows : [];
  const previewHasRows = previewRows.length > 0;
  // Data are authoritative: stale/global preview errors must not replace valid rows.
  const blockingPreviewErrorText = previewHasRows ? "" : previewErrorText;
  const blockingPreviewErrorForSourcePreviewText = previewHasRows
    ? ""
    : previewErrorForSourcePreviewText;
  const previewForChart = blockingPreviewErrorText && isOpen
    ? {
        error: blockingPreviewErrorForSourcePreviewText,
        source: { name: row.name, source_type: def.sourceType },
      }
    : previewData
      ? { ...previewData, source: { name: row.name, source_type: def.sourceType } }
      : { source: { name: row.name, source_type: def.sourceType } };
  const previewCanRenderChart = canRenderAradCatalogChart(
    previewData,
    blockingPreviewErrorText,
    def?.sourceType,
  );
  const previewHasUsableRows =
    previewCanRenderChart ||
    (previewHasRows && !blockingPreviewErrorText);

  return (
isOpen && isEmbeddedPreview && (
          <div
            className={
              isPreviewOverlay
                ? "min-w-0"
                : isDetailPanel
                ? "min-w-0"
                : isCompactFlat || isDeepSearch
                  ? "px-2 pb-2 pt-1 border-t border-border/60"
                  : "px-4 pb-4 pt-1 bg-muted/30 border-t border-border/60"
            }
          >
            {(previewData?.imf_catalog_notice ||
              previewData?.data360_preview_notice ||
              previewData?.oecd_preview_notice) ? (
              <div className="mb-2.5 rounded-lg border border-amber-300/70 canvas-dark:border-amber-600/50 bg-amber-50 canvas-dark:bg-amber-950/35 px-3 py-2 text-[11px] text-amber-950 canvas-dark:text-amber-50 leading-snug">
                {previewData.imf_catalog_notice ||
                  previewData.data360_preview_notice ||
                  previewData.oecd_preview_notice}
              </div>
            ) : null}
            {previewFallbackNotice ? (
              <div className="mb-2.5 rounded-lg border border-blue-300/70 bg-blue-50 px-3 py-2 text-[11px] text-blue-900 leading-snug">
                {previewFallbackNotice}
              </div>
            ) : null}
            {isImfPreviewRow && (!isDetailPanel || isPreviewOverlay) ? (() => {
              const imfFreqOptions = resolveImfFreqOptions({
                previewData: isOpen ? previewData : null,
                row: displayRow || row,
              });
              if (imfFreqOptions.length <= 1) return null;
              return (
              <div
                className="mb-2.5 flex flex-wrap items-center gap-2"
                data-testid="imf-freq-switch"
                onClick={(e) => e.stopPropagation()}
              >
                <span className="text-[11px] text-muted-foreground shrink-0">Frekvence:</span>
                {imfFreqOptions.map((opt) => (
                  <button
                    key={opt.frekvence}
                    type="button"
                    disabled={previewLoading}
                    onClick={() => {
                      const freq = opt.frekvence;
                      setImfPreviewFreq(freq);
                      const rowWithFreq = {
                        ...displayRow,
                        frekvence: freq,
                        query_params: {
                          ...(displayRow?.query_params &&
                          typeof displayRow.query_params === "object"
                            ? displayRow.query_params
                            : {}),
                          imf_frekvence: freq,
                        },
                      };
                      const ind = String(
                        previewData?.selected_indicator || displayRow?.imf_indicator || "",
                      ).trim();
                      void fetchPreview(def, rowWithFreq, ind || undefined);
                    }}
                    className={`px-2.5 h-7 text-xs rounded-lg border transition-colors ${
                      imfFreqActive === opt.frekvence
                        ? "border-teal-500 bg-teal-50 text-teal-950 font-medium"
                        : "border-border bg-card hover:bg-muted/60 text-foreground"
                    }`}
                    data-testid={`imf-freq-${opt.frekvence}`}
                  >
                    {opt.frekvence_label}
                  </button>
                ))}
              </div>
              );
            })() : null}
            {def.sourceType === "world_bank_data360" && previewData?.data360_indicator_info ? (
              <div className="mb-2.5 rounded-lg border border-emerald-200/80 canvas-dark:border-emerald-700/50 bg-emerald-50/90 canvas-dark:bg-emerald-950/35 px-3 py-2.5 text-[11px] text-emerald-950 canvas-dark:text-emerald-50 leading-snug space-y-1">
                <div className="font-semibold text-[12px]">
                  {previewData.data360_indicator_info.name || previewData.title || "Ukazatel"}
                </div>
                {previewData.data360_indicator_info.subtitle ? (
                  <div className="text-foreground/90">{previewData.data360_indicator_info.subtitle}</div>
                ) : null}
                {previewData.data360_indicator_info.summary ? (
                  <p className="text-muted-foreground">{previewData.data360_indicator_info.summary}</p>
                ) : null}
                {previewData.data360_indicator_info.methodology_url ? (
                  <a
                    href={previewData.data360_indicator_info.methodology_url}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-block text-emerald-800 canvas-dark:text-emerald-200 underline underline-offset-2"
                    onClick={(e) => e.stopPropagation()}
                  >
                    Metodologie (World Bank)
                  </a>
                ) : null}
              </div>
            ) : null}
            {!isDetailPanel ? (
            <div className="flex items-center justify-between gap-2 flex-wrap mb-2.5">
              <div className="text-[11px] text-muted-foreground font-mono min-h-[1.25rem] flex items-center">
                {previewLoading ? (
                  <DataLoadInline label="Načítám data ze zdroje…" />
                ) : syncingPreview ? (
                  <DataLoadInline label="Synchronizuji data automaticky…" />
                ) : sourceIsRunning && !syncingPreview ? (
                  <DataLoadInline label="U tohoto zdroje právě probíhá synchronizace (na serveru)…" />
                ) : sourceIsQueued && !syncingPreview ? (
                  <span className="font-sans text-muted-foreground">
                    {previewSource?.last_sync_message || "Synchronizace čeká ve frontě po OECD rate limitu…"}
                  </span>
                ) : blockingPreviewErrorText && isOpen ? (
                  <span className="font-sans text-muted-foreground">Náhled se nepodařilo načíst — zpráva níže v rámečku.</span>
                ) : previewState === "rate_limited" ? (
                  <span className="font-sans text-muted-foreground">
                    {previewData?.message || "OECD API dočasně omezuje počet dotazů. Data zatím nebyla stažena."}
                  </span>
                ) : previewState === "not_synced" ? (
                  <span className="font-sans text-muted-foreground">
                    {previewData?.message || "Tato řada ještě nebyla synchronizována."}
                  </span>
                ) : previewState === "sync_failed" ? (
                  <span className="font-sans text-muted-foreground">
                    {previewData?.message || "Synchronizace zdroje selhala."}
                  </span>
                ) : previewState === "synced_empty" || previewState === "no_data" ? (
                  <span className="font-sans text-muted-foreground">
                    {previewData?.message || "Zdroj momentálně neobsahuje žádné hodnoty."}
                  </span>
                ) : previewData ? (
                  <span className="font-sans">
                    {`Načteno ${previewData.total_count ?? previewData.rows?.length ?? 0} záznamů${previewData.truncated ? ` (zobrazeno ${previewData.rows?.length || 0})` : ""}`}
                    {/* Data přišla, ale ne v plném rozsahu (např. ČSÚ nevrátil celý dataset, tak
                        se vzal jen poslední výběr bez historie). Bez téhle věty uživatel vidí
                        graf o jednom bodu a nemá jak poznat proč. */}
                    {previewData.message ? (
                      <span className="text-amber-700 canvas-dark:text-amber-400"> · {previewData.message}</span>
                    ) : null}
                  </span>
                ) : (
                  ""
                )}
              </div>
              <div className="flex items-center gap-1.5">
                {canShowSyncButton && (
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      syncAndReloadPreview(def, displayRow);
                    }}
                    className="flex items-center gap-1.5 h-7 px-3 text-xs border border-border/70 rounded-lg bg-card hover:bg-muted/50"
                    title={previewSource ? "Znovu spustit synchronizaci zdroje" : "Přidat zdroj a spustit synchronizaci"}
                  >
                    <Play className="h-3 w-3" />
                    Synchronizovat
                  </button>
                )}
                {isSubscriber && (
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      addSeriesToPersonalDashboard(def, displayRow);
                    }}
                    disabled={
                      addingToDash ||
                      personalDashLoading ||
                      saveWidgetLoading ||
                      !canPersonalDashboard ||
                      !canSaveWidget ||
                      previewLoading ||
                      !!blockingPreviewErrorText ||
                      !previewData ||
                      !previewCanRenderChart ||
                      syncingPreview
                    }
                    className="flex items-center gap-1.5 h-7 px-3 text-xs border border-[hsl(var(--primary)/0.45)] rounded-lg bg-[hsl(var(--primary-soft))] text-[hsl(var(--primary-deep))] hover:opacity-95 disabled:opacity-50"
                    title={
                      !canPersonalDashboard
                        ? personalDashMsg || "Osobní dashboard není k dispozici"
                        : !canSaveWidget
                          ? saveWidgetMsg || "Uložení widgetů není k dispozici"
                          : "Přidat tento graf z katalogu na váš osobní dashboard (bez globálního zdroje)"
                    }
                  >
                    {addingToDash ? (
                      <RefreshCw className="h-3 w-3 animate-spin" />
                    ) : (
                      <Sparkles className="h-3 w-3" />
                    )}
                    Na můj dashboard
                  </button>
                )}
                <div className="relative">
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      setDownloadOpen((v) => !v);
                    }}
                    disabled={
                      previewLoading ||
                      !!blockingPreviewErrorText ||
                      Boolean(downloadingFmt) ||
                      syncingPreview ||
                      exportFeLoading ||
                      !previewHasUsableRows ||
                      !canExportData
                    }
                    className="flex items-center gap-1.5 h-7 px-3 text-xs border border-border/70 rounded-lg bg-card hover:bg-muted/50 disabled:opacity-50"
                    title={
                      exportFeLoading
                        ? exportDataLockMsg || "Kontrola oprávnění…"
                        : !canExportData
                          ? exportDataLockMsg || "Stahování není k dispozici"
                          : "Stáhnout data"
                    }
                  >
                    {downloadingFmt ? (
                      <RefreshCw className="h-3 w-3 animate-spin" />
                    ) : (
                      <Download className="h-3 w-3" />
                    )}
                    Stáhnout
                    <ChevronDown className="h-3 w-3" />
                  </button>
                  {downloadOpen && (
                    <div
                      className="absolute right-0 top-8 z-30 w-44 rounded-md border border-border/80 bg-card shadow-xl py-1 text-xs"
                      onClick={(e) => e.stopPropagation()}
                    >
                      {[
                        { fmt: "csv", label: "CSV (.csv)" },
                        { fmt: "xlsx", label: "Excel (.xlsx)" },
                        { fmt: "json", label: "JSON (.json)" },
                      ].map((opt) => (
                        <button
                          key={opt.fmt}
                          type="button"
                          onClick={() => downloadCurrent(opt.fmt, def, displayRow)}
                          className="block w-full text-left px-3 py-1.5 hover:bg-muted/50"
                        >
                          {opt.label}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
                {!isPreviewOverlay ? (
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    closePreview();
                  }}
                  className="h-7 w-7 inline-flex items-center justify-center rounded-lg border border-border/70 bg-card hover:bg-muted/50"
                  title="Zavřít náhled"
                  aria-label="Zavřít náhled"
                >
                  <XIcon className="h-3.5 w-3.5" />
                </button>
                ) : null}
              </div>
            </div>
            ) : null}
            <CatalogLiveChartPreview
              widgetId={`catalog-preview-${def.id}-${String(displayRow?.set_id || row?.set_id || "row")}`}
              title={catalogRowTitle}
              sourceType={def.sourceType}
              catalogDef={def}
              catalogRow={displayRow || row}
              preferAradView
              preview={previewForChart}
              previewError={blockingPreviewErrorText && isOpen ? blockingPreviewErrorForSourcePreviewText : ""}
              previewLoading={previewLoading}
              catalogChartSize={
                isPreviewOverlay
                  ? "fullscreen"
                  : isDetailPanel && seriesDetailOpen
                    ? catalogChartExpanded
                      ? "detail-expanded"
                      : "detail"
                    : undefined
              }
              controlsInOptionsPanel
              onChartDisplayStateChange={
                isDetailPanel && seriesDetailOpen ? handleSeriesDetailChartDisplayState : undefined
              }
              actions={{
                compareList: previewCompareList,
                onCompareSave: async (payload) => {
                  const compareIds = (payload?.chart_compare_with || [])
                    .map((c) => String(c?.selected_indicator || "").trim())
                    .filter(Boolean);
                  setPreviewCompareList(Array.isArray(payload?.chart_compare_with) ? payload.chart_compare_with : []);
                  const main = String(
                    previewData?.selected_indicator ||
                      previewSelectedIndicators[0] ||
                      displayRow?.indicator_id ||
                      ""
                  ).trim();
                  const all = [...new Set([main, ...compareIds].filter(Boolean))];
                  const geo =
                    extractGeoValuesFromDimensionFilters(previewData?.metadata?.filters_applied).length > 0
                      ? extractGeoValuesFromDimensionFilters(previewData?.metadata?.filters_applied)
                      : extractGeoValuesFromDimensionFilters(previewData?.requested_filters);
                  setPreviewSelectedIndicators(all);
                  const dimFilters =
                    previewData?.dimension_filters || previewData?.metadata?.filters_applied || null;
                  await fetchPreview(def, displayRow, main, all, geo, dimFilters);
                },
              }}
              sourcePreviewProps={{
                catalogChartActions: {
                  show:
                    previewable &&
                    isOpen &&
                    !blockingPreviewErrorText &&
                    (previewLoading || Boolean(previewData?.rows?.length)),
                  canSync:
                    canShowSyncButton &&
                    !(isDetailPanel && seriesDetailOpen && isSubscriber && canPersonalDashboard && canSaveWidget),
                  onSync: () => syncAndReloadPreview(def, displayRow),
                  syncing: syncingPreview,
                  syncTitle: previewSource
                    ? "Znovu synchronizovat uložený zdroj v systému"
                    : "Přidat řadu jako zdroj a stáhnout celou historii",
                  canAddToDashboard: Boolean(isSubscriber && canPersonalDashboard && canSaveWidget && previewCanRenderChart),
                  onAddToDashboard: (seriesConfig) => addSeriesToPersonalDashboard(def, displayRow, seriesConfig),
                  addingToDash,
                  dashboardLoading: personalDashLoading || saveWidgetLoading,
                  loading: previewLoading,
                  previewError: Boolean(blockingPreviewErrorText),
                  hasPreviewData:
                    previewHasUsableRows
                    && Boolean(previewData)
                    && !previewData?.all_values_zero
                    && previewData?.preview_state !== "all_zero"
                    && !previewData?.metadata?.all_values_zero,
                  dashboardTitle: "Widget načte celou časovou řadu z ECB/Eurostat (živě z API)",
                  hint:
                    def?.sourceType === "ecb" || def?.id === "ecb2"
                      ? isDetailPanel && seriesDetailOpen
                        ? "Na dashboard se uloží celá historie z ECB API včetně vybraných zemí."
                        : "Na můj dashboard: celá historie z ECB API včetně vybraných zemí. Pro trvalý zdroj v analýze použijte „Přidat do analýzy“ v seznamu."
                      : "Na dashboardu se načte celá časová řada; tabulka náhledu může ukazovat jen prvních 1000 bodů.",
                  share: {
                    catalogId: def.id,
                    sourceType: def.sourceType,
                    setId: String(displayRow?.set_id || row?.set_id || "").trim(),
                    title: String(displayRow?.name || row?.name || catalogRowTitle || "").trim(),
                    indicatorId: String(displayRow?.indicator_id || row?.indicator_id || "").trim(),
                  },
                },
                preview: previewForChart,
                catalogValueDescriptor:
                  def.sourceType === "ecb" || def.id === "ecb2"
                    ? String(row.ecb_value_descriptor || "").trim()
                    : undefined,
                catalogRowUnit: String(
                  displayRow?.unit_label_cs
                  || displayRow?.unit
                  || row?.unit_label_cs
                  || row?.unit
                  || "",
                ).trim() || undefined,
                loading: previewLoading,
                compact: true,
                liveCatalogPreview: true,
                catalogChartSize:
                  isPreviewOverlay
                    ? "fullscreen"
                    : isDetailPanel && seriesDetailOpen
                      ? catalogChartExpanded
                        ? "detail-expanded"
                        : "detail"
                      : undefined,
                catalogFreqLabel: isImfPreviewRow ? imfFreqLabel(imfFreqActive) : undefined,
                catalogFreqCode: isImfPreviewRow ? imfFreqActive : undefined,
                onIndicatorChange: (indicatorId) => {
                  const geo =
                    extractGeoValuesFromDimensionFilters(previewData?.metadata?.filters_applied).length > 0
                      ? extractGeoValuesFromDimensionFilters(previewData?.metadata?.filters_applied)
                      : extractGeoValuesFromDimensionFilters(previewData?.requested_filters);
                  setPreviewSelectedIndicators([indicatorId]);
                  void fetchPreview(def, displayRow, indicatorId, [indicatorId], geo);
                },
                onIndicatorSelectionChange: allowGroupMultiSelection
                  ? (indicatorIds) => {
                      const next = normalizeSelectedIndicators(indicatorIds);
                      if (!next.length) return;
                      const geo =
                        extractGeoValuesFromDimensionFilters(previewData?.metadata?.filters_applied).length > 0
                          ? extractGeoValuesFromDimensionFilters(previewData?.metadata?.filters_applied)
                          : extractGeoValuesFromDimensionFilters(previewData?.requested_filters);
                      setPreviewSelectedIndicators(next);
                      void fetchPreview(def, displayRow, next[0], next, geo);
                    }
                  : undefined,
                onGeoSelectionChange: (geoIds) => {
                  if (def.sourceType === "imf") {
                    // IMF: série = země (COUNTRY v SDMX klíči) — indikátory neposíláme.
                    setPreviewSelectedIndicators([]);
                    void fetchPreview(def, displayRow, undefined, [], geoIds);
                    return;
                  }
                  const many = normalizeSelectedIndicators(
                    previewSelectedIndicators.length
                      ? previewSelectedIndicators
                      : previewData?.selected_indicators,
                  );
                  const one = String(many[0] || previewData?.selected_indicator || "").trim();
                  void fetchPreview(def, displayRow, one, many, geoIds);
                },
                onDimensionFiltersApply:
                  def.sourceType === "imf"
                    ? undefined
                    : (dimensionFilters) => {
                        const many = normalizeSelectedIndicators(
                          previewSelectedIndicators.length
                            ? previewSelectedIndicators
                            : previewData?.selected_indicators,
                        );
                        const one = String(many[0] || previewData?.selected_indicator || "").trim();
                        const geo = extractGeoValuesFromDimensionFilters(dimensionFilters);
                        void fetchPreview(def, displayRow, one, many, geo, dimensionFilters);
                      },
                catalogCountryLabel: String(displayRow?.territory || "").trim(),
                onFindInCatalogSearch: (query) => {
                  const q = String(query || "").trim();
                  if (!q) return;
                  setUseAiAssistant(true);
                  setAiQuery(q);
                  void applySuggestedDeepSearch(q).catch(() => {});
                  catalogAiSectionRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
                },
              }}
            />
            {eurostatDeepAiPreviewErrorSanitized ? (
              <EurostatDeepAiTechnicalDetails raw={previewErrorText} />
            ) : null}
          </div>
        )
  );
}
