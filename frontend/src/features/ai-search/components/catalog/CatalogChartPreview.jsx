import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import SourcePreview from "@/components/sources/SourcePreview";
import AradView from "@/components/widgets/AradView";
import ExternalCatalogChartCompareModal from "@/components/widgets/ExternalCatalogChartCompareModal";
import UnifiedChartCompareModal from "@/components/widgets/UnifiedChartCompareModal";
import PersonalDashboardPagePickModal from "@/components/myDashboard/PersonalDashboardPagePickModal";
import { DataLoadInline } from "@/components/ui/DataLoadIndicator";
import CatalogChartActionsBar from "@/components/catalog/CatalogChartActionsBar";
import CatalogPreviewHeader from "@/components/catalog/CatalogPreviewHeader";
import { confirmPersonalDashboardPagePick } from "@/lib/catalogPageDashboard";
import api from "@/lib/api";
import {
  buildAradDataFromCatalogPreview,
  canRenderAradCatalogChart,
} from "@/lib/mapCatalogPreviewToArad";
import {
  buildCatalogPreviewState,
  catalogPreviewNeedsDimensionChoice,
} from "@/lib/catalogDimensions";

function valuesFromDimensionMeta(entry) {
  if (!entry) return [];
  if (Array.isArray(entry.values)) {
    return entry.values
      .map((v) => (v && typeof v === "object" ? v.label || v.name || v.id || v.value : v))
      .map((v) => String(v || "").trim())
      .filter(Boolean);
  }
  return [];
}

function buildCatalogSplitDimensionConfig(previewState, aradData) {
  if (!aradData?.multi_series || !aradData?.group_field) return {};
  const field = String(aradData.group_field).trim();
  if (!field) return {};
  const dim =
    (previewState.dimensions || []).find((d) => String(d?.id || "").trim().toLowerCase() === field.toLowerCase()) ||
    null;
  const fromDim = valuesFromDimensionMeta(dim);
  const fromSeries = Array.isArray(aradData.series)
    ? aradData.series.map((s) => String(s?.label || s?.name || s?.key || "").trim()).filter(Boolean)
    : [];
  const seen = new Set();
  const values = [];
  for (const value of [...fromSeries, ...fromDim]) {
    const key = value.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    values.push(value);
  }
  return {
    chart_series_mode: "multi",
    chart_series_dim: field,
    available_split_dimensions: [{ field, values }],
  };
}

