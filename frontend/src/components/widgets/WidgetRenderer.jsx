import React, { useMemo } from "react";
import MySeriesInlineActions from "@/components/myDashboard/MySeriesInlineActions";
import { buildCompareLeftRefFromWidget, buildMySeriesSavePayloadFromWidget } from "@/lib/mySeriesFromWidget";
import { buildWidgetShareContext } from "@/lib/widgetChartShare";
import { safeWidgetConfigPatch } from "@/lib/safeWidgetConfigPatch";
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  BarChart,
  Bar,
  Cell,
  ReferenceLine,
} from "recharts";
import {
  ArrowDownRight,
  ArrowUpRight,
  Minus,
  AlertTriangle,
  CheckCircle2,
  XCircle,
  RefreshCw,
  Clock,
  CircleAlert,
} from "lucide-react";
import { fmtCompact, fmtCurrency, fmtDateTime, fmtInt, fmtNumber } from "@/lib/format";
import { mergeRechartsTooltipProps } from "@/lib/rechartsTooltipShared";
import { coerceChartNumeric, getYAxisDomainForChart } from "@/lib/chartZoomHelpers";
import { SafeRechartsContainer } from "@/lib/SafeRechartsContainer";
import { useFeatureAccessContextOptional } from "@/contexts/FeatureAccessContext";
import { useAuth } from "@/contexts/AuthContext";
import { useLocalizedContent } from "@/hooks/useLocalizedContent";

import { hasWidgetRenderableData } from "@/lib/widgetSnapshot";
import api from "@/lib/api";
import AradView from "@/components/widgets/AradView";
import RichText from "@/components/widgets/RichText";
import AdWidget from "@/components/widgets/AdWidget";
import ExportMenu from "@/components/widgets/ExportMenu";
import { resolveWidgetPanel } from "@/lib/widgetPanel";
import { mergeAvailableSplitDimensions } from "@/lib/catalogDimensions";
import { DATASET_CHART_ENGINE_TYPES } from "@/lib/datasetChartEngineTypes";
import LoadingSpinner from "@/components/ui/loading/LoadingSpinner.jsx";
import RssMonitoringWidget from "@/components/widgets/RssMonitoringWidget";

export const WIDTH_CLS = {
  full: "xl:col-span-24",
  "three-quarters": "xl:col-span-18",
  "two-thirds": "xl:col-span-16",
  half: "xl:col-span-12",
  third: "xl:col-span-8",
  quarter: "xl:col-span-6",
  sixth: "xl:col-span-4",
  eighth: "xl:col-span-3",
};

// Playful infographic-matched chart palette. The `mint` key is kept as an
// alias to the bright primary so existing usages don't need to be renamed.
// Mirrors --chart-1 … --chart-6 in `index.css` as literal HSL for Recharts.
export const CHART_PALETTE = {
  mint: "hsl(202 90% 52%)",       // bright cyan-blue (primary, alias kept)
  mintDeep: "hsl(218 65% 22%)",   // deep navy
  rose: "hsl(344 70% 70%)",       // rose accent
  cream: "hsl(36 90% 68%)",       // warm cream accent
  blue: "hsl(208 75% 48%)",       // medium steel blue
  lavender: "hsl(240 55% 70%)",   // lavender
  terracotta: "hsl(18 75% 62%)",  // terracotta accent
  grid: "#D4E6F7",
  axis: "#5878A0",
};

function WidgetSkeleton({ title, width }) {
  const compact = ["third", "quarter", "sixth", "eighth"].includes(width);
  return (
    <div
      role="status"
      aria-live="polite"
      aria-busy="true"
      aria-label="Načítám widget"
      className="kpi-card h-full min-h-[140px] flex flex-col gap-3 justify-center px-4 py-3"
    >
      <div className="flex items-center gap-2 min-w-0 text-slate-600">
        <LoadingSpinner suppressAria size="xs" />
        <span className="text-[10px] font-mono truncate" aria-hidden>
          Načítám…
        </span>
      </div>
      <div
        className="h-3 rounded max-w-[12rem] animate-pulse motion-reduce:animate-none motion-reduce:opacity-80"
        style={{ background: "hsl(205 60% 88%)" }}
      />
      {title ? (
        <div
          className="h-3 rounded max-w-[10rem] opacity-80 animate-pulse motion-reduce:animate-none motion-reduce:opacity-70"
          style={{ background: "hsl(205 55% 92%)" }}
        />
      ) : null}
      <div
        className="rounded-lg flex-1 min-h-[72px] animate-pulse motion-reduce:animate-none motion-reduce:opacity-80"
        style={{ background: "hsl(205 60% 91% / 0.65)" }}
      />
      {!compact ? (
        <div
          className="h-2 rounded max-w-[85%] opacity-70 animate-pulse motion-reduce:animate-none motion-reduce:opacity-70"
          style={{ background: "hsl(205 55% 90%)" }}
        />
      ) : null}
    </div>
  );
}

function normalizeDimToken(value) {
  return String(value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-zA-Z0-9]/g, "")
    .toLowerCase();
}

function isRegionalDimensionName(value) {
  const t = normalizeDimToken(value);
  return (
    t.includes("kraj") ||
    t.includes("region") ||
    t.includes("uzemikraj") ||
    t.includes("refarea") ||
    t === "geo" ||
    t.includes("country") ||
    t.includes("territory")
  );
}

function isMultiDimCatalogSource(viewType, config) {
  const sourceType = String(config?.source_type || "").toLowerCase();
  const catalog = String(config?.catalog || "").toLowerCase();
  if (viewType === "csu_view" || sourceType === "csu" || catalog === "csu") return true;
  if (viewType === "ecb_view" || sourceType === "ecb" || catalog === "ecb" || catalog === "ecb2") return true;
  if (viewType === "eurostat_view" || sourceType === "eurostat" || catalog === "eurostat") return true;
  if (viewType === "external_catalog_chart" || sourceType === "external_catalog") {
    return ["csu", "ecb", "ecb2", "eurostat"].includes(catalog);
  }
  return false;
}

function getFilteredDimensionKeys(config) {
  const dimFilters = config?.dimension_filters;
  return dimFilters && typeof dimFilters === "object"
    ? Object.keys(dimFilters).map((key) => normalizeDimToken(key))
    : [];
}

const JUNK_DIM_TOKENS = new Set(["source", "sourcetype", "variable", "metadata", "frequency"]);

function getSeriesDimensionOptions(config, data) {
  // Kanonický model dimenzí (sdílený s katalogovým náhledem): ponecháme jen dimenze,
  // které DATA nebo uložená konfigurace reálně potvrzují ≥2 hodnotami. Tím se mj.
  // NEnabízí ÚZEMÍ-KRAJ u národní sady, která kraje vůbec neobsahuje — i když je
  // chart_series_dim=ÚZEMÍ-KRAJ uloženo v configu (jinak rozbalovátko nabízelo mrtvou volbu).
  const merged = mergeAvailableSplitDimensions(data, config);
  const seen = new Set();
  return merged.filter((dim) => {
    const key = normalizeDimToken(dim.field);
    if (!key || JUNK_DIM_TOKENS.has(key)) return false;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function pickSeriesDimension(config, data, optionsArg = {}) {
  const preferCsuKraj = Boolean(optionsArg.preferCsuKraj);
  const filterKeys = getFilteredDimensionKeys(config);
  const configured = String(config?.chart_series_dim || "").trim();
  const configuredToken = normalizeDimToken(configured);
  const options = getSeriesDimensionOptions(config, data);
  const optionTokens = new Set(options.map((dim) => normalizeDimToken(dim.field)));

  // Konfigurovanou dimenzi (chart_series_dim) respektuj jen když ji data reálně nabízejí.
  // Jinak by se vracela mrtvá dimenze (např. ÚZEMÍ-KRAJ u národní sady) a graf zůstal 1 řadou.
  if (configured && optionTokens.has(configuredToken)) return configured;

  const preferredOptions = options.filter((dim) => !filterKeys.includes(normalizeDimToken(dim.field)));
  const regional = preferredOptions.find((dim) => isRegionalDimensionName(dim.field));
  if (regional) return regional.field;
  if (preferredOptions[0]?.field) return preferredOptions[0].field;

  if (preferCsuKraj && optionTokens.has(normalizeDimToken("ÚZEMÍ-KRAJ"))) return "ÚZEMÍ-KRAJ";
  if (options[0]?.field) return options[0].field;
  const fields = Array.isArray(data?.fields) ? data.fields : [];
  const fieldDim = fields.find((field) => isRegionalDimensionName(field));
  if (fieldDim) return fieldDim;
  const rows = Array.isArray(data?.rows) ? data.rows : [];
  for (const row of rows.slice(0, 20)) {
    if (!row || typeof row !== "object") continue;
    const rowDim = Object.keys(row).find((field) => isRegionalDimensionName(field));
    if (rowDim) return rowDim;
  }
  return null;
}

function canShowMultiSeriesToggle(viewType, config, data) {
  const sourceType = String(config?.source_type || "").toLowerCase();
  const catalog = String(config?.catalog || "").toLowerCase();
  const preferCsuKraj = viewType === "csu_view" || sourceType === "csu" || catalog === "csu";
  if (!isMultiDimCatalogSource(viewType, config)) return false;
  return (
    Boolean(pickSeriesDimension(config, data, { preferCsuKraj })) ||
    Boolean(config?.selected_indicator || config?.group_field || data?.group_field)
  );
}

function buildMultiSeriesConfigPatch(config, data, splitOverride) {
  const isCsu = String(config?.catalog || "").toLowerCase() === "csu" || String(config?.source_type || "").toLowerCase() === "csu";
  const splitDimension = String(splitOverride || "").trim() || pickSeriesDimension(config, data, { preferCsuKraj: isCsu }) || "ÚZEMÍ-KRAJ";
  const next = {
    ...(config || {}),
    chart_series_dim: splitDimension,
    chart_series_mode: "multi",
    agg: "avg",
    limit: 0,
  };
  const groupField = String(config?.series_field || config?.group_field || data?.group_field || "").trim();
  const selectedIndicator = String(config?.series_value || config?.selected_indicator || data?.selected_indicator || "").trim();
  if (groupField && selectedIndicator) {
    next.series_field = groupField;
    next.series_value = selectedIndicator;
  }
  if (next.dimension_filters && typeof next.dimension_filters === "object") {
    const cleaned = { ...next.dimension_filters };
    for (const key of Object.keys(cleaned)) {
      if (normalizeDimToken(key) === normalizeDimToken(splitDimension)) delete cleaned[key];
    }
    next.dimension_filters = Object.keys(cleaned).length > 0 ? cleaned : undefined;
  }
  return next;
}

/**
 * Výstup vzorců (`formula_chart`) má dynamické klíče podle `group_by`; sjednotíme na `period` + `value` pro `AradView`.
 */
function mapFormulaRowsForAradView(rows, groupBy) {
  const gb = Array.isArray(groupBy) && groupBy.length > 0 ? groupBy : ["date"];
  return (rows || [])
    .map((r, idx) => {
      const parts = gb.map((k) => String(r?.[k] ?? "").trim()).filter(Boolean);
      const period = parts.join(" · ") || `Řádek ${idx + 1}`;
      const value = r?.result ?? r?.value ?? r?.y;
      return { period, value };
    })
    .filter((r) => r.period && r.value !== undefined && r.value !== null);
}

/** Legacy `dataset_chart` (x/y) → stejný tvar řad jako u ostatních grafů v `AradView`. */
function mapDatasetChartRowsForAradView(rows) {
  return (rows || [])
    .map((r) => ({
      period: String(r?.x ?? "").trim(),
      value: r?.y,
    }))
    .filter((r) => r.period && r.value !== undefined && r.value !== null);
}

/** Jen chyba aktualizace — datum/snapshot se na dashboardu nezobrazuje. */
function WidgetSnapshotMeta({ w, onWidgetRefresh }) {
  if (!w?.refresh_error) return null;

  return (
    <div className="flex shrink-0 flex-wrap items-center gap-x-2 gap-y-1 px-1 pb-1 text-[10px] text-slate-500">
      <span className="inline-flex items-center gap-1 text-amber-800">
        <CircleAlert className="h-3 w-3 shrink-0" aria-hidden />
        {w.refresh_error}
      </span>
      {typeof onWidgetRefresh === "function" && !w?._refreshing ? (
        <button
          type="button"
          className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 hover:bg-slate-100 text-slate-600 disabled:opacity-50"
          onClick={() => onWidgetRefresh(w.id)}
          title="Zkusit aktualizovat data znovu"
          aria-label="Zkusit aktualizovat data znovu"
        >
          <RefreshCw className="h-3 w-3" aria-hidden />
          Zkusit znovu
        </button>
      ) : null}
    </div>
  );
}

function ExpandableCaption({ caption, compact }) {
  const [expanded, setExpanded] = React.useState(false);
  const canExpand = caption && caption.length > 80;
  if (compact) {
    return (
      <div className="shrink-0 text-[8px] text-slate-600 leading-snug italic px-0.5 line-clamp-2">
        {caption}
      </div>
    );
  }
  return (
    <div className="shrink-0 text-[12px] text-slate-600 leading-relaxed italic px-1">
      <span className={canExpand && !expanded ? "line-clamp-2" : undefined}>
        {caption}
      </span>
      {canExpand && !expanded && (
        <button
          type="button"
          onClick={() => setExpanded(true)}
          className="ml-1 text-[11px] italic text-sky-600 hover:underline"
        >
          … více
        </button>
      )}
    </div>
  );
}

export default function WidgetRenderer({
  w,
  defaultChartType = "line",
  defaultChartFrequency,
  aradMultiSeriesHelpContext = "public_site",
  /** Osobní / admin dashboard: uložení části `config` (např. `chart_compare_with`) z AradView. */
  onWidgetConfigPatch,
  /** ID stránky osobního dashboardu — povolí sdílení widgetu. */
  dashboardSharePageId = null,
  /** Volitelný refresh dat widgetu (live resolve + aktualizace snapshotu). */
  onWidgetRefresh = null,
  /** Přepsání režimu KPI stripu pro embedded náhledy. Dashboard nechává auto. */
  kpiSummaryMode = "auto",
  /** Sdílený dashboard (host) — bez editace, sdílení odkazů a patch configu. */
  readOnlyDashboardView = false,
  /** Kontext diváka sdíleného dashboardu — lokální compare preview (neukládá se). */
  viewerCompareContext = null,
}) {
  const { isAdmin } = useAuth();
  const loc = useLocalizedContent();
  const feCtx = useFeatureAccessContextOptional();
  const adFreeDashboard =
    feCtx?.accessMapReady && feCtx?.effective?.ad_free_dashboard?.allowed === true;
  const displayTitle = loc.widgetTitle(w);
  const displayCaption = loc.widgetCaption(w);
  const effectiveDashboardSharePageId = readOnlyDashboardView ? null : dashboardSharePageId;
  const effectiveOnWidgetConfigPatch = readOnlyDashboardView ? null : onWidgetConfigPatch;
  const onViewerComparePreview =
    readOnlyDashboardView && viewerCompareContext
      ? async (compareWith) => {
          const { data: rendered } = await api.post("/dashboard-share/widget-compare-preview", {
            token: viewerCompareContext.token,
            page_id: viewerCompareContext.pageId,
            widget_id: w.id,
            compare_with: compareWith,
          });
          return rendered;
        }
      : null;

  const chartShareContext = useMemo(() => {
    if (!effectiveDashboardSharePageId || !w?.id || w._loading) return null;
    return buildWidgetShareContext({
      widget: w,
      pageId: effectiveDashboardSharePageId,
      title: displayTitle,
    });
  }, [effectiveDashboardSharePageId, w, displayTitle]);
  if (w._loading && !hasWidgetRenderableData(w)) {
    return <WidgetSkeleton title={displayTitle} width={w.width} />;
  }
  const { data, width } = w;
  /** Osobní dashboard: API vrací uložený `type` (např. chart) + `engine_type` pro renderer (arad_view). Po PATCH nesmíme spoléhat jen na `type`. */
  const viewType = w.engine_type || w.type;
  const caption = displayCaption;
  const compactCaption = ["third", "quarter", "sixth", "eighth"].includes(width);
  if (data && data.error) return <ErrorCard title={displayTitle} message={data.error} />;
  const pageDefaultChartType = String(defaultChartType || "line").toLowerCase();
  const inner = renderWidget(
    w,
    pageDefaultChartType,
    defaultChartFrequency,
    adFreeDashboard,
    isAdmin,
    aradMultiSeriesHelpContext,
    effectiveOnWidgetConfigPatch,
    displayTitle,
    displayCaption,
    chartShareContext,
    kpiSummaryMode,
    onViewerComparePreview,
  );
  // Společný renderer časových řad (`AradView`) — ARAD, katalog, výpočty, upload, vzorce, …
  // má vlastní kartu s overflow; caption i AI komentář patří dovnitř, jinak se spodní text
  // ořízne nebo skončí mimo scrollovatelnou oblast.
  const usesAradView =
    viewType === "arad_view" ||
    viewType === "computed_view" ||
    viewType === "user_upload_chart" ||
    viewType === "formula_chart" ||
    viewType === "dataset_chart" ||
    viewType === "external_catalog_chart" ||
    (DATASET_CHART_ENGINE_TYPES.has(viewType) && data?.view === "chart");
  if (usesAradView) {
    return (
      <div className="flex h-full min-h-0 flex-col">
        {w?.refresh_error ? <WidgetSnapshotMeta w={w} onWidgetRefresh={onWidgetRefresh} /> : null}
        <div className="flex min-h-0 flex-1 flex-col">{inner}</div>
      </div>
    );
  }
  // AradView / RichText manage their own headings; caption is rendered below.
  // Reklamní widget (`ad`) je z principu bez titulku/popisku — žádnou caption
  // pod ním nezobrazujeme, i kdyby byla v configu omylem nastavená.
  const showCaption = caption && !["markdown", "text", "note", "ad"].includes(viewType);
  if (showCaption) {
    return (
      <div className="h-full flex flex-col min-h-0">
        <div className="flex-1 min-h-0">{inner}</div>
        {showCaption ? (
          <ExpandableCaption caption={caption} compact={compactCaption} />
        ) : null}
      </div>
    );
  }
  return inner;
}

function renderWidget(
  w,
  pageDefaultChartType = "line",
  pageDefaultChartFrequency,
  adFreeDashboard = false,
  isAdmin = false,
  aradMultiSeriesHelpContext = "public_site",
  onWidgetConfigPatch,
  displayTitle = "",
  displayCaption = "",
  chartShareContext = null,
  kpiSummaryMode = "auto",
  onViewerComparePreview = null,
) {
  const { title, data, config, width } = w;
  const viewTitle = displayTitle || title;
  const viewCaption = displayCaption;
  const viewType = w.engine_type || w.type;
  const compactLayout = ["third", "quarter", "sixth", "eighth"].includes(width);
  const fallbackChart = String(pageDefaultChartType || "line").toLowerCase();
  const chartType = (config?.chart_type || data?.chart_type || fallbackChart).toLowerCase();
  switch (viewType) {
    case "kpi_active_sources":
    case "kpi_datasets":
    case "kpi_records":
    case "kpi_sync_errors":
      return (
        <KpiCard
          panelConfig={config}
          label={viewTitle || viewType}
          value={data?.value}
          hint={data?.hint}
          trend={data?.trend}
        />
      );
    case "chart_net_result":
      return <NetResultChart panelConfig={config} title={viewTitle || "Čistý výsledek"} data={data} />;
    case "chart_dataset_distribution":
      return <DistributionChart panelConfig={config} title={viewTitle || "Distribuce"} data={data} />;
    case "table_recent_syncs":
      return <SyncTable panelConfig={config} title={viewTitle || "Synchronizace"} rows={data || []} />;
    case "dataset_table":
      return <DatasetTable panelConfig={config} title={viewTitle} data={data} />;
    case "dataset_chart": {
      const msPayload = buildMySeriesSavePayloadFromWidget({ viewType, config, data, title });
      const msCompare = buildCompareLeftRefFromWidget({ viewType, config, data, title });
      const aradRows = mapDatasetChartRowsForAradView(data?.rows);
      return (
        <div className="flex h-full min-h-0 min-w-0 flex-col">
          <div className="min-h-0 flex-1 min-w-0">
            <AradView
              userTitle={viewTitle}
              data={{
                ...data,
                view: data?.view || "chart",
                rows: aradRows,
              }}
              widget={w}
              caption={viewCaption}
              aiCommentary={w.ai_commentary}
              aiAnalysis={w.ai_analysis_payload}
              defaultChartType={chartType}
              defaultChartFrequency={pageDefaultChartFrequency}
              aradMultiSeriesHelpContext={aradMultiSeriesHelpContext}
              onWidgetConfigPatch={onWidgetConfigPatch}
              onViewerComparePreview={onViewerComparePreview}
              chartShareContext={chartShareContext}
              kpiSummaryMode={kpiSummaryMode}
            />
          </div>
          {(msPayload || msCompare) && aradRows.length > 0 ? (
            <div className="shrink-0 border-t border-border/60 bg-slate-50/90 px-2 py-1.5 dark:bg-slate-900/40">
              <MySeriesInlineActions savePayload={msPayload} compareLeft={msCompare} compact={compactLayout} />
            </div>
          ) : null}
        </div>
      );
    }
    case "user_upload_chart":
      return (
        <AradView
          userTitle={viewTitle}
          data={{ ...data, view: data?.view || "chart" }}
          widget={w}
          caption={viewCaption}
          aiCommentary={w.ai_commentary}
          aiAnalysis={w.ai_analysis_payload}
          defaultChartType={chartType}
          defaultChartFrequency={pageDefaultChartFrequency}
          aradMultiSeriesHelpContext={aradMultiSeriesHelpContext}
          onWidgetConfigPatch={onWidgetConfigPatch}
          onViewerComparePreview={onViewerComparePreview}
          chartShareContext={chartShareContext}
          kpiSummaryMode={kpiSummaryMode}
        />
      );
    case "formula_chart":
      return (
        <AradView
          userTitle={viewTitle}
          data={{
            ...data,
            view: data?.view || "chart",
            rows: mapFormulaRowsForAradView(data?.rows, data?.group_by),
          }}
          widget={w}
          caption={viewCaption}
          aiCommentary={w.ai_commentary}
          aiAnalysis={w.ai_analysis_payload}
          defaultChartType={chartType}
          defaultChartFrequency={pageDefaultChartFrequency}
          aradMultiSeriesHelpContext={aradMultiSeriesHelpContext}
          onWidgetConfigPatch={onWidgetConfigPatch}
          onViewerComparePreview={onViewerComparePreview}
          chartShareContext={chartShareContext}
          kpiSummaryMode={kpiSummaryMode}
        />
      );
    case "arad_view":
      return (
        <AradView
          userTitle={viewTitle}
          data={data}
          widget={w}
          caption={viewCaption}
          aiCommentary={w.ai_commentary}
          aiAnalysis={w.ai_analysis_payload}
          defaultChartType={pageDefaultChartType}
          defaultChartFrequency={pageDefaultChartFrequency}
          aradMultiSeriesHelpContext={aradMultiSeriesHelpContext}
          onWidgetConfigPatch={onWidgetConfigPatch}
          onViewerComparePreview={onViewerComparePreview}
          chartShareContext={chartShareContext}
          kpiSummaryMode={kpiSummaryMode}
        />
      );
    case "computed_view":
      return (
        <AradView
          userTitle={viewTitle}
          data={data}
          widget={w}
          caption={viewCaption}
          aiCommentary={w.ai_commentary}
          aiAnalysis={w.ai_analysis_payload}
          defaultChartType={pageDefaultChartType}
          defaultChartFrequency={pageDefaultChartFrequency}
          aradMultiSeriesHelpContext={aradMultiSeriesHelpContext}
          onWidgetConfigPatch={onWidgetConfigPatch}
          onViewerComparePreview={onViewerComparePreview}
          chartShareContext={chartShareContext}
          kpiSummaryMode={kpiSummaryMode}
        />
      );
    case "dataset_view":
    case "eurostat_view":
    case "csu_view":
    case "ecb_view":
    case "fred_view":
    case "alphavantage_view":
    case "worldbank_view":
    case "world_bank_data360_view":
    case "bis_view":
    case "imf_view":
    case "oecd_view":
    case "external_catalog_view":
    case "external_catalog_chart":
      if (data?.view === "chart") {
        // Pro CSU/ECB/Eurostat zdroje: rychlé přepnutí single-series/pivot; konkrétní
        // dimenze (Ukazatel, Odvětví, ÚZEMÍ-KRAJ, REF_AREA, …) se nastavuje v editoru
        // nebo přímo v grafu přes tlačítko „Dimenze".
        const canToggleSeries = canShowMultiSeriesToggle(viewType, config, data);
        const hasSeriesToggle = canToggleSeries && onWidgetConfigPatch && w.id;
        const splitDim = String(config?.chart_series_dim || "").trim();
        const preferCsuKraj =
          viewType === "csu_view" ||
          String(config?.source_type || "").toLowerCase() === "csu" ||
          String(config?.catalog || "").toLowerCase() === "csu";
        const splitOptions = getSeriesDimensionOptions(config, data);
        const effectiveSplitDim = pickSeriesDimension(config, data, { preferCsuKraj }) || splitDim || "ÚZEMÍ-KRAJ";
        const seriesToggle = hasSeriesToggle ? (
          <>
            <span className="text-[9px] uppercase tracking-wider text-slate-400 shrink-0">Série</span>
            <button
              type="button"
              onClick={() =>
                void safeWidgetConfigPatch(onWidgetConfigPatch, w.id, {
                  config: { ...(config || {}), chart_series_dim: null, chart_series_mode: "single" },
                })
              }
              className={`h-5 px-1.5 text-[10px] rounded border font-mono transition-colors ${
                !splitDim
                  ? "bg-blue-600 text-white border-blue-600"
                  : "bg-white text-slate-600 border-border/60 hover:bg-slate-100"
              }`}
            >
              Jedna řada
            </button>
            <button
              type="button"
              onClick={() =>
                void safeWidgetConfigPatch(onWidgetConfigPatch, w.id, {
                  config: buildMultiSeriesConfigPatch(config, data, effectiveSplitDim),
                })
              }
              className={`h-5 px-1.5 text-[10px] rounded border font-mono transition-colors ${
                splitDim
                  ? "bg-blue-600 text-white border-blue-600"
                  : "bg-white text-slate-600 border-border/60 hover:bg-slate-100"
              }`}
            >
              Více řad
            </button>
            {splitOptions.length > 0 ? (
              <label className="inline-flex h-5 items-center gap-1 rounded border border-blue-200 bg-blue-50 px-1.5 text-[10px] font-mono text-blue-900">
                <span className="text-blue-700/80">podle</span>
                <select
                  value={effectiveSplitDim}
                  onChange={(e) =>
                    void safeWidgetConfigPatch(onWidgetConfigPatch, w.id, {
                      config: buildMultiSeriesConfigPatch(config, data, e.target.value),
                    })
                  }
                  className="h-4 max-w-[9rem] rounded border-0 bg-transparent px-0 text-[10px] font-mono text-blue-900 outline-none"
                  title="Vybrat dimenzi pro více řad"
                >
                  {splitOptions.map((dim) => (
                    <option key={dim.field} value={dim.field}>
                      {dim.field}
                      {Array.isArray(dim.values) && dim.values.length > 1 ? ` (${dim.values.length})` : ""}
                    </option>
                  ))}
                </select>
              </label>
            ) : null}
          </>
        ) : null;
        // Reuse AradView for all external dataset charts so they inherit
        // the same interactive toolbar (time range, chart type, export, …).
        return (
          <div className="flex flex-col h-full min-h-0">
            <div className="flex-1 min-h-0">
              <AradView
                userTitle={viewTitle || data?.title}
                data={data}
                widget={w}
                caption={viewCaption}
                aiCommentary={w.ai_commentary}
                aiAnalysis={w.ai_analysis_payload}
                defaultChartType={pageDefaultChartType}
                defaultChartFrequency={pageDefaultChartFrequency}
                aradMultiSeriesHelpContext={aradMultiSeriesHelpContext}
                onWidgetConfigPatch={onWidgetConfigPatch}
                onViewerComparePreview={onViewerComparePreview}
                chartShareContext={chartShareContext}
                kpiSummaryMode={kpiSummaryMode}
                toolbarSlot={seriesToggle}
              />
            </div>
          </div>
        );
      }
      return (
        <DatasetTable
          panelConfig={config}
          title={viewTitle || data?.title}
          data={data}
          compact={compactLayout}
        />
      );
    case "markdown":
    case "text":
    case "note":
      // "text"/"note" jsou textové widgety osobního dashboardu (obsah v config.content) — renderují
      // se stejně jako "markdown" přes RichText. Bez těchto větví padaly do defaultu ("Neznámý widget").
      return <RichText title={viewTitle} data={data} config={config} widget={w} />;
    case "rss_monitoring":
      return <RssMonitoringWidget title={viewTitle} data={data} config={config} compact={compactLayout} />;
    case "ad":
      // Premium „bez reklam“ se netýká admin náhledu/editoru.
      if (adFreeDashboard && !isAdmin) {
        return null;
      }
      return <AdWidget data={data} config={config} />;
    default:
      return <ErrorCard title={viewTitle} message={`Neznámý widget: ${viewType}`} />;
  }
}

function KpiCard({ label, value, hint, trend, panelConfig }) {
  const Icon = trend === "up" ? ArrowUpRight : trend === "down" ? ArrowDownRight : Minus;
  const trendChip =
    trend === "up"
      ? { cls: "chip-mint" }
      : trend === "down"
      ? { cls: "chip-rose" }
      : { cls: "chip-cream" };
  const display = Number.isFinite(Number(value)) ? fmtInt(value) : value ?? "—";
  const ps = String(panelConfig?.panel_style || panelConfig?.card_style || "default").toLowerCase();
  const useClassicKpi = !ps || ps === "default" || ps === "white";
  const surface = resolveWidgetPanel(panelConfig);
  const wrapCls = useClassicKpi
    ? "kpi-card h-full"
    : `${surface.className} h-full flex flex-col gap-3 p-6`;
  const wrapStyle = useClassicKpi ? undefined : surface.style;
  return (
    <div
      className={wrapCls}
      style={wrapStyle}
      data-testid={`kpi-${String(label).toLowerCase().replace(/\s+/g, "-")}`}
    >
      <div className="flex items-center justify-between">
        <span className="kpi-label">{label}</span>
        <span
          className={`inline-flex items-center justify-center h-6 w-6 rounded-full ${trendChip.cls}`}
          title={trend || "n/a"}
        >
          <Icon className="h-3 w-3" strokeWidth={2} />
        </span>
      </div>
      <div className="kpi-value">{display}</div>
      {hint && <div className="kpi-hint">{hint}</div>}
    </div>
  );
}

function NetResultChart({ title, data, panelConfig }) {
  const surface = resolveWidgetPanel(panelConfig);
  const rows = data?.rows || [];
  const n = rows.length;
  const xTilt = n > 24 ? -35 : 0;
  const netMargins = { top: 8, right: 10, left: 6, bottom: xTilt ? 38 : 22 };
  const xTick = {
    fontSize: 10,
    fill: CHART_PALETTE.axis,
    fontFamily: "JetBrains Mono",
    ...(xTilt ? { angle: xTilt, textAnchor: "end" } : {}),
  };
  return (
    <div className={`${surface.className} p-6 h-full min-w-0`} style={surface.style}>
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <div className="kpi-label line-clamp-2">{title}</div>
          <div className="font-serif text-[42px] leading-none mt-3">
            {fmtCurrency(data?.total || 0)}
          </div>
          <div className="text-[11px] text-slate-500 font-mono mt-2">vzorec: profit.amount − loss.amount</div>
        </div>
        <span className="text-[10px] uppercase tracking-[0.16em] px-3 py-1 rounded-full chip-mint font-medium shrink-0 self-start">
          Posledních 30 dní
        </span>
      </div>
      <div className="h-64 mt-6 min-w-0">
        <SafeRechartsContainer minHeight={72}>
          <AreaChart data={rows} margin={netMargins}>
            <defs>
              <linearGradient id="netGradient" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={CHART_PALETTE.mint} stopOpacity={0.35} />
                <stop offset="100%" stopColor={CHART_PALETTE.mint} stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid vertical={false} stroke={CHART_PALETTE.grid} strokeDasharray="2 4" />
            <XAxis
              dataKey="date"
              tick={xTick}
              tickLine={false}
              interval={0}
              minTickGap={14}
              height={xTilt ? 34 : 24}
              axisLine={{ stroke: CHART_PALETTE.grid }}
            />
            <YAxis width={56} tick={{ fontSize: 10, fill: CHART_PALETTE.axis, fontFamily: "JetBrains Mono" }} tickLine={false} axisLine={false} tickFormatter={(v) => fmtCompact(v)} />
            <Tooltip
              cursor={{ stroke: CHART_PALETTE.mint, strokeDasharray: "2 4" }}
              {...mergeRechartsTooltipProps({
                contentStyle: { fontSize: 12 },
                formatter: (v) => fmtNumber(v),
              })}
            />
            <ReferenceLine y={0} stroke={CHART_PALETTE.grid} />
            <Area type="monotone" dataKey="result" stroke={CHART_PALETTE.mintDeep} strokeWidth={2} fill="url(#netGradient)" />
          </AreaChart>
        </SafeRechartsContainer>
      </div>
    </div>
  );
}

function DistributionChart({ title, data, panelConfig }) {
  const surface = resolveWidgetPanel(panelConfig);
  const rows = Array.isArray(data) ? data : [];
  const n = rows.length;
  const xTilt = n > 12 ? -32 : 0;
  const distMargins = { top: 8, right: 10, left: 6, bottom: xTilt ? 40 : 20 };
  const xTick = {
    fontSize: 10,
    fill: CHART_PALETTE.axis,
    fontFamily: "JetBrains Mono",
    ...(xTilt ? { angle: xTilt, textAnchor: "end" } : {}),
  };
  const barValues = useMemo(
    () =>
      rows
        .map((row) => coerceChartNumeric(row?.count))
        .filter((v) => typeof v === "number" && Number.isFinite(v)),
    [rows]
  );
  const barAxis = useMemo(() => getYAxisDomainForChart("bar", barValues, { tickCount: 4 }), [barValues]);
  // Cycle through pastel palette so each bar gets its own accent
  const palette = [
    CHART_PALETTE.mint,
    CHART_PALETTE.rose,
    CHART_PALETTE.cream,
    CHART_PALETTE.blue,
    CHART_PALETTE.lavender,
    CHART_PALETTE.terracotta,
  ];
  return (
    <div className={`${surface.className} p-6 h-full min-w-0`} style={surface.style}>
      <div className="kpi-label line-clamp-2">{title}</div>
      <div className="text-sm text-slate-500 mt-1.5">Počet záznamů v sadách</div>
      <div className="h-64 mt-6 min-w-0">
        <SafeRechartsContainer minHeight={72}>
          <BarChart data={rows} margin={distMargins}>
            <CartesianGrid vertical={false} stroke={CHART_PALETTE.grid} strokeDasharray="2 4" />
            <XAxis
              dataKey="name"
              tick={xTick}
              tickLine={false}
              interval={0}
              minTickGap={12}
              height={xTilt ? 36 : 26}
              axisLine={{ stroke: CHART_PALETTE.grid }}
            />
            <YAxis
              width={48}
              tick={{ fontSize: 10, fill: CHART_PALETTE.axis, fontFamily: "JetBrains Mono" }}
              tickLine={false}
              axisLine={false}
              domain={barAxis.domain}
              ticks={barAxis.axis.ticks}
              allowDataOverflow={false}
              niceTicks="none"
            />
            <Tooltip
              cursor={{ fill: "hsl(202 90% 52% / 0.12)" }}
              {...mergeRechartsTooltipProps({ contentStyle: { fontSize: 12 } })}
            />
            <Bar dataKey="count" radius={[6, 6, 0, 0]} minPointSize={0}>
              {rows.map((_, i) => (
                <Cell key={i} fill={palette[i % palette.length]} />
              ))}
            </Bar>
          </BarChart>
        </SafeRechartsContainer>
      </div>
    </div>
  );
}

function SyncTable({ title, rows, panelConfig }) {
  const surface = resolveWidgetPanel(panelConfig);
  return (
    <div className={`${surface.className} overflow-hidden`} style={surface.style}>
      <div className="px-6 py-5 border-b border-border/60">
        <div className="kpi-label">{title}</div>
        <div className="text-sm text-slate-500 mt-1.5">Posledních {rows.length} spuštění konektorů</div>
      </div>
      {rows.length === 0 ? (
        <div className="px-6 py-10 text-sm text-slate-500 font-mono border border-dashed border-border mx-6 my-6 rounded-sm text-center">
          Zatím žádná synchronizace neproběhla.
        </div>
      ) : (
        <table className="data-table">
          <thead>
            <tr>
              <th>Zdroj</th>
              <th>Stav</th>
              <th>Zahájeno</th>
              <th>Dokončeno</th>
              <th className="num">Záznamů</th>
              <th>Zpráva</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((l) => (
              <tr key={l.id}>
                <td className="font-medium">{l.source_name}</td>
                <td><StatusBadge status={l.status} /></td>
                <td className="mono text-xs text-slate-600">{fmtDateTime(l.started_at)}</td>
                <td className="mono text-xs text-slate-600">{fmtDateTime(l.finished_at)}</td>
                <td className="num mono">{fmtInt(l.records_ingested)}</td>
                <td className="text-xs text-slate-500 truncate max-w-[360px]">{l.message}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function DatasetTable({ title, data, compact, panelConfig }) {
  const surface = resolveWidgetPanel(panelConfig);
  const rows = data?.rows || [];
  const fields = data?.fields || [];
  const pad = compact ? "px-2 py-2" : "px-6 py-5";
  const sub = compact ? "text-[9px] leading-snug" : "text-sm text-slate-500 mt-1.5";
  const lbl = compact ? "text-[10px] font-semibold uppercase tracking-wider text-slate-600 truncate" : "kpi-label";
  return (
    <div className={`${surface.className} overflow-hidden h-full min-h-0 flex flex-col`} style={surface.style}>
      <div className={`${pad} border-b border-border/60 flex items-start justify-between gap-2 shrink-0`}>
        <div className="min-w-0">
          <div className={lbl}>{title || data?.dataset || "Záznamy"}</div>
          <div className={sub}>Datová sada: {data?.dataset || "—"} · {rows.length} řádků</div>
        </div>
        <ExportMenu
          title={title || data?.dataset || "data"}
          subtitle={`${rows.length} záznamů`}
          columns={fields}
          rows={rows}
          compact={compact}
        />
      </div>
      {rows.length === 0 ? (
        <div className={`${compact ? "px-2 py-4 text-[10px]" : "px-6 py-10 text-sm"} text-slate-500 font-mono text-center`}>Žádné záznamy.</div>
      ) : (
        <div className={`overflow-x-auto flex-1 min-h-0 ${compact ? "max-h-[220px]" : ""}`}>
          <table className={`data-table ${compact ? "text-[9px]" : ""}`}>
            <thead>
              <tr>{fields.map((f) => <th key={f} className={compact ? "!py-1 !px-1" : ""}>{f}</th>)}</tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={i}>
                  {fields.map((f) => <td key={f} className={`mono ${compact ? "text-[9px] !py-0.5" : "text-xs"}`}>{String(r?.[f] ?? "")}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function ErrorCard({ title, message }) {
  return (
    <div className="soft-card p-5 h-full" style={{ borderColor: "hsl(354 60% 90%)" }}>
      <div className="flex items-center gap-2" style={{ color: "hsl(354 60% 45%)" }}>
        <AlertTriangle className="h-4 w-4" strokeWidth={1.6} />
        <div className="kpi-label" style={{ color: "hsl(354 60% 45%)" }}>{title || "Widget"}</div>
      </div>
      <div className="text-xs font-mono mt-2 break-words" style={{ color: "hsl(354 50% 40%)" }}>{message}</div>
    </div>
  );
}

export function StatusBadge({ status }) {
  const map = {
    success: { icon: CheckCircle2, label: "Úspěch", cls: "chip-mint", spin: false },
    error: { icon: XCircle, label: "Chyba", cls: "chip-rose", spin: false },
    running: { icon: RefreshCw, label: "Probíhá", cls: "chip-cream", spin: true },
    timeout: { icon: Clock, label: "Vypršení", cls: "chip-navy", spin: false },
    partial: { icon: CircleAlert, label: "Částečně", cls: "chip-lavender", spin: false },
    stuck: {
      icon: AlertTriangle,
      label: "Zaseknutá synch.",
      cls: "chip-rose ring-1 ring-amber-200",
      spin: false,
    },
  };
  const m = map[status] || map.running;
  const Icon = m.icon;
  return (
    <span className={`inline-flex items-center gap-1.5 text-[10px] uppercase tracking-[0.12em] font-medium px-2.5 py-1 rounded-full ${m.cls}`}>
      <Icon className={`h-3 w-3 ${m.spin ? "animate-spin" : ""}`} strokeWidth={2} />
      {m.label}
    </span>
  );
}