function objectMapOrEmpty(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

function normalizedSelectionList(raw) {
  const list = Array.isArray(raw) ? raw : raw ? [raw] : [];
  const out = [];
  const seen = new Set();
  for (const item of list) {
    const value = String(item ?? "").trim();
    if (!value || seen.has(value)) continue;
    seen.add(value);
    out.push(value);
  }
  return out;
}

function isGeoGroupField(field) {
  const key = String(field || "").trim().toLowerCase();
  return key === "geo" || key === "ref_area" || key === "country";
}

function buildAnalyticsActionState(preview, row) {
  const groupField = String(preview?.group_field || "").trim();
  const selectedMany = normalizedSelectionList(preview?.selected_indicators);
  const selectedOne = String(preview?.selected_indicator || selectedMany[0] || row?.indicator_id || "").trim();
  const primarySelection = selectedOne || selectedMany[0] || "";
  const appliedFilters = {
    ...objectMapOrEmpty(preview?.metadata?.filters_applied),
    ...objectMapOrEmpty(preview?.applied_filters),
    ...objectMapOrEmpty(preview?.dimension_filters),
  };
  const dimensionFilters = { ...appliedFilters };
  if (groupField && primarySelection) {
    dimensionFilters[groupField] = primarySelection;
  }

  const rowGeo = String(row?.geo || row?.geo_label || row?.country || row?.ref_area || "").trim();
  if (!groupField && rowGeo && !dimensionFilters.geo && !dimensionFilters.REF_AREA && !dimensionFilters.ref_area) {
    dimensionFilters.geo = rowGeo;
  }

  const queryParams = {
    ...objectMapOrEmpty(row?.query_params),
    ...objectMapOrEmpty(preview?.metadata?.query_params),
  };
  const geo =
    groupField && isGeoGroupField(groupField) && primarySelection
      ? primarySelection
      : rowGeo;
  const indicators = Array.isArray(preview?.indicators) ? preview.indicators : [];
  const comparisonGroups = selectedMany.map((value) => {
    const indicator = indicators.find((item) => String(item?.id || "").trim() === value);
    return {
      value,
      label: String(indicator?.name || indicator?.label || value).trim(),
    };
  });

  return {
    geo,
    queryParams: Object.keys(queryParams).length ? queryParams : null,
    dimensionFilters: Object.keys(dimensionFilters).length ? dimensionFilters : null,
    selectedIndicator: primarySelection,
    selectedIndicators: selectedMany.length ? selectedMany : (primarySelection ? [primarySelection] : []),
    comparisonDimension: groupField && comparisonGroups.length > 1 ? groupField : "",
    comparisonGroups: comparisonGroups.length > 1 ? comparisonGroups : [],
  };
}

function catalogTableMaxHeightClass(catalogChartSize) {
  if (catalogChartSize === "fullscreen") return "max-h-[min(52vh,36rem)]";
  if (catalogChartSize === "detail-expanded") return "max-h-[min(52vh,30rem)] md:max-h-[min(62vh,34rem)]";
  if (catalogChartSize === "detail") return "max-h-[min(48vh,28rem)] md:h-full md:max-h-none";
  return "max-h-[310px]";
}

function catalogKpiSummaryMode(catalogChartSize) {
  const size = String(catalogChartSize || "").trim().toLowerCase();
  if (size === "compact") return "mini";
  return "compact";
}

/**
 * Katalogové preview — dimenzní controller + AradView renderer + akce.
 * SourcePreview zůstává pro dimenze (parity); graf jde přes AradView chartSlot.
 */
export default function CatalogChartPreview({
  preview,
  previewError = "",
  previewLoading = false,
  title = "",
  widgetId = "catalog-live-preview",
  catalogChartSize,
  preferAradView = true,
  controlsInOptionsPanel = true,
  sourceType = "",
  catalogDef = null,
  catalogRow = null,
  sourcePreviewProps = {},
  /** Akce z rodiče (GlobalCatalogSearchPage) */
  actions = {},
  /** Detail řady — synchronizace horního metadata panelu se stavem grafu. */
  onChartDisplayStateChange = null,
  /**
   * "cross-source" (výchozí) = UnifiedChartCompareModal s hledáním napříč zdroji (katalog,
   * uploady, výpočty, akcie) + vlastní ephemerní uložení přes /homepage/render-widget - stejný
   * mechanismus, který už funguje na StockSearchPage.
   * "same-dataset" = starší ExternalCatalogChartCompareModal (jiný ukazatel stejné datové sady
   * přes actions.onCompareSave) - jeho "+ Přidat řadu" je jen prázdné textové pole bez
   * vyhledávání/nabídky (žádné API volání), takže reálně nikdy nic nenabídl. "cross-source"
   * funguje pro libovolný katalogový zdroj stejně jako pro akcie, proto je teď výchozí i pro
   * statistické katalogy (IMF/BIS/Eurostat/…), ne jen pro akcie.
   */
  compareMode = "cross-source",
}) {
  const nav = useNavigate();
  const [compareOpen, setCompareOpen] = useState(false);
  const [pagePick, setPagePick] = useState(null);
  const [pagePickBusy, setPagePickBusy] = useState(false);
  const [crossSourceCompareList, setCrossSourceCompareList] = useState([]);
  const [crossSourceOverrideData, setCrossSourceOverrideData] = useState(null);
  const lastPreviewRef = useRef(null);
  // Periodicita/agregace aktuálně vybraná v grafu (AradView) - viz chartDisplayState. Použije se
  // k synchronizaci vnější tabulky (SourcePreview) a Copy Excel/XLSX exportu (CatalogChartActionsBar)
  // se stejnou periodicitou, jakou má právě vybranou graf, ne se syrovými `preview.rows`.
  const [chartDisplayState, setChartDisplayState] = useState(null);
  const handleChartDisplayStateChange = useCallback(
    (state) => {
      setChartDisplayState(state);
      if (typeof onChartDisplayStateChange === "function") {
        onChartDisplayStateChange(state);
      }
    },
    [onChartDisplayStateChange]
  );

  // Nová řada/ukazatel byl vybrán -> předchozí ephemerní cross-source přidání už nedává smysl.
  useEffect(() => {
    setCrossSourceOverrideData(null);
    setCrossSourceCompareList([]);
    setChartDisplayState(null);
  }, [catalogRow?.set_id, catalogDef?.id]);

  useEffect(() => {
    if (preview?.rows?.length) {
      lastPreviewRef.current = preview;
    }
  }, [preview]);

  const effectivePreview = useMemo(() => {
    if (previewLoading && !preview?.rows?.length && lastPreviewRef.current) {
      return lastPreviewRef.current;
    }
    return preview;
  }, [preview, previewLoading]);

  const previewState = useMemo(
    () =>
      buildCatalogPreviewState(effectivePreview, {
        sourceType,
        setId: catalogRow?.set_id,
        rowTitle: title,
      }),
    [effectivePreview, sourceType, catalogRow?.set_id, title]
  );

  const baseAradData = useMemo(() => {
    if (!effectivePreview || previewError) return null;
    return buildAradDataFromCatalogPreview(
      {
        ...effectivePreview,
        source: effectivePreview.source || sourcePreviewProps.preview?.source,
      },
      title || effectivePreview?.source?.name || ""
    );
  }, [effectivePreview, previewError, title, sourcePreviewProps.preview?.source]);

  // Ephemerní cross-source přidání (Srovnat s... -> UnifiedChartCompareModal) přepíše zobrazená
  // data lokálně - bez uloženého widgetu, jen pro tento náhled (viz handleCrossSourceCompareSave).
  const aradData = crossSourceOverrideData || baseAradData;

  const resolvedSourceType =
    String(
      sourceType ||
        effectivePreview?.source?.source_type ||
        sourcePreviewProps.preview?.source_type ||
        ""
    )
      .trim()
      .toLowerCase();

  const needsDimensionChoice =
    catalogPreviewNeedsDimensionChoice(effectivePreview) && !previewLoading;
  const showAradView =
    preferAradView &&
    canRenderAradCatalogChart(effectivePreview, previewError, resolvedSourceType) &&
    !needsDimensionChoice;

  const chartSlot =
    showAradView && aradData ? (() => {
      const splitConfig = buildCatalogSplitDimensionConfig(previewState, aradData);
      return (
      <AradView
        userTitle={title || effectivePreview?.source?.name || aradData.title}
        data={aradData}
        widget={{
          id: widgetId,
          type: "external_catalog_chart",
          config: {
            selected_dimensions: previewState.selected_dimensions,
            catalog: catalogDef?.id,
            set_id: catalogRow?.set_id,
            ...splitConfig,
          },
        }}
        controlsInOptionsPanel={controlsInOptionsPanel}
        unlockChartPeriod
        unlockViewToggle
        catalogLivePreview
        catalogChartSize={catalogChartSize}
        kpiSummaryMode={catalogKpiSummaryMode(catalogChartSize)}
        aradMultiSeriesHelpContext="public_site"
        onChartDisplayStateChange={handleChartDisplayStateChange}
      />
      );
    })() : needsDimensionChoice && !previewError ? (
      <div className="flex items-center justify-center rounded-xl border border-dashed border-border/60 bg-muted/10 min-h-[12rem] px-4 text-center text-sm text-slate-600">
        Vyberte dimenze výše pro zobrazení grafu.
      </div>
    ) : null;

  // Tabulka i export mají ukazovat přesně to, co je vidět v grafu - viz AradView.jsx
  // chartDisplayState. `rows` je jen {period, value}[] (viz SourcePreview/CatalogChartActionsBar,
  // kde se namapuje na skutečné sloupce).
  const periodicityOverrideRows =
    showAradView && Array.isArray(chartDisplayState?.rows) && chartDisplayState.rows.length > 0
      ? chartDisplayState.rows
      : null;
  const periodicityOverrideFrequencyLabel = periodicityOverrideRows
    ? String(chartDisplayState?.frequencyLabel || "").trim()
    : "";
  // Kód (D/W/M/Q/H/Y), ne label - jde na backend Analytiky/Forecastu (target_frequency), aby
  // počítaly ze stejné periodicity jako graf, ne vždy z nativní.
  const periodicityFrequencyCode = chartDisplayState?.isAggregated
    ? String(chartDisplayState?.frequencyCode || "").trim()
    : "";

  const catalogActions = sourcePreviewProps.catalogChartActions || {};
  const hasData = Boolean(effectivePreview?.rows?.length) && !previewError;
  const compareCatalog = catalogDef?.id || effectivePreview?.source?.catalog;
  const compareSetId = String(catalogRow?.set_id || effectivePreview?.source?.set_id || "").trim();
  const mainIndicator = String(effectivePreview?.selected_indicator || "").trim();
  const isFullscreenPreview = catalogChartSize === "fullscreen";
  const isDetailPreview =
    catalogChartSize === "detail" || catalogChartSize === "detail-expanded";
  const analyticsState = useMemo(
    () => buildAnalyticsActionState(effectivePreview, catalogRow),
    [effectivePreview, catalogRow]
  );
  const analyticsActionsProps = {
    sourceType: resolvedSourceType,
    setId: compareSetId,
    catalogTitle: title,
    geo: analyticsState.geo,
    queryParams: analyticsState.queryParams,
    dimensionFilters: analyticsState.dimensionFilters,
    selectedIndicator: analyticsState.selectedIndicator,
    selectedIndicators: analyticsState.selectedIndicators,
    comparisonDimension: analyticsState.comparisonDimension,
    comparisonGroups: analyticsState.comparisonGroups,
    query: title,
  };

  const handleAddToDashboard = useCallback(async () => {
    const fn = catalogActions.onAddToDashboard || actions.onAddToDashboard;
    if (typeof fn !== "function") return;
    await fn({ setPagePick });
  }, [catalogActions.onAddToDashboard, actions.onAddToDashboard]);

  // Ad-hoc náhledy (např. /search/stocks) nemají uložený widget, takže "Srovnat s..." tu nemůže
  // patchovat widget.config jako na dashboardu - reprodukuje aktuální graf přes
  // /homepage/render-widget (stejný ephemerní mechanismus jako AradView.localComparePreview)
  // a merge jen dočasně přepíše zobrazená data přes crossSourceOverrideData.
  const handleCrossSourceCompareSave = useCallback(
    async ({ chart_compare_with: cmp, primary_y_axis: pya } = {}) => {
      const entries = Array.isArray(cmp) ? cmp : [];
      if (!baseAradData || !compareCatalog || !compareSetId) return;
      try {
        const { data: resp } = await api.post("/homepage/render-widget", {
          type: "external_catalog_chart",
          title: title || "",
          width: "full",
          skip_ai: true,
          config: {
            catalog: compareCatalog,
            set_id: compareSetId,
            selected_indicator: mainIndicator,
            chart_compare_with: entries,
            chart_primary_snapshot: baseAradData,
            chart_series_mode: entries.length > 0 ? "multi" : "single",
            primary_y_axis: pya === "right" ? "right" : "left",
          },
        });
        const merged = resp?.data ?? resp;
        const mergedOk =
          entries.length > 0 &&
          merged?.multi_series &&
          Array.isArray(merged?.series) &&
          merged.series.length > 1;
        if (mergedOk) {
          // Auto-osy: doplňkovou řadu s výrazně jiným měřítkem než primární (s0) dej na PRAVOU osu,
          // jinak kvůli sdílené levé ose splyne s nulou (např. HDP v mil. Kč vs inflace v %).
          // Stejná heuristika jako AradView.localComparePreview; renderer (renderMultiComposedChart)
          // nakreslí druhou osu, jakmile má některá řada y_axis="right". Additivní — nešahá na řady,
          // které uživatel ručně vlevo/vpravo vybral, jen doplní pravou osu pro řádově jiné škály.
          try {
            const mrows = Array.isArray(merged.rows) ? merged.rows : [];
            const mseries = merged.series;
            const medianMag = (key) => {
              const vals = mrows
                .map((r) => Math.abs(Number(r?.[key])))
                .filter((v) => Number.isFinite(v) && v > 0)
                .sort((a, b) => a - b);
              return vals.length ? vals[Math.floor(vals.length / 2)] : null;
            };
            const primaryKey = mseries[0]?.key;
            const primaryMag = primaryKey ? medianMag(primaryKey) : null;
            if (primaryMag) {
              for (const s of mseries) {
                if (!s || s.key === primaryKey) continue;
                const m = medianMag(s.key);
                if (m && (m / primaryMag > 4 || primaryMag / m > 4)) {
                  s.y_axis = "right";
                }
              }
            }
          } catch {
            /* auto-osy jsou jen kosmetika – při chybě necháme původní osy */
          }
        }
        setCrossSourceOverrideData(mergedOk ? merged : null);
        setCrossSourceCompareList(mergedOk ? entries : []);
      } catch {
        // graf necháme beze změny; uživatel to uvidí podle toho, že se přidaná řada neobjevila
      }
    },
    [baseAradData, compareCatalog, compareSetId, mainIndicator, title]
  );

  const confirmPagePick = useCallback(async () => {
    setPagePickBusy(true);
    try {
      await confirmPersonalDashboardPagePick({
        api,
        nav,
        pagePick,
        onDone: () => setPagePick(null),
      });
    } finally {
      setPagePickBusy(false);
    }
  }, [nav, pagePick]);

  const isInlineSearchPreview =
    !isFullscreenPreview && !isDetailPreview && !catalogChartSize;

  if (previewLoading && !effectivePreview?.rows?.length && !previewError) {
    return (
      <div className="catalog-live-chart-loading flex items-center justify-center rounded-xl border border-border/60 bg-muted/15 min-h-[16rem] h-64 sm:h-72">
        <DataLoadInline label="Načítám data ze zdroje…" />
      </div>
    );
  }

  return (
    <div
      className={`catalog-chart-preview min-w-0 max-w-full overflow-x-hidden ${
        isFullscreenPreview
          ? "flex flex-col flex-1 min-h-0 h-full max-md:space-y-1 space-y-2"
          : isInlineSearchPreview
            ? "space-y-1"
            : "space-y-2"
      }`}
      data-testid="catalog-chart-preview"
    >
      {/* Meta panel jen mimo fullscreen/detail (tam je velká hlavička) — akční
          lišta (Srovnat s…, export, dashboard) ale musí zůstat všude. */}
      {!isFullscreenPreview && !isDetailPreview && !isInlineSearchPreview ? (
        <CatalogPreviewHeader previewState={previewState} preview={effectivePreview} />
      ) : null}
      {!isDetailPreview ? (
        <CatalogChartActionsBar
          preview={effectivePreview}
          previewError={previewError}
          title={title}
          widgetId={widgetId}
          canAddToDashboard={catalogActions.canAddToDashboard}
          onAddToDashboard={handleAddToDashboard}
          addingToDashboard={catalogActions.addingToDash}
          canSync={catalogActions.canSync}
          onSync={catalogActions.onSync}
          syncing={catalogActions.syncing}
          canCompare={hasData && Boolean(compareCatalog && compareSetId)}
          onCompare={() => setCompareOpen(true)}
          canExport={actions.canExport !== false}
          disabled={previewLoading}
          periodicityOverrideRows={periodicityOverrideRows}
          periodicityOverrideFrequencyLabel={periodicityOverrideFrequencyLabel}
          periodicityFrequencyCode={periodicityFrequencyCode}
          {...analyticsActionsProps}
        />
      ) : (
        <CatalogChartActionsBar
          preview={effectivePreview}
          previewError={previewError}
          title={title}
          widgetId={widgetId}
          canAddToDashboard={catalogActions.canAddToDashboard}
          onAddToDashboard={handleAddToDashboard}
          addingToDashboard={catalogActions.addingToDash}
          canCompare={hasData && Boolean(compareCatalog && compareSetId)}
          onCompare={() => setCompareOpen(true)}
          canExport={actions.canExport !== false}
          disabled={previewLoading}
          periodicityOverrideRows={periodicityOverrideRows}
          periodicityOverrideFrequencyLabel={periodicityOverrideFrequencyLabel}
          periodicityFrequencyCode={periodicityFrequencyCode}
          {...analyticsActionsProps}
        />
      )}
      <SourcePreview
        preview={effectivePreview}
        loading={previewLoading}
        liveCatalogPreview
        compact
        catalogChartSize={catalogChartSize}
        catalogTableMaxHeightClass={catalogTableMaxHeightClass(catalogChartSize)}
        chartSlot={chartSlot}
        periodicityOverrideRows={periodicityOverrideRows}
        {...sourcePreviewProps}
      />
      {compareOpen && compareMode === "cross-source" ? (
        <UnifiedChartCompareModal
          open={compareOpen}
          onClose={() => setCompareOpen(false)}
          mainLabel={title}
          currentCatalog={compareCatalog}
          currentSetId={compareSetId}
          initialCompareList={crossSourceCompareList}
          onSave={async (payload) => {
            await handleCrossSourceCompareSave(payload);
            setCompareOpen(false);
          }}
          compositeAllowed
        />
      ) : compareOpen ? (
        <ExternalCatalogChartCompareModal
          open={compareOpen}
          onClose={() => setCompareOpen(false)}
          catalog={compareCatalog}
          setId={compareSetId}
          mainIndicator={mainIndicator}
          mainLabel={title}
          groupField={effectivePreview?.group_field}
          selectedDimensions={previewState.selected_dimensions}
          initialCompareList={
            effectivePreview?.config?.chart_compare_with ||
            catalogActions.compareList ||
            actions.compareList
          }
          onSave={async (payload) => {
            if (typeof actions.onCompareSave === "function") {
              await actions.onCompareSave(payload);
            }
            setCompareOpen(false);
          }}
          compositeAllowed
        />
      ) : null}
      <PersonalDashboardPagePickModal
        open={Boolean(pagePick)}
        pages={pagePick?.pages}
        selectedId={pagePick?.selectedId || ""}
        onSelectedIdChange={(id) =>
          setPagePick((prev) => (prev ? { ...prev, selectedId: id } : prev))
        }
        onConfirm={confirmPagePick}
        onCancel={() => setPagePick(null)}
        loading={pagePickBusy || catalogActions.addingToDash}
      />
    </div>
  );
}
