import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, lazy, Suspense } from "react";
import { createPortal } from "react-dom";
import { useNavigate } from "react-router-dom";
import {
  LineChart,
  Line,
  BarChart,
  Bar,
  AreaChart,
  Area,
  ComposedChart,
  PieChart,
  Pie,
  Cell,
  Legend,
  LabelList,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ReferenceLine,
  ReferenceArea,
  ReferenceDot,
} from "recharts";
import {
  LineChart as LineIcon,
  Table as TableIcon,
  SlidersHorizontal,
  X as XIcon,
  GitCompare,
  Pencil,
  Check,
  Maximize2,
  ArrowLeftRight,
  ClipboardCopy,
  Database,
} from "lucide-react";
import api from "@/lib/api";
import { fmtCompact, fmtPeriodAxisTick, fmtPeriodAxisTickFreqAware, fmtPeriodLabel, parseNumber } from "@/lib/format";
import { aggregateBucketValues } from "@/lib/chartTimeSeriesPivot";
import { parseChartPeriod } from "@/lib/chartPeriodParse";
import {
  buildRechartsValueDomain,
  buildSafeNumericAxis,
  BAR_VALUE_AXIS,
  chartBarPointValue,
  chartRowsWithZeroBaselineBars,
  getBarChartValueAxisSpec,
  getLineChartValueAxisSpec,
  getYAxisDomainForChart,
  clampNum,
  coerceChartNumeric,
  collectOverlayYValues,
  computeNextXZoom,
  getFullXDomain,
  getSafeYDomain,
  getVisibleData,
} from "@/lib/chartZoomHelpers";
import { isChartDebugEnabled, measureAndLogBarChartDebug } from "@/lib/chartBarDebug";
import {
  buildPeriodAnnotationLayout,
} from "../../lib/periodAnnotationLayout";
import UploadSeriesPanel from "@/components/widgets/UploadSeriesPanel";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";
import { useIsMobileDashboard } from "@/hooks/useMediaQuery";
import { isMobileEmbedPath } from "@/lib/mobileEmbed";
import { usePhysicalLandscape } from "@/hooks/usePhysicalLandscape";
import MobileChartLandscapeShell from "@/components/widgets/MobileChartLandscapeShell";
import { useAuth } from "@/contexts/AuthContext";
import FeatureLock from "@/components/FeatureLock";
import ExportMenu from "@/components/widgets/ExportMenu";
import CatalogChartShareButtons from "@/components/catalog/CatalogChartShareButtons";
import { buildWidgetConceptExplainMeta } from "@/components/explore/SeriesConceptExplainTrigger";
import VerticalResizeHandle from "@/components/common/VerticalResizeHandle";
import { useVerticalSplitDrag } from "@/hooks/useVerticalSplitDrag";
import ChartAnalystTrigger, { ChartAnalystPanel } from "@/components/widgets/ChartAnalystTrigger";
import ChartStaleDataNotice from "@/components/catalog/ChartStaleDataNotice";
import { SafeRechartsContainer } from "@/lib/SafeRechartsContainer";
import { resolveWidgetPanel } from "@/lib/widgetPanel";
import { cleanWidgetCaption } from "@/lib/widgetCaption";
import { buildChartTheme } from "@/lib/chartTheme";
import { resolveNativeFrequencyCode } from "@/lib/chartFrequencyInfer";
import { ALL_FREQS, FREQ_RANK, resolveDefaultTargetFreq } from "@/components/widgets/aradViewChartFreq";
import { mergeRechartsTooltipProps } from "@/lib/rechartsTooltipShared";
import ChartVisualConfigControls from "@/components/charts/ChartVisualConfigControls";
const SpecialChartView = lazy(() => import("@/components/charts/SpecialChartViews"));
import { CHART_KINDS, isCustomChartKind, normalizeChartKind, normalizeIconOrientation } from "@/lib/chartKindCatalog";
import {
  buildGeoMapRowsFromChartRows,
  isGeoGroupField,
  rowLabelToIso,
} from "@/lib/chartGeoMapData";
import { chartDisplayStatesEqual } from "@/lib/catalogSeriesDetailMetadata";

import { contractFromAradViewState } from "@/charts/contractFromAradViewState";
import { safeWidgetConfigPatch } from "@/lib/safeWidgetConfigPatch";
import {
  applyAradViewDisplayTransform,
  ARAD_VIEW_TRANSFORMS,
} from "@/charts/applyAradViewDisplayTransform";
import { resolveToolbarCapabilities } from "@/charts/chartToolbarCapabilities";
import { buildOlapCubePackage, copyChartDataWideToClipboard } from "@/charts/chartExport";
import { normalizeExportPeriod } from "@/charts/chartExportSanitize";
import {
  alignMultiSeriesRowsToCoarsestFrequency,
  CHART_FREQUENCY_LABEL_CS,
} from "@/lib/chartFrequencyAlign";
import { buildChartSeriesStatistics, buildSingleSeriesStatistics } from "@/lib/chartSeriesStatistics";
import AradViewChartDataPanel from "@/charts/AradViewChartDataPanel";
import ChartTooltip from "@/charts/ChartTooltip";
import {
  barChartHorizontalScrollEnabled,
  categoryAxisLabelMax,
  chartScrollMinWidth as resolveChartScrollMinWidth,
  makeCategoryAxisTick,
} from "@/charts/chartPlotHelpers";
import { computeValueCompareStats, resolveKpiSummaryMode } from "@/charts/chartValueCompareStats";
import { computeTimeSeriesStats } from "@/charts/chartTimeSeriesStats";
import AradChartCompareModal from "@/components/widgets/AradChartCompareModal";
import {
  resolveChartCompareToolbarVisible,
  resolveChartTransformToolbarVisible,
  resolveChartActionsInSidePanel,
} from "@/components/widgets/aradViewToolbarVisibility";
import DatasetChartCompareModal from "@/components/widgets/DatasetChartCompareModal";
import UnifiedChartCompareModal from "@/components/widgets/UnifiedChartCompareModal";
import AradViewAdvancedControlsPanel from "@/components/widgets/arad/AradViewAdvancedControlsPanel";
import AradViewChartInsightsPanel from "@/components/widgets/arad/AradViewChartInsightsPanel";
import AradViewCaptionNotesPanel from "@/components/widgets/arad/AradViewCaptionNotesPanel";
import AradViewDataTablePanel from "@/components/widgets/arad/AradViewDataTablePanel";
import {
  canPersistChartCompare,
  isExternalCatalogWidgetEngine,
  isUploadPrimaryWidgetEngine,
  resolveDatasetChartCompareBaseline,
  resolveExternalCatalogCompareBaseline,
} from "@/components/widgets/datasetChartCompareBaseline";
import { DATASET_CHART_ENGINE_TYPES } from "@/lib/datasetChartEngineTypes";
import {
  bucketKey,
  selectEvenlySpacedXTicksArad,
  maxXTicksCountArad,
  fmtTrendSlopePerStep,
  normalizeRollingWindow,
  normalizeManualTrendLine,
  medianOfNumbers,
  trendSegmentForPoints,
  resolveInitialDataView,
  normalizeAgg,
  normalizeBarOrientation,
  normalizePieVariant,
} from "@/lib/aradViewUtils";

const MIN_BAR_POINT_SIZE = 2;

function normalizeAnnotationSearchText(value) {
  return String(value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function annotationRemovalTokens(action) {
  const text = normalizeAnnotationSearchText([
    action?.query,
    action?.target,
    action?.target_cz,
    action?.label,
    action?.layer_id,
  ].filter(Boolean).join(" "));
  const stop = new Set([
    "oddelej", "odstran", "smaz", "vymaz", "skryj", "zrus", "zrusit",
    "remove", "delete", "clear", "hide", "mi", "prosim", "ty", "to", "tam",
    "z", "ze", "do", "graf", "grafu", "anotace", "anotaci", "vyznaceni",
    "zvyrazneni", "udalosti", "udalost", "obdobi", "interval", "vrstva",
    "vrstvy", "popisky", "popisek", "period", "annotation", "annotations",
    "event", "events",
  ]);
  return text.split(/\s+/).filter((token) => token.length > 2 && !stop.has(token));
}

function annotationMatchesRemoval(annotation, tokens) {
  if (!Array.isArray(tokens) || !tokens.length) return true;
  const haystack = normalizeAnnotationSearchText([
    annotation?.label,
    annotation?.description_cz,
    annotation?.layer_id,
    annotation?.from,
    annotation?.to,
  ].filter(Boolean).join(" "));
  return tokens.some((token) => haystack.includes(token));
}

/**
 * Jednotný renderer časových řad (graf / tabulka / export) pro všechny datové zdroje,
 * které do něj směruje `WidgetRenderer` — ARAD, katalog (ČSÚ, Eurostat, …), vlastní výpočty,
 * upload, vzorce, legacy `dataset_chart`. Název souboru `AradView` je historický.
 */

/** Mapování engine_type / type na zobrazovaný název datového zdroje. */
const SOURCE_LABELS = {
  arad_view: "ARAD · ČNB",
  eurostat_view: "Eurostat",
  csu_view: "ČSÚ",
  ecb_view: "ECB",
  fred_view: "FRED",
  alphavantage_view: "Alpha Vantage",
  worldbank_view: "World Bank",
  world_bank_data360_view: "World Bank",
  bis_view: "BIS",
  imf_view: "IMF",
  oecd_view: "OECD",
  dataset_view: "data",
};

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

function asObject(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

const CATALOG_SOURCE_LABELS = {
  arad: "ARAD · ČNB",
  csu: "ČSÚ",
  eurostat: "Eurostat",
  ecb: "ECB",
  fred: "FRED",
  alphavantage: "Alpha Vantage",
  worldbank: "World Bank",
  world_bank: "World Bank",
  data360: "World Bank",
  world_bank_data360: "World Bank",
  bis: "BIS",
  imf: "IMF",
  oecd: "OECD",
};

function resolveSourceLabel(widget, data, widgetEngine) {
  if (widgetEngine === "external_catalog_chart" || widgetEngine === "external_catalog_view") {
    const cfg = widget?.config && typeof widget.config === "object" ? widget.config : {};
    const fromCfg = String(cfg.catalog || cfg.source_type || "").trim().toLowerCase();
    const fromDataset = String(data?.dataset || "").match(/^catalog:([^:]+)/i)?.[1]?.toLowerCase() || "";
    const key = fromCfg && fromCfg !== "external_catalog" ? fromCfg : fromDataset;
    return CATALOG_SOURCE_LABELS[key] || (key ? key.toUpperCase() : "Katalog");
  }
  return SOURCE_LABELS[widgetEngine] || "";
}

function ellipsizeLabel(value, max = 16) {
  const s = String(value ?? "").trim();
  if (!s || s.length <= max) return s;
  return `${s.slice(0, Math.max(1, max - 1)).trimEnd()}…`;
}

function resolveDefaultMedianLineEnabled(widget) {
  if (widget?.config?.chart_median_line === true) return true;
  if (widget?.config?.chart_median_line === false) return false;
  const latest =
    String(widget?.config?.chart_data_mode || widget?.data?.chart_data_mode || "").toLowerCase() ===
    "latest";
  const kind = String(widget?.config?.chart_type || widget?.data?.chart_type || "line").toLowerCase();
  return latest && kind === "bar";
}

const BAR_ORIENTATIONS = [
  { id: "vertical", label: "Svisle" },
  { id: "horizontal", label: "Vodorovně" },
];

const PIE_VARIANTS = [
  { id: "donut", label: "Kolečko" },
  { id: "full", label: "Plný" },
];

// Infographic-matched palette — bright cyan primary, deep navy, medium blue,
// pale blue + warm accents so pie slices stay readable and cheerful.
const PIE_COLORS = [
  "hsl(202 90% 52%)", // bright cyan-blue (primary)
  "hsl(218 65% 28%)", // deep navy
  "hsl(208 75% 48%)", // medium steel blue
  "hsl(205 65% 78%)", // pale blue
  "hsl(225 55% 65%)", // periwinkle
  "hsl(188 65% 52%)", // teal
  "hsl(240 55% 70%)", // lavender
  "hsl(344 70% 70%)", // rose
  "hsl(36 90% 68%)",  // warm cream
  "hsl(18 75% 62%)",  // terracotta
  "hsl(165 60% 48%)", // emerald-teal
  "hsl(275 45% 68%)", // orchid
];

const CHART_COLORS = {
  primary: "hsl(202 90% 52%)",     // bright cyan — main line/area stroke
  primarySoft: "hsl(208 75% 48%)", // medium blue — bar fill
  grid: "#D4E6F7",
  axis: "#5878A0",
};

/** Recharts 3: explicitní ticks + doména; data se nevykreslují mimo osu (headroom řeší buildSafeNumericAxis). */
const EXPLICIT_LINEAR_AXIS = { allowDataOverflow: false, niceTicks: "none" };

// hexToRgb a buildChartTheme jsou sdílené v `@/lib/chartTheme` (používá je
// i RichText / ostatní widgety, aby barva byla konzistentní napříč panely).

// Frequency rollup mirrors the ARAD UI exactly: D → W → M → Q → H → Y.
export { ALL_FREQS, FREQ_RANK, resolveDefaultTargetFreq } from "@/components/widgets/aradViewChartFreq";

/** Formát popisku osy X při vykreslení; freqCode zajišťuje jednotný formát v rámci grafu. */
export function formatXAxisTick(value, _visibleXDomain, freqCode) {
  if (freqCode) return fmtPeriodAxisTickFreqAware(value, freqCode);
  return fmtPeriodAxisTick(value);
}

function manualTrendSegment(manualTrendLine) {
  const line = normalizeManualTrendLine(manualTrendLine);
  if (!line) return null;
  return {
    xStart: line.start.x,
    yStart: line.start.y,
    xEnd: line.end.x,
    yEnd: line.end.y,
  };
}

function FrozenYAxisGutter({ spec, chartTheme }) {
  const rootRef = useRef(null);
  const [height, setHeight] = useState(0);

  useLayoutEffect(() => {
    const el = rootRef.current;
    if (!el) return undefined;
    const sync = () => setHeight(el.clientHeight || 0);
    sync();
    if (typeof ResizeObserver === "undefined") {
      window.addEventListener("resize", sync);
      return () => window.removeEventListener("resize", sync);
    }
    const ro = new ResizeObserver(sync);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const min = Number(spec?.min);
  const max = Number(spec?.max);
  const plotTop = Number(spec?.top) || 0;
  const plotBottom = Number(spec?.bottom) || 0;
  const axisTop = plotTop;
  const axisHeight = Math.max(0, height - plotTop - plotBottom);
  const fontSize = Number(spec?.fontSize) || 10;
  const lineHeight = fontSize + 5;

  const displayTicks = useMemo(() => {
    const all = Array.isArray(spec?.ticks) ? spec.ticks : [];
    if (!all.length || !Number.isFinite(min) || !Number.isFinite(max) || max === min) return [];
    const maxLabels = Math.max(2, Math.floor(axisHeight / lineHeight));
    if (all.length <= maxLabels) return all;
    const picked = [];
    for (let i = 0; i < maxLabels; i += 1) {
      const idx = Math.round((i * (all.length - 1)) / Math.max(1, maxLabels - 1));
      picked.push(all[idx]);
    }
    return [...new Set(picked)];
  }, [spec?.ticks, min, max, axisHeight, lineHeight]);

  if (!spec || displayTicks.length === 0) return null;

  const axisColor = chartTheme?.grid || CHART_COLORS.grid;
  const tickColor = chartTheme?.axis || CHART_COLORS.axis;
  const maskBleed = 4;
  const unitLabel = String(spec?.unit || "").trim();

  return (
    <div
      ref={rootRef}
      className="relative h-full shrink-0 self-stretch overflow-visible"
      style={{
        width: spec.width + maskBleed,
        background: "hsl(var(--card))",
      }}
      aria-hidden
    >
      {unitLabel ? (
        <div
          className="pointer-events-none absolute left-0 top-1 z-[1] max-h-[42%] max-w-[calc(100%-4px)] truncate px-0.5 text-[8px] font-semibold uppercase leading-tight tracking-wide"
          style={{
            color: tickColor,
            writingMode: "vertical-rl",
            transform: "rotate(180deg)",
          }}
          title={unitLabel}
        >
          {unitLabel}
        </div>
      ) : null}
      <div className="absolute inset-0 overflow-visible" style={{ left: 0, right: maskBleed }}>
        <div
          className="absolute right-0 w-px"
          style={{
            top: axisTop,
            height: axisHeight,
            backgroundColor: axisColor,
          }}
        />
        {displayTicks.map((tick) => {
          const ratio = (Number(tick) - min) / (max - min);
          const rawY = axisTop + (1 - ratio) * axisHeight;
          const labelPad = Math.ceil(fontSize * 0.55);
          const y = Math.min(height - plotBottom - labelPad, Math.max(axisTop + labelPad, rawY));
          return (
            <div
              key={tick}
              className="pointer-events-none absolute left-0 right-1 flex items-center justify-end pr-1 font-mono"
              style={{
                top: y,
                transform: "translateY(-50%)",
                color: tickColor,
                fontSize,
              }}
            >
              {fmtCompact(tick)}
            </div>
          );
        })}
      </div>
    </div>
  );
}

// Horní okraj vykreslovací oblasti — tick popisky Y osy a horní grid nesmí narazit na overflow karty.
function chartAreaTopMargin({ showBarLabels = false, miniNarrow = false, compact = false } = {}) {
  if (showBarLabels) return compact ? 20 : 24;
  if (miniNarrow) return 10;
  return compact ? 16 : 22;
}

/** Sdílené okraje plot area — musí sedět mezi Recharts margin a FrozenYAxisGutter. */
function computeChartPlotMargins({
  latestBarMode = false,
  showBarLabels = false,
  miniNarrow = false,
  compact = false,
  mobileDense = false,
  veryNarrow = false,
  n = 0,
  shortLabels = false,
} = {}) {
  // Krátké popisky (MM, QN, YYYY) se vejdou vodorovně i u ~12 bodů → nenaklánět (jinak „spadnou“ pod graf).
  const useTilt = latestBarMode
    ? n > 4 && n <= LATEST_BAR_TILT_MAX_ITEMS
    : !veryNarrow && !miniNarrow && n > 8 && !(shortLabels && n <= 16);
  if (mobileDense && !latestBarMode) {
    return {
      top: showBarLabels ? 14 : 8,
      bottom: useTilt ? 6 : 4,
      useTilt,
    };
  }
  const latestBottom = latestBarMode
    ? useTilt
      ? compact
        ? 38
        : 48
      : compact
        ? 22
        : 28
    : null;
  return {
    top: chartAreaTopMargin({ showBarLabels, miniNarrow, compact }),
    bottom:
      latestBottom ??
      (useTilt
        ? compact
          ? 26
          : 28
        : veryNarrow
          ? 34
          : compact
            ? 16
            : 12),
    useTilt,
  };
}

// Build one of four recharts chart kinds on the same aggregated rows.
function buildHighlightDots(chartRows, highlightSpec, primary) {
  if (!highlightSpec || !Array.isArray(chartRows) || !chartRows.length) return null;
  const pts = chartRows
    .map((r) => ({
      x: r.x,
      y: coerceChartNumeric(chartBarPointValue(r.y) ?? r.y),
    }))
    .filter((p) => p.x != null && p.y != null && Number.isFinite(p.y));
  if (!pts.length) return null;
  const nodes = [];
  if (highlightSpec.latest) {
    const last = pts[pts.length - 1];
    nodes.push(
      <ReferenceDot
        key="hl-latest"
        x={last.x}
        y={last.y}
        r={5}
        fill={primary}
        stroke="#fff"
        strokeWidth={2}
        ifOverflow="extendDomain"
      />
    );
  }
  if (highlightSpec.extrema) {
    let maxP = pts[0];
    let minP = pts[0];
    for (const p of pts) {
      if (p.y > maxP.y) maxP = p;
      if (p.y < minP.y) minP = p;
    }
    nodes.push(
      <ReferenceDot
        key="hl-max"
        x={maxP.x}
        y={maxP.y}
        r={5}
        fill="#b45309"
        stroke="#fff"
        strokeWidth={2}
        ifOverflow="extendDomain"
      />
    );
    if (minP.x !== maxP.x || minP.y !== maxP.y) {
      nodes.push(
        <ReferenceDot
          key="hl-min"
          x={minP.x}
          y={minP.y}
          r={5}
          fill="#64748b"
          stroke="#fff"
          strokeWidth={2}
          ifOverflow="extendDomain"
        />
      );
    }
  }
  return nodes.length ? nodes : null;
}

const PERIOD_ANNOTATION_PALETTES = {
  blue: { fill: "#2563eb", stroke: "#1d4ed8", text: "#1e3a8a" },
  red: { fill: "#dc2626", stroke: "#b91c1c", text: "#7f1d1d" },
  gray: { fill: "#64748b", stroke: "#475569", text: "#334155" },
  amber: { fill: "#f59e0b", stroke: "#d97706", text: "#92400e" },
  green: { fill: "#16a34a", stroke: "#15803d", text: "#14532d" },
  purple: { fill: "#9333ea", stroke: "#7e22ce", text: "#581c87" },
};

function periodAnnotationPalette(value) {
  return PERIOD_ANNOTATION_PALETTES[String(value || "").trim().toLowerCase()]
    || PERIOD_ANNOTATION_PALETTES.amber;
}

function PeriodBandLabel({ viewBox, label, color }) {
  if (!viewBox || !label) return null;
  const width = Number(viewBox.width || 0);
  const maxCharacters = Math.floor((width - 8) / 5.4);
  if (maxCharacters < 4) return null;
  const fullLabel = String(label || "Událost").trim();
  const displayLabel = fullLabel.length > maxCharacters
    ? `${fullLabel.slice(0, Math.max(1, maxCharacters - 1)).trimEnd()}…`
    : fullLabel;
  const centerX = Number(viewBox.x || 0) + width / 2;
  const baselineY = Number(viewBox.y || 0) + 13;
  return (
    <g className="arad-period-annotation-label" pointerEvents="none">
      <title>{fullLabel}</title>
      <text
        x={centerX}
        y={baselineY}
        textAnchor="middle"
        fill={color}
        stroke="#ffffff"
        strokeOpacity={0.82}
        strokeWidth={2.4}
        paintOrder="stroke"
        fontSize={9}
        fontWeight={700}
      >
        {displayLabel}
      </text>
    </g>
  );
}

function renderPeriodAnnotationNodes(layout, hasMultipleAxes = false) {
  if (!Array.isArray(layout?.entries) || !layout.entries.length) return null;
  return layout.entries.map((entry) => {
    const { annotation, from, to, label, displayLabel, originalIndex } = entry;
    const palette = periodAnnotationPalette(annotation?.color);
    const displayMode = String(annotation?.display_mode || (from === to ? "marker" : "band")).toLowerCase();
    if (displayMode === "marker") {
      return (
        <ReferenceLine
          key={`web-event-marker-${from}-${originalIndex}`}
          {...(hasMultipleAxes ? { yAxisId: "left" } : {})}
          x={from}
          stroke={palette.stroke}
          strokeWidth={1.8}
          strokeDasharray="4 3"
          ifOverflow="extendDomain"
          label={{ value: displayLabel, position: "insideTopRight", fill: palette.text, fontSize: 9, fontWeight: 700 }}
        />
      );
    }
    return (
      <ReferenceArea
        key={`web-event-${from}-${to}-${originalIndex}`}
        {...(hasMultipleAxes ? { yAxisId: "left" } : {})}
        x1={from}
        x2={to}
        fill={palette.fill}
        fillOpacity={0.13}
        stroke={palette.stroke}
        strokeOpacity={0.6}
        strokeWidth={1}
        ifOverflow="extendDomain"
        label={(
          <PeriodBandLabel
            label={label}
            color={palette.text}
          />
        )}
      />
    );
  });
}

function nearestChartPeriod(target, rows) {
  const value = String(target || "").trim();
  const periods = (Array.isArray(rows) ? rows : [])
    .map((row) => String(row?.x ?? row?.period ?? "").trim())
    .filter(Boolean);
  if (!value || !periods.length) return "";
  if (periods.includes(value)) return value;
  const targetDate = parseChartPeriod(value);
  if (!targetDate) return "";
  let nearest = "";
  let nearestDistance = Number.POSITIVE_INFINITY;
  for (const period of periods) {
    const date = parseChartPeriod(period);
    if (!date) continue;
    const distance = Math.abs(date.getTime() - targetDate.getTime());
    if (distance < nearestDistance) {
      nearestDistance = distance;
      nearest = period;
    }
  }
  return nearest;
}

// For pie we cap slices so a 70-quarter series doesn't become unreadable —
// the largest 11 values keep their label and everything else collapses
// into a single "Ostatní" slice (matches ARAD UI behaviour).
function renderChart(
  kind,
  chartRows,
  unit,
  compact,
  chartColor,
  chartTheme,
  widgetWidth,
  miniMode = false,
  barMultiColor = true,
  latestDataMode = false,
  showBarLabels = false,
  hideYAxis = false,
  barOrientation = "vertical",
  pieVariant = "donut",
  yAxisOverride = null,
  denseXTicks = false,
  visibleXDomainForFormat = null,
  valueCompareMode = false,
  overlaySpec = null,
  sharedBarYAxisSpec = null,
  highlightSpec = null,
  mobileDense = false,
  freqCode = null,
  periodAnnotations = []
) {
  const override = /^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})$/.test((chartColor || "").trim())
    ? chartColor
    : null;
  const primary = override || CHART_COLORS.primary;
  const primarySoft = override || CHART_COLORS.primarySoft;
  const unitSuffix = unit ? ` ${unit}` : "";
  const n = chartRows.length;
  const latestBarMode = kind === "bar" && (latestDataMode || valueCompareMode);
  // Velmi úzké widgety (1/8, 1/6) potřebují agresivně zmenšit popisky obou os,
  // aby se Y-osa vůbec vešla a X-osa nezmizela kvůli sklonu.
  const veryNarrow = widgetWidth === "eighth" || widgetWidth === "sixth";
  const miniNarrow = miniMode && (veryNarrow || compact);
  const denseMobile = Boolean(mobileDense) && !miniMode;
  const layoutCompact = compact || denseMobile;
  const mxc = denseXTicks
    ? Math.min(24, Math.max(8, Math.ceil(n / 8)))
    : maxXTicksCountArad(n, layoutCompact, veryNarrow);
  const xTickValues = latestBarMode ? null : mxc != null ? selectEvenlySpacedXTicksArad(chartRows, mxc) : null;
  // U minigrafů a 1/8, 1/6 je málo výšky — skloněné popisky se ořezávají (overflow).
  // Krátké popisky (MM/QN/YYYY) necháme vodorovné i u ~12 bodů — naklonění je vytlačovalo „mimo“ graf.
  const _displayedXsForTilt = xTickValues && xTickValues.length ? xTickValues : chartRows.map((r) => r.x);
  const _maxXLabelLen = _displayedXsForTilt.reduce(
    (m, x) => Math.max(m, String(formatXAxisTick(x, null, freqCode) ?? "").length),
    0,
  );
  const shortXLabels = _maxXLabelLen > 0 && _maxXLabelLen <= 4;
  const useTilt = latestBarMode
    ? n > 4 && n <= LATEST_BAR_TILT_MAX_ITEMS
    : !veryNarrow && !miniNarrow && n > 8 && !(shortXLabels && n <= 16);
  const tilt = useTilt
    ? latestBarMode
      ? n > 10
        ? -40
        : n > 6
          ? -32
          : -24
      : layoutCompact
        ? -36
        : -30
    : 0;
  const plotMargins = computeChartPlotMargins({
    latestBarMode,
    showBarLabels,
    miniNarrow,
    compact: layoutCompact,
    mobileDense: denseMobile,
    veryNarrow,
    n,
    shortLabels: shortXLabels,
  });
  const periodAnnotationLayout = buildPeriodAnnotationLayout(periodAnnotations);
  // Větší okraje + padding kategorie. Tooltip držíme v Recharts viewBox (viz mergeRechartsTooltipProps).
  const overlayStatsActive =
    (Boolean(overlaySpec?.showAverage) && Number.isFinite(overlaySpec?.average)) ||
    (Boolean(overlaySpec?.showMedian) && Number.isFinite(overlaySpec?.median)) ||
    (Boolean(overlaySpec?.showTrend) && overlaySpec?.trendSegment) ||
    (Boolean(overlaySpec?.showManualTrend) && overlaySpec?.manualTrendSegment);
  const chartMarginExtraRight = overlayStatsActive && !miniNarrow ? (layoutCompact ? 10 : 14) : 0;
  const barShowYAxis = kind === "bar" && barOrientation !== "horizontal";
  const effectiveHideYAxis = barShowYAxis ? false : hideYAxis;
  const chartMargin = {
    top: plotMargins.top,
    right: (denseMobile ? 8 : veryNarrow ? 10 : compact ? 14 : 30) + chartMarginExtraRight,
    left: barShowYAxis
      ? (denseMobile ? 2 : veryNarrow ? 4 : compact ? 6 : 10)
      : effectiveHideYAxis
        ? (denseMobile ? 0 : veryNarrow ? 2 : compact ? 3 : 6)
        : (denseMobile ? 2 : veryNarrow ? 4 : compact ? 6 : 10),
    bottom: plotMargins.bottom,
  };
  const xCategoryPadding = {
    left: denseMobile ? 2 : veryNarrow ? 3 : compact ? 5 : 10,
    right: denseMobile ? 6 : veryNarrow ? 8 : compact ? 12 : 24,
  };
  const xTick = {
    fontSize: latestBarMode ? (layoutCompact ? 7 : 8) : denseMobile ? 9 : veryNarrow ? 8 : compact ? 9 : n > 40 ? 9 : 10,
    fill: chartTheme?.axis || CHART_COLORS.axis,
    fontFamily: "JetBrains Mono",
    ...(tilt ? { angle: tilt, textAnchor: "end", dy: latestBarMode ? 6 : 0 } : {}),
  };
  const yTick = {
    fontSize: denseMobile ? 9 : veryNarrow ? 8 : compact ? 9 : 10,
    fill: effectiveHideYAxis ? "transparent" : chartTheme?.axis || CHART_COLORS.axis,
    fontFamily: "JetBrains Mono",
  };
  const xAxisStyle = {
    tick: xTick,
    tickLine: false,
    tickFormatter: (value) => formatXAxisTick(value, visibleXDomainForFormat, freqCode),
  };
  const yAxisStyle = { tick: yTick, tickLine: false };
  const axisStroke = effectiveHideYAxis ? "transparent" : chartTheme?.grid || CHART_COLORS.grid;
  const yValues = chartRows.map((r) => coerceChartNumeric(r?.y)).filter((v) => v !== null);
  const overlayYValues = collectOverlayYValues(overlaySpec);
  const yValuesForAxis = overlayYValues.length ? [...yValues, ...overlayYValues] : yValues;
  const barUsesSharedAxis = kind === "bar" && barOrientation !== "horizontal" && sharedBarYAxisSpec;
  const yAxisSafe = barUsesSharedAxis
    ? sharedBarYAxisSpec.axis
    : yAxisOverride ||
      buildSafeNumericAxis(
        kind === "bar" ? [0, ...yValuesForAxis] : yValuesForAxis,
        miniNarrow ? 2 : veryNarrow ? 3 : layoutCompact ? 4 : 5
      );
  const yAxisDomain = barUsesSharedAxis
    ? sharedBarYAxisSpec.domain
    : buildRechartsValueDomain(yAxisSafe, yValuesForAxis);
  const hiddenYAxisWidth = denseMobile ? 44 : miniNarrow ? 22 : veryNarrow ? 30 : compact ? 40 : 64;
  const formatBarValue = (v) => fmtCompact(chartBarPointValue(v) ?? v);
  const yAxisUnitLabel =
    unit && !effectiveHideYAxis
      ? {
          value: unit,
          angle: -90,
          position: "insideLeft",
          offset: 4,
          style: {
            textAnchor: "middle",
            fill: chartTheme?.axis || CHART_COLORS.axis,
            fontSize: layoutCompact ? 9 : 10,
            fontFamily: "JetBrains Mono",
          },
        }
      : undefined;
  const barValueLabelStyle = {
    fill: chartTheme?.axis || CHART_COLORS.axis,
    fontFamily: "JetBrains Mono",
    fontSize: layoutCompact ? 8 : 9,
    fontWeight: 600,
  };
  const yAxis = (
    <YAxis
      type="number"
      hide={effectiveHideYAxis}
      width={effectiveHideYAxis ? 0 : hiddenYAxisWidth}
      domain={yAxisDomain}
      ticks={yAxisSafe.ticks}
      label={yAxisUnitLabel}
      {...(kind === "bar" && barOrientation !== "horizontal" ? BAR_VALUE_AXIS : EXPLICIT_LINEAR_AXIS)}
      {...yAxisStyle}
      axisLine={{ stroke: effectiveHideYAxis ? "transparent" : axisStroke }}
      tickFormatter={(v) => fmtCompact(v)}
    />
  );
  const tooltipProps = mergeRechartsTooltipProps({
    contentStyle: { fontSize: 12 },
    formatter: (v) => [
      (kind === "bar" ? formatBarValue(v) : fmtCompact(v)) + unitSuffix,
      undefined,
    ],
    labelFormatter: (l) => latestDataMode ? `Řada: ${l}` : `Období: ${fmtPeriodLabel(l)}`,
  });
  const categoryLabelMax = latestBarMode ? categoryAxisLabelMax({ compact: layoutCompact, n, latestBarMode }) : layoutCompact ? 10 : 14;
  const formatCategoryTick = latestBarMode
    ? (v) => ellipsizeLabel(v, categoryLabelMax)
    : (value) => formatXAxisTick(value, visibleXDomainForFormat, freqCode);
  const categoryAxisTick = latestBarMode
    ? makeCategoryAxisTick({
        maxLen: categoryLabelMax,
        angle: tilt,
        textAnchor: tilt ? "end" : "middle",
        dy: tilt ? 6 : 0,
        fill: chartTheme?.axis || CHART_COLORS.axis,
        fontSize: latestBarMode ? (layoutCompact ? 7 : 8) : denseMobile ? 9 : veryNarrow ? 8 : compact ? 9 : n > 40 ? 9 : 10,
        fontFamily: "JetBrains Mono",
      })
    : null;
  const showAvgLine = Boolean(overlaySpec?.showAverage) && Number.isFinite(overlaySpec?.average);
  const showMedianLine = Boolean(overlaySpec?.showMedian) && Number.isFinite(overlaySpec?.median);
  const showTrendLine = Boolean(overlaySpec?.showTrend) && overlaySpec?.trendSegment;
  const showManualTrendLine = Boolean(overlaySpec?.showManualTrend) && overlaySpec?.manualTrendSegment;
  const highlightDots = buildHighlightDots(chartRows, highlightSpec, primary);
  const overlayRefLabels = !miniNarrow;
  const refLabel = (text, fill, position = "right") => ({
    value: text,
    fill,
    fontSize: layoutCompact ? 8 : 9,
    fontWeight: 600,
    position,
  });
  const avgLineNode = showAvgLine ? (
    <ReferenceLine
      y={overlaySpec.average}
      stroke="#0f766e"
      strokeDasharray="5 4"
      strokeWidth={1.8}
      ifOverflow="extendDomain"
      label={
        overlayRefLabels
          ? refLabel(`⌀ ${fmtCompact(overlaySpec.average)}${unitSuffix}`, "#0f766e", "insideRight")
          : undefined
      }
    />
  ) : null;
  // Nulová referenční čára — když data kříží nulu (kladné i záporné, např. „% změna m/m“),
  // ať je vidět, co je nad a pod nulou (jinak X-osa sedí na minimu, ne na 0).
  const _zeroLineVals = (chartRows || [])
    .flatMap((r) =>
      r && typeof r === "object"
        ? Object.keys(r)
            .filter((k) => k === "y" || /^s\d+$/.test(k))
            .map((k) => r[k])
        : [],
    )
    .filter((v) => typeof v === "number" && Number.isFinite(v));
  const _dataCrossesZero = _zeroLineVals.some((v) => v > 0) && _zeroLineVals.some((v) => v < 0);
  const zeroLineNode =
    !latestBarMode && _dataCrossesZero ? (
      <ReferenceLine y={0} stroke={chartTheme?.axis || CHART_COLORS.axis} strokeWidth={1} ifOverflow="extendDomain" />
    ) : null;
  const avgLineHorizontalNode = showAvgLine ? (
    <ReferenceLine
      x={overlaySpec.average}
      stroke="#0f766e"
      strokeDasharray="5 4"
      strokeWidth={1.8}
      ifOverflow="extendDomain"
      label={
        overlayRefLabels
          ? refLabel(`⌀ ${fmtCompact(overlaySpec.average)}${unitSuffix}`, "#0f766e", "insideTop")
          : undefined
      }
    />
  ) : null;
  const medianStroke = latestBarMode ? "#2563eb" : "#7c3aed";
  const medianLabelText = showMedianLine
    ? latestBarMode
      ? `Medián ${fmtCompact(overlaySpec.median)}${unitSuffix}`
      : `Md ${fmtCompact(overlaySpec.median)}${unitSuffix}`
    : "";
  const medianLineNode = showMedianLine ? (
    <ReferenceLine
      y={overlaySpec.median}
      stroke={medianStroke}
      strokeDasharray="4 3"
      strokeWidth={1.8}
      ifOverflow="extendDomain"
      label={
        overlayRefLabels
          ? refLabel(medianLabelText, medianStroke, "insideRight")
          : undefined
      }
    />
  ) : null;
  const medianLineHorizontalNode = showMedianLine ? (
    <ReferenceLine
      x={overlaySpec.median}
      stroke={medianStroke}
      strokeDasharray="4 3"
      strokeWidth={1.8}
      ifOverflow="extendDomain"
      label={
        overlayRefLabels
          ? refLabel(medianLabelText, medianStroke, "insideTop")
          : undefined
      }
    />
  ) : null;
  const trendSlopeLabel =
    showTrendLine &&
    overlaySpec.trendSlopePerStep != null &&
    Number.isFinite(overlaySpec.trendSlopePerStep)
      ? `${fmtTrendSlopePerStep(overlaySpec.trendSlopePerStep)}${unitSuffix}/krok`
      : null;
  const trendLineNode = showTrendLine ? (
    <ReferenceLine
      segment={[
        { x: overlaySpec.trendSegment.xStart, y: overlaySpec.trendSegment.yStart },
        { x: overlaySpec.trendSegment.xEnd, y: overlaySpec.trendSegment.yEnd },
      ]}
      stroke="#b45309"
      strokeWidth={2}
      ifOverflow="extendDomain"
      label={
        overlayRefLabels && trendSlopeLabel
          ? refLabel(`Trend ${trendSlopeLabel}`, "#b45309", "insideEndTop")
          : undefined
      }
    />
  ) : null;
  const manualTrendLineNode = showManualTrendLine ? (
    <ReferenceLine
      segment={[
        { x: overlaySpec.manualTrendSegment.xStart, y: overlaySpec.manualTrendSegment.yStart },
        { x: overlaySpec.manualTrendSegment.xEnd, y: overlaySpec.manualTrendSegment.yEnd },
      ]}
      stroke="#dc2626"
      strokeDasharray="8 3"
      strokeWidth={2.3}
      ifOverflow="extendDomain"
      label={overlayRefLabels ? refLabel("Vlastní trend", "#dc2626", "insideEndTop") : undefined}
    />
  ) : null;
  const periodAnnotationNodes = renderPeriodAnnotationNodes(periodAnnotationLayout);

  if (kind === "dot") {
    return (
      <LineChart data={chartRows} margin={chartMargin}>
        <CartesianGrid vertical={false} stroke={chartTheme?.grid || CHART_COLORS.grid} strokeDasharray="2 4" />
        <XAxis
          type="category"
          dataKey="x"
          ticks={xTickValues && xTickValues.length ? xTickValues : undefined}
          {...xAxisStyle}
          padding={xCategoryPadding}
          axisLine={{ stroke: axisStroke }}
          interval="preserveStartEnd"
          minTickGap={layoutCompact ? 30 : 38}
          height={useTilt ? (layoutCompact ? 28 : 30) : veryNarrow ? 32 : layoutCompact ? 20 : 18}
        />
        {yAxis}
        <Tooltip {...tooltipProps} />
        {avgLineNode}
        {zeroLineNode}
        {medianLineNode}
        {trendLineNode}
        {manualTrendLineNode}
        {highlightDots}
        <Line
          type="linear"
          dataKey="y"
          stroke={primary}
          strokeWidth={1.5}
          dot={{ r: layoutCompact ? 3 : 4, fill: primary, stroke: "#fff", strokeWidth: 1.5 }}
          activeDot={{ r: layoutCompact ? 5 : 6, fill: primary, stroke: "#fff", strokeWidth: 2 }}
        />
      </LineChart>
    );
  }

  if (kind === "bar") {
    if (barOrientation === "horizontal") {
      const xValues = chartRows.map((r) => coerceChartNumeric(r?.y)).filter((v) => v !== null);
      const hBarUsesShared = Boolean(sharedBarYAxisSpec);
      const xAxisSafe = hBarUsesShared
        ? sharedBarYAxisSpec.axis
        : buildSafeNumericAxis([0, ...xValues], miniNarrow ? 2 : veryNarrow ? 3 : compact ? 4 : 5);
      const xAxisDomain = hBarUsesShared
        ? sharedBarYAxisSpec.domain
        : buildRechartsValueDomain(xAxisSafe, xValues);
      const useZeroBaselineHBar = hBarUsesShared
        ? sharedBarYAxisSpec.useZeroBaselineShape
        : getYAxisDomainForChart("bar", xValues, {
            tickCount: miniNarrow ? 2 : veryNarrow ? 3 : compact ? 4 : 5,
          }).useZeroBaselineShape;
      const categoryWidth = miniNarrow ? 54 : veryNarrow ? 62 : compact ? 76 : 108;
      const hBarAllPositive = xValues.length > 0 && xValues.every((v) => v >= 0);
      const hBarRows = hBarAllPositive ? chartRowsWithZeroBaselineBars(chartRows) : chartRows;
      return (
        <BarChart
          key={`bar-h-${xAxisDomain[0]}-${xAxisDomain[1]}-${chartRows.length}`}
          data={hBarRows}
          layout="vertical"
          margin={{ ...chartMargin, left: 0, bottom: compact ? 8 : 10 }}
        >
          <CartesianGrid horizontal={false} stroke={chartTheme?.grid || CHART_COLORS.grid} strokeDasharray="2 4" />
          <XAxis
            type="number"
            domain={xAxisDomain}
            ticks={xAxisSafe.ticks}
            {...BAR_VALUE_AXIS}
            tick={{ ...yTick, fill: chartTheme?.axis || CHART_COLORS.axis }}
            tickLine={false}
            axisLine={{ stroke: chartTheme?.grid || CHART_COLORS.grid }}
            tickFormatter={(v) => fmtCompact(v)}
          />
          <YAxis
            type="category"
            dataKey="x"
            width={categoryWidth}
            tick={{ ...yTick, fill: chartTheme?.axis || CHART_COLORS.axis }}
            tickLine={false}
            axisLine={{ stroke: chartTheme?.grid || CHART_COLORS.grid }}
            tickFormatter={(v) => ellipsizeLabel(v, compact ? 12 : 18)}
          />
          <Tooltip {...tooltipProps} />
          {avgLineHorizontalNode}
          {medianLineHorizontalNode}
          {manualTrendLineNode}
          <Bar
            dataKey="y"
            radius={[0, 6, 6, 0]}
            fill={barMultiColor ? undefined : primarySoft}
            barSize={compact ? 12 : 16}
            minPointSize={useZeroBaselineHBar ? 0 : MIN_BAR_POINT_SIZE}
            isAnimationActive={false}
          >
            {showBarLabels ? (
              <LabelList
                dataKey="y"
                position="right"
                offset={4}
                formatter={(v) => formatBarValue(v)}
                style={barValueLabelStyle}
              />
            ) : null}
            {barMultiColor
              ? chartRows.map((_, i) => (
                  <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />
                ))
              : null}
          </Bar>
        </BarChart>
      );
    }
    const barAllPositive = yValues.length > 0 && yValues.every((v) => v >= 0);
    const verticalBarRows = barAllPositive ? chartRowsWithZeroBaselineBars(chartRows) : chartRows;
    return (
      <BarChart
        key={`bar-${yAxisDomain[0]}-${yAxisDomain[1]}-${chartRows.length}`}
        data={verticalBarRows}
        margin={chartMargin}
      >
        <CartesianGrid vertical={false} stroke={chartTheme?.grid || CHART_COLORS.grid} strokeDasharray="2 4" />
        <XAxis
          type="category"
          dataKey="x"
          ticks={xTickValues && xTickValues.length ? xTickValues : undefined}
          {...xAxisStyle}
          tick={latestBarMode ? categoryAxisTick : xTick}
          tickFormatter={latestBarMode ? undefined : formatCategoryTick}
          padding={xCategoryPadding}
          axisLine={{ stroke: axisStroke }}
          interval={latestBarMode ? 0 : "preserveStartEnd"}
          minTickGap={latestBarMode ? 0 : layoutCompact ? 30 : 38}
          height={
            latestBarMode
              ? useTilt
                ? layoutCompact
                  ? 40
                  : 48
                : layoutCompact
                  ? 24
                  : 28
              : useTilt
                ? layoutCompact
                  ? 28
                  : 30
                : veryNarrow
                  ? 32
                  : layoutCompact
                    ? 20
                    : 18
          }
        />
        {yAxis}
        <Tooltip {...tooltipProps} />
        {avgLineNode}
        {zeroLineNode}
        {medianLineNode}
        {trendLineNode}
        {manualTrendLineNode}
        {highlightDots}
        <Bar
          dataKey="y"
          radius={[6, 6, 0, 0]}
          fill={barMultiColor ? undefined : primarySoft}
          minPointSize={0}
          isAnimationActive={false}
        >
          {showBarLabels ? (
            <LabelList
              dataKey="y"
              position="top"
              offset={4}
              formatter={(v) => formatBarValue(v)}
              style={barValueLabelStyle}
            />
          ) : null}
          {barMultiColor
            ? chartRows.map((_, i) => (
                <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />
              ))
            : null}
        </Bar>
      </BarChart>
    );
  }

  if (kind === "area") {
    return (
      <AreaChart data={chartRows} margin={chartMargin}>
        <defs>
          <linearGradient id="arad-area-grad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={primarySoft} stopOpacity={0.45} />
            <stop offset="100%" stopColor={primarySoft} stopOpacity={0.02} />
          </linearGradient>
        </defs>
        <CartesianGrid vertical={false} stroke={chartTheme?.grid || CHART_COLORS.grid} strokeDasharray="2 4" />
        <XAxis
          type="category"
          dataKey="x"
          ticks={xTickValues && xTickValues.length ? xTickValues : undefined}
          {...xAxisStyle}
          padding={xCategoryPadding}
          axisLine={{ stroke: axisStroke }}
          interval="preserveStartEnd"
          minTickGap={layoutCompact ? 30 : 38}
          height={useTilt ? (layoutCompact ? 28 : 30) : veryNarrow ? 32 : layoutCompact ? 20 : 18}
        />
        {yAxis}
        <Tooltip {...tooltipProps} />
        {avgLineNode}
        {zeroLineNode}
        {medianLineNode}
        {trendLineNode}
        {manualTrendLineNode}
        {highlightDots}
        <Area type="linear" dataKey="y" stroke={primary} strokeWidth={2.5} fill="url(#arad-area-grad)" />
      </AreaChart>
    );
  }

  if (kind === "pie") {
    // Pie chart represents the SHARE of the total across periods.
    // Negative values can't be shown as slices, so we drop them and warn.
    const positive = chartRows.filter((r) => typeof r.y === "number" && r.y > 0);
    const sorted = [...positive].sort((a, b) => b.y - a.y);
    const MAX_SLICES = 11;
    let pieData;
    if (sorted.length <= MAX_SLICES) {
      pieData = sorted.map((r, i) => ({ name: r.x, value: r.y, color: PIE_COLORS[i % PIE_COLORS.length] }));
    } else {
      const top = sorted.slice(0, MAX_SLICES);
      const rest = sorted.slice(MAX_SLICES);
      const restSum = rest.reduce((s, r) => s + r.y, 0);
      pieData = [
        ...top.map((r, i) => ({ name: r.x, value: r.y, color: PIE_COLORS[i % PIE_COLORS.length] })),
        { name: `Ostatní (${rest.length})`, value: restSum, color: "#CBD5E1" },
      ];
    }
    const total = pieData.reduce((s, d) => s + d.value, 0) || 1;
    return (
      <PieChart>
        <Pie
          data={pieData}
          dataKey="value"
          nameKey="name"
          innerRadius={pieVariant === "full" ? 0 : "45%"}
          outerRadius="80%"
          paddingAngle={pieVariant === "full" ? 1 : 2}
          isAnimationActive={false}
        >
          {pieData.map((d, i) => (
            <Cell key={i} fill={d.color} stroke="white" strokeWidth={2.5} />
          ))}
        </Pie>
        <Tooltip
          {...mergeRechartsTooltipProps({
            contentStyle: { fontSize: 12 },
            cursor: true,
            formatter: (v, _n, entry) => [
              `${fmtCompact(v)}${unitSuffix} (${((v / total) * 100).toFixed(1)} %)`,
              entry?.payload?.name || "",
            ],
          })}
        />
        <Legend
          wrapperStyle={{
            fontSize: 10,
            fontFamily: "JetBrains Mono",
            maxWidth: "100%",
          }}
          formatter={(name) => <span style={{ color: "#334155" }}>{name}</span>}
        />
      </PieChart>
    );
  }

  // Default: line
  return (
    <LineChart data={chartRows} margin={chartMargin}>
      <CartesianGrid vertical={false} stroke={chartTheme?.grid || CHART_COLORS.grid} strokeDasharray="2 4" />
      <XAxis
        type="category"
        dataKey="x"
        ticks={xTickValues && xTickValues.length ? xTickValues : undefined}
        {...xAxisStyle}
        padding={xCategoryPadding}
        axisLine={{ stroke: axisStroke }}
        interval="preserveStartEnd"
        minTickGap={layoutCompact ? 30 : 38}
        height={useTilt ? (layoutCompact ? 28 : 30) : veryNarrow ? 32 : layoutCompact ? 20 : 18}
      />
      {yAxis}
      <Tooltip {...tooltipProps} />
      {avgLineNode}
      {zeroLineNode}
      {medianLineNode}
      {trendLineNode}
      {manualTrendLineNode}
      {periodAnnotationNodes}
      {highlightDots}
      <Line type="linear" dataKey="y" stroke={primary} strokeWidth={2.5} dot={false} />
    </LineChart>
  );
}

/** Složený graf (více řad) — typ grafu podle toolbaru nebo `seriesMeta[].chart_type`; dvě osy Y podle `seriesMeta[].y_axis`. */
function renderMultiComposedChart(
  seriesMeta,
  chartRows,
  unit,
  compact,
  chartTheme,
  widgetWidth,
  miniMode = false,
  hideLeftYAxis = false,
  leftAxisOverride = null,
  rightAxisOverride = null,
  denseXTicks = false,
  visibleXDomainForFormat = null,
  mobileDense = false,
  overlaySpec = null,
  freqCode = null,
  chartKindOverride = null,
  showLegend = true,
  periodAnnotations = []
) {
  const unitSuffix = unit ? ` ${unit}` : "";
  const n = chartRows.length;
  const veryNarrow = widgetWidth === "eighth" || widgetWidth === "sixth";
  const miniNarrow = miniMode && (veryNarrow || compact);
  const denseMobile = Boolean(mobileDense) && !miniMode;
  const layoutCompact = compact || denseMobile;
  const mxc = denseXTicks
    ? Math.min(24, Math.max(8, Math.ceil(n / 8)))
    : maxXTicksCountArad(n, layoutCompact, veryNarrow);
  const xTickValues = mxc != null ? selectEvenlySpacedXTicksArad(chartRows, mxc) : null;
  const useTilt = !veryNarrow && !miniNarrow && n > 8;
  const tilt = useTilt ? (layoutCompact ? -36 : -30) : 0;
  const anyRightAxis = Array.isArray(seriesMeta) && seriesMeta.some((s) => String(s?.y_axis || "").toLowerCase() === "right");
  const periodAnnotationLayout = buildPeriodAnnotationLayout(periodAnnotations);
  const chartMargin = {
    top: miniNarrow ? 6 : denseMobile ? 8 : chartAreaTopMargin({ miniNarrow, compact: layoutCompact }),
    right: denseMobile ? (anyRightAxis ? 24 : 8) : miniNarrow ? 14 : anyRightAxis ? (veryNarrow ? 22 : compact ? 36 : 52) : veryNarrow ? 10 : compact ? 18 : 30,
    left: denseMobile ? 2 : miniNarrow ? 2 : veryNarrow ? 4 : compact ? 14 : 18,
    bottom: miniNarrow ? 4 : denseMobile ? (useTilt ? 6 : 4) : useTilt ? (layoutCompact ? 26 : 28) : veryNarrow ? 34 : compact ? (anyRightAxis ? 20 : 16) : 14,
  };
  const xCategoryPadding = {
    left: denseMobile ? 2 : miniNarrow ? 1 : veryNarrow ? 4 : compact ? 6 : 10,
    right: denseMobile ? 6 : miniNarrow ? 2 : veryNarrow ? 10 : compact ? 16 : 24,
  };
  const xTick = {
    fontSize: denseMobile ? 9 : veryNarrow ? 8 : compact ? 9 : n > 40 ? 9 : 10,
    fill: chartTheme?.axis || CHART_COLORS.axis,
    fontFamily: "JetBrains Mono",
    ...(tilt ? { angle: tilt, textAnchor: "end", dy: 0 } : {}),
  };
  const yTick = {
    fontSize: denseMobile ? 9 : veryNarrow ? 8 : compact ? 9 : 10,
    fill: chartTheme?.axis || CHART_COLORS.axis,
    fontFamily: "JetBrains Mono",
  };
  const axisStroke = chartTheme?.grid || CHART_COLORS.grid;
  const hiddenLeftTick = { ...yTick, fill: "transparent" };
  const leftKeys = (seriesMeta || [])
    .filter((s) => String(s?.y_axis || "").toLowerCase() !== "right")
    .map((s) => s?.key)
    .filter(Boolean);
  const rightKeys = (seriesMeta || [])
    .filter((s) => String(s?.y_axis || "").toLowerCase() === "right")
    .map((s) => s?.key)
    .filter(Boolean);
  const collectValuesByKeys = (keys) =>
    (chartRows || []).flatMap((row) =>
      keys.map((k) => {
        const v = row?.[k];
        return typeof v === "number" ? v : parseNumber(v);
      })
    );
  const defaultTickCount = miniNarrow ? 2 : veryNarrow ? 3 : layoutCompact ? 4 : 5;
  const leftAxisSafe = leftAxisOverride || buildSafeNumericAxis(
      collectValuesByKeys(anyRightAxis ? leftKeys : [...leftKeys, ...rightKeys]),
      defaultTickCount
    );
  const rightAxisSafe = anyRightAxis
    ? rightAxisOverride || buildSafeNumericAxis(collectValuesByKeys(rightKeys), defaultTickCount)
    : leftAxisSafe;
  const yAxisShared = {
    type: "number",
    width: denseMobile ? 42 : miniNarrow ? 22 : veryNarrow ? 30 : compact ? 40 : 52,
    tick: yTick,
    tickLine: false,
    axisLine: { stroke: axisStroke },
    tickFormatter: (v) => fmtCompact(v),
    ...EXPLICIT_LINEAR_AXIS,
  };
  const yAxisUnitLabel =
    unit && !hideLeftYAxis
      ? {
          value: unit,
          angle: -90,
          position: "insideLeft",
          offset: 4,
          style: {
            textAnchor: "middle",
            fill: chartTheme?.axis || CHART_COLORS.axis,
            fontSize: layoutCompact ? 9 : 10,
            fontFamily: "JetBrains Mono",
          },
        }
      : undefined;
  const yAxisLeftShared = hideLeftYAxis
    ? { ...yAxisShared, width: 0, tick: hiddenLeftTick, axisLine: { stroke: "transparent" } }
    : yAxisShared;
  const leftAxisValues = collectValuesByKeys(anyRightAxis ? leftKeys : [...leftKeys, ...rightKeys]).filter(
    (v) => typeof v === "number" && Number.isFinite(v)
  );
  const rightAxisValues = collectValuesByKeys(rightKeys).filter((v) => typeof v === "number" && Number.isFinite(v));
  const yAxisLeft = anyRightAxis ? (
    <YAxis
      yAxisId="left"
      {...yAxisLeftShared}
      label={hideLeftYAxis ? undefined : yAxisUnitLabel}
      domain={buildRechartsValueDomain(leftAxisSafe, leftAxisValues)}
      ticks={leftAxisSafe.ticks}
    />
  ) : (
    <YAxis
      {...yAxisLeftShared}
      label={hideLeftYAxis ? undefined : yAxisUnitLabel}
      domain={buildRechartsValueDomain(leftAxisSafe, leftAxisValues)}
      ticks={leftAxisSafe.ticks}
    />
  );
  const yAxisRight = anyRightAxis ? (
    <YAxis
      yAxisId="right"
      orientation="right"
      {...yAxisShared}
      width={denseMobile ? 38 : miniNarrow ? 20 : veryNarrow ? 28 : compact ? 38 : 48}
      domain={buildRechartsValueDomain(rightAxisSafe, rightAxisValues)}
      ticks={rightAxisSafe.ticks}
    />
  ) : null;
  const seriesYAxisId = (s) => (anyRightAxis && String(s?.y_axis || "").toLowerCase() === "right" ? "right" : "left");
  const multiSeriesCatalog = (seriesMeta || [])
    .filter((s) => s?.key)
    .map((s, idx) => ({
      key: s.key,
      name: s.name || s.label || s.indicator_id || s.key,
      color: PIE_COLORS[idx % PIE_COLORS.length],
    }));
  const multiTooltipProps = mergeRechartsTooltipProps({
    content: (props) => (
      <ChartTooltip
        {...props}
        unit={unitSuffix.trim()}
        seriesCatalog={multiSeriesCatalog}
        compact={denseMobile}
      />
    ),
  });
  const overlaySeries = Array.isArray(overlaySpec?.series) ? overlaySpec.series : [];
  const overlayRefLabels = !miniNarrow && overlaySeries.length <= 3;
  const overlayLabel = (text, fill, position = "insideRight") => ({
    value: text,
    fill,
    fontSize: layoutCompact ? 8 : 9,
    fontWeight: 600,
    position,
  });
  const overlayAxisProps = (item) => (anyRightAxis ? { yAxisId: item?.yAxisId || "left" } : {});
  const overlayLineNodes = overlaySeries.flatMap((item, idx) => {
    const shortLabel = ellipsizeLabel(item.label || `Řada ${idx + 1}`, 10);
    const color = item.color || PIE_COLORS[idx % PIE_COLORS.length];
    const nodes = [];
    if (item.showAverage && Number.isFinite(item.average)) {
      nodes.push(
        <ReferenceLine
          key={`${item.key}-avg`}
          {...overlayAxisProps(item)}
          y={item.average}
          stroke={color}
          strokeDasharray="5 4"
          strokeWidth={1.5}
          ifOverflow="extendDomain"
          label={overlayRefLabels ? overlayLabel(`Ø ${shortLabel}`, color) : undefined}
        />
      );
    }
    if (item.showMedian && Number.isFinite(item.median)) {
      nodes.push(
        <ReferenceLine
          key={`${item.key}-median`}
          {...overlayAxisProps(item)}
          y={item.median}
          stroke={color}
          strokeDasharray="2 3"
          strokeWidth={1.5}
          ifOverflow="extendDomain"
          label={overlayRefLabels ? overlayLabel(`Md ${shortLabel}`, color, "insideLeft") : undefined}
        />
      );
    }
    if (item.showTrend && item.trendSegment) {
      nodes.push(
        <ReferenceLine
          key={`${item.key}-trend`}
          {...overlayAxisProps(item)}
          segment={[
            { x: item.trendSegment.xStart, y: item.trendSegment.yStart },
            { x: item.trendSegment.xEnd, y: item.trendSegment.yEnd },
          ]}
          stroke={color}
          strokeDasharray="8 4"
          strokeWidth={2}
          ifOverflow="extendDomain"
          label={overlayRefLabels ? overlayLabel(`Trend ${shortLabel}`, color, "insideEndTop") : undefined}
        />
      );
    }
    return nodes;
  });
  const manualTrendLineNode = overlaySpec?.manualTrendSegment ? (
    <ReferenceLine
      key="manual-trend"
      {...(anyRightAxis ? { yAxisId: "left" } : {})}
      segment={[
        { x: overlaySpec.manualTrendSegment.xStart, y: overlaySpec.manualTrendSegment.yStart },
        { x: overlaySpec.manualTrendSegment.xEnd, y: overlaySpec.manualTrendSegment.yEnd },
      ]}
      stroke="#dc2626"
      strokeDasharray="8 3"
      strokeWidth={2.3}
      ifOverflow="extendDomain"
      label={overlayRefLabels ? overlayLabel("Vlastní trend", "#dc2626", "insideEndTop") : undefined}
    />
  ) : null;
  const periodAnnotationNodes = renderPeriodAnnotationNodes(periodAnnotationLayout, anyRightAxis);
  return (
    <ComposedChart data={chartRows} margin={chartMargin}>
      <CartesianGrid vertical={false} stroke={chartTheme?.grid || CHART_COLORS.grid} strokeDasharray="2 4" />
      <XAxis
        type="category"
        dataKey="x"
        ticks={xTickValues && xTickValues.length ? xTickValues : undefined}
        tick={xTick}
        tickLine={false}
        tickFormatter={(value) => formatXAxisTick(value, visibleXDomainForFormat, freqCode)}
        axisLine={{ stroke: axisStroke }}
        padding={xCategoryPadding}
        interval="preserveStartEnd"
        minTickGap={layoutCompact ? 30 : 38}
        height={useTilt ? (layoutCompact ? 28 : 30) : veryNarrow ? 34 : layoutCompact ? 20 : 18}
      />
      {yAxisLeft}
      {yAxisRight}
      <Tooltip {...multiTooltipProps} />
      {showLegend && !miniNarrow && !miniMode && (
        <Legend
          iconType="line"
          iconSize={10}
          wrapperStyle={{
            fontSize: layoutCompact ? 9 : 10,
            paddingTop: 2,
            color: chartTheme?.axis || CHART_COLORS.axis,
            lineHeight: "1.5",
          }}
        />
      )}
      {overlayLineNodes}
      {manualTrendLineNode}
      {periodAnnotationNodes}
      {seriesMeta.map((s, idx) => {
        const color = PIE_COLORS[idx % PIE_COLORS.length];
        const overrideKind = normalizeChartKind(chartKindOverride || "", "");
        const kind = ["line", "bar", "area", "dot"].includes(overrideKind)
          ? overrideKind
          : normalizeChartKind(s.chart_type || "line");
        const baseName = s.name || s.label || s.indicator_id || `Řada ${idx + 1}`;
        const legendName = anyRightAxis
          ? String(s?.y_axis || "").toLowerCase() === "right"
            ? `${baseName} · pravá osa`
            : `${baseName} · levá osa`
          : baseName;
        const k = s.key;
        if (!k) return null;
        const yId = anyRightAxis ? seriesYAxisId(s) : undefined;
        if (kind === "bar") {
          return (
            <Bar
              key={k}
              yAxisId={yId}
              dataKey={k}
              name={legendName}
              fill={color}
              radius={[4, 4, 0, 0]}
              minPointSize={MIN_BAR_POINT_SIZE}
            />
          );
        }
        if (kind === "area") {
          return (
            <Area
              key={k}
              yAxisId={yId}
              type="linear"
              dataKey={k}
              name={legendName}
              stroke={color}
              fill={color}
              fillOpacity={0.12}
              strokeWidth={2.5}
              dot={false}
              connectNulls
            />
          );
        }
        if (kind === "dot") {
          return (
            <Line
              key={k}
              yAxisId={yId}
              type="linear"
              dataKey={k}
              name={legendName}
              stroke={color}
              strokeWidth={0}
              dot={{ r: 3, stroke: color, strokeWidth: 1.5, fill: "#ffffff" }}
              activeDot={{ r: 4, stroke: color, strokeWidth: 2, fill: color }}
              connectNulls={false}
            />
          );
        }
        return (
          <Line
            key={k}
            yAxisId={yId}
            type="linear"
            dataKey={k}
            name={legendName}
            stroke={color}
            strokeWidth={2.5}
            dot={false}
            connectNulls
          />
        );
      })}
    </ComposedChart>
  );
}

function aggregateRows(rows, targetFreq, agg, meta = {}) {
  // rows: [{period, value, date}], where date is a Date.
  const buckets = new Map();
  for (const r of rows) {
    const value = coerceChartNumeric(r?.value);
    if (value === null) continue;
    if (!(r.date instanceof Date) || Number.isNaN(r.date.getTime())) continue;
    const b = bucketKey(r.date, targetFreq);
    if (!b) continue;
    if (!buckets.has(b.key)) {
      buckets.set(b.key, { key: b.key, label: b.label, sort: b.sort, values: [] });
    }
    buckets.get(b.key).values.push(value);
  }
  const out = Array.from(buckets.values()).map((b) => {
    const v = aggregateBucketValues(b.values, agg, meta);
    return { period: b.label, value: v, sort: b.sort, _count: b.values.length };
  });
  out.sort((a, b) => a.sort - b.sort);
  return out;
}

const TIMEFRAMES = [
  { id: "1Y", label: "1R", years: 1 },
  { id: "3Y", label: "3R", years: 3 },
  { id: "5Y", label: "5R", years: 5 },
  { id: "10Y", label: "10R", years: 10 },
  { id: "20Y", label: "20R", years: 20 },
  { id: "CUSTOM", label: "OD-DO", years: null },
  { id: "ALL", label: "MAX", years: null },
];

function customRangeFieldLabels(freqCode) {
  const f = String(freqCode || "").toUpperCase();
  if (f === "Y") return { from: "Od roku", to: "Do roku" };
  if (f === "Q") return { from: "Od čtvrtletí", to: "Do čtvrtletí" };
  if (f === "H") return { from: "Od pololetí", to: "Do pololetí" };
  if (f === "M") return { from: "Od měsíce", to: "Do měsíce" };
  if (f === "W") return { from: "Od týdne", to: "Do týdne" };
  return { from: "Od období", to: "Do období" };
}

/** Minimální délka společného prefixu názvů řad, aby se v tabulce zkrátil (opakující se kontext výkazu). */
const MULTI_SERIES_TABLE_PREFIX_MIN = 24;

function longestCommonPrefix(strings) {
  const arr = strings.map((s) => String(s || "").trim()).filter(Boolean);
  if (arr.length < 2) return "";
  let prefix = arr[0];
  for (let i = 1; i < arr.length; i += 1) {
    const s = arr[i];
    let j = 0;
    const max = Math.min(prefix.length, s.length);
    while (j < max && prefix[j] === s[j]) j += 1;
    prefix = prefix.slice(0, j);
    if (!prefix) return "";
  }
  return prefix;
}

/** Zarovná společný prefix typicky na poslední „, " před vlastním názvem řady. */
function refineMultiSeriesTableStripPrefix(prefix) {
  if (!prefix || prefix.length < MULTI_SERIES_TABLE_PREFIX_MIN) return "";
  if (prefix.endsWith(", ") || prefix.endsWith(": ")) return prefix;
  const cut = prefix.lastIndexOf(", ");
  if (cut >= MULTI_SERIES_TABLE_PREFIX_MIN - 1) return prefix.slice(0, cut + 2);
  return prefix;
}

const CHART_ANALYST_PANEL_HEIGHT_KEY = "bankoapp:chart-analyst-panel-height";
const CHART_ANALYST_PANEL_HEIGHT_DEFAULT = 320;
const CHART_ANALYST_PANEL_HEIGHT_MIN = 180;
const CHART_ANALYST_PANEL_MIN_CHART = 200;

function loadChartAnalystPanelHeight() {
  try {
    const raw = localStorage.getItem(CHART_ANALYST_PANEL_HEIGHT_KEY);
    const n = Number(raw);
    if (Number.isFinite(n) && n >= CHART_ANALYST_PANEL_HEIGHT_MIN) return n;
  } catch {
    /* ignore */
  }
  return CHART_ANALYST_PANEL_HEIGHT_DEFAULT;
}

const VIEWER_COMPARE_STORAGE_PREFIX = "bankintel:chart-compare:v1";
/** Kolik sloupců ještě dává smysl vypsat do legendy pod grafem srovnání hodnot. */
const LATEST_BAR_LEGEND_MAX_ITEMS = 12;
/**
 * Do kolika sloupců se popisky kategorií naklánějí.
 *
 * Naklonění stojí 48 px výšky. Ve widgetu na dashboardu je to celá plocha grafu —
 * u 27 zemí vyšla vykreslovací oblast záporná a nevykreslil se ani jeden sloupec.
 * Nad tímhle počtem se graf navíc posouvá vodorovně a každý sloupec má ~68 px šířky,
 * takže se popisek vejde i naplocho a naklánět ho není proč.
 */
const LATEST_BAR_TILT_MAX_ITEMS = 12;

function buildViewerCompareStorageKey(widget) {
  const config = widget?.config && typeof widget.config === "object" ? widget.config : {};
  const catalog = String(config.catalog || config.source_type || widget?.engine_type || widget?.type || "chart")
    .trim()
    .toLowerCase();
  const dataset = String(
    config.set_id || config.dataset_id || config.source_id || config.indicator_id || widget?.id || "unknown"
  ).trim();
  return `${VIEWER_COMPARE_STORAGE_PREFIX}:${encodeURIComponent(catalog)}:${encodeURIComponent(dataset)}`;
}

function normalizeViewerCompareEntries(entries) {
  if (!Array.isArray(entries)) return [];
  return entries.filter((entry) => {
    if (!entry || typeof entry !== "object") return false;
    const catalog = String(entry.catalog || entry.source || "").trim();
    const dataset = String(entry.set_id || entry.source_id || entry.selected_indicator || entry.indicator_id || "").trim();
    return Boolean(catalog && dataset);
  });
}

function readViewerCompareEntries(storageKey) {
  if (typeof window === "undefined" || !storageKey) return [];
  try {
    return normalizeViewerCompareEntries(JSON.parse(window.sessionStorage.getItem(storageKey) || "[]"));
  } catch {
    return [];
  }
}

function writeViewerCompareEntries(storageKey, entries) {
  if (typeof window === "undefined" || !storageKey) return;
  try {
    const normalized = normalizeViewerCompareEntries(entries);
    if (normalized.length) window.sessionStorage.setItem(storageKey, JSON.stringify(normalized));
    else window.sessionStorage.removeItem(storageKey);
  } catch {
    /* Session storage is optional; the in-memory comparison still works. */
  }
}

export default function AradView({
  userTitle,
  data,
  widget,
  caption,
  aiCommentary,
  aiAnalysis,
  defaultChartType = "line",
  defaultChartFrequency,
  /** Nápověda „Více řad“: na osobním dashboardu jiný postup než u veřejné stránky v administraci. */
  aradMultiSeriesHelpContext = "public_site",
  /** Osobní / admin dashboard: uložit `chart_compare_with` (+ volitelně `primary_y_axis`) přímo z grafu. */
  onWidgetConfigPatch = null,
  /** Divák sdíleného dashboardu: dočasný compare preview (neukládá se). */
  onViewerComparePreview = null,
  /** Volitelná inline ovládací skupina z rendereru (např. Jedna řada / Více řad podle dimenze). */
  toolbarSlot = null,
  /** Vynutit ovládání pod ikonou „Možnosti grafu“ (true) nebo vždy v liště (false). */
  controlsInOptionsPanel: controlsInOptionsPanelProp = undefined,
  /** Sdílení grafu (odkaz / chat) — typicky z osobního dashboardu. */
  chartShareContext = null,
  /** Katalogový live náhled — periodicita bez paywallu (agregace v prohlížeči). */
  unlockChartPeriod = false,
  /** Katalogový live náhled — přepínání graf / tabulka bez paywallu. */
  unlockViewToggle = false,
  /** Katalogový live náhled — skrýt dashboardové „Více řad“ (compare modal / nápověda). */
  catalogLivePreview = false,
  /** KPI headline strip: auto/full/compact/mini/hidden. */
  kpiSummaryMode = "auto",
  /** Volitelná velikost katalogového preview shellu (detail/detail-expanded/compact). */
  catalogChartSize = "",
  /** Katalogový detail — synchronizace horního metadata panelu se stavem grafu. */
  onChartDisplayStateChange = null,
}) {
  const navigate = useNavigate();
  const viewerCompareStorageKey = useMemo(() => buildViewerCompareStorageKey(widget), [
    widget?.config?.catalog,
    widget?.config?.source_type,
    widget?.config?.set_id,
    widget?.config?.dataset_id,
    widget?.config?.source_id,
    widget?.config?.indicator_id,
    widget?.engine_type,
    widget?.type,
    widget?.id,
  ]);
  const [viewerComparedData, setViewerComparedData] = useState(null);
  const [viewerCompareEntries, setViewerCompareEntries] = useState(() =>
    readViewerCompareEntries(viewerCompareStorageKey)
  );
  const updateViewerCompareEntries = useCallback(
    (entries) => {
      const normalized = normalizeViewerCompareEntries(entries);
      setViewerCompareEntries(normalized);
      writeViewerCompareEntries(viewerCompareStorageKey, normalized);
    },
    [viewerCompareStorageKey]
  );
  const effectiveData = viewerComparedData ?? data;
  // Ruční popisek → AI → automatická patička (jednotka / sloupec hodnot / počet období) — Eurostat atd. často nemají unit v API.
  const manualCaption = cleanWidgetCaption(caption || widget?.config?.caption);
  const fallbackCaption = manualCaption ? "" : cleanWidgetCaption(aiCommentary || widget?.ai_commentary || "");
  const aiAnalysisPayload = asObject(aiAnalysis || widget?.ai_analysis_payload);
  const aiAnalysisDatasets = asArray(aiAnalysisPayload?.datasets);
  const hasAiAnalysisDatasets = aiAnalysisDatasets.length > 0;
  const heading = userTitle || effectiveData?.title || "Graf";
  const subtitle = effectiveData?.title || "";
  const sourceDataLocked = Boolean(widget?.config?.lock_source_data || widget?.lock_source_data);
  const isUserUploadChart = String(widget?.engine_type || widget?.type || "").toLowerCase() === "user_upload_chart";
  const uploadUnitOverride = String(widget?.config?.unit || "").trim();
  const unit = (isUserUploadChart && uploadUnitOverride) ? uploadUnitOverride : (effectiveData?.unit || "");
  const rowsRaw = effectiveData?.rows || [];
  const seriesList = Array.isArray(effectiveData?.series) ? effectiveData.series : [];
  const isMultiSeries = Boolean(effectiveData?.multi_series && seriesList.length > 0);
  const patchWidgetConfigSafe = useCallback(
    async (payload) => safeWidgetConfigPatch(onWidgetConfigPatch, widget?.id, payload),
    [onWidgetConfigPatch, widget?.id]
  );
  const latestDataMode = String(effectiveData?.chart_data_mode || widget?.config?.chart_data_mode || "").toLowerCase() === "latest";
  const chartSortOrder = String(widget?.config?.chart_sort_order || effectiveData?.chart_sort_order || "source").toLowerCase();
  const chartLabelOverrides = useMemo(() => {
    const raw = widget?.config?.chart_label_overrides;
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return {};
    const out = {};
    for (const [key, value] of Object.entries(raw)) {
      const k = String(key || "").trim();
      const v = String(value || "").trim();
      if (k && v) out[k] = v;
    }
    return out;
  }, [widget?.config?.chart_label_overrides]);
  const nativeFrequency = useMemo(
    () =>
      latestDataMode
        ? ""
        : resolveNativeFrequencyCode({
            explicitFrequency: effectiveData?.frequency,
            rows: rowsRaw,
            isMultiSeries,
          }),
    [effectiveData?.frequency, rowsRaw, isMultiSeries, latestDataMode]
  );
  /** Periodicita řady ze serveru nebo odhad ze vzorků období (např. katalog bez pole frequency). */
  const currentFreq = nativeFrequency;

  const [view, setView] = useState(() => resolveInitialDataView(data, widget));
  /** Desktop: graf + tabulka vedle sebe (split), jen graf, nebo jen tabulka. */
  const [layoutPreference, setLayoutPreference] = useState(() => {
    const pref = String(widget?.config?.default_data_view || "").toLowerCase();
    if (pref === "table") return "table";
    if (pref === "split" || pref === "chart_table" || pref === "chart+table") return "split";
    if (pref === "chart") return "chart";
    const initialView = resolveInitialDataView(data, widget);
    if (initialView === "table") return "table";
    return "chart";
  });
  const [timeframe, setTimeframe] = useState("ALL");
  const [customFromPeriod, setCustomFromPeriod] = useState("");
  const [customToPeriod, setCustomToPeriod] = useState("");
  const [targetFreq, setTargetFreq] = useState(() =>
    resolveDefaultTargetFreq(
      widget?.config?.chart_frequency,
      defaultChartFrequency,
      currentFreq
    )
  );
  const [agg, setAgg] = useState(() => normalizeAgg(widget?.config?.agg, unit));
  const initialChartKind = normalizeChartKind(
    widget?.config?.chart_type || data?.chart_type || defaultChartType || "line"
  );
  const [chartKind, setChartKind] = useState(
    CHART_KINDS.some((k) => k.id === initialChartKind) ? initialChartKind : "line"
  );
  const [captionExpanded, setCaptionExpanded] = useState(false);
  const [fullscreenCaptionHidden, setFullscreenCaptionHidden] = useState(false);
  const captionAnchorRef = useRef(null);
  const [titleExpanded, setTitleExpanded] = useState(false);
  const titleAnchorRef = useRef(null);
  const [titleOverflows, setTitleOverflows] = useState(false);
  const [titleEditing, setTitleEditing] = useState(false);
  const [titleDraft, setTitleDraft] = useState("");
  const [titleSaving, setTitleSaving] = useState(false);
  const chartCaptureRef = useRef(null);
  const chartWheelRef = useRef(null);
  const chartFrameRef = useRef(null);
  const chartHintRef = useRef(null);
  /** Tlačítko ozubeného kola u kompaktního režimu — pro ukotvení portálového panelu mimo overflow karty. */
  const compactChartOptsBtnRef = useRef(null);
  const chartRowsRef = useRef([]);
  const chartKindRef = useRef("line");
  const viewRef = useRef("chart");
  const [xZoom, setXZoom] = useState(null);
  const [controlsOpen, setControlsOpen] = useState(false);
  const [barMultiColor, setBarMultiColor] = useState(true);
  const [barOrientation, setBarOrientation] = useState(() => normalizeBarOrientation(widget?.config?.chart_bar_orientation));
  const [pieVariant, setPieVariant] = useState(() => normalizePieVariant(widget?.config?.chart_pie_variant));
  const [mapRegion, setMapRegion] = useState(() => String(widget?.config?.chart_map_region || "europe").toLowerCase());
  const [pictogramIcon, setPictogramIcon] = useState(() => String(widget?.config?.chart_pictogram_icon || "person"));
  const [pictogramUnit, setPictogramUnit] = useState(() => {
    const n = Number(widget?.config?.chart_pictogram_unit);
    return Number.isFinite(n) && n > 0 ? n : 1000;
  });
  const [defaultChartIcon, setDefaultChartIcon] = useState(() =>
    String(widget?.config?.chart_icon_default || "chart")
  );
  const [iconOrientation, setIconOrientation] = useState(() =>
    normalizeIconOrientation(widget?.config?.chart_icon_orientation)
  );
  const [avgLineEnabled, setAvgLineEnabled] = useState(() => widget?.config?.chart_avg_line === true);
  const [medianLineEnabled, setMedianLineEnabled] = useState(() => resolveDefaultMedianLineEnabled(widget));
  const [trendLineEnabled, setTrendLineEnabled] = useState(() => widget?.config?.chart_trend_line === true);
  const [legendHidden, setLegendHidden] = useState(() => widget?.config?.chart_legend_hidden === true);
  const [chartDataPanelOpen, setChartDataPanelOpen] = useState(false);
  const [chartDataPanelMode, setChartDataPanelMode] = useState("data");
  const [chartAnalystPanelOpen, setChartAnalystPanelOpen] = useState(false);
  const [chartAnalystPanelHeight, setChartAnalystPanelHeight] = useState(loadChartAnalystPanelHeight);
  const chartAnalystSplitRef = useRef(null);
  const [chartStatsPanelOpen, setChartStatsPanelOpen] = useState(false);
  const [chartStatsFocus, setChartStatsFocus] = useState("summary");
  const [displayTransform, setDisplayTransform] = useState("raw");
  const [rollingWindow, setRollingWindow] = useState(() => normalizeRollingWindow(widget?.config?.chart_rolling_window, 4));
  const [manualTrendLine, setManualTrendLine] = useState(() => normalizeManualTrendLine(widget?.config?.chart_manual_trend_line));
  const [manualTrendMode, setManualTrendMode] = useState(false);
  const [manualTrendDraft, setManualTrendDraft] = useState(null);
  const [highlightLatestEnabled, setHighlightLatestEnabled] = useState(false);
  const [highlightExtremaEnabled, setHighlightExtremaEnabled] = useState(false);
  const [periodAnnotations, setPeriodAnnotations] = useState(() =>
    Array.isArray(data?.web_annotations) ? data.web_annotations : []
  );
  useEffect(() => {
    if (!Array.isArray(effectiveData?.web_annotations)) return;
    setPeriodAnnotations(effectiveData.web_annotations.slice(-24));
  }, [effectiveData?.web_annotations]);
  const [chartTableTransposed, setChartTableTransposed] = useState(() =>
    Boolean(widget?.config?.chart_table_transpose)
  );
  /** Pozice pevného panelu „Možnosti grafu“ (kompaktní šířky) v souřadnicích viewportu. */
  const [compactControlsPanelPos, setCompactControlsPanelPos] = useState(null);
  const [compareHelpOpen, setCompareHelpOpen] = useState(false);
  const [compareModalOpen, setCompareModalOpen] = useState(false);
  const [chartFullscreenOpen, setChartFullscreenOpen] = useState(false);
  /** Mobilní velký graf: výchozí na šířku (simulovaná landscape na portrait telefonu). */
  const [mobileFsLayout, setMobileFsLayout] = useState("landscape");
  /** WebView v mobilní app — vždy mobilní ovládání, bez webového fullscreen. */
  const isMobileEmbed = isMobileEmbedPath();
  const suppressWebFullscreen = isMobileEmbed;
  /** Pracovní plocha na celou obrazovku — musí být k dispozici před výpočtem kompaktnosti grafu (viz `chartRenderWidth`). */
  const fsExpand = Boolean(chartFullscreenOpen) && !suppressWebFullscreen;
  const showChartAnalystSplit = fsExpand && chartAnalystPanelOpen;

  const persistChartAnalystPanelHeight = useCallback((next) => {
    const rounded = Math.round(next);
    setChartAnalystPanelHeight(rounded);
    try {
      localStorage.setItem(CHART_ANALYST_PANEL_HEIGHT_KEY, String(rounded));
    } catch {
      /* ignore */
    }
  }, []);

  const { onPointerDown: onChartAnalystSplitPointerDown } = useVerticalSplitDrag({
    containerRef: chartAnalystSplitRef,
    height: chartAnalystPanelHeight,
    onHeightChange: persistChartAnalystPanelHeight,
    minHeight: CHART_ANALYST_PANEL_HEIGHT_MIN,
    minOppositeHeight: CHART_ANALYST_PANEL_MIN_CHART,
  });

  useEffect(() => {
    if (!showChartAnalystSplit) return undefined;
    const el = chartAnalystSplitRef.current;
    if (!el || typeof ResizeObserver === "undefined") return undefined;
    const clampToContainer = () => {
      const containerHeight = el.getBoundingClientRect().height;
      if (containerHeight <= 0) return;
      const maxHeight = Math.max(
        CHART_ANALYST_PANEL_HEIGHT_MIN,
        containerHeight - CHART_ANALYST_PANEL_MIN_CHART,
      );
      setChartAnalystPanelHeight((prev) => {
        const clamped = Math.min(prev, maxHeight);
        if (clamped === prev) return prev;
        try {
          localStorage.setItem(CHART_ANALYST_PANEL_HEIGHT_KEY, String(Math.round(clamped)));
        } catch {
          /* ignore */
        }
        return clamped;
      });
    };
    clampToContainer();
    const observer = new ResizeObserver(clampToContainer);
    observer.observe(el);
    return () => observer.disconnect();
  }, [showChartAnalystSplit]);
  const [uploadSeriesOpen, setUploadSeriesOpen] = useState(false);
  const compareHelpWrapRef = useRef(null);
  const fePeriod = useFeatureAccess("chart_period");
  const feTimeRange = useFeatureAccess("chart_time_range");
  const feChartType = useFeatureAccess("chart_type");
  const feViewToggle = useFeatureAccess("chart_table_toggle");
  const feCompositeCharts = useFeatureAccess("composite_charts");
  /** Fail-closed: loading → allowed false → controls disabled. */
  const lockPeriod = !fePeriod.allowed && !unlockChartPeriod;
  const lockTimeRange = !feTimeRange.allowed;
  const lockChartType = !feChartType.allowed;
  const lockViewToggle = !feViewToggle.allowed && !unlockViewToggle;
  const isMobileDashboard = useIsMobileDashboard();
  const isMobileChartUi = isMobileEmbed || isMobileDashboard;
  const physicalLandscape = usePhysicalLandscape();
  const mobileLargeChartView = isMobileEmbed || fsExpand;
  const applyMobileLandscape =
    isMobileChartUi &&
    mobileLargeChartView &&
    mobileFsLayout === "landscape" &&
    !physicalLandscape;
  const { openLogin, isAdmin } = useAuth();
  const lockMsgCount = [fePeriod, feTimeRange, feChartType, feViewToggle].filter((f) => f.ready && !f.allowed).length;

  useEffect(() => {
    if (isMobileEmbed && chartFullscreenOpen) setChartFullscreenOpen(false);
  }, [isMobileEmbed, chartFullscreenOpen]);

  useEffect(() => {
    if (chartFullscreenOpen && isMobileDashboard) setMobileFsLayout("landscape");
  }, [chartFullscreenOpen, isMobileDashboard]);

  useEffect(() => {
    if (!chartFullscreenOpen) return undefined;
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const onKey = (e) => {
      if (e.key === "Escape") setChartFullscreenOpen(false);
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.body.style.overflow = prevOverflow;
      document.removeEventListener("keydown", onKey);
    };
  }, [chartFullscreenOpen]);

  // Admin panel / dědění výchozího typu: při změně config.chart_type nebo defaultChartType
  // musí lokální stihnout okamžitě přerender (useState inicializace běží jen při mount).
  useEffect(() => {
    setViewerComparedData(null);
    setViewerCompareEntries(readViewerCompareEntries(viewerCompareStorageKey));
  }, [viewerCompareStorageKey]);

  useEffect(() => {
    const next = normalizeChartKind(
      widget?.config?.chart_type || effectiveData?.chart_type || defaultChartType || "line"
    );
    const k = CHART_KINDS.some((x) => x.id === next) ? next : "line";
    setChartKind(k);
  }, [widget?.config?.chart_type, effectiveData?.chart_type, defaultChartType, widget?.id]);

  useEffect(() => {
    if (!isMultiSeries) return;
    const allowed = new Set(["line", "bar", "area", "geo_map", "dot"]);
    if (!allowed.has(chartKind)) {
      setChartKind("line");
    }
  }, [isMultiSeries, chartKind]);

  useEffect(() => {
    setMapRegion(String(widget?.config?.chart_map_region || "europe").toLowerCase());
    setPictogramIcon(String(widget?.config?.chart_pictogram_icon || "person"));
    const n = Number(widget?.config?.chart_pictogram_unit);
    setPictogramUnit(Number.isFinite(n) && n > 0 ? n : 1000);
    setDefaultChartIcon(String(widget?.config?.chart_icon_default || "chart"));
    setIconOrientation(normalizeIconOrientation(widget?.config?.chart_icon_orientation));
  }, [
    widget?.id,
    widget?.config?.chart_map_region,
    widget?.config?.chart_pictogram_icon,
    widget?.config?.chart_pictogram_unit,
    widget?.config?.chart_icon_default,
    widget?.config?.chart_icon_orientation,
  ]);

  useEffect(() => {
    const pref = String(widget?.config?.default_data_view || "").toLowerCase();
    if (pref === "table") {
      setLayoutPreference("table");
      setView("table");
    } else if (pref === "chart") {
      setLayoutPreference("chart");
      setView("chart");
    } else if (pref === "split" || pref === "chart_table" || pref === "chart+table") {
      setLayoutPreference("split");
      setView("chart");
    }
  }, [widget?.config?.default_data_view, widget?.id]);

  useEffect(() => {
    setBarOrientation(normalizeBarOrientation(widget?.config?.chart_bar_orientation));
    setPieVariant(normalizePieVariant(widget?.config?.chart_pie_variant));
    setLegendHidden(widget?.config?.chart_legend_hidden === true);
  }, [widget?.config?.chart_bar_orientation, widget?.config?.chart_pie_variant, widget?.config?.chart_legend_hidden, widget?.id]);

  useEffect(() => {
    setAvgLineEnabled(widget?.config?.chart_avg_line === true);
    setMedianLineEnabled(resolveDefaultMedianLineEnabled(widget));
    setTrendLineEnabled(widget?.config?.chart_trend_line === true);
  }, [widget?.config?.chart_avg_line, widget?.config?.chart_median_line, widget?.config?.chart_trend_line, widget?.id]);

  useEffect(() => {
    setRollingWindow(normalizeRollingWindow(widget?.config?.chart_rolling_window, 4));
    setManualTrendLine(normalizeManualTrendLine(widget?.config?.chart_manual_trend_line));
    setManualTrendMode(false);
    setManualTrendDraft(null);
  }, [widget?.config?.chart_rolling_window, widget?.config?.chart_manual_trend_line, widget?.id]);

  useEffect(() => {
    setChartTableTransposed(Boolean(widget?.config?.chart_table_transpose));
  }, [widget?.config?.chart_table_transpose, widget?.id]);

  // Výchozí periodicita z widgetu / stránky / nativní nebo inferred data.
  useEffect(() => {
    setTargetFreq(
      resolveDefaultTargetFreq(widget?.config?.chart_frequency, defaultChartFrequency, currentFreq)
    );
    setAgg(normalizeAgg(widget?.config?.agg, effectiveData?.unit || ""));
  }, [
    currentFreq,
    effectiveData?.unit,
    widget?.config?.agg,
    widget?.config?.chart_frequency,
    defaultChartFrequency,
    widget?.id,
  ]);

  const normalized = useMemo(() => {
    if (isMultiSeries) return [];
    const rows = rowsRaw
      .map((r, idx) => {
        const rawPeriod = latestDataMode ? String(r.x ?? r.period ?? "") : String(r.period ?? r.x ?? "");
        const rawPeriodTrimmed = rawPeriod.trim();
        const overrideLabel = chartLabelOverrides[rawPeriod] || chartLabelOverrides[rawPeriodTrimmed] || "";
        const fallbackLabel = latestDataMode ? `Položka ${idx + 1}` : `Období ${idx + 1}`;
        const normalizedPeriod = normalizeExportPeriod(rawPeriodTrimmed);
        const canonicalPeriod = normalizedPeriod.period || rawPeriodTrimmed;
        const period = String(
          latestDataMode
            ? (overrideLabel || rawPeriodTrimmed || fallbackLabel)
            : (canonicalPeriod || fallbackLabel)
        ).trim();
        const value = r.value ?? r.y;
        return {
          period,
          value,
          date: latestDataMode ? null : parseChartPeriod(canonicalPeriod || period),
          latestPeriod: r.period,
          rawPeriod: rawPeriodTrimmed || rawPeriod,
        };
      })
      .filter((r) => r.period && r.value !== undefined && r.value !== null);
    const shouldSortByValue =
      (chartSortOrder === "asc" || chartSortOrder === "desc") &&
      (latestDataMode || (isUserUploadChart && chartKind === "bar"));
    if (shouldSortByValue) {
      const cmpVal = (a, b) => {
        const na = coerceChartNumeric(a.value);
        const nb = coerceChartNumeric(b.value);
        if (na != null && nb != null && na !== nb) return na - nb;
        return String(a.period).localeCompare(String(b.period), "cs");
      };
      if (chartSortOrder === "asc") {
        return rows.sort(cmpVal);
      }
      return rows.sort((a, b) => cmpVal(b, a));
    }
    if (latestDataMode) {
      return rows.sort((a, b) => String(a.period).localeCompare(String(b.period), "cs"));
    }
    return rows.sort((a, b) => {
      const ta =
        a.date instanceof Date && !Number.isNaN(a.date.getTime())
          ? a.date.getTime()
          : 0;
      const tb =
        b.date instanceof Date && !Number.isNaN(b.date.getTime())
          ? b.date.getTime()
          : 0;
      return ta - tb;
    });
  }, [rowsRaw, isMultiSeries, latestDataMode, chartSortOrder, chartLabelOverrides, isUserUploadChart, chartKind]);

  const multiNormalized = useMemo(() => {
    if (!isMultiSeries) return [];
    const rows = rowsRaw
      .map((r, idx) => {
        const periodRaw = String(r.period ?? r.x ?? "").trim();
        const normalizedPeriod = normalizeExportPeriod(periodRaw);
        const period = normalizedPeriod.period || periodRaw || `Období ${idx + 1}`;
        const row = { period, date: parseChartPeriod(normalizedPeriod.period || period) };
        for (const s of seriesList) {
          const k = s.key;
          if (!k) continue;
          const v = r[k];
          const n = typeof v === "number" && !Number.isNaN(v) ? v : parseNumber(v);
          if (n !== null) row[k] = n;
        }
        return row;
      })
      .filter((r) => r.period);
    return rows.sort((a, b) => {
      const ta =
        a.date instanceof Date && !Number.isNaN(a.date.getTime())
          ? a.date.getTime()
          : 0;
      const tb =
        b.date instanceof Date && !Number.isNaN(b.date.getTime())
          ? b.date.getTime()
          : 0;
      if (ta !== tb) return ta - tb;
      return String(a.period).localeCompare(String(b.period));
    });
  }, [rowsRaw, isMultiSeries, seriesList]);

  const activeBase = isMultiSeries ? multiNormalized : normalized;
  const hasDates = activeBase.some((r) => r.date instanceof Date && !Number.isNaN(r.date.getTime()));
  const periodOptions = useMemo(() => {
    const seen = new Set();
    const out = [];
    for (const r of activeBase) {
      const p = String(r?.period || "");
      if (!p || seen.has(p)) continue;
      seen.add(p);
      out.push(p);
    }
    return out;
  }, [activeBase]);

  useEffect(() => {
    if (timeframe !== "CUSTOM") return;
    if (!periodOptions.length) return;
    setCustomFromPeriod((prev) => (prev && periodOptions.includes(prev) ? prev : periodOptions[0]));
    setCustomToPeriod((prev) =>
      prev && periodOptions.includes(prev) ? prev : periodOptions[periodOptions.length - 1]
    );
  }, [timeframe, periodOptions]);

  const timeFiltered = useMemo(() => {
    if (!hasDates || timeframe === "ALL") return activeBase;
    if (timeframe === "CUSTOM") {
      if (!customFromPeriod && !customToPeriod) return activeBase;
      const orderByPeriod = new Map();
      for (let i = 0; i < activeBase.length; i++) {
        const p = String(activeBase[i]?.period || "");
        if (!p || orderByPeriod.has(p)) continue;
        const dt = activeBase[i]?.date;
        const ord =
          dt instanceof Date && !Number.isNaN(dt.getTime()) ? dt.getTime() : i * 86_400_000;
        orderByPeriod.set(p, ord);
      }
      const fromOrd = customFromPeriod ? orderByPeriod.get(customFromPeriod) : null;
      const toOrd = customToPeriod ? orderByPeriod.get(customToPeriod) : null;
      if (fromOrd == null && toOrd == null) return activeBase;
      const lo = fromOrd == null ? toOrd : toOrd == null ? fromOrd : Math.min(fromOrd, toOrd);
      const hi = fromOrd == null ? toOrd : toOrd == null ? fromOrd : Math.max(fromOrd, toOrd);
      return activeBase.filter((r, idx) => {
        const dt = r?.date;
        const ord =
          dt instanceof Date && !Number.isNaN(dt.getTime()) ? dt.getTime() : idx * 86_400_000;
        return ord >= lo && ord <= hi;
      });
    }
    const tf = TIMEFRAMES.find((t) => t.id === timeframe);
    if (!tf || !tf.years) return activeBase;
    const latest = activeBase
      .map((r) => r.date?.getTime())
      .filter((t) => typeof t === "number" && !Number.isNaN(t))
      .sort((a, b) => b - a)[0];
    if (!latest) return activeBase;
    const cutoff = new Date(latest);
    cutoff.setFullYear(cutoff.getFullYear() - tf.years);
    return activeBase.filter((r) => r.date && r.date.getTime() >= cutoff.getTime());
  }, [activeBase, timeframe, hasDates, customFromPeriod, customToPeriod]);

  const hasDuplicateTimeBuckets = useMemo(() => {
    if (isMultiSeries || !hasDates) return false;
    const seen = new Set();
    for (const r of timeFiltered) {
      if (!(r?.date instanceof Date) || Number.isNaN(r.date.getTime())) continue;
      const b = bucketKey(r.date, targetFreq);
      if (!b?.key) continue;
      if (seen.has(b.key)) return true;
      seen.add(b.key);
    }
    return false;
  }, [isMultiSeries, hasDates, timeFiltered, targetFreq]);

  const filtered = useMemo(() => {
    if (isMultiSeries) return timeFiltered;
    const sourceRank = FREQ_RANK[currentFreq] ?? 99;
    const targetRank = FREQ_RANK[targetFreq] ?? 99;
    if (!hasDates || targetRank < sourceRank) {
      return timeFiltered;
    }
    if (targetRank === sourceRank && !hasDuplicateTimeBuckets) {
      return timeFiltered;
    }
    return aggregateRows(timeFiltered, targetFreq, agg, {
      unit: effectiveData?.unit || "",
      title: effectiveData?.title || userTitle || "",
    });
  }, [
    isMultiSeries,
    timeFiltered,
    targetFreq,
    agg,
    currentFreq,
    hasDates,
    hasDuplicateTimeBuckets,
    effectiveData?.unit,
    effectiveData?.title,
    userTitle,
  ]);

  const multiFrequencyAlignment = useMemo(() => {
    if (!isMultiSeries) {
      return { rows: [], aligned: false, targetFrequency: "", sourceFrequencies: {} };
    }
    return alignMultiSeriesRowsToCoarsestFrequency(timeFiltered, seriesList, {
      userTargetFrequency: targetFreq,
    });
  }, [isMultiSeries, timeFiltered, seriesList, targetFreq]);

  const multiRowsForChart = isMultiSeries && multiFrequencyAlignment.aligned
    ? multiFrequencyAlignment.rows
    : timeFiltered;
  const multiFrequencyAlignmentLabel = useMemo(() => {
    if (!isMultiSeries || !multiFrequencyAlignment.aligned || !multiFrequencyAlignment.targetFrequency) return "";
    const label = CHART_FREQUENCY_LABEL_CS[multiFrequencyAlignment.targetFrequency] || multiFrequencyAlignment.targetFrequency;
    return `sjednoceno: ${label}`;
  }, [isMultiSeries, multiFrequencyAlignment.aligned, multiFrequencyAlignment.targetFrequency]);

  const chartRows = useMemo(() => {
    if (isMultiSeries) {
      return multiRowsForChart.map((r) => {
        const o = { x: r.x ?? r.period };
        for (const s of seriesList) {
          const k = s.key;
          if (!k) continue;
          const n = coerceChartNumeric(r[k]);
          if (n !== null) o[k] = n;
        }
        return o;
      });
    }
    return filtered
      .map((r) => {
        const yNum = coerceChartNumeric(r.value);
        return { x: r.period, y: yNum !== null ? yNum : null, period: r.latestPeriod };
      })
      .filter((r) => r.y !== null && Number.isFinite(r.y));
  }, [isMultiSeries, multiRowsForChart, filtered, seriesList]);

  const chartDataSig = useMemo(
    () =>
      `${chartRows.length}|${String(chartRows[0]?.x ?? "")}|${String(chartRows[chartRows.length - 1]?.x ?? "")}`,
    [chartRows]
  );

  useEffect(() => {
    setXZoom(null);
  }, [chartDataSig, widget?.id]);

  useEffect(() => {
    if (chartKind === "pie") setXZoom(null);
  }, [chartKind]);

  useEffect(() => {
    setXZoom(null);
  }, [displayTransform]);

  const transformedChartRows = useMemo(
    () =>
      applyAradViewDisplayTransform(chartRows, displayTransform, {
        isMultiSeries,
        seriesList,
        frequency: currentFreq || targetFreq,
        window: rollingWindow,
      }),
    [chartRows, displayTransform, isMultiSeries, seriesList, currentFreq, targetFreq, rollingWindow]
  );

  const visibleXDomain = useMemo(() => {
    if (chartKind === "pie" || !xZoom) return null;
    const [fullStart, fullEnd] = getFullXDomain(transformedChartRows);
    const start = clampNum(xZoom.start, fullStart, fullEnd);
    const end = clampNum(xZoom.end, start, fullEnd);
    return start <= fullStart && end >= fullEnd ? null : [start, end];
  }, [transformedChartRows, xZoom, chartKind]);

  useEffect(() => {
    if (!xZoom) return;
    const n = transformedChartRows.length;
    if (n < 1) return;
    if (xZoom.start <= 0 && xZoom.end >= n - 1) {
      setXZoom(null);
    }
  }, [xZoom, transformedChartRows.length]);

  const chartRowsZoomed = useMemo(
    () => getVisibleData(transformedChartRows, visibleXDomain),
    [transformedChartRows, visibleXDomain]
  );
  const valueCompareStats = useMemo(() => {
    if (isMultiSeries) return null;
    if (chartKind !== "bar" && chartKind !== "pie") return null;
    const categoricalCompare =
      latestDataMode ||
      (chartKind === "bar" &&
        isUserUploadChart &&
        (chartSortOrder === "asc" || chartSortOrder === "desc"));
    if (!categoricalCompare) return null;
    if (chartKind === "pie" && !latestDataMode) return null;
    return computeValueCompareStats(chartRowsZoomed);
  }, [
    isMultiSeries,
    chartKind,
    latestDataMode,
    isUserUploadChart,
    chartSortOrder,
    chartRowsZoomed,
  ]);
  const timeSeriesStats = useMemo(() => {
    if (isMultiSeries || latestDataMode || chartKind === "pie") return null;
    const visibleRows = getVisibleData(chartRows, visibleXDomain);
    return computeTimeSeriesStats(visibleRows);
  }, [isMultiSeries, latestDataMode, chartKind, chartRows, visibleXDomain]);
  const chartDisplayState = useMemo(() => {
    if (isMultiSeries || latestDataMode || chartKind === "pie" || !timeSeriesStats) return null;
    const freqEntry = ALL_FREQS.find((f) => f.code === targetFreq);
    // "rows"/"isAggregated" nesou aktuálně zobrazovanou (podle periodicity agregovanou) řadu ven z
    // grafu - konzument (CatalogChartPreview.jsx) je použije k synchronizaci vnější tabulky/exportu
    // se stejnou periodicitou, jakou má právě vybranou graf (viz "filtered" výše).
    const isAggregatedNow =
      (FREQ_RANK[targetFreq] ?? 99) > (FREQ_RANK[currentFreq] ?? 99) || hasDuplicateTimeBuckets;
    return {
      frequencyCode: targetFreq || "",
      frequencyLabel: freqEntry?.title || "",
      lastValue: timeSeriesStats.lastValue,
      lastPeriod: timeSeriesStats.lastPeriod,
      prevValue: timeSeriesStats.prevValue,
      unit,
      isAggregated: isAggregatedNow,
      rows: filtered.map((r) => ({ period: r.period, value: r.value })),
    };
  }, [
    isMultiSeries,
    latestDataMode,
    chartKind,
    timeSeriesStats,
    targetFreq,
    currentFreq,
    hasDuplicateTimeBuckets,
    unit,
    filtered,
  ]);
  const chartDisplayStateOutRef = useRef(undefined);
  useEffect(() => {
    if (typeof onChartDisplayStateChange !== "function") return;
    if (chartDisplayStatesEqual(chartDisplayStateOutRef.current, chartDisplayState)) return;
    chartDisplayStateOutRef.current = chartDisplayState;
    onChartDisplayStateChange(chartDisplayState);
  }, [onChartDisplayStateChange, chartDisplayState]);
  const singleSeriesOverlaySpec = useMemo(() => {
    const manualSegment = manualTrendSegment(manualTrendLine);
    if (!avgLineEnabled && !medianLineEnabled && !trendLineEnabled && !manualSegment) return null;
    const rows = Array.isArray(chartRowsZoomed) ? chartRowsZoomed : [];
    if (!rows.length && !manualSegment) return null;
    const pts = rows
      .map((r) => {
        const y = coerceChartNumeric(r?.y ?? r?.value);
        const x = r?.x ?? r?.period ?? r?.name;
        return { x, y };
      })
      .filter((p) => p.x != null && p.x !== "" && p.y != null && Number.isFinite(p.y));
    if (!pts.length && !manualSegment) return null;
    const values = pts.map((p) => p.y);
    let average = null;
    if (avgLineEnabled && values.length) {
      average = values.reduce((s, v) => s + v, 0) / values.length;
    }
    let median = null;
    if (medianLineEnabled && values.length) median = medianOfNumbers(values);
    const trend = trendLineEnabled ? trendSegmentForPoints(pts) : { trendSegment: null, trendSlopePerStep: null };
    return {
      showAverage: avgLineEnabled,
      showMedian: medianLineEnabled,
      showTrend: trendLineEnabled,
      showManualTrend: Boolean(manualSegment),
      average,
      median,
      trendSegment: trend.trendSegment,
      trendSlopePerStep: trend.trendSlopePerStep,
      manualTrendSegment: manualSegment,
    };
  }, [chartRowsZoomed, avgLineEnabled, medianLineEnabled, trendLineEnabled, manualTrendLine]);

  const multiSeriesOverlaySpec = useMemo(() => {
    const manualSegment = manualTrendSegment(manualTrendLine);
    if (!isMultiSeries || (!avgLineEnabled && !medianLineEnabled && !trendLineEnabled && !manualSegment)) return null;
    const rows = Array.isArray(chartRowsZoomed) ? chartRowsZoomed : [];
    const items = (seriesList || [])
      .map((series, idx) => {
        const key = series?.key;
        if (!key) return null;
        const points = rows
          .map((row) => {
            const y = coerceChartNumeric(row?.[key]);
            const x = row?.x ?? row?.period;
            return { x, y };
          })
          .filter((point) => point.x != null && point.x !== "" && point.y != null && Number.isFinite(point.y));
        if (!points.length) return null;
        const values = points.map((point) => point.y);
        const trend = trendLineEnabled ? trendSegmentForPoints(points) : { trendSegment: null, trendSlopePerStep: null };
        return {
          key,
          label: series?.name || series?.label || series?.indicator_id || `Řada ${idx + 1}`,
          color: PIE_COLORS[idx % PIE_COLORS.length],
          yAxisId: String(series?.y_axis || "").toLowerCase() === "right" ? "right" : "left",
          showAverage: avgLineEnabled,
          showMedian: medianLineEnabled,
          showTrend: trendLineEnabled,
          average: avgLineEnabled ? values.reduce((sum, value) => sum + value, 0) / values.length : null,
          median: medianLineEnabled ? medianOfNumbers(values) : null,
          trendSegment: trend.trendSegment,
          trendSlopePerStep: trend.trendSlopePerStep,
        };
      })
      .filter(Boolean);
    if (!items.length && !manualSegment) return null;
    return {
      series: items,
      manualTrendSegment: manualSegment,
      showManualTrend: Boolean(manualSegment),
    };
  }, [isMultiSeries, chartRowsZoomed, seriesList, avgLineEnabled, medianLineEnabled, trendLineEnabled, manualTrendLine]);

  const aradChartContract = useMemo(
    () =>
      contractFromAradViewState({
        title: heading,
        subtitle,
        unit,
        chartKind,
        latestDataMode,
        isMultiSeries,
        seriesList,
        chartRows: chartRowsZoomed,
        data: effectiveData,
        widget,
        frequency: currentFreq || targetFreq,
        transformId: displayTransform,
      }),
    [
      heading,
      subtitle,
      unit,
      chartKind,
      latestDataMode,
      isMultiSeries,
      seriesList,
      chartRowsZoomed,
      effectiveData,
      widget,
      currentFreq,
      targetFreq,
      displayTransform,
    ]
  );
  const chartAnalystContract = useMemo(
    () => ({
      ...aradChartContract,
      active_annotations: (Array.isArray(periodAnnotations) ? periodAnnotations : []).slice(-24).map((item) => ({
        label: String(item?.label || "").slice(0, 180),
        from: String(item?.from || "").slice(0, 32),
        to: String(item?.to || "").slice(0, 32),
        display_mode: String(item?.display_mode || "").slice(0, 16),
        color: String(item?.color || "").slice(0, 16),
        layer_id: String(item?.layer_id || "").slice(0, 64),
        description_cz: String(item?.description_cz || "").slice(0, 360),
      })),
    }),
    [aradChartContract, periodAnnotations]
  );
  const olapPackage = useMemo(() => {
    if (!aradChartContract?.data?.length) return null;
    return buildOlapCubePackage(aradChartContract, { query: subtitle || heading || "" });
  }, [aradChartContract, heading, subtitle]);
  const canExportSourceData = !sourceDataLocked && Boolean(aradChartContract?.data?.length);
  const handleOpenOlapCube = useCallback(() => {
    setChartDataPanelMode("olap");
    setChartDataPanelOpen((open) => (chartDataPanelMode === "olap" ? !open : true));
  }, [chartDataPanelMode]);

  const aradTransformCapabilities = useMemo(() => {
    const base = contractFromAradViewState({
      title: heading,
      unit,
      chartKind,
      latestDataMode,
      isMultiSeries,
      seriesList,
      chartRows,
      data: effectiveData,
      widget,
      frequency: currentFreq || targetFreq,
      transformId: "raw",
    });
    return resolveToolbarCapabilities(base);
  }, [
    heading,
    unit,
    chartKind,
    latestDataMode,
    isMultiSeries,
    seriesList,
    chartRows,
    effectiveData,
    widget,
    currentFreq,
    targetFreq,
  ]);

  const allowedAradTransforms = useMemo(() => {
    const allowed = new Set(aradTransformCapabilities.transform || []);
    return ARAD_VIEW_TRANSFORMS.filter((t) => t.id === "raw" || allowed.has(t.id));
  }, [aradTransformCapabilities]);

  const highlightSpec = useMemo(() => {
    if (isMultiSeries || chartKind === "pie") return null;
    if (!highlightLatestEnabled && !highlightExtremaEnabled) return null;
    return { latest: highlightLatestEnabled, extrema: highlightExtremaEnabled };
  }, [isMultiSeries, chartKind, highlightLatestEnabled, highlightExtremaEnabled]);

  const handleCopyChartData = useCallback(async () => {
    if (sourceDataLocked) return;
    if (!aradChartContract?.data?.length) return;
    try {
      await copyChartDataWideToClipboard(aradChartContract, { locale: "cs-CZ" });
    } catch (err) {
      console.error("Copy chart data failed", err);
    }
  }, [aradChartContract, sourceDataLocked]);

  chartRowsRef.current = chartRows;
  chartKindRef.current = chartKind;
  viewRef.current = view;

  // "filtered" uz respektuje vybranou periodicitu (aggregateRows podle targetFreq) - drivejsi
  // pouziti "timeFiltered" (jen orezane podle obdobi, vzdy nativni frekvence) znamenalo, ze tabulka
  // po prepnuti na Tydenni/Mesicni.../.. porad ukazovala syrove denni/nativni radky, i kdyz graf uz
  // spravne prekreslil agregovana data.
  const tableRows = useMemo(() => latestDataMode ? [...filtered] : [...filtered].reverse(), [filtered, latestDataMode]);
  const isAggregated = !isMultiSeries && (
    (FREQ_RANK[targetFreq] ?? 99) > (FREQ_RANK[currentFreq] ?? 99) || hasDuplicateTimeBuckets
  );
  const seriesTableLabels = useMemo(() => {
    if (!isMultiSeries) {
      return { stripPrefix: "", displayLabels: [], fullLabels: [] };
    }
    const fullLabels = seriesList.map((s, i) =>
      String((s.name || s.label || s.indicator_id || s.key || `Řada ${i + 1}`).trim() || `s${i + 1}`)
    );
    const stripPrefix = refineMultiSeriesTableStripPrefix(longestCommonPrefix(fullLabels));
    const displayLabels = seriesList.map((s, i) => {
      const keyStr = String(s.key || "").trim();
      const ov = keyStr ? chartLabelOverrides[keyStr] : "";
      if (ov) return ov;
      const full = fullLabels[i];
      if (stripPrefix && full.startsWith(stripPrefix)) {
        const rest = full.slice(stripPrefix.length).trim();
        return rest || full;
      }
      return full;
    });
    return { stripPrefix, displayLabels, fullLabels };
  }, [isMultiSeries, seriesList, chartLabelOverrides]);
  const compareGeoHints = useMemo(() => {
    if (!isMultiSeries) return [];
    const hints = [];
    const qp = widget?.config?.query_params;
    const primaryGeo = qp?.geo ?? qp?.REF_AREA ?? qp?.ref_area;
    if (primaryGeo != null && primaryGeo !== "") {
      hints[0] = String(Array.isArray(primaryGeo) ? primaryGeo[0] : primaryGeo).trim();
    }
    const titleGeo = rowLabelToIso(heading);
    if (!hints[0] && titleGeo) hints[0] = titleGeo;
    const cmp = widget?.config?.chart_compare_with;
    if (Array.isArray(cmp)) {
      cmp.forEach((entry, j) => {
        const params = entry?.query_params && typeof entry.query_params === "object" ? entry.query_params : {};
        const g = params.geo ?? params.REF_AREA ?? params.ref_area ?? entry?.geo ?? entry?.ref_area;
        if (g != null && g !== "") {
          hints[j + 1] = String(Array.isArray(g) ? g[0] : g).trim();
        } else if (entry?.name) {
          hints[j + 1] = String(entry.name).trim();
        }
      });
    }
    const groupField = String(
      effectiveData?.group_field || widget?.config?.group_field || ""
    ).trim();
    if (isGeoGroupField(groupField)) {
      seriesList.forEach((s, i) => {
        if (rowLabelToIso(hints[i])) return;
        const candidates = [
          s?.geo,
          s?.ref_area,
          s?.country,
          s?.id,
          s?.name,
          s?.label,
          seriesTableLabels.displayLabels?.[i],
        ];
        for (const c of candidates) {
          if (c == null || c === "") continue;
          const token = String(c).trim();
          if (rowLabelToIso(token)) {
            hints[i] = token;
            break;
          }
        }
      });
    }
    return hints;
  }, [
    isMultiSeries,
    widget?.config?.query_params,
    widget?.config?.chart_compare_with,
    widget?.config?.group_field,
    effectiveData?.group_field,
    heading,
    seriesList,
    seriesTableLabels.displayLabels,
  ]);
  const multiSeriesStatistics = useMemo(() => {
    if (!isMultiSeries || chartKind === "pie") return { ok: false, series: [], pairs: [] };
    return buildChartSeriesStatistics({
      chartRows: chartRowsZoomed,
      seriesList,
      frequency: multiFrequencyAlignment.targetFrequency || currentFreq || targetFreq,
      displayLabels: seriesTableLabels.displayLabels,
    });
  }, [
    isMultiSeries,
    chartKind,
    chartRowsZoomed,
    seriesList,
    multiFrequencyAlignment.targetFrequency,
    currentFreq,
    targetFreq,
    seriesTableLabels.displayLabels,
  ]);
  const singleSeriesStatistics = useMemo(() => {
    if (isMultiSeries || latestDataMode || chartKind === "pie") return { ok: false };
    return buildSingleSeriesStatistics({
      chartRows: chartRowsZoomed,
      frequency: currentFreq || targetFreq,
      label: heading,
    });
  }, [isMultiSeries, latestDataMode, chartKind, chartRowsZoomed, currentFreq, targetFreq, heading]);
  const exportColumns = useMemo(() => {
    if (!isMultiSeries) return [latestDataMode ? "řada" : "období", "hodnota"];
    if (chartTableTransposed) {
      return ["ukazatel", ...tableRows.map((r) => String(fmtPeriodLabel(r.period)))];
    }
    return ["období", ...seriesTableLabels.displayLabels];
  }, [isMultiSeries, latestDataMode, chartTableTransposed, tableRows, seriesTableLabels.displayLabels]);
  const exportRows = useMemo(() => {
    if (!isMultiSeries) {
      const labelKey = latestDataMode ? "řada" : "období";
      return tableRows.map((r) => ({ [labelKey]: r.period, "hodnota": r.value }));
    }
    if (chartTableTransposed) {
      return seriesList.map((s, i) => {
        const row = { ukazatel: String(seriesTableLabels.displayLabels[i] ?? seriesTableLabels.fullLabels[i] ?? "").trim() };
        tableRows.forEach((r) => {
          const col = String(fmtPeriodLabel(r.period));
          const n = s.key ? parseNumber(r[s.key]) : null;
          row[col] = n !== null ? n : "";
        });
        return row;
      });
    }
    return tableRows.map((r) => {
      const o = { období: r.period };
      seriesList.forEach((s, i) => {
        const label = String(seriesTableLabels.displayLabels[i] ?? seriesTableLabels.fullLabels[i] ?? `s${i + 1}`).trim();
        const n = s.key ? parseNumber(r[s.key]) : null;
        o[label] = n !== null ? n : "";
      });
      return o;
    });
  }, [
    isMultiSeries,
    tableRows,
    seriesList,
    latestDataMode,
    chartTableTransposed,
    seriesTableLabels.displayLabels,
    seriesTableLabels.fullLabels,
  ]);
  const chartWidgetWidth = isMobileChartUi ? "full" : widget?.width;
  /** V režimu celé obrazovky kreslit graf jako široký panel (osy, písma, mezery), ne jako zvětšenou úzkou dlaždici. */
  const chartRenderWidth = fsExpand ? "full" : chartWidgetWidth;
  const chartCompact = !fsExpand && ["half", "third", "quarter", "sixth", "eighth"].includes(chartWidgetWidth);
  const veryNarrowWidget = !fsExpand && (chartWidgetWidth === "quarter" || chartWidgetWidth === "sixth" || chartWidgetWidth === "eighth");
  /** Na široké dlaždici zůstává větší písmo grafu; ovládání je stejně v panelu Možnosti. */
  const wideChartTile =
    chartWidgetWidth === "full" ||
    chartWidgetWidth === "three-quarters" ||
    chartWidgetWidth === "two-thirds";
  const hideChartControls = Boolean(widget?.config?.hide_chart_controls) && !wideChartTile;
  /** Kompaktní dlaždice = minigraf: flex výška, skryté popisky, ovládání v Možnostech. */
  const miniChartMode =
    !isMobileChartUi && !fsExpand && (chartCompact || Boolean(widget?.config?.mini_chart));
  const showBarLabels =
    !isMultiSeries &&
    chartKind === "bar" &&
    !veryNarrowWidget &&
    (widget?.config?.chart_bar_labels === true ||
      catalogLivePreview ||
      ((latestDataMode ||
        (isUserUploadChart && (chartSortOrder === "asc" || chartSortOrder === "desc"))) &&
        widget?.config?.chart_bar_labels !== false));
  const valueCompareBarMode =
    !isMultiSeries &&
    chartKind === "bar" &&
    isUserUploadChart &&
    (chartSortOrder === "asc" || chartSortOrder === "desc");
  const horizontalBarMode = !isMultiSeries && chartKind === "bar" && barOrientation === "horizontal";
  const showLatestBarLegend =
    !legendHidden &&
    !isMultiSeries &&
    chartKind === "bar" &&
    !horizontalBarMode &&
    latestDataMode &&
    chartRowsZoomed.length > 1 &&
    // Legenda u srovnání hodnot jen opisuje popisky na ose X. U hrstky sloupců pomůže,
    // u dvaceti sedmi zemí to je stěna jmen, která si vezme celou výšku karty a na graf
    // nezbude nic — widget pak ukazoval jen seznam států bez jediného sloupce.
    // Sloupce mají díky vodorovnému posuvníku dost šířky, aby se popsaly samy.
    chartRowsZoomed.length <= LATEST_BAR_LEGEND_MAX_ITEMS &&
    !miniChartMode &&
    !chartCompact;
  const latestBarLegendItems = showLatestBarLegend ? chartRowsZoomed.slice(0, LATEST_BAR_LEGEND_MAX_ITEMS) : [];
  const effectiveKpiSummaryMode = resolveKpiSummaryMode({
    mode: kpiSummaryMode,
    catalogLivePreview,
    catalogChartSize,
    miniChartMode,
    veryNarrowWidget,
    chartCompact,
    fsExpand,
    hasStats: Boolean(valueCompareStats || timeSeriesStats),
    isMobileChartUi,
  });
  const showValueCompareSummary =
    view === "chart" &&
    effectiveKpiSummaryMode !== "hidden" &&
    !isMultiSeries &&
    Boolean(valueCompareStats);
  const showTimeSeriesSummary =
    view === "chart" &&
    effectiveKpiSummaryMode !== "hidden" &&
    !isMultiSeries &&
    !showValueCompareSummary &&
    Boolean(timeSeriesStats);
  const showSeriesStatisticsButton =
    view === "chart" &&
    chartKind !== "pie" &&
    !miniChartMode &&
    (isMultiSeries ? Boolean(multiSeriesStatistics?.ok) : Boolean(singleSeriesStatistics?.ok));
  const showSeriesStatisticsPanel =
    showSeriesStatisticsButton &&
    chartStatsPanelOpen &&
    !miniChartMode;
  const showMultiSeriesLegend =
    !legendHidden &&
    isMultiSeries &&
    !miniChartMode &&
    Array.isArray(seriesList) &&
    seriesList.length > 1;
  const multiSeriesLegendItems = showMultiSeriesLegend ? seriesList.slice(0, 40) : [];
  const showSingleSeriesLegend =
    !legendHidden &&
    view === "chart" &&
    !isMultiSeries &&
    !miniChartMode &&
    !chartCompact &&
    chartKind !== "pie" &&
    filtered.length > 0 &&
    !showLatestBarLegend;
  const chartColorCfg = (widget?.config?.chart_color || "").trim();
  const singleSeriesLegendColor = /^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})$/.test(chartColorCfg)
    ? chartColorCfg
    : CHART_COLORS.primary;
  const latestBarScrollMode =
    !isMultiSeries && chartKind === "bar" && !horizontalBarMode && (latestDataMode || valueCompareBarMode);
  const chartScrollable =
    !isMultiSeries &&
    chartKind === "bar" &&
    !horizontalBarMode &&
    barChartHorizontalScrollEnabled(chartRowsZoomed.length, {
      latestBarMode: latestBarScrollMode,
      mobile: isMobileChartUi,
    });
  const valueCompareBarSummaryMode =
    !isMultiSeries &&
    chartKind === "bar" &&
    !horizontalBarMode &&
    showValueCompareSummary &&
    Boolean(valueCompareStats);
  const chartScrollMinWidth = chartScrollable
    ? resolveChartScrollMinWidth(chartRowsZoomed.length, {
        compact: chartCompact,
        isBar: true,
        latestBarMode: latestBarScrollMode,
        mobile: isMobileChartUi,
      })
    : undefined;
  const visibleAxisSpec = useMemo(() => {
    const veryNarrow = veryNarrowWidget;
    const tickCount = veryNarrow ? 3 : chartCompact ? 4 : 5;
    const barValueAxis = chartKind === "bar" && !horizontalBarMode;
    const axisInputValues = (values) => (barValueAxis ? [0, ...values] : values);
    if (isMultiSeries) {
      const leftKeys = (seriesList || [])
        .filter((s) => String(s?.y_axis || "").toLowerCase() !== "right")
        .map((s) => s?.key)
        .filter(Boolean);
      const rightKeys = (seriesList || [])
        .filter((s) => String(s?.y_axis || "").toLowerCase() === "right")
        .map((s) => s?.key)
        .filter(Boolean);
      const allKeys = [...leftKeys, ...rightKeys];
      const fullValuesForKeys = (keys) =>
        (transformedChartRows || []).flatMap((row) =>
          keys
            .map((key) => coerceChartNumeric(row?.[key]))
            .filter((v) => typeof v === "number" && Number.isFinite(v))
        );
      const multiOverlayValuesForAxis = (axisId) => {
        if (!multiSeriesOverlaySpec) return [];
        const series = Array.isArray(multiSeriesOverlaySpec.series)
          ? multiSeriesOverlaySpec.series.filter((item) => {
              if (!rightKeys.length) return true;
              return String(item?.yAxisId || "left") === axisId;
            })
          : [];
        return collectOverlayYValues({
          series,
          manualTrendSegment:
            axisId === "left" || !rightKeys.length ? multiSeriesOverlaySpec.manualTrendSegment : null,
        });
      };
      const leftOverlayValues = multiOverlayValuesForAxis("left");
      const rightOverlayValues = multiOverlayValuesForAxis("right");
      const leftFallback = buildSafeNumericAxis(axisInputValues(fullValuesForKeys(leftKeys.length ? leftKeys : allKeys)), tickCount);
      const rightFallback = rightKeys.length ? buildSafeNumericAxis(axisInputValues(fullValuesForKeys(rightKeys)), tickCount) : null;
      return {
        left: getSafeYDomain(chartRowsZoomed, leftKeys.length ? leftKeys : allKeys, leftFallback, tickCount, leftOverlayValues),
        right: rightKeys.length ? getSafeYDomain(chartRowsZoomed, rightKeys, rightFallback, tickCount, rightOverlayValues) : null,
      };
    }
    const overlayYValues = collectOverlayYValues(singleSeriesOverlaySpec);
    if (chartKind === "bar") {
      const spec = getBarChartValueAxisSpec(transformedChartRows, overlayYValues, tickCount);
      return { left: spec.axis, right: null, barSpec: spec };
    }
    return {
      left: getLineChartValueAxisSpec(chartRowsZoomed, overlayYValues, tickCount),
      right: null,
      barSpec: null,
    };
  }, [
    chartKind,
    horizontalBarMode,
    veryNarrowWidget,
    chartCompact,
    isMultiSeries,
    seriesList,
    transformedChartRows,
    chartRowsZoomed,
    singleSeriesOverlaySpec,
    multiSeriesOverlaySpec,
  ]);

  const sharedBarYAxisSpec = visibleAxisSpec?.barSpec || null;
  const stickyYAxisSpec = useMemo(() => {
    // Sloupcový graf: Y osu kreslí Recharts (doména + baseline 0). Sticky gutter by rozbíjel scale.
    if (!chartScrollable || chartKind === "pie" || chartKind === "bar") return null;
    const veryNarrow = veryNarrowWidget;
    const miniNarrow = miniChartMode && (veryNarrow || chartCompact);
    const n = chartRowsZoomed.length;
    const latestBarMode = chartKind === "bar" && (latestDataMode || valueCompareBarMode);
    const plotMargins = computeChartPlotMargins({
      latestBarMode,
      showBarLabels,
      miniNarrow,
      compact: chartCompact,
      veryNarrow,
      n,
    });
    const top = plotMargins.top;
    const bottom = plotMargins.bottom;
    const tickCount = veryNarrow ? 3 : chartCompact ? 4 : 5;
    const width = veryNarrow ? 30 : chartCompact ? 42 : 64;
    let safe = sharedBarYAxisSpec?.axis ?? visibleAxisSpec?.left;
    if (!safe && isMultiSeries) {
      const leftKeys = (seriesList || [])
        .filter((s) => String(s?.y_axis || "").toLowerCase() !== "right")
        .map((s) => s?.key)
        .filter(Boolean);
      const keys = leftKeys.length ? leftKeys : (seriesList || []).map((s) => s?.key).filter(Boolean);
      const values = (chartRowsZoomed || []).flatMap((row) =>
        keys.map((key) => {
          const v = row?.[key];
          return typeof v === "number" ? v : parseNumber(v);
        })
      );
      safe = buildSafeNumericAxis(chartKind === "bar" ? [0, ...values] : values, tickCount);
    } else if (!safe) {
      let values = (chartRowsZoomed || []).map((row) => row?.y);
      safe = buildSafeNumericAxis(chartKind === "bar" ? [0, ...values] : values, tickCount);
    }
    return {
      ...safe,
      unit: unit || "",
      top,
      bottom,
      width,
      fontSize: veryNarrow ? 7 : chartCompact ? 8 : 10,
    };
  }, [
    chartScrollable,
    chartKind,
    veryNarrowWidget,
    miniChartMode,
    chartRowsZoomed,
    latestDataMode,
    valueCompareBarMode,
    showBarLabels,
    chartCompact,
    isMultiSeries,
    seriesList,
    visibleAxisSpec,
    sharedBarYAxisSpec,
    unit,
  ]);
  const stickyAxisOverride = useMemo(() => {
    if (!stickyYAxisSpec) return null;
    return {
      min: stickyYAxisSpec.min,
      max: stickyYAxisSpec.max,
      ticks: stickyYAxisSpec.ticks,
    };
  }, [stickyYAxisSpec]);
  useLayoutEffect(() => {
    if (!isChartDebugEnabled() || view !== "chart" || chartKind !== "bar") return undefined;
    const germanyRow = (chartRows || []).find((r) =>
      String(r?.x || "").toLowerCase().includes("germany")
    );
    const id = window.requestAnimationFrame(() => {
      measureAndLogBarChartDebug(chartCaptureRef.current, {
        chartTitle: heading,
        component: "AradView.jsx / renderChart",
        chartType: chartKind,
        dataKeyBar: "y",
        dataKeyTooltip: "y",
        germanyLabel: "Germany",
        germanyValue: germanyRow ? coerceChartNumeric(germanyRow.y) : null,
        domain: sharedBarYAxisSpec?.domain,
        ticks: sharedBarYAxisSpec?.axis?.ticks,
        stickyDomain: stickyYAxisSpec
          ? [stickyYAxisSpec.min, stickyYAxisSpec.max]
          : sharedBarYAxisSpec?.domain,
        zeroBaselineShapeUsed: false,
        customShapeUsed: false,
        hideYAxis: Boolean(stickyYAxisSpec),
        yAxisWidth: stickyYAxisSpec ? 0 : veryNarrowWidget ? 30 : chartCompact ? 42 : 64,
      });
    });
    return () => window.cancelAnimationFrame(id);
  }, [
    view,
    chartKind,
    heading,
    chartRows,
    chartRowsZoomed.length,
    stickyYAxisSpec,
    sharedBarYAxisSpec,
  ]);

  /** Výraznější titulek (pás, tučnější a větší písmo) — volitelně v konfiguraci widgetu. */
  const chartTitleEmphasis = Boolean(widget?.config?.chart_title_emphasis);
  const catalogInlineSearchPreview =
    catalogLivePreview &&
    !catalogChartSize &&
    !fsExpand;
  const hideChartTitleChrome =
    catalogLivePreview &&
    (catalogChartSize === "fullscreen" ||
      catalogChartSize === "detail" ||
      catalogChartSize === "detail-expanded" ||
      !catalogChartSize);
  /** Ruční popisek vždy; AI komentář jako fallback. Automatický „Jednotka: …“ se NEzobrazuje —
   *  jednotka je vidět v KPI kartách a uživatele mátlo, že má popisek, který nezvolil. */
  const captionDisplayText =
    manualCaption ||
    (miniChartMode ? "" : (hasAiAnalysisDatasets ? "" : fallbackCaption));
  // Mini režim: zjednodušený titulek; automatický popisek skrytý, ruční zůstává.
  const showInteractiveControls = !hideChartControls;
  const widgetEngine = String(widget?.engine_type || widget?.type || "").toLowerCase();
  const sourceLabel = resolveSourceLabel(widget, data, widgetEngine);
  const conceptExplainMeta = useMemo(
    () =>
      buildWidgetConceptExplainMeta({
        widget,
        data: effectiveData,
        heading,
        unit,
        frequency: currentFreq,
      }),
    [widget, effectiveData, heading, unit, currentFreq],
  );
  /** KPI/varování jsou v SourcePreview liště — v náhledu katalogu nezabírat plochu grafu. */
  const catalogSuppressChartInsights =
    catalogLivePreview && !fsExpand && !miniChartMode;
  /** Katalogový detail už má tabulku v SourcePreview — neukazovat druhou uvnitř AradView. */
  const catalogSuppressInternalTable =
    catalogLivePreview &&
    (catalogChartSize === "detail" || catalogChartSize === "detail-expanded");
  const showValueCompareSummaryInChart =
    showValueCompareSummary && !catalogSuppressChartInsights;
  const showTimeSeriesSummaryInChart =
    showTimeSeriesSummary && !catalogSuppressChartInsights;
  const showStaleNoticeInChart =
    Boolean(conceptExplainMeta) && !catalogLivePreview && !miniChartMode && !catalogSuppressChartInsights;
  const handleStaleFindInCatalog = useCallback(
    (query) => {
      const q = String(query || "").trim();
      if (!q) return;
      navigate(`/search/catalog?q=${encodeURIComponent(q)}`);
    },
    [navigate],
  );

  const canEditAradCompare = useMemo(() => {
    return typeof onWidgetConfigPatch === "function" && widgetEngine === "arad_view" && Boolean(widget?.id);
  }, [onWidgetConfigPatch, widgetEngine, widget?.id]);

  const datasetCompareBaseline = useMemo(
    () => resolveDatasetChartCompareBaseline(widget, data),
    [widget?.config, widget?.id, data?.group_field, data?.selected_indicator, data?.dataset]
  );

  const externalCatalogCompareBaseline = useMemo(
    () => resolveExternalCatalogCompareBaseline(widget, data),
    [
      widget?.config,
      widget?.id,
      data?.group_field,
      data?.selected_indicator,
      data?.selected_indicator_label,
    ]
  );

  const canEditDatasetCompare = useMemo(() => {
    if (typeof onWidgetConfigPatch !== "function" || !widget?.id) return false;
    if (!DATASET_CHART_ENGINE_TYPES.has(widgetEngine)) return false;
    if (isExternalCatalogWidgetEngine(widgetEngine)) return false;
    const { sid, sf, sv } = datasetCompareBaseline;
    return Boolean(sid && sf && sv);
  }, [onWidgetConfigPatch, widget?.id, widgetEngine, datasetCompareBaseline]);

  const canEditExternalCatalogCompare = useMemo(() => {
    if (typeof onWidgetConfigPatch !== "function" || !widget?.id) return false;
    if (!isExternalCatalogWidgetEngine(widgetEngine)) return false;
    const { catalog, ready } = externalCatalogCompareBaseline;
    return ready && catalog !== "arad";
  }, [onWidgetConfigPatch, widget?.id, widgetEngine, externalCatalogCompareBaseline]);

  const canEditExternalAradCatalogCompare = useMemo(() => {
    if (typeof onWidgetConfigPatch !== "function" || !widget?.id) return false;
    if (!isExternalCatalogWidgetEngine(widgetEngine)) return false;
    const { catalog, setId } = externalCatalogCompareBaseline;
    return catalog === "arad" && Boolean(setId);
  }, [onWidgetConfigPatch, widget?.id, widgetEngine, externalCatalogCompareBaseline]);

  // Živě zjištěno: „Srovnat s řadou" na grafu z vlastních dat se dřív vždycky ukládal jen jako
  // dočasný náhled (viz `canPersistChartCompare` docstring) — tenhle boolean dá
  // `handleUnifiedCompareSave` vědět, že widget z vlastních dat je taky platný primární graf pro
  // trvalé uložení, ne jen katalogové widgety.
  const canEditUploadPrimaryCompare = useMemo(() => {
    return (
      typeof onWidgetConfigPatch === "function" && Boolean(widget?.id) && isUploadPrimaryWidgetEngine(widgetEngine)
    );
  }, [onWidgetConfigPatch, widget?.id, widgetEngine]);

  const viewerCompareEnabled = typeof onViewerComparePreview === "function";

  const canViewerAradCompare = useMemo(() => {
    return viewerCompareEnabled && widgetEngine === "arad_view" && Boolean(widget?.id);
  }, [viewerCompareEnabled, widgetEngine, widget?.id]);

  const canViewerDatasetCompare = useMemo(() => {
    if (!viewerCompareEnabled || !widget?.id) return false;
    if (!DATASET_CHART_ENGINE_TYPES.has(widgetEngine)) return false;
    if (isExternalCatalogWidgetEngine(widgetEngine)) return false;
    const { sid, sf, sv } = datasetCompareBaseline;
    return Boolean(sid && sf && sv);
  }, [viewerCompareEnabled, widget?.id, widgetEngine, datasetCompareBaseline]);

  const canViewerExternalCatalogCompare = useMemo(() => {
    if (!viewerCompareEnabled || !widget?.id) return false;
    if (!isExternalCatalogWidgetEngine(widgetEngine)) return false;
    const { catalog, ready } = externalCatalogCompareBaseline;
    return ready && catalog !== "arad";
  }, [viewerCompareEnabled, widget?.id, widgetEngine, externalCatalogCompareBaseline]);

  const canViewerExternalAradCatalogCompare = useMemo(() => {
    if (!viewerCompareEnabled || !widget?.id) return false;
    if (!isExternalCatalogWidgetEngine(widgetEngine)) return false;
    const { catalog, setId } = externalCatalogCompareBaseline;
    return catalog === "arad" && Boolean(setId);
  }, [viewerCompareEnabled, widget?.id, widgetEngine, externalCatalogCompareBaseline]);

  const viewerCanChartCompare =
    canViewerAradCompare ||
    canViewerDatasetCompare ||
    canViewerExternalCatalogCompare ||
    canViewerExternalAradCatalogCompare;
  const viewerComparePreviewActive = Boolean(viewerComparedData);

  const canEditUploadSeries = useMemo(() => {
    return (
      typeof onWidgetConfigPatch === "function" &&
      widgetEngine === "user_upload_chart" &&
      Boolean(widget?.id) &&
      Boolean(widget?.config?.user_upload_id)
    );
  }, [onWidgetConfigPatch, widgetEngine, widget?.id, widget?.config?.user_upload_id]);

  const canEditChartCompare =
    canEditAradCompare ||
    canEditDatasetCompare ||
    canEditExternalCatalogCompare ||
    canEditExternalAradCatalogCompare;
  // Univerzální compare: každý graf s rekonstruovatelným configem umí ephemerní overlay
  // (localComparePreview přes /homepage/render-widget), nezávisle na engine typu i oprávnění.
  const localCompareEnabled =
    Boolean(widgetEngine) && Boolean(widget?.config && Object.keys(widget.config).length);
  const showChartCompareToolbar = resolveChartCompareToolbarVisible({
    catalogLivePreview,
    canEditChartCompare: canEditChartCompare || viewerCanChartCompare || localCompareEnabled,
    canEditUploadSeries,
    isMultiSeries,
  });
  const showChartTransformToolbar = resolveChartTransformToolbarVisible({
    allowedTransformCount: allowedAradTransforms.length,
    hasDates,
    latestDataMode,
  });
  const canEditTitle = typeof onWidgetConfigPatch === "function" && Boolean(widget?.id);

  // Univerzální ephemerní compare: reprodukuj AKTUÁLNÍ graf přes /homepage/render-widget
  // (ad-hoc, bez uloženého widgetu a bez oprávnění) s přidaným chart_compare_with a překryj
  // jím graf. Funguje na KAŽDÉM grafu, který má config. Vrací true při úspěchu.
  const localComparePreview = useCallback(
    async (entries) => {
      const list = Array.isArray(entries) ? entries : [];
      const baseCfg = widget?.config && typeof widget.config === "object" ? widget.config : {};
      const type = widgetEngine || String(widget?.type || widget?.engine_type || "").toLowerCase();
      if (!type || !Object.keys(baseCfg).length) return false;
      try {
        const { data: resp } = await api.post("/homepage/render-widget", {
          type,
          title: widget?.title || "",
          width: widget?.width || "full",
          skip_ai: true,
          config: {
            ...baseCfg,
            chart_compare_with: list,
            chart_primary_snapshot: data,
            chart_series_mode: list.length > 0 ? "multi" : baseCfg.chart_series_mode || "single",
            primary_y_axis: baseCfg.primary_y_axis === "right" ? "right" : "left",
          },
        });
        const merged = resp?.data ?? resp;
        const confirmedAdded = Number(merged?.compare_added_count);
        const structurallyAdded = Boolean(
          merged?.multi_series && Array.isArray(merged?.series) && merged.series.length > 1
        );
        const comparisonApplied = Number.isFinite(confirmedAdded)
          ? confirmedAdded > 0
          : structurallyAdded;
        if (list.length && merged && comparisonApplied) {
          // Auto-osy: doplňkovou řadu s výrazně jiným měřítkem než primární (s0) dej na PRAVOU osu,
          // jinak je kvůli sdílené levé ose mimo viditelný rozsah (např. kurz ~20 vs poměr ~1–4).
          try {
            const mrows = Array.isArray(merged.rows) ? merged.rows : [];
            const mseries = Array.isArray(merged.series) ? merged.series : [];
            const medianMag = (key) => {
              const vals = mrows
                .map((r) => Math.abs(Number(r?.[key])))
                .filter((v) => Number.isFinite(v) && v > 0)
                .sort((a, b) => a - b);
              return vals.length ? vals[Math.floor(vals.length / 2)] : null;
            };
            const primaryMag = medianMag("s0");
            if (primaryMag) {
              for (const s of mseries) {
                if (!s || s.key === "s0") continue;
                const m = medianMag(s.key);
                if (m && (m / primaryMag > 4 || primaryMag / m > 4)) {
                  s.y_axis = "right";
                }
              }
            }
          } catch {
            /* auto-osy jsou jen kosmetika – při chybě necháme původní osy */
          }
          setViewerComparedData(merged);
          updateViewerCompareEntries(list);
          return true;
        }
        if (!list.length) {
          setViewerComparedData(null);
          updateViewerCompareEntries([]);
          return true;
        }
        return false;
      } catch {
        return false;
      }
    },
    [data, widget?.config, widget?.type, widget?.engine_type, widget?.title, widget?.width, widgetEngine, updateViewerCompareEntries],
  );

  const previousBaseDataRef = useRef(data);
  const viewerCompareRestoreKeyRef = useRef("");
  useEffect(() => {
    if (previousBaseDataRef.current === data) return;
    previousBaseDataRef.current = data;
    if (!viewerCompareEntries.length) {
      setViewerComparedData(null);
      return;
    }
    void localComparePreview(viewerCompareEntries);
  }, [data, viewerCompareEntries, localComparePreview]);

  useEffect(() => {
    if (viewerComparedData || !viewerCompareEntries.length) return;
    if (viewerCompareRestoreKeyRef.current === viewerCompareStorageKey) return;
    viewerCompareRestoreKeyRef.current = viewerCompareStorageKey;
    void localComparePreview(viewerCompareEntries);
  }, [viewerComparedData, viewerCompareEntries, viewerCompareStorageKey, localComparePreview]);

  const handleAradCompareSave = useCallback(
    async ({ chart_compare_with: cmp, primary_y_axis: pya }) => {
      if (viewerCompareEnabled && onViewerComparePreview && canViewerAradCompare) {
        try {
          const rendered = await onViewerComparePreview(cmp);
          setViewerComparedData(rendered?.data ?? null);
        } catch {
          /* preview nedostupný */
        }
        return;
      }
      if (!canEditAradCompare || !onWidgetConfigPatch || !widget?.id) return;
      const baseCfg = widget.config && typeof widget.config === "object" ? widget.config : {};
      await safeWidgetConfigPatch(onWidgetConfigPatch, widget?.id, {
        config: {
          ...baseCfg,
          chart_compare_with: cmp,
          chart_series_mode: Array.isArray(cmp) && cmp.length > 0 ? "multi" : baseCfg.chart_series_mode,
          primary_y_axis: pya === "right" ? "right" : "left",
        },
      });
    },
    [
      viewerCompareEnabled,
      onViewerComparePreview,
      canViewerAradCompare,
      canEditAradCompare,
      onWidgetConfigPatch,
      widget?.id,
      widget?.config,
    ]
  );

  // === Geo přepínač (zatím Eurostat): zapnout/vypnout země přímo v grafu → multi-series ===
  const [geoOptions, setGeoOptions] = useState([]);
  const [geoLoading, setGeoLoading] = useState(false);
  const [geoError, setGeoError] = useState("");
  // Optimistický lokální výběr — zaškrtnutí se projeví HNED, i než config patch doběhne.
  const [geoOverride, setGeoOverride] = useState(null);
  const geoBtnRef = useRef(null);
  const [geoMenuOpen, setGeoMenuOpen] = useState(false);
  const [geoMenuPos, setGeoMenuPos] = useState(null);
  const geoCatalog = String(widget?.config?.catalog || widget?.config?.source_type || "").trim().toLowerCase();
  const geoDatasetId = String(widget?.config?.set_id || widget?.config?.dataset_id || "").trim();
  const canEditGeo =
    geoCatalog === "eurostat" &&
    Boolean(geoDatasetId) &&
    typeof onWidgetConfigPatch === "function" &&
    Boolean(widget?.id);
  const currentGeoCodes = useMemo(() => {
    const cfg = widget?.config && typeof widget.config === "object" ? widget.config : {};
    const raw = cfg.selected_dimensions?.geo != null ? cfg.selected_dimensions.geo : cfg.query_params?.geo;
    if (Array.isArray(raw)) return raw.map((x) => String(x).trim()).filter(Boolean);
    if (typeof raw === "string" && raw.trim()) return raw.split(/[,+]/).map((x) => x.trim()).filter(Boolean);
    return [];
  }, [widget?.config]);
  const effectiveGeoCodes = geoOverride ?? currentGeoCodes;
  // Když se config (po patchi) aktualizuje, lokální override zahodíme = sync se serverem.
  useEffect(() => {
    setGeoOverride(null);
  }, [currentGeoCodes]);
  const loadGeoOptions = useCallback(async () => {
    if (!canEditGeo || geoOptions.length || geoLoading) return;
    setGeoLoading(true);
    setGeoError("");
    try {
      const { data: resp } = await api.post(
        `/eurostat/datasets/${encodeURIComponent(geoDatasetId)}/dimension-availability`,
        { selected_dimensions: {}, target_dimension: "geo" },
      );
      const opts = Array.isArray(resp?.options) ? resp.options : [];
      setGeoOptions(opts);
      if (!opts.length) setGeoError("Seznam zemí se nepodařilo načíst.");
    } catch (e) {
      setGeoError(String(e?.response?.data?.detail || e?.message || "Načtení zemí selhalo."));
    } finally {
      setGeoLoading(false);
    }
  }, [canEditGeo, geoDatasetId, geoOptions.length, geoLoading]);
  const toggleGeoCode = useCallback(
    async (code) => {
      const c = String(code || "").trim();
      if (!c || !canEditGeo || !widget?.id) return;
      const set = new Set(effectiveGeoCodes);
      if (set.has(c)) set.delete(c);
      else set.add(c);
      const next = Array.from(set);
      if (!next.length) return; // aspoň jedna země musí zůstat
      setGeoOverride(next); // okamžitá vizuální odezva
      const baseCfg = widget.config && typeof widget.config === "object" ? widget.config : {};
      const baseSel =
        baseCfg.selected_dimensions && typeof baseCfg.selected_dimensions === "object" ? baseCfg.selected_dimensions : {};
      await patchWidgetConfigSafe({
        config: {
          ...baseCfg,
          selected_dimensions: { ...baseSel, geo: next },
          chart_series_mode: next.length > 1 ? "multi" : baseCfg.chart_series_mode,
        },
      });
    },
    [canEditGeo, widget?.id, widget?.config, effectiveGeoCodes, patchWidgetConfigSafe],
  );
  const openGeoMenu = useCallback(() => {
    const r = geoBtnRef.current?.getBoundingClientRect();
    if (r) setGeoMenuPos({ top: Math.round(r.bottom + 4), left: Math.round(Math.max(8, r.right - 256)) });
    setGeoMenuOpen(true);
    void loadGeoOptions();
  }, [loadGeoOptions]);
  // Plain dropdown přes createPortal (NE Radix Popover) — Radix klik uvnitř panelu „požíral“.
  const geoControlNode = canEditGeo ? (
    <>
      <button
        ref={geoBtnRef}
        type="button"
        onClick={() => (geoMenuOpen ? setGeoMenuOpen(false) : openGeoMenu())}
        className="flex items-center gap-1 h-6 px-2.5 text-[9px] uppercase tracking-[0.08em] rounded-full border border-border/60 bg-white text-slate-600 hover:bg-[hsl(var(--primary-soft))]"
        title="Zapnout/vypnout země přímo v grafu"
        data-testid="arad-geo-toggle"
      >
        Země ({effectiveGeoCodes.length})
      </button>
      {geoMenuOpen && geoMenuPos && typeof document !== "undefined"
        ? createPortal(
            <>
              <div className="fixed inset-0 z-[998]" onMouseDown={() => setGeoMenuOpen(false)} />
              <div
                className="fixed z-[999] w-64 rounded-lg border border-border/70 bg-white shadow-xl flex flex-col max-h-72 overflow-hidden text-left"
                style={{ top: geoMenuPos.top, left: geoMenuPos.left }}
                onMouseDown={(e) => e.stopPropagation()}
              >
                <div className="px-3 py-2 border-b border-border/60 text-[11px] font-medium text-slate-700">
                  Země v grafu
                </div>
                {geoLoading ? (
                  <div className="px-3 py-4 text-[11px] text-slate-500">Načítám země…</div>
                ) : geoError ? (
                  <div className="px-3 py-3 text-[11px] text-rose-700">{geoError}</div>
                ) : (
                  <ul className="overflow-y-auto py-1">
                    {geoOptions.map((o) => {
                      const code = String(o?.code || "").trim();
                      if (!code) return null;
                      const checked = effectiveGeoCodes.includes(code);
                      return (
                        <li key={code}>
                          <button
                            type="button"
                            onMouseDown={(e) => {
                              e.preventDefault();
                              e.stopPropagation();
                              void toggleGeoCode(code);
                            }}
                            className={`w-full text-left px-3 py-1.5 text-[11.5px] flex items-center gap-2 hover:bg-slate-50 ${
                              checked ? "text-slate-900 font-medium" : "text-slate-600"
                            }`}
                          >
                            <span
                              className={`h-3.5 w-3.5 shrink-0 rounded border ${
                                checked ? "bg-blue-600 border-blue-600" : "border-slate-300"
                              }`}
                            />
                            <span className="truncate">{o?.label || code}</span>
                            <span className="ml-auto text-[10px] text-slate-400">{code}</span>
                          </button>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>
            </>,
            document.body,
          )
        : null}
    </>
  ) : null;

  const handleDatasetCompareSave = useCallback(
    async ({ chart_compare_with: cmp, primary_y_axis: pya }) => {
      if (viewerCompareEnabled && onViewerComparePreview && canViewerDatasetCompare) {
        try {
          const rendered = await onViewerComparePreview(cmp);
          setViewerComparedData(rendered?.data ?? null);
        } catch {
          /* preview nedostupný */
        }
        return;
      }
      if (!canEditDatasetCompare || !onWidgetConfigPatch || !widget?.id) return;
      const baseCfg = widget.config && typeof widget.config === "object" ? widget.config : {};
      await patchWidgetConfigSafe({
        config: {
          ...baseCfg,
          chart_compare_with: cmp,
          chart_series_mode: Array.isArray(cmp) && cmp.length > 0 ? "multi" : baseCfg.chart_series_mode,
          primary_y_axis: pya === "right" ? "right" : "left",
        },
      });
    },
    [
      viewerCompareEnabled,
      onViewerComparePreview,
      canViewerDatasetCompare,
      canEditDatasetCompare,
      onWidgetConfigPatch,
      widget?.id,
      widget?.config,
      patchWidgetConfigSafe,
    ]
  );

  const handleUnifiedCompareSave = useCallback(
    async ({ chart_compare_with: cmp, primary_y_axis: pya }) => {
      const entries = Array.isArray(cmp) ? cmp : [];
      if (
        viewerCompareEnabled &&
        onViewerComparePreview &&
        (canViewerExternalCatalogCompare || canViewerExternalAradCatalogCompare)
      ) {
        try {
          const rendered = await onViewerComparePreview(entries);
          setViewerComparedData(rendered?.data ?? null);
        } catch {
          /* preview nedostupný */
        }
        return;
      }
      if (
        !canPersistChartCompare({
          isExternalCatalogPrimary: canEditExternalCatalogCompare || canEditExternalAradCatalogCompare,
          isUploadPrimary: canEditUploadPrimaryCompare,
          hasWidgetConfigPatch: Boolean(onWidgetConfigPatch),
          hasWidgetId: Boolean(widget?.id),
        })
      ) {
        // Bez uloženého/editovatelného widgetu → univerzální ephemerní overlay (žádné uložení do configu).
        await localComparePreview(entries);
        return;
      }
      const baseCfg = widget.config && typeof widget.config === "object" ? widget.config : {};
      await patchWidgetConfigSafe({
        config: {
          ...baseCfg,
          chart_compare_with: entries,
          chart_series_mode: entries.length > 0 ? "multi" : baseCfg.chart_series_mode,
          primary_y_axis: pya === "right" ? "right" : "left",
        },
      });
    },
    [
      viewerCompareEnabled,
      onViewerComparePreview,
      canViewerExternalCatalogCompare,
      canViewerExternalAradCatalogCompare,
      canEditExternalCatalogCompare,
      canEditExternalAradCatalogCompare,
      canEditUploadPrimaryCompare,
      onWidgetConfigPatch,
      widget?.id,
      widget?.config,
      patchWidgetConfigSafe,
      localComparePreview,
    ]
  );

  const aradCompareInitialList = useMemo(() => {
    const raw = widget?.config?.chart_compare_with;
    if (!Array.isArray(raw)) return raw;
    if (!canEditExternalAradCatalogCompare && !canViewerExternalAradCatalogCompare) return raw;
    return raw.map((r) => ({
      source_id: String(r?.source_id || r?.set_id || widget?.config?.set_id || "").trim(),
      indicator_id: String(r?.indicator_id || r?.selected_indicator || "").trim(),
      label: String(r?.label || r?.name || "").trim(),
      chart_type: r?.chart_type,
      y_axis: r?.y_axis,
    }));
  }, [
    widget?.config?.chart_compare_with,
    widget?.config?.set_id,
    canEditExternalAradCatalogCompare,
    canViewerExternalAradCatalogCompare,
  ]);

  const openCompareModal = useCallback(() => {
    setCompareHelpOpen(false);
    setControlsOpen(false);
    setCompareModalOpen(true);
  }, []);

  const closeCompareModal = useCallback(() => {
    setCompareModalOpen(false);
  }, []);

  useEffect(() => {
    if (!compareModalOpen) return;
    setControlsOpen(false);
    setCompareHelpOpen(false);
  }, [compareModalOpen]);

  const handleUploadSeriesSave = useCallback(
    async (patchConfig) => {
      if (!canEditUploadSeries || !onWidgetConfigPatch || !widget?.id) return;
      const baseCfg = widget.config && typeof widget.config === "object" ? widget.config : {};
      await patchWidgetConfigSafe({
        config: { ...baseCfg, ...patchConfig },
      });
    },
    [canEditUploadSeries, patchWidgetConfigSafe, widget?.config]
  );

  useEffect(() => {
    if (!titleEditing) setTitleDraft(String(userTitle || ""));
  }, [titleEditing, userTitle]);

  const startTitleEdit = useCallback(() => {
    setTitleDraft(String(userTitle || heading || ""));
    setTitleEditing(true);
  }, [userTitle, heading]);

  const cancelTitleEdit = useCallback(() => {
    setTitleDraft(String(userTitle || ""));
    setTitleEditing(false);
  }, [userTitle]);

  const saveTitleEdit = useCallback(async () => {
    if (!canEditTitle || !onWidgetConfigPatch || !widget?.id || titleSaving) return;
    const nextTitle = titleDraft.trim();
    setTitleSaving(true);
    try {
      const ok = await patchWidgetConfigSafe({ title: nextTitle });
      if (ok) setTitleEditing(false);
    } finally {
      setTitleSaving(false);
    }
  }, [canEditTitle, patchWidgetConfigSafe, titleDraft, titleSaving]);

  const canPersistChartOverlay = typeof onWidgetConfigPatch === "function" && Boolean(widget?.id);
  const persistChartOverlayFlag = useCallback(
    async (key, next) => {
      if (!canPersistChartOverlay) return;
      const baseCfg = widget?.config && typeof widget.config === "object" ? widget.config : {};
      await patchWidgetConfigSafe({
        config: { ...baseCfg, [key]: next },
      });
    },
    [canPersistChartOverlay, patchWidgetConfigSafe, widget?.config]
  );
  const persistChartVisualConfig = useCallback(
    async (patch) => {
      if (!canPersistChartOverlay) return;
      const baseCfg = widget?.config && typeof widget.config === "object" ? widget.config : {};
      await patchWidgetConfigSafe({
        config: { ...baseCfg, ...patch },
      });
    },
    [canPersistChartOverlay, patchWidgetConfigSafe, widget?.config]
  );
  const setOverlayAvg = useCallback(
    (next) => {
      setAvgLineEnabled(next);
      persistChartOverlayFlag("chart_avg_line", next);
    },
    [persistChartOverlayFlag]
  );
  const setOverlayMedian = useCallback(
    (next) => {
      setMedianLineEnabled(next);
      persistChartOverlayFlag("chart_median_line", next);
    },
    [persistChartOverlayFlag]
  );
  const setOverlayTrend = useCallback(
    (next) => {
      setTrendLineEnabled(next);
      persistChartOverlayFlag("chart_trend_line", next);
    },
    [persistChartOverlayFlag]
  );
  const setRollingWindowValue = useCallback(
    (nextValue) => {
      const next = normalizeRollingWindow(nextValue, rollingWindow);
      setRollingWindow(next);
      persistChartOverlayFlag("chart_rolling_window", next);
    },
    [persistChartOverlayFlag, rollingWindow]
  );
  const setLegendHiddenValue = useCallback(
    (next) => {
      setLegendHidden(next);
      persistChartOverlayFlag("chart_legend_hidden", next);
    },
    [persistChartOverlayFlag]
  );
  const setManualTrendLineValue = useCallback(
    (nextLine) => {
      const normalizedLine = normalizeManualTrendLine(nextLine);
      setManualTrendLine(normalizedLine);
      persistChartOverlayFlag("chart_manual_trend_line", normalizedLine);
    },
    [persistChartOverlayFlag]
  );
  const clearManualTrendLine = useCallback(() => {
    setManualTrendLine(null);
    setManualTrendDraft(null);
    setManualTrendMode(false);
    persistChartOverlayFlag("chart_manual_trend_line", null);
  }, [persistChartOverlayFlag]);
  const applyChartAnalystActions = useCallback(
    (actions) => {
      const list = Array.isArray(actions) ? actions : [];
      const replacedAnnotationLayers = new Set(
        list
          .filter((action) => String(action?.type || "").trim() === "annotate_period" && action?.replace_layer === true)
          .map((action) => String(action?.layer_id || "").trim())
          .filter(Boolean),
      );
      if (replacedAnnotationLayers.size) {
        setPeriodAnnotations((current) => current.filter(
          (annotation) => !replacedAnnotationLayers.has(String(annotation?.layer_id || "").trim()),
        ));
      }
      let openedDataPanel = false;
      const catalogSeriesToAdd = [];
      let catalogSearchQuery = "";
      for (const action of list) {
        const type = String(action?.type || "").trim();
        if (type === "draw_trend_line" && action?.start && action?.end) {
          setManualTrendLineValue({ start: action.start, end: action.end });
          setManualTrendMode(false);
          setManualTrendDraft(null);
        } else if (type === "highlight_period") {
          const rows = chartRowsRef.current || [];
          const from = String(action.from || "").trim();
          const to = String(action.to || "").trim();
          const startIdx = rows.findIndex((row) => String(row?.x ?? row?.period ?? "").trim() === from);
          const endIdx = rows.findIndex((row) => String(row?.x ?? row?.period ?? "").trim() === to);
          if (startIdx >= 0 && endIdx >= 0) {
            setXZoom({
              start: Math.max(0, Math.min(startIdx, endIdx)),
              end: Math.max(startIdx, endIdx),
            });
          }
        } else if (type === "annotate_period") {
          const rows = chartRowsRef.current || [];
          const from = nearestChartPeriod(action.from, rows);
          const to = nearestChartPeriod(action.to || action.from, rows);
          if (from && to) {
            const annotation = {
              from,
              to,
              label: String(action.label || "Událost").trim(),
              display_mode: String(action.display_mode || (from === to ? "marker" : "band")).trim(),
              color: String(action.color || "amber").trim(),
              layer_id: String(action.layer_id || "").trim(),
              replace_layer: action.replace_layer === true,
              description_cz: String(action.description_cz || "").trim(),
              source_urls: Array.isArray(action.source_urls) ? action.source_urls : [],
            };
            setPeriodAnnotations((current) => {
              const key = `${annotation.from}|${annotation.to}|${annotation.label}`;
              const withoutDuplicate = current.filter(
                (item) => `${item.from}|${item.to}|${item.label}` !== key,
              );
              return [...withoutDuplicate, annotation].slice(-24);
            });
          }
        } else if (type === "clear_period_annotations") {
          const tokens = annotationRemovalTokens(action);
          setPeriodAnnotations((current) => {
            const list = Array.isArray(current) ? current : [];
            if (!list.length) return [];
            const filtered = list.filter((annotation) => !annotationMatchesRemoval(annotation, tokens));
            return filtered.length === list.length ? [] : filtered;
          });
        } else if (type === "open_cube_view" || type === "add_derived_series") {
          if (type === "open_cube_view") setChartDataPanelMode("olap");
          openedDataPanel = true;
        } else if (type === "add_catalog_series") {
          const catalog = String(action.catalog || action.source || "").trim().toLowerCase();
          const setId = String(action.set_id || "").trim();
          const indicator = String(action.selected_indicator || action.indicator_id || "").trim();
          if (catalog && (setId || indicator)) {
            catalogSeriesToAdd.push({
              catalog,
              set_id: setId,
              selected_indicator: indicator,
              name: String(action.name || action.title || setId || indicator).trim(),
            });
          }
          if (!catalogSearchQuery) {
            catalogSearchQuery = String(action.query || action.title || action.name || "").trim();
          }
        } else if (type === "open_catalog_search") {
          if (!catalogSearchQuery) catalogSearchQuery = String(action.query || "").trim();
        }
      }
      if (openedDataPanel) setChartDataPanelOpen(true);

      // Přidání navržené řady z AI chatu → UNIVERZÁLNÍ ephemerní overlay na KAŽDÉM grafu
      // (localComparePreview přes /homepage/render-widget). Když se to nepovede (graf bez
      // configu / chyba backendu), nabídni doshledání v katalogu.
      if (catalogSeriesToAdd.length) {
        const configured = Array.isArray(widget?.config?.chart_compare_with) ? widget.config.chart_compare_with : [];
        const existing = [...configured, ...viewerCompareEntries];
        const keyOf = (e) =>
          `${String(e?.catalog || "").toLowerCase()}|${String(e?.set_id || e?.source_id || "").trim()}|${String(e?.selected_indicator || e?.indicator_id || "").trim()}`;
        const seen = new Set(existing.map(keyOf));
        const merged = [...existing];
        for (const s of catalogSeriesToAdd) {
          const entry = {
            catalog: s.catalog,
            set_id: s.set_id,
            ...(s.selected_indicator ? { selected_indicator: s.selected_indicator } : {}),
            name: s.name,
            chart_type: "line",
            y_axis: "left",
          };
          if (!seen.has(keyOf(entry))) {
            seen.add(keyOf(entry));
            merged.push(entry);
          }
        }
        // Vrať výsledek přidání zpět do AI chatu (true = řada překryta, false = nešlo přidat).
        // Při neúspěchu NEbloudíme do katalogového hledání (dřív padalo na 0 výsledků) — chat
        // ukáže jasnou hlášku, že řada vyžaduje upřesnění (dimenzi/kategorii).
        return localComparePreview(merged);
      }
      if (catalogSearchQuery) {
        // AI nenašla přidatelnou řadu ("open_catalog_search") → NEbloudit do hledání;
        // vrátit neúspěch, ať chat ukáže hlášku + nabídne „Vybrat v katalogu".
        return false;
      }
    },
    [setManualTrendLineValue, widget?.config?.chart_compare_with, viewerCompareEntries, localComparePreview]
  );
  const handleManualTrendChartClick = useCallback(
    (event) => {
      if (!manualTrendMode || view !== "chart" || chartKind === "pie" || !chartRowsZoomed.length) return;
      const rect = chartWheelRef.current?.getBoundingClientRect();
      if (!rect || rect.width <= 0 || rect.height <= 0) return;
      const xRatio = clampNum((event.clientX - rect.left) / rect.width, 0, 1);
      const yRatio = clampNum((event.clientY - rect.top) / rect.height, 0, 1);
      const idx = clampNum(
        Math.round(xRatio * Math.max(0, chartRowsZoomed.length - 1)),
        0,
        Math.max(0, chartRowsZoomed.length - 1)
      );
      const row = chartRowsZoomed[idx];
      const x = String(row?.x ?? row?.period ?? "");
      if (!x) return;
      const axis = visibleAxisSpec?.left;
      let min = Number(axis?.min);
      let max = Number(axis?.max);
      if (!Number.isFinite(min) || !Number.isFinite(max) || min === max) {
        const values = isMultiSeries
          ? (chartRowsZoomed || []).flatMap((r) =>
              (seriesList || [])
                .map((s) => coerceChartNumeric(r?.[s?.key]))
                .filter((v) => v != null && Number.isFinite(v))
            )
          : (chartRowsZoomed || [])
              .map((r) => coerceChartNumeric(r?.y))
              .filter((v) => v != null && Number.isFinite(v));
        min = values.length ? Math.min(...values) : 0;
        max = values.length ? Math.max(...values) : 1;
        if (min === max) {
          min -= 1;
          max += 1;
        }
      }
      const y = max - yRatio * (max - min);
      const point = { x, y };
      if (!manualTrendDraft) {
        setManualTrendDraft(point);
      } else {
        setManualTrendLineValue({ start: manualTrendDraft, end: point });
        setManualTrendDraft(null);
        setManualTrendMode(false);
      }
      event.preventDefault();
      event.stopPropagation();
    },
    [
      manualTrendMode,
      view,
      chartKind,
      chartRowsZoomed,
      visibleAxisSpec,
      isMultiSeries,
      seriesList,
      manualTrendDraft,
      setManualTrendLineValue,
    ]
  );
  const toggleChartTableTranspose = useCallback(() => {
    const next = !chartTableTransposed;
    setChartTableTransposed(next);
    persistChartOverlayFlag("chart_table_transpose", next);
  }, [chartTableTransposed, persistChartOverlayFlag]);

  const panel = resolveWidgetPanel(widget?.config || {});
  const chartTheme = useMemo(
    () => buildChartTheme(widget?.config?.chart_color),
    [widget?.config?.chart_color]
  );
  const customChartActive = isCustomChartKind(chartKind);
  const showCustomChartView =
    customChartActive && (!isMultiSeries || chartKind === "geo_map");
  const chartKindOptions = useMemo(() => {
    if (!isMultiSeries) return CHART_KINDS;
    return CHART_KINDS.filter((k) => ["line", "bar", "area", "geo_map", "dot"].includes(k.id));
  }, [isMultiSeries]);
  const geoMapChartRows = useMemo(() => {
    if (!showCustomChartView || chartKind !== "geo_map") return [];
    return buildGeoMapRowsFromChartRows(chartRowsZoomed, isMultiSeries ? seriesList : null, {
      displayLabels: seriesTableLabels.displayLabels,
      geoHints: compareGeoHints,
    });
  }, [
    showCustomChartView,
    chartKind,
    isMultiSeries,
    chartRowsZoomed,
    seriesList,
    seriesTableLabels.displayLabels,
    compareGeoHints,
  ]);
  const geoMapKpiMode =
    chartKind === "geo_map" && geoMapChartRows.length >= 2
      ? effectiveKpiSummaryMode === "hidden"
        ? fsExpand
          ? "full"
          : "compact"
        : effectiveKpiSummaryMode
      : effectiveKpiSummaryMode;
  const specialVisualConfig = useMemo(
    () => ({
      mapRegion,
      pictogramIcon,
      pictogramUnit,
      defaultIcon: defaultChartIcon,
      iconOrientation,
      seriesIcons: widget?.config?.chart_series_icons || {},
      primaryColor: widget?.config?.chart_color || chartTheme?.accent,
    }),
    [
      mapRegion,
      pictogramIcon,
      pictogramUnit,
      defaultChartIcon,
      iconOrientation,
      widget?.config?.chart_series_icons,
      widget?.config?.chart_color,
      chartTheme?.accent,
    ]
  );
  const handleChartKindChange = useCallback(
    (nextKind) => {
      if (lockChartType) return;
      setChartKind(nextKind);
      void persistChartVisualConfig({ chart_type: nextKind });
    },
    [lockChartType, persistChartVisualConfig]
  );
  const activeControlStyle = {
    background: chartTheme.accentSoft,
    color: chartTheme.accent,
    borderColor: "transparent",
  };

  const showChartKind =
    view === "chart" &&
    rowsRaw.length > 0 &&
    filtered.length > 0;
  /** Minigraf: minHeight slotu = rezerva KPI + minimální výška samotného grafu (ne jen 72px celkem). */
  const mobileChartVerticalUnit = applyMobileLandscape ? "100dvw" : "100dvh";
  const miniChartPlotMinPx = veryNarrowWidget ? 92 : chartCompact ? 108 : 116;
  const miniKpiStripReservePx =
    effectiveKpiSummaryMode === "mini"
      ? 30
      : effectiveKpiSummaryMode === "compact"
        ? 50
        : effectiveKpiSummaryMode === "full"
          ? 60
          : 0;
  const miniChartBodyMinHeight =
    miniChartPlotMinPx +
    ((showValueCompareSummary || showTimeSeriesSummary) ? miniKpiStripReservePx : 0);
  const catalogFullscreenMobile =
    catalogLivePreview && catalogChartSize === "fullscreen" && isMobileChartUi;
  const chartBodyMinHeight = fsExpand
    ? isMobileChartUi
      ? `max(280px, calc(${mobileChartVerticalUnit} - 12rem))`
      : "min(58dvh, 720px)"
    : isMobileEmbed
      ? `max(240px, calc(${mobileChartVerticalUnit} - 5.5rem))`
    : catalogFullscreenMobile
      ? 0
    : catalogLivePreview &&
        (catalogChartSize === "detail-expanded" || catalogChartSize === "detail")
      ? catalogChartSize === "detail-expanded"
        ? (isMobileChartUi ? "max(340px, min(60svh, 560px))" : catalogSuppressChartInsights ? "min(56vh, 460px)" : "min(52vh, 420px)")
        : (isMobileChartUi ? "max(320px, min(56svh, 520px))" : catalogSuppressChartInsights ? "min(52vh, 420px)" : "min(48vh, 380px)")
    : catalogLivePreview && catalogChartSize === "fullscreen"
      ? "min(52vh, 640px)"
    : valueCompareBarSummaryMode
      ? 230
    : miniChartMode
      ? miniChartBodyMinHeight
      : veryNarrowWidget
        ? 120
        : chartCompact
          ? 156
          : 250;
  const isGeoMapChart = view === "chart" && chartKind === "geo_map";
  const geoMapBodyMinHeight = fsExpand
    ? chartBodyMinHeight
    : miniChartMode
      ? 180
      : chartCompact
        ? 200
        : 240;
  const resolvedChartBodyMinHeight = isGeoMapChart
    ? geoMapBodyMinHeight
    : chartBodyMinHeight;
  const tableBodyHeight = miniChartMode ? 150 : chartCompact ? 210 : 280;

  const showXZoomWheelHint =
    !isMobileChartUi && !miniChartMode && chartKind !== "pie" && chartKind !== "geo_map" && chartRows.length >= 10;
  const showChartOverlayControls =
    view === "chart" && chartKind !== "pie" && !showCustomChartView && chartRowsZoomed.length > 0;
  const xZoomSpan = useMemo(() => {
    if (!visibleXDomain || !Array.isArray(visibleXDomain)) return chartRows.length;
    const start = Math.floor(Number(visibleXDomain[0]));
    const end = Math.ceil(Number(visibleXDomain[1]));
    if (!Number.isFinite(start) || !Number.isFinite(end)) return chartRows.length;
    return Math.max(1, end - start + 1);
  }, [visibleXDomain, chartRows.length]);
  const xZoomPanMax = Math.max(0, chartRows.length - xZoomSpan);
  const xZoomPanValue = visibleXDomain
    ? clampNum(Math.floor(Number(visibleXDomain[0])), 0, xZoomPanMax)
    : 0;
  // Posuvník se musí ukázat VŽDY, když je graf zoomnutý a jde posouvat — nezávisle na tom,
  // jestli běží wheel-hint (ten chce ≥10 bodů a ne-mobil; pan slider chceme i u menších grafů a na mobilu).
  const showXZoomSlider =
    Boolean(visibleXDomain) && xZoomPanMax > 0 && chartKind !== "pie" && chartKind !== "geo_map" && !miniChartMode;
  const xZoomRangeLabel = showXZoomSlider
    ? `${String(chartRows[xZoomPanValue]?.x ?? "")} → ${String(
        chartRows[Math.min(chartRows.length - 1, xZoomPanValue + xZoomSpan - 1)]?.x ?? ""
      )}`
    : "";
  const handleXZoomPan = useCallback(
    (nextStartRaw) => {
      const nextStart = clampNum(Math.round(Number(nextStartRaw)), 0, xZoomPanMax);
      setXZoom((prev) => {
        const n = chartRowsRef.current.length;
        if (n < 1) return null;
        const baseStart = prev?.start ?? xZoomPanValue;
        const baseEnd = prev?.end ?? Math.min(n - 1, baseStart + xZoomSpan - 1);
        const span = clampNum(baseEnd - baseStart + 1, 1, n);
        const maxStart = Math.max(0, n - span);
        const safeStart = clampNum(nextStart, 0, maxStart);
        return { start: safeStart, end: safeStart + span - 1 };
      });
    },
    [xZoomPanMax, xZoomPanValue, xZoomSpan]
  );
  const chartHeaderRows =
    (showValueCompareSummaryInChart || showTimeSeriesSummaryInChart ? 1 : 0) +
    (showSeriesStatisticsPanel ? 1 : 0);
  const rechartsMinHeight = miniChartMode ? Math.max(72, miniChartPlotMinPx - 16) : 72;
  const mobileDenseChart = isMobileChartUi && !fsExpand;

  useEffect(() => {
    if (view !== "chart" || !showXZoomWheelHint) return undefined;
    const el = chartFrameRef.current;
    if (!el) return undefined;
    const onWheel = (e) => {
      try {
        if (viewRef.current !== "chart") return;
        if (chartKindRef.current === "pie") return;
        const rows = chartRowsRef.current;
        const n = rows.length;
        if (n < 10) return;
        const plotEl = chartWheelRef.current;
        if (!plotEl) return;
        const r = plotEl.getBoundingClientRect();
        if (r.width < 2 || r.height < 2) return;
        e.preventDefault();
        e.stopPropagation();
        // Zoom mění pouze viditelný rozsah osy X; data, frekvence ani jednotky se neupravují.
        setXZoom((prev) => computeNextXZoom(prev, n, e.deltaY, e.clientX, r.left, r.width));
      } catch (err) {
        // eslint-disable-next-line no-console
        console.error("[AradView] wheel zoom", err);
      }
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, [view, chartDataSig, showXZoomWheelHint]);

  const canExpandCaption = captionDisplayText.length > 80;
  const canExpandTitle = !miniChartMode && titleOverflows;
  const compactHeaderMode = chartCompact && !fsExpand;
  /** V pracovní ploše na celou obrazovku zobrazíme stejné ovládací prvky jako na desktopu. */
  // On mobile keep the compact "mobile chrome" header even in fullscreen.
  // Desktop toolbar rows wrap badly on narrow screens and can consume most of
  // the viewport height when expanded.
  const showMobileChrome = isMobileChartUi && showInteractiveControls && !miniChartMode;
  const compactCatalogMobilePreview = catalogLivePreview && isMobileChartUi && !fsExpand;
  /** Kompaktní i široké dlaždice na desktopu — ovládání pod ikonou Možnosti grafu. */
  const controlsInOptionsPanel =
    controlsInOptionsPanelProp ??
    (!fsExpand && !isMobileChartUi && showInteractiveControls);

  const chartFooterRows =
    (showSingleSeriesLegend ? 1 : 0) +
    (showLatestBarLegend ? 1 : 0) +
    (showMultiSeriesLegend ? 1 : 0) +
    (showXZoomWheelHint ? 1 : 0) +
    (showXZoomSlider ? 1 : 0) +
    (sourceLabel && (!chartCompact || fsExpand) ? 1 : 0);

  /** Data / Copy / Export jen v bočním panelu — ne duplicitně v horní liště. */
  const chartActionsInSidePanel = resolveChartActionsInSidePanel({
    showMobileChrome,
    controlsInOptionsPanel,
    showInteractiveControls,
    fsExpand,
  });

  const canSplitChartTable =
    !catalogSuppressInternalTable &&
    !miniChartMode &&
    !isMobileChartUi &&
    showInteractiveControls &&
    filtered.length > 0 &&
    (fsExpand || wideChartTile || chartWidgetWidth === "half");

  const showSplitLayout = canSplitChartTable && layoutPreference === "split";
  const showChartPanel =
    showSplitLayout ||
    layoutPreference === "chart" ||
    (!canSplitChartTable && view === "chart");
  const showTablePanel =
    showSplitLayout ||
    layoutPreference === "table" ||
    (!canSplitChartTable && view === "table");
  const hideCatalogTableOnMobile =
    catalogLivePreview && isMobileChartUi && !fsExpand;
  const effectiveShowTablePanel =
    showTablePanel && !hideCatalogTableOnMobile && !catalogSuppressInternalTable;
  const effectiveShowChartPanel =
    showChartPanel || (hideCatalogTableOnMobile && showTablePanel);
  const chartBodyFixedHeight =
    !showSplitLayout &&
    !fsExpand &&
    !isMobileChartUi &&
    !miniChartMode &&
    !isGeoMapChart &&
    typeof resolvedChartBodyMinHeight === "number"
      ? resolvedChartBodyMinHeight
      : undefined;

  useEffect(() => {
    if (!canSplitChartTable) return;
    setView(layoutPreference === "table" ? "table" : "chart");
  }, [canSplitChartTable, layoutPreference]);

  useEffect(() => {
    if (showSplitLayout && chartDataPanelOpen && chartDataPanelMode !== "olap") setChartDataPanelOpen(false);
  }, [showSplitLayout, chartDataPanelOpen, chartDataPanelMode]);

  useEffect(() => {
    if (!catalogSuppressInternalTable) return;
    if (layoutPreference !== "chart") setLayoutPreference("chart");
    if (view !== "chart") setView("chart");
    if (chartDataPanelOpen) setChartDataPanelOpen(false);
  }, [catalogSuppressInternalTable, layoutPreference, view, chartDataPanelOpen]);

  useLayoutEffect(() => {
    if (miniChartMode || titleEditing) {
      setTitleOverflows(false);
      return undefined;
    }
    const el = titleAnchorRef.current;
    if (!el) {
      setTitleOverflows(false);
      return undefined;
    }
    let raf = 0;
    const measure = () => {
      const next =
        el.scrollWidth > el.clientWidth + 1 ||
        el.scrollHeight > el.clientHeight + 1;
      setTitleOverflows((prev) => (prev === next ? prev : next));
    };
    const schedule = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(measure);
    };
    schedule();
    if (typeof ResizeObserver === "undefined") {
      window.addEventListener("resize", schedule);
      return () => {
        cancelAnimationFrame(raf);
        window.removeEventListener("resize", schedule);
      };
    }
    const ro = new ResizeObserver(schedule);
    ro.observe(el);
    if (el.parentElement) ro.observe(el.parentElement);
    window.addEventListener("resize", schedule);
    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
      window.removeEventListener("resize", schedule);
    };
  }, [
    heading,
    miniChartMode,
    titleEditing,
    chartCompact,
    chartTitleEmphasis,
    showMobileChrome,
    isMobileDashboard,
    widget?.width,
  ]);

  useEffect(() => {
    if (!canExpandTitle) setTitleExpanded(false);
  }, [canExpandTitle]);

  useEffect(() => {
    if (miniChartMode && !showInteractiveControls && view !== "chart") setView("chart");
    if (!showInteractiveControls) setControlsOpen(false);
  }, [miniChartMode, view, showInteractiveControls]);

  useLayoutEffect(() => {
    if (showMobileChrome || !controlsInOptionsPanel || !controlsOpen || !showInteractiveControls) {
      setCompactControlsPanelPos(null);
      return undefined;
    }
    const update = () => {
      const el = compactChartOptsBtnRef.current;
      if (!el) return;
      const r = el.getBoundingClientRect();
      const panelW = Math.min(320, Math.max(280, window.innerWidth - 16));
      let left = r.right - panelW;
      if (left < 8) left = 8;
      if (left + panelW > window.innerWidth - 8) left = Math.max(8, window.innerWidth - 8 - panelW);
      const maxPanelH = Math.min(Math.floor(window.innerHeight * 0.88), 560);
      let top = r.bottom + 6;
      if (top + maxPanelH > window.innerHeight - 8) {
        const aboveTop = r.top - 6 - maxPanelH;
        if (aboveTop >= 8) top = aboveTop;
        else top = Math.max(8, window.innerHeight - 8 - maxPanelH);
      }
      setCompactControlsPanelPos({ top, left, width: panelW, maxHeight: maxPanelH });
    };
    update();
    window.addEventListener("resize", update);
    window.addEventListener("scroll", update, true);
    return () => {
      window.removeEventListener("resize", update);
      window.removeEventListener("scroll", update, true);
    };
  }, [showMobileChrome, controlsInOptionsPanel, controlsOpen, showInteractiveControls]);

  useEffect(() => {
    if (!controlsOpen || showMobileChrome || !controlsInOptionsPanel || !showInteractiveControls) return undefined;
    const onKey = (e) => {
      if (e.key === "Escape") setControlsOpen(false);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [controlsOpen, showMobileChrome, controlsInOptionsPanel, showInteractiveControls]);

  useEffect(() => {
    if (!showMobileChrome || !controlsOpen) return undefined;
    // Na mobilu neschovávej scroll stránky přes `body overflow: hidden`:
    // iOS tím umí "poskočit" viewport a vracet uživatele zpět nahoru.
    const onKey = (e) => {
      if (e.key === "Escape") setControlsOpen(false);
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
    };
  }, [showMobileChrome, controlsOpen]);

  useEffect(() => {
    if (!compareHelpOpen) return undefined;
    const onDoc = (e) => {
      if (compareHelpWrapRef.current && !compareHelpWrapRef.current.contains(e.target)) {
        setCompareHelpOpen(false);
      }
    };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, [compareHelpOpen]);

  const mobileFreqLabel = ALL_FREQS.find((f) => f.code === targetFreq)?.label || targetFreq || "";
  const mobileTfLabel = useMemo(() => {
    if (timeframe !== "CUSTOM") return TIMEFRAMES.find((t) => t.id === timeframe)?.label || timeframe;
    const from = customFromPeriod || "…";
    const to = customToPeriod || "…";
    return `OD-DO ${from}→${to}`;
  }, [timeframe, customFromPeriod, customToPeriod]);
  const mobileKindLabel = CHART_KINDS.find((k) => k.id === chartKind)?.label || chartKind;
  const customRangeLabels = customRangeFieldLabels(currentFreq);

  const customRangeControls = (compactMode = false) => {
    if (!hasDates || rowsRaw.length === 0 || timeframe !== "CUSTOM") return null;
    const baseSelectCls = compactMode
      ? "h-6 px-2 text-[10px] rounded border border-border/70 bg-white text-slate-700"
      : "h-5 px-1.5 text-[10px] rounded border border-border/60 bg-white text-slate-700";
    return (
      <div className={`${compactMode ? "mt-1.5" : "w-full mt-1"} flex flex-wrap items-center gap-1.5`}>
        <span className="text-[9px] uppercase tracking-wider text-slate-400 shrink-0">{customRangeLabels.from}</span>
        <select
          value={customFromPeriod}
          onChange={(e) => setCustomFromPeriod(e.target.value)}
          disabled={lockTimeRange}
          className={baseSelectCls}
        >
          {periodOptions.map((p) => (
            <option key={`from-${p}`} value={p}>
              {fmtPeriodLabel(p)}
            </option>
          ))}
        </select>
        <span className="text-[9px] uppercase tracking-wider text-slate-400 shrink-0">{customRangeLabels.to}</span>
        <select
          value={customToPeriod}
          onChange={(e) => setCustomToPeriod(e.target.value)}
          disabled={lockTimeRange}
          className={baseSelectCls}
        >
          {periodOptions.map((p) => (
            <option key={`to-${p}`} value={p}>
              {fmtPeriodLabel(p)}
            </option>
          ))}
        </select>
      </div>
    );
  };

  function renderViewLayoutControls({ sheet = false, testIdSuffix = "" } = {}) {
    const chipClass = sheet
      ? "h-6 px-2 text-[10px] rounded border font-mono"
      : "flex items-center gap-1 h-6 px-2.5 text-[9px] uppercase tracking-[0.1em] rounded-full border transition-colors";

    const applyLayout = (next) => {
      if (lockViewToggle) return;
      if (canSplitChartTable) {
        setLayoutPreference(next);
      } else {
        setView(next === "table" ? "table" : "chart");
      }
    };

    const activeLayout = canSplitChartTable
      ? layoutPreference
      : view === "table"
        ? "table"
        : "chart";

    const activeChip = (mode) =>
      `${chipClass} ${
        activeLayout === mode
          ? sheet
            ? "chip-mint border-transparent font-semibold"
            : "chip-mint border-transparent font-medium"
          : sheet
            ? "border-border/60 text-slate-600"
            : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
      } ${lockViewToggle ? "opacity-60 cursor-not-allowed" : ""}`;

    const activeStyle = (mode) => (activeLayout === mode ? activeControlStyle : undefined);

    if (canSplitChartTable) {
      return (
        <>
          <button
            type="button"
            onClick={() => applyLayout("split")}
            disabled={lockViewToggle}
            title="Graf a tabulka vedle sebe"
            className={activeChip("split")}
            style={activeStyle("split")}
            data-testid={`arad-view-split${testIdSuffix}`}
          >
            {sheet ? "Graf + tabulka" : (
              <>
                <LineIcon className="h-3 w-3" aria-hidden />
                Graf + tabulka
              </>
            )}
          </button>
          <button
            type="button"
            onClick={() => applyLayout("chart")}
            disabled={lockViewToggle}
            title="Jen graf"
            className={activeChip("chart")}
            style={activeStyle("chart")}
            data-testid={`arad-view-chart${testIdSuffix}`}
          >
            {sheet ? "Jen graf" : (
              <>
                <LineIcon className="h-3 w-3" aria-hidden />
                Graf
              </>
            )}
          </button>
          <button
            type="button"
            onClick={() => applyLayout("table")}
            disabled={lockViewToggle}
            title="Jen tabulka"
            className={activeChip("table")}
            style={activeStyle("table")}
            data-testid={`arad-view-table${testIdSuffix}`}
          >
            {sheet ? "Jen tabulka" : (
              <>
                <TableIcon className="h-3 w-3" aria-hidden />
                Tabulka
              </>
            )}
          </button>
          {(activeLayout === "table" || activeLayout === "split") && isMultiSeries && !lockViewToggle ? (
            <button
              type="button"
              onClick={toggleChartTableTranspose}
              aria-pressed={chartTableTransposed}
              title={
                chartTableTransposed
                  ? "Vrátit tabulku: období v řádcích, ukazatele ve sloupcích"
                  : "Transponovat: ukazatele v řádcích, období ve sloupcích"
              }
              className={`${chipClass} ${
                chartTableTransposed
                  ? sheet
                    ? "chip-mint border-transparent font-semibold"
                    : "chip-mint border-transparent font-medium"
                  : sheet
                    ? "border-border/60 text-slate-600"
                    : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
              }`}
              style={chartTableTransposed ? activeControlStyle : undefined}
              data-testid={`arad-view-table-transpose${testIdSuffix}`}
            >
              {sheet ? "Řádky ↔ sloupce" : (
                <>
                  <ArrowLeftRight className="h-3 w-3" aria-hidden />
                  Řádky ↔ sloupce
                </>
              )}
            </button>
          ) : null}
        </>
      );
    }

    return (
      <>
        <button
          type="button"
          onClick={() => applyLayout("chart")}
          disabled={lockViewToggle}
          title={lockViewToggle ? feViewToggle.message || "Graf" : "Graf"}
          className={activeChip("chart")}
          style={activeStyle("chart")}
          data-testid={`arad-view-chart${testIdSuffix}`}
        >
          {sheet ? "Graf" : (
            <>
              <LineIcon className="h-3 w-3" aria-hidden />
              Graf
            </>
          )}
        </button>
        <button
          type="button"
          onClick={() => applyLayout("table")}
          disabled={lockViewToggle}
          title={lockViewToggle ? feViewToggle.message || "Tabulka" : "Tabulka"}
          className={activeChip("table")}
          style={activeStyle("table")}
          data-testid={`arad-view-table${testIdSuffix}`}
        >
          {sheet ? "Tabulka" : (
            <>
              <TableIcon className="h-3 w-3" aria-hidden />
              Tabulka
            </>
          )}
        </button>
        {activeLayout === "table" && isMultiSeries && !lockViewToggle ? (
          <button
            type="button"
            onClick={toggleChartTableTranspose}
            aria-pressed={chartTableTransposed}
            title={
              chartTableTransposed
                ? "Vrátit tabulku: období v řádcích, ukazatele ve sloupcích"
                : "Transponovat: ukazatele v řádcích, období ve sloupcích"
            }
            className={`${chipClass} ${
              chartTableTransposed
                ? sheet
                  ? "chip-mint border-transparent font-semibold"
                  : "chip-mint border-transparent font-medium"
                : sheet
                  ? "border-border/60 text-slate-600"
                  : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
            }`}
            style={chartTableTransposed ? activeControlStyle : undefined}
            data-testid={`arad-view-table-transpose${testIdSuffix}`}
          >
            {sheet ? "Řádky ↔ sloupce" : (
              <>
                <ArrowLeftRight className="h-3 w-3" aria-hidden />
                Řádky ↔ sloupce
              </>
            )}
          </button>
        ) : null}
      </>
    );
  }

  function renderCompareToolbarControl({ sheet = false, testIdSuffix = "" } = {}) {
    if (!showChartCompareToolbar) return null;

    const chipClass = sheet
      ? "h-6 px-2 text-[10px] rounded border font-mono inline-flex items-center gap-1"
      : "flex items-center gap-1 h-6 px-2.5 text-[9px] uppercase tracking-[0.08em] rounded-full border border-border/60 bg-white text-slate-600 hover:bg-[hsl(var(--primary-soft))]";

    if (canEditChartCompare || viewerCanChartCompare || localCompareEnabled) {
      return (
        <div className={sheet ? "mb-2" : "flex items-center gap-1 shrink-0 flex-wrap"}>
          {sheet ? (
            <div className="text-[9px] uppercase tracking-wider text-slate-400 mb-1">Srovnání</div>
          ) : (
            <span className="text-[9px] uppercase tracking-wider text-slate-400 shrink-0">Srovnání</span>
          )}
          {viewerComparePreviewActive ? (
            <>
              <span
                className="inline-flex items-center rounded-full border border-amber-200 bg-amber-50 px-2 py-0.5 text-[9px] font-medium text-amber-800"
                title="Porovnání vidíte jen vy a zůstane zachováno v této kartě prohlížeče"
              >
                Náhled jen pro vás — neuloženo
              </span>
              <button
                type="button"
                onClick={() => {
                  setViewerComparedData(null);
                  updateViewerCompareEntries([]);
                }}
                className={`${chipClass} border-amber-200 text-amber-800 hover:bg-amber-50`}
                title="Vrátit graf do stavu nastaveného vlastníkem"
                data-testid={`arad-compare-clear-preview${testIdSuffix}`}
              >
                Zrušit překryv
              </button>
            </>
          ) : null}
          <div className={sheet ? "flex flex-wrap gap-1" : undefined}>
            <button
              type="button"
              onClick={openCompareModal}
              className={chipClass}
              title={
                isMultiSeries
                  ? "Upravit doplnkové řady, typ čáry a osu Y"
                  : canEditDatasetCompare || canViewerDatasetCompare
                    ? "Přidat další řady stejného zdroje (jiná hodnota dimenze)"
                    : canEditExternalCatalogCompare || canViewerExternalCatalogCompare
                      ? "Přidat další řadu do grafu z libovolného zdroje"
                      : "Přidat další datovou řadu pro srovnání v tomto grafu"
              }
              data-testid={`arad-compare-edit${testIdSuffix}`}
            >
              <GitCompare className="h-3 w-3 shrink-0" aria-hidden />
              {isMultiSeries ? "Upravit řady" : "+ Srovnat s řadou"}
            </button>
          </div>
        </div>
      );
    }

    if (canEditUploadSeries) {
      return (
        <div className={sheet ? "mb-2" : "flex items-center gap-1 shrink-0"}>
          {sheet ? (
            <div className="text-[9px] uppercase tracking-wider text-slate-400 mb-1">Srovnání</div>
          ) : (
            <span className="text-[9px] uppercase tracking-wider text-slate-400 shrink-0">Srovnání</span>
          )}
          <div className={sheet ? "flex flex-wrap gap-1" : undefined}>
            <button
              type="button"
              onClick={() => setUploadSeriesOpen((o) => !o)}
              className={chipClass}
              title="Přidat / odebrat sloupce grafu (složený graf)"
              data-testid={`upload-series-edit${testIdSuffix}`}
            >
              <GitCompare className="h-3 w-3 shrink-0" aria-hidden />
              {isMultiSeries ? "Upravit řady" : "+ Srovnat s řadou"}
            </button>
          </div>
        </div>
      );
    }

    if (!isMultiSeries) {
      return (
        <div className={`relative shrink-0 ${sheet ? "mb-2" : ""}`} ref={sheet ? undefined : compareHelpWrapRef}>
          {sheet ? (
            <div className="text-[9px] uppercase tracking-wider text-slate-400 mb-1">Srovnání</div>
          ) : (
            <span className="text-[9px] uppercase tracking-wider text-slate-400 shrink-0 mr-1">Srovnání</span>
          )}
          <button
            type="button"
            onClick={() => setCompareHelpOpen((o) => !o)}
            className={chipClass}
            title="Jak zobrazit více indikátorů v jednom grafu"
            data-testid={`arad-compare-help${testIdSuffix}`}
          >
            <GitCompare className="h-3 w-3 shrink-0" aria-hidden />
            Více řad
          </button>
          {compareHelpOpen ? (
            <div
              className={
                sheet
                  ? "mt-2 w-full rounded-lg border border-border/70 bg-slate-50 p-3 text-left text-[11px] leading-snug text-slate-700"
                  : "absolute right-0 top-7 z-[45] w-[min(18rem,calc(100vw-2rem))] rounded-lg border border-border/70 bg-white p-3 text-left text-[11px] leading-snug text-slate-700 shadow-lg"
              }
              role="dialog"
              aria-label="Nápověda: více řad v grafu"
            >
              <p className="font-semibold text-slate-800 mb-1.5">Více čar v jednom grafu</p>
              {aradMultiSeriesHelpContext === "personal_dashboard" ? (
                <p className="mb-2">
                  Na <strong>Můj dashboard</strong> můžete v jednom grafu kombinovat více ARAD indikátorů nebo více řad
                  ze stejného zdroje: klikněte na <strong>Srovnat s řadou</strong> u widgetu s oprávněním úprav, nebo
                  zvolte porovnání v editoru widgetu.
                </p>
              ) : (
                <p className="mb-2">
                  Jako správce můžete přidat další řady přímo z grafu tlačítkem <strong>Srovnat s řadou</strong> (u
                  ARAD, Eurostat, ČSÚ, ECB, …). U ARAD lze řady doplnit i v editoru widgetů v bloku{" "}
                  <strong>Porovnání v grafu</strong>.
                </p>
              )}
              {feCompositeCharts.ready && !feCompositeCharts.allowed ? (
                <p className="text-amber-800 bg-amber-50/90 border border-amber-200/70 rounded px-2 py-1.5">
                  {feCompositeCharts.message ||
                    "Kombinace více řad je u nás vedená jako funkce složených grafů (předplatné)."}
                </p>
              ) : null}
            </div>
          ) : null}
        </div>
      );
    }

    return null;
  }

  function resolveStatsActions() {
    if (!showSeriesStatisticsButton) return [];
    if (isMultiSeries) {
      return [
        ["correlation", "Korelace", "Korelace, rolling korelace a lead-lag korelace"],
        ["regression", "Regrese", "Lineární regrese, beta a R²"],
        ["collinearity", "Kolinearita", "Nejsilnější absolutní korelace mezi řadami"],
        ["spread", "Spread/ratio", "Rozdíl a poměr dvojice řad"],
        ["zscore", "Z-score", "Z-score a detekce anomálií"],
        ["ranking", "Pořadí", "Regionální pořadí a odchylka od průměru"],
      ];
    }
    return [
      ["summary", "Souhrn", "Poslední hodnota, změna a meziroční změna"],
      ["minmax", "Min/max", "Minimum, maximum, průměr a medián"],
      ["volatility", "Volatilita", "Směrodatná odchylka a volatilita změn"],
      ["moving", "Ø klouzavý", "Klouzavý průměr aktuální řady"],
      ["zscore", "Z-score", "Z-score a detekce anomálií"],
    ];
  }

  function renderStatsActionButtons({ inline = false } = {}) {
    const actions = resolveStatsActions();
    if (!actions.length) return null;
    return actions.map(([id, label, title]) => (
      <button
        key={id}
        type="button"
        onClick={() => {
          setChartStatsFocus(id);
          setChartStatsPanelOpen(true);
          setControlsOpen(false);
          setCompactControlsPanelPos(null);
        }}
        aria-pressed={chartStatsPanelOpen && chartStatsFocus === id}
        className={`${inline ? "h-5 px-2 text-[9px] rounded border font-mono" : "h-6 px-2 text-[10px] rounded border font-mono"} ${
          chartStatsPanelOpen && chartStatsFocus === id
            ? "chip-mint border-transparent font-semibold"
            : "border-border/60 text-slate-600"
        }`}
        style={chartStatsPanelOpen && chartStatsFocus === id ? activeControlStyle : undefined}
        title={title}
        data-testid={`arad-statistics-${id}${inline ? "" : "-sheet"}`}
      >
        {label}
      </button>
    ));
  }

  function renderRollingWindowControl({ inline = false } = {}) {
    if (displayTransform !== "rolling_average") return null;
    return (
      <label
        className={`inline-flex items-center gap-1 rounded border border-border/60 bg-white/70 font-mono text-slate-600 ${
          inline ? "h-5 px-1.5 text-[9px]" : "h-6 px-2 text-[10px]"
        }`}
        title="Počet období pro klouzavý průměr"
      >
        <span>n</span>
        <input
          type="number"
          min={1}
          max={120}
          step={1}
          value={rollingWindow}
          onChange={(e) => setRollingWindowValue(e.target.value)}
          onClick={(e) => e.stopPropagation()}
          className="h-4 w-10 border-0 bg-transparent p-0 text-right font-mono outline-none"
          aria-label="Počet období klouzavého průměru"
          data-testid={`arad-rolling-window${inline ? "" : "-sheet"}`}
        />
      </label>
    );
  }

  function renderManualTrendControls({ inline = false } = {}) {
    const active = manualTrendMode || Boolean(manualTrendLine);
    const baseClass = inline
      ? "h-5 px-1.5 text-[9px] uppercase tracking-[0.08em] rounded-full border transition-colors inline-flex items-center justify-center leading-tight"
      : "h-5 px-2 text-[9px] rounded border font-mono inline-flex items-center justify-center leading-tight";
    const inactiveClass = inline
      ? "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
      : "border-border/60 text-slate-600";
    return (
      <span className="inline-flex items-center gap-1">
        <button
          type="button"
          onClick={() => {
            setManualTrendMode((v) => !v);
            setManualTrendDraft(null);
          }}
          className={`${baseClass} ${active ? "chip-mint border-transparent font-semibold" : inactiveClass}`}
          style={active ? activeControlStyle : undefined}
          title={
            manualTrendMode
              ? "Režim kreslení je zapnutý: klikněte dva body v grafu"
              : "Nakreslit vlastní trendovou čáru: klikněte první a druhý bod přímo v grafu"
          }
          data-testid="arad-manual-trend-toggle"
        >
          vlastní
        </button>
        {manualTrendLine ? (
          <button
            type="button"
            onClick={clearManualTrendLine}
            className={`${inline ? "h-5 w-5 rounded-full" : "h-5 w-5 rounded"} border border-border/60 bg-white/70 text-slate-500 hover:text-slate-800 inline-flex items-center justify-center`}
            title="Smazat vlastní trendovou čáru"
            aria-label="Smazat vlastní trendovou čáru"
            data-testid="arad-manual-trend-clear"
          >
            <XIcon className="h-3 w-3" aria-hidden />
          </button>
        ) : null}
        {manualTrendMode ? (
          <span className="font-mono text-[9px] text-slate-500">
            {manualTrendDraft ? "klik 2" : "klik 1"}
          </span>
        ) : null}
      </span>
    );
  }

  function renderLegendToggle({ inline = false } = {}) {
    const active = !legendHidden;
    return (
      <span className="inline-flex items-stretch gap-1 shrink-0">
        <button
          type="button"
          onClick={() => setLegendHiddenValue(!legendHidden)}
          className={`${inline ? "h-5 px-1.5 text-[9px] uppercase tracking-[0.08em] rounded-full" : "h-5 px-2 text-[9px] rounded"} border transition-colors inline-flex items-center justify-center leading-tight ${
            active
              ? "chip-mint border-transparent font-medium"
              : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
          }`}
          style={active ? activeControlStyle : undefined}
          title={legendHidden ? "Zobrazit legendu grafu" : "Skrýt legendu grafu"}
          data-testid="arad-legend-toggle"
        >
          legenda
        </button>
      </span>
    );
  }

  function renderTransformToolbarControls({ inline = false } = {}) {
    if (view !== "chart") return null;
    if (!showChartTransformToolbar && !showSeriesStatisticsButton) return null;

    const buttons = showChartTransformToolbar
      ? allowedAradTransforms.map((t) => (
          <button
            key={t.id}
            type="button"
            onClick={() => setDisplayTransform(t.id)}
            className={`${inline ? "h-5 px-2 text-[9px] rounded border font-mono" : "h-6 px-2 text-[10px] rounded border font-mono"} ${
              displayTransform === t.id ? "chip-mint border-transparent font-semibold" : "border-border/60 text-slate-600"
            }`}
            style={displayTransform === t.id ? activeControlStyle : undefined}
            title={t.title || t.label}
            data-testid={`arad-transform-${t.id}${inline ? "" : "-sheet"}`}
          >
            {t.label}
          </button>
        ))
      : null;
    const statsButtons = renderStatsActionButtons({ inline });

    if (inline) {
      return (
        <>
          <span className="hidden sm:inline w-px h-3.5 bg-border/50 shrink-0 mx-0.5" aria-hidden />
          <span className="text-[9px] uppercase tracking-wider text-slate-400 shrink-0">Analýzy</span>
          {buttons}
          {renderRollingWindowControl({ inline })}
          {statsButtons}
        </>
      );
    }

    return (
      <div className="mb-2">
        <div className="text-[9px] uppercase tracking-wider text-slate-400 mb-1">Analýzy</div>
        <div className="flex flex-wrap gap-1">
          {buttons}
          {renderRollingWindowControl({ inline })}
          {statsButtons}
        </div>
      </div>
    );
  }

  function renderAdvancedControlsPanels(includeExport = false) {
    return (
      <AradViewAdvancedControlsPanel
        includeExport={includeExport}
        mobile={{ mobileLargeChartView, isMobileChartUi, mobileFsLayout, setMobileFsLayout }}
        layout={{ activeControlStyle, renderViewLayoutControls, renderCompareToolbarControl, renderTransformToolbarControls, renderManualTrendControls, toolbarSlot, geoControlNode, showChartPanel }}
        period={{ currentFreq, targetFreq, lockPeriod, fePeriod, setTargetFreq, isAggregated, agg, setAgg, hasDates, rowsRawCount: rowsRaw.length, timeframes: TIMEFRAMES, timeframe, lockTimeRange, feTimeRange, setTimeframe, customRangeControls }}
        chart={{ showChartKind, chartKindOptions, handleChartKindChange, lockChartType, feChartType, chartKind, isMultiSeries, barOrientations: BAR_ORIENTATIONS, barOrientation, setBarOrientation, barMultiColor, setBarMultiColor, pieVariants: PIE_VARIANTS, pieVariant, setPieVariant }}
        visual={{ mapRegion, setMapRegion, pictogramIcon, setPictogramIcon, pictogramUnit, setPictogramUnit, defaultChartIcon, setDefaultChartIcon, iconOrientation, setIconOrientation, persistChartVisualConfig }}
        overlay={{ showChartOverlayControls, avgLineEnabled, setOverlayAvg, medianLineEnabled, setOverlayMedian, trendLineEnabled, setOverlayTrend, legendHidden, setLegendHidden: setLegendHiddenValue, singleSeriesOverlaySpec, unit, highlightLatestEnabled, setHighlightLatestEnabled, highlightExtremaEnabled, setHighlightExtremaEnabled }}
        actions={{ miniChartMode, showInteractiveControls, chartDataPanelOpen, setChartDataPanelOpen, chartDataPanelMode, setChartDataPanelMode, sourceDataLocked, canExportSourceData, handleCopyChartData, handleOpenOlapCube, heading, subtitle, exportColumns, exportRows, chartCaptureRef, aradChartContract, widget }}
      />
    );
  }

  const compositeChartsLocked =
    data?.feature_lock === "composite_charts" &&
    aradMultiSeriesHelpContext === "personal_dashboard" &&
    !isAdmin;

  if (compositeChartsLocked) {
    return (
      <div
        className={`${panel.className} flex h-full min-h-0 flex-1 flex-col overflow-hidden`}
        style={{ ...panel.style, borderColor: chartTheme.border }}
      >
        <div className="p-4 border-b" style={{ background: chartTheme.headerBg, borderColor: chartTheme.border }}>
          <div
            className={
              chartTitleEmphasis
                ? "font-serif text-xl sm:text-2xl font-bold tracking-tight leading-snug"
                : "font-serif text-lg"
            }
            style={{ color: chartTheme.accent }}
          >
            {heading}
          </div>
        </div>
        <div className="p-4">
          <FeatureLock message={data?.lock_message} />
        </div>
      </div>
    );
  }

  const aradCard = (
    <div
      data-arad-card="1"
      data-mini-chart={miniChartMode ? "1" : undefined}
      data-mobile-embed={isMobileEmbed ? "1" : undefined}
      className={`${panel.className} flex h-full min-h-0 flex-1 flex-col ${
        isGeoMapChart ? "overflow-x-hidden overflow-y-auto" : "overflow-hidden"
      }${
        isMobileEmbed || catalogInlineSearchPreview ? " !shadow-none !border-0 !rounded-none" : ""
      }${
        fsExpand || applyMobileLandscape
          ? ` !z-[1] !h-full !max-h-full !w-full !max-w-full !shadow-2xl !pt-[env(safe-area-inset-top,0px)]${
            fsExpand || isMobileChartUi ? " !rounded-none" : " !rounded-2xl"
          }`
          : ""
      }`}
      style={{ ...panel.style, borderColor: chartTheme.border }}
    >
      <div
        className={`${compactCatalogMobilePreview ? "max-md:px-2 max-md:py-1" : "max-md:px-3 max-md:py-2"} border-b shrink-0 ${
          miniChartMode ? "px-3 py-1.5" : compactHeaderMode ? "px-2.5 py-1.5" : catalogInlineSearchPreview ? "px-1 py-1" : catalogLivePreview && !fsExpand ? "px-2 py-1" : "px-4 py-2.5"
        }${isMobileEmbed ? " banko-arad-embed-header px-2 py-1.5" : ""}`}
        style={{ background: chartTheme.headerBg, borderColor: chartTheme.border }}
      >
      {showMobileChrome ? (
        <div className={`flex flex-col ${compactCatalogMobilePreview ? "gap-1" : "gap-2"} min-w-0 w-full`}>
          {lockMsgCount > 0 ? (
            <div className={`flex flex-wrap items-center gap-x-2 gap-y-1 rounded-lg border border-amber-300/60 bg-amber-50/90 px-2.5 py-1.5 text-[11px] text-slate-800 ${
              compactCatalogMobilePreview ? "max-md:gap-x-1.5 max-md:px-2 max-md:py-1 max-md:text-[10px] max-md:leading-tight" : ""
            }`}>
              {compactCatalogMobilePreview ? (
                <>
                  <span className="md:hidden">Některé funkce po přihlášení.</span>
                  <span className="hidden md:inline">Některé funkce jsou dostupné po přihlášení.</span>
                </>
              ) : (
                <span>Některé funkce jsou dostupné po přihlášení.</span>
              )}
              <button type="button" className="font-semibold shrink-0 underline text-[hsl(var(--primary-deep))]" onClick={openLogin}>
                Přihlásit se
              </button>
            </div>
          ) : null}
          <div className={`flex items-start gap-2 min-w-0 ${hideChartTitleChrome ? "justify-end" : "justify-between"}`}>
            {!hideChartTitleChrome ? (
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-baseline gap-x-1 gap-y-0.5">
                {titleEditing ? (
                  <span className="flex min-w-[12rem] max-w-full flex-1 items-center gap-1">
                    <input
                      className="h-8 min-w-0 flex-1 rounded-md border border-border/70 bg-white px-2 text-[12px] font-semibold text-slate-800 shadow-sm outline-none focus:border-[hsl(var(--primary))]"
                      value={titleDraft}
                      onChange={(e) => setTitleDraft(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") saveTitleEdit();
                        if (e.key === "Escape") cancelTitleEdit();
                      }}
                      autoFocus
                    />
                    <button
                      type="button"
                      onClick={saveTitleEdit}
                      disabled={titleSaving}
                      className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-emerald-200 bg-emerald-50 text-emerald-700 disabled:opacity-60"
                      title="Uložit název"
                    >
                      <Check className="h-3.5 w-3.5" />
                    </button>
                    <button
                      type="button"
                      onClick={cancelTitleEdit}
                      className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-border/70 bg-white text-slate-500"
                      title="Zrušit úpravu"
                    >
                      <XIcon className="h-3.5 w-3.5" />
                    </button>
                  </span>
                ) : (
                  <span
                    ref={titleAnchorRef}
                    title={heading}
                    className={
                      chartTitleEmphasis
                        ? "text-[14px] sm:text-[15px] font-extrabold leading-snug tracking-tight normal-case line-clamp-3 [overflow-wrap:break-word] [word-break:normal] rounded-md border px-2 py-1.5 shadow-sm"
                        : "text-[13px] font-semibold leading-snug tracking-normal normal-case line-clamp-2 [overflow-wrap:break-word] [word-break:normal]"
                    }
                    style={
                      {
                        maxWidth:
                          canEditTitle && !titleEditing
                            ? "min(32rem, calc(100% - 1.75rem))"
                            : "min(32rem, 100%)",
                        ...(chartTitleEmphasis
                          ? {
                              color: chartTheme.accent,
                              backgroundColor: chartTheme.insightBg,
                              borderColor: chartTheme.border,
                            }
                          : { color: chartTheme.accent }),
                      }
                    }
                  >
                    {heading}
                  </span>
                )}
                {canEditTitle && !titleEditing ? (
                  <button
                    type="button"
                    onClick={startTitleEdit}
                    className="inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-md border border-border/60 bg-white/80 text-slate-500 hover:bg-white hover:text-slate-800"
                    title="Upravit název grafu"
                  >
                    <Pencil className="h-2.5 w-2.5" />
                  </button>
                ) : null}
                {canExpandTitle && !titleEditing ? (
                  <button
                    type="button"
                    onClick={() => setTitleExpanded(true)}
                    className="text-[10px] italic font-normal normal-case tracking-normal hover:underline whitespace-nowrap leading-none opacity-75 hover:opacity-100 shrink-0"
                    style={{ color: chartTheme.accent }}
                    title="Zobrazit celý název"
                  >
                    … více
                  </button>
                ) : null}
              </div>
              <div className="text-[10px] text-slate-600 mt-1 leading-snug tracking-normal normal-case">
                {[
                  currentFreq ? mobileFreqLabel || null : null,
                  mobileTfLabel || null,
                  view === "chart" ? mobileKindLabel : "Tabulka",
                  `${filtered.length} bodů`,
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </div>
            </div>
            ) : null}
            <div className="shrink-0 pt-0.5">
              <button
                type="button"
                onClick={() => setControlsOpen(true)}
                className={`inline-flex items-center gap-1.5 h-9 px-3 rounded-xl border shadow-sm bg-[hsl(var(--card)/0.92)] text-[12px] font-medium text-[hsl(var(--foreground))] ${
                  compactCatalogMobilePreview ? "max-md:h-8 max-md:px-2.5 max-md:rounded-lg max-md:text-[11px]" : ""
                }`}
                style={{ borderColor: chartTheme.border }}
                data-testid="arad-mobile-moznosti"
              >
                <SlidersHorizontal className="h-4 w-4 shrink-0" aria-hidden />
                Možnosti
              </button>
            </div>
          </div>
        </div>
      ) : (
        <>
        {/* Řádek 1: titulek + popisky v jedné linii, vpravo režim zobrazení.
            Vyhrazujeme min. 2 řádky výšky (compact) / 1 řádek (široké),
            aby všechny widgety v řadě měly stejně vysokou hlavičku
            a plocha grafu/tabulky začínala ve stejné Y-pozici. */}
        <div className={`flex items-start gap-2 min-w-0 ${hideChartTitleChrome ? "justify-end" : "justify-between"}`}>
          {!hideChartTitleChrome ? (
          <div
            className={`min-w-0 flex flex-wrap items-baseline gap-x-2 gap-y-0.5 ${
              miniChartMode ? "min-h-0" : compactHeaderMode ? "min-h-[1.8em]" : "min-h-[1.6em]"
            }`}
          >
            {titleEditing ? (
              <span className="flex min-w-[14rem] max-w-[min(100%,34rem)] flex-1 items-center gap-1">
                <input
                  className="h-8 min-w-0 flex-1 rounded-md border border-border/70 bg-white px-2 text-xs font-semibold text-slate-800 shadow-sm outline-none focus:border-[hsl(var(--primary))]"
                  value={titleDraft}
                  onChange={(e) => setTitleDraft(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") saveTitleEdit();
                    if (e.key === "Escape") cancelTitleEdit();
                  }}
                  autoFocus
                />
                <button
                  type="button"
                  onClick={saveTitleEdit}
                  disabled={titleSaving}
                  className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-emerald-200 bg-emerald-50 text-emerald-700 disabled:opacity-60"
                  title="Uložit název"
                >
                  <Check className="h-3.5 w-3.5" />
                </button>
                <button
                  type="button"
                  onClick={cancelTitleEdit}
                  className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-border/70 bg-white text-slate-500"
                  title="Zrušit úpravu"
                >
                  <XIcon className="h-3.5 w-3.5" />
                </button>
              </span>
            ) : (
              <span
                ref={titleAnchorRef}
                title={heading}
                className={
                  chartTitleEmphasis
                    ? `max-w-[min(100%,32rem)] font-extrabold uppercase tracking-[0.1em] leading-snug rounded-md border px-2 py-1.5 shadow-sm ${
                        chartCompact
                          ? "text-[11px] sm:text-[12px] line-clamp-3"
                          : "text-xs sm:text-sm md:text-[15px] max-md:break-words md:line-clamp-2"
                      }`
                    : `kpi-label max-w-[min(100%,32rem)] ${
                        chartCompact
                          ? "line-clamp-2 leading-tight normal-case tracking-normal text-[10px] font-semibold"
                          : "leading-snug max-md:break-words md:truncate"
                      }`
                }
                style={
                  {
                    maxWidth:
                      canEditTitle && !titleEditing
                        ? "min(32rem, calc(100% - 1.75rem))"
                        : "min(32rem, 100%)",
                    ...(chartTitleEmphasis
                      ? {
                          color: chartTheme.accent,
                          backgroundColor: chartTheme.insightBg,
                          borderColor: chartTheme.border,
                        }
                      : { color: chartTheme.accent }),
                  }
                }
              >
                {heading}
              </span>
            )}
            {canEditTitle && !titleEditing ? (
              <button
                type="button"
                onClick={startTitleEdit}
                className="inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-md border border-border/60 bg-white/80 text-slate-500 hover:bg-white hover:text-slate-800"
                title="Upravit název grafu"
              >
                <Pencil className="h-2.5 w-2.5" />
              </button>
            ) : null}
            {canExpandTitle && !titleEditing && (
              <button
                type="button"
                onClick={() => setTitleExpanded(true)}
                className="text-[10px] italic font-normal normal-case tracking-normal hover:underline whitespace-nowrap leading-none opacity-75 hover:opacity-100"
                style={{ color: chartTheme.accent }}
                title="Zobrazit celý název"
              >
                … více
              </button>
            )}
          </div>
          ) : null}
          <div className="relative flex items-center gap-0.5 shrink-0">
            {chartShareContext ? (
              <CatalogChartShareButtons shareContext={chartShareContext} compact disabled={!data?.rows?.length} />
            ) : null}
            <ChartAnalystTrigger
              chartContract={chartAnalystContract}
              olapPackage={olapPackage}
              olapActive={Boolean(olapPackage)}
              conceptMeta={conceptExplainMeta}
              disabled={!showChartPanel || (!aradChartContract?.data?.length && !conceptExplainMeta?.title)}
              onApplyActions={applyChartAnalystActions}
              onFindInCatalogSearch={handleStaleFindInCatalog}
              onOpenInline={fsExpand ? () => setChartAnalystPanelOpen((v) => !v) : undefined}
            />
            {!fsExpand && !suppressWebFullscreen ? (
              <button
                type="button"
                data-export-ignore="1"
                onClick={() => setChartFullscreenOpen(true)}
                className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-border/70 bg-white text-slate-600 shadow-sm hover:bg-white hover:text-slate-900"
                title="Pracovní plocha na celou obrazovku"
                aria-label="Graf na celou obrazovku"
              >
                <Maximize2 className="h-3.5 w-3.5" strokeWidth={2} />
              </button>
            ) : null}
            {(!controlsInOptionsPanel || fsExpand) && showInteractiveControls && (
              <>
                {renderViewLayoutControls({ testIdSuffix: fsExpand ? "-fs" : "" })}
              </>
            )}

            {!catalogSuppressInternalTable &&
            !showMobileChrome &&
            (!miniChartMode || showInteractiveControls || fsExpand) &&
            !chartActionsInSidePanel && (
              <>
                <button
                  type="button"
                  onClick={() => {
                    setChartDataPanelMode("data");
                    setChartDataPanelOpen((open) => (chartDataPanelMode === "data" ? !open : true));
                  }}
                  data-testid="arad-view-data-toggle"
                  title="Tabulka dat pod grafem"
                  className={`flex items-center gap-1 h-6 px-2 text-[9px] uppercase tracking-[0.08em] rounded-full border transition-colors ${
                    chartDataPanelOpen && chartDataPanelMode === "data"
                      ? "chip-mint border-transparent font-medium"
                      : "border-border/60 bg-white text-slate-600 hover:bg-[hsl(var(--primary-soft))]"
                  }`}
                  style={chartDataPanelOpen && chartDataPanelMode === "data" ? activeControlStyle : undefined}
                >
                  Data
                </button>
                {canExportSourceData ? (
                  <button
                    type="button"
                    onClick={handleOpenOlapCube}
                    data-testid="arad-view-olap-toggle"
                    title="Převést aktuální pozorování grafu do OLAP/star-schema formátu"
                    className={`flex items-center gap-1 h-6 px-2 text-[9px] uppercase tracking-[0.08em] rounded-full border transition-colors ${
                      chartDataPanelOpen && chartDataPanelMode === "olap"
                        ? "chip-mint border-transparent font-medium"
                        : "border-violet-200 bg-violet-50 text-violet-800 hover:bg-violet-100"
                    }`}
                    style={chartDataPanelOpen && chartDataPanelMode === "olap" ? activeControlStyle : undefined}
                  >
                    <Database className="h-3 w-3" aria-hidden />
                    OLAP
                  </button>
                ) : null}
                {!sourceDataLocked ? (
                  <button
                    type="button"
                    onClick={handleCopyChartData}
                    data-testid="arad-view-copy"
                    title="Kopírovat data pro Excel"
                    className="flex items-center justify-center h-6 w-6 rounded-full border border-border/60 bg-white text-slate-600 hover:bg-[hsl(var(--primary-soft))]"
                  >
                    <ClipboardCopy className="h-3 w-3" aria-hidden />
                  </button>
                ) : null}
                <ExportMenu
                  compact
                  title={`${heading || "ARAD"}${unit ? ` (${unit})` : ""}`}
                  subtitle={subtitle && subtitle !== heading ? subtitle : ""}
                  columns={exportColumns}
                  rows={exportRows}
                  chartTargetRef={chartCaptureRef}
                  enableChartImageExport={showChartPanel}
                  lockSourceData={sourceDataLocked}
                  chartContract={aradChartContract}
                  meCatalogWidgetId={
                    widget?.config?.source_type === "external_catalog" && widget?.id
                      ? widget.id
                      : null
                  }
                />
              </>
            )}

            {!showMobileChrome && controlsInOptionsPanel && showInteractiveControls && !fsExpand && (
              <div className="relative shrink-0">
                <button
                  ref={compactChartOptsBtnRef}
                  type="button"
                  onClick={() => setControlsOpen((v) => !v)}
                  className="h-6 w-6 inline-flex items-center justify-center rounded-full border border-border/60 bg-white/80 text-slate-600"
                  title="Možnosti grafu"
                  aria-label="Možnosti grafu"
                  aria-expanded={controlsOpen}
                >
                  <SlidersHorizontal className="h-3.5 w-3.5" />
                </button>
              </div>
            )}
            {fsExpand ? (
              <button
                type="button"
                onClick={() => setChartFullscreenOpen(false)}
                className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-xl border border-border/70 bg-white text-slate-600 hover:bg-muted/50 hover:text-slate-900"
                title="Zavřít pracovní plochu (Esc)"
                aria-label="Zavřít pracovní plochu"
              >
                <XIcon className="h-4 w-4" strokeWidth={2} />
              </button>
            ) : null}
          </div>
        </div>
        </>
      )}

        {/* Řádek 2: periodicita vs. období (časové okno); typ grafu; srovnání ve fullscreen */}
        {!showMobileChrome && (!controlsInOptionsPanel || fsExpand) && showInteractiveControls && (currentFreq || (hasDates && rowsRaw.length > 0) || showChartKind || toolbarSlot || (fsExpand && showChartCompareToolbar && showChartPanel)) && (
          <div className="mt-2 flex max-h-[4.5rem] shrink-0 flex-wrap items-center gap-x-2 gap-y-1 overflow-y-auto overscroll-contain border-t border-border/40 pt-2">
            {currentFreq && (
              <>
                <span className="text-[9px] uppercase tracking-wider text-slate-400 shrink-0">Periodicita</span>
                {ALL_FREQS.map((f) => {
                  const isActive = f.code === targetFreq;
                  const isNative = f.code === currentFreq;
                  const isAvailable =
                    FREQ_RANK[f.code] !== undefined &&
                    FREQ_RANK[currentFreq] !== undefined &&
                    FREQ_RANK[f.code] >= FREQ_RANK[currentFreq];
                  const tip = isNative
                    ? `${f.title} – nativní periodicita řady`
                    : isAvailable
                    ? `${f.title} – agregace z ${currentFreq} (${agg === "avg" ? "průměr" : "součet"})`
                    : `${f.title} – jemnější než zdrojová data (${currentFreq}), nelze`;
                  return (
                    <button
                      key={f.code}
                      type="button"
                      onClick={() => isAvailable && !lockPeriod && setTargetFreq(f.code)}
                      disabled={!isAvailable || lockPeriod}
                      title={lockPeriod ? fePeriod.message || tip : tip}
                      className={`h-5 w-7 text-[10px] rounded border font-mono transition-colors ${
                        isActive
                          ? "chip-mint border-transparent font-semibold"
                          : isAvailable
                          ? `border-border/60 hover:bg-[hsl(var(--primary-soft))] ${isNative ? "text-slate-800 font-semibold" : "text-slate-600"}`
                          : "border-border/40 text-slate-300 cursor-not-allowed bg-slate-50/60"
                      } ${lockPeriod ? "opacity-60" : ""}`}
                      style={isActive ? activeControlStyle : undefined}
                      data-testid={`arad-freq-${f.code}`}
                    >
                      {f.label}
                    </button>
                  );
                })}
                {isAggregated && (
                  <>
                    <span className="text-[9px] text-slate-400 font-mono shrink-0 ml-1">agreg.</span>
                    <button
                      type="button"
                      onClick={() => setAgg("sum")}
                      className={`h-5 px-1.5 text-[9px] uppercase tracking-[0.08em] rounded border font-mono transition-colors ${
                        agg === "sum" ? "chip-mint border-transparent" : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
                      }`}
                      style={agg === "sum" ? activeControlStyle : undefined}
                      title="Součet hodnot v období"
                    >
                      Σ
                    </button>
                    <button
                      type="button"
                      onClick={() => setAgg("avg")}
                      className={`h-5 px-1.5 text-[9px] uppercase tracking-[0.08em] rounded border font-mono transition-colors ${
                        agg === "avg" ? "chip-mint border-transparent" : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
                      }`}
                      style={agg === "avg" ? activeControlStyle : undefined}
                      title="Průměr hodnot v období"
                    >
                      ⌀
                    </button>
                    <button
                      type="button"
                      onClick={() => setAgg("last")}
                      className={`h-5 px-1.5 text-[9px] uppercase tracking-[0.08em] rounded border font-mono transition-colors ${
                        agg === "last" ? "chip-mint border-transparent" : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
                      }`}
                      style={agg === "last" ? activeControlStyle : undefined}
                      title="Poslední hodnota v období (typicky pro rozvahy)"
                    >
                      konec
                    </button>
                  </>
                )}
              </>
            )}

            {currentFreq && hasDates && rowsRaw.length > 0 && (
              <span className="hidden sm:inline w-px h-3.5 bg-border/50 shrink-0 mx-0.5" aria-hidden />
            )}

            {hasDates && rowsRaw.length > 0 && (
              <>
                <span className="text-[9px] uppercase tracking-wider text-slate-400 shrink-0">Období</span>
                {TIMEFRAMES.map((tf) => (
                  <button
                    key={tf.id}
                    type="button"
                    onClick={() => !lockTimeRange && setTimeframe(tf.id)}
                    disabled={lockTimeRange}
                    title={lockTimeRange ? feTimeRange.message || tf.label : tf.label}
                    className={`h-5 px-1.5 text-[10px] rounded border font-mono transition-colors ${
                      timeframe === tf.id
                        ? "chip-mint border-transparent font-semibold"
                        : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
                    } ${lockTimeRange ? "opacity-60 cursor-not-allowed" : ""}`}
                    style={timeframe === tf.id ? activeControlStyle : undefined}
                    data-testid={`arad-timeframe-${tf.id}`}
                  >
                    {tf.label}
                  </button>
                ))}
                <span className="text-[9px] text-slate-400 font-mono whitespace-nowrap">
                  · {filtered.length}{" "}
                  {isAggregated ? `agr. (z ${timeFiltered.length} ${currentFreq})` : `/ ${rowsRaw.length}`}
                </span>
                {multiFrequencyAlignmentLabel ? (
                  <span
                    className="text-[9px] text-slate-500 font-mono whitespace-nowrap"
                    title="Řady s jemnější periodou jsou převedené na nejhrubší periodu v grafu pomocí poslední dostupné hodnoty v období."
                  >
                    · {multiFrequencyAlignmentLabel}
                  </span>
                ) : null}
                {customRangeControls(false)}
              </>
            )}

            {toolbarSlot && (currentFreq || (hasDates && rowsRaw.length > 0) || showChartKind) && (
              <span className="hidden sm:inline w-px h-3.5 bg-border/50 shrink-0 mx-0.5" aria-hidden />
            )}

            {toolbarSlot}

            {showChartPanel && showChartCompareToolbar && (!controlsInOptionsPanel || fsExpand) ? (
              <>
                <span className="hidden sm:inline w-px h-3.5 bg-border/50 shrink-0 mx-0.5" aria-hidden />
                {renderCompareToolbarControl({ testIdSuffix: fsExpand ? "-fs" : "" })}
                {geoControlNode}
              </>
            ) : null}

            {!controlsInOptionsPanel ? renderTransformToolbarControls({ inline: true }) : null}

            {showChartKind && (currentFreq || (hasDates && rowsRaw.length > 0)) && (
              <span className="hidden md:inline w-px h-3.5 bg-border/50 shrink-0 mx-0.5" aria-hidden />
            )}

            {showChartKind && (
              <>
                <span className="text-[9px] uppercase tracking-wider text-slate-400 shrink-0">Typ</span>
                {chartKindOptions.map(({ id, label, Icon, title: tTitle }) => (
                  <button
                    key={id}
                    type="button"
                    onClick={() => handleChartKindChange(id)}
                    disabled={lockChartType}
                    title={lockChartType ? feChartType.message || tTitle : tTitle}
                    className={`flex items-center gap-0.5 h-5 px-1.5 text-[9px] uppercase tracking-[0.08em] rounded-full border transition-colors ${
                      lockChartType
                        ? "border-border/40 bg-slate-50/70 text-slate-400 cursor-not-allowed"
                        : chartKind === id
                          ? "chip-mint border-transparent font-medium"
                          : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
                    }`}
                    style={!lockChartType && chartKind === id ? activeControlStyle : undefined}
                    data-testid={`arad-chart-${id}`}
                  >
                    <Icon className="h-3 w-3 shrink-0" /> {label}
                  </button>
                ))}
                {chartKind === "bar" && !isMultiSeries && (
                  <>
                    <span className="text-[9px] uppercase tracking-wider text-slate-400 shrink-0">Směr</span>
                    {BAR_ORIENTATIONS.map((opt) => (
                      <button
                        key={opt.id}
                        type="button"
                        onClick={() => setBarOrientation(opt.id)}
                        className={`h-5 px-1.5 text-[9px] uppercase tracking-[0.08em] rounded-full border transition-colors ${
                          barOrientation === opt.id
                            ? "chip-mint border-transparent font-medium"
                            : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
                        }`}
                        style={barOrientation === opt.id ? activeControlStyle : undefined}
                        title={opt.id === "horizontal" ? "Vodorovné pruhy" : "Svislé sloupce"}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </>
                )}
                {chartKind === "pie" && !isMultiSeries && (
                  <>
                    <span className="text-[9px] uppercase tracking-wider text-slate-400 shrink-0">Tvar</span>
                    {PIE_VARIANTS.map((opt) => (
                      <button
                        key={opt.id}
                        type="button"
                        onClick={() => setPieVariant(opt.id)}
                        className={`h-5 px-1.5 text-[9px] uppercase tracking-[0.08em] rounded-full border transition-colors ${
                          pieVariant === opt.id
                            ? "chip-mint border-transparent font-medium"
                            : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
                        }`}
                        style={pieVariant === opt.id ? activeControlStyle : undefined}
                        title={opt.id === "full" ? "Plný koláč bez otvoru" : "Kolečko s otvorem uprostřed"}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </>
                )}
                <ChartVisualConfigControls
                  chartKind={chartKind}
                  mapRegion={mapRegion}
                  onMapRegionChange={(v) => {
                    setMapRegion(v);
                    void persistChartVisualConfig({ chart_map_region: v });
                  }}
                  pictogramIcon={pictogramIcon}
                  onPictogramIconChange={(v) => {
                    setPictogramIcon(v);
                    void persistChartVisualConfig({ chart_pictogram_icon: v });
                  }}
                  pictogramUnit={pictogramUnit}
                  onPictogramUnitChange={(v) => {
                    setPictogramUnit(v);
                    void persistChartVisualConfig({ chart_pictogram_unit: v });
                  }}
                  defaultIcon={defaultChartIcon}
                  onDefaultIconChange={(v) => {
                    setDefaultChartIcon(v);
                    void persistChartVisualConfig({ chart_icon_default: v });
                  }}
                  iconOrientation={iconOrientation}
                  onIconOrientationChange={(v) => {
                    setIconOrientation(normalizeIconOrientation(v));
                    void persistChartVisualConfig({ chart_icon_orientation: normalizeIconOrientation(v) });
                  }}
                  activeControlStyle={activeControlStyle}
                />
                {showChartOverlayControls && (
                  <>
                    <span className="text-[9px] uppercase tracking-wider text-slate-400 shrink-0">Linky</span>
                    <span className="inline-flex items-stretch gap-1 shrink-0">
                      <button
                        type="button"
                        onClick={() => setOverlayAvg(!avgLineEnabled)}
                        className={`h-5 px-1.5 text-[9px] uppercase tracking-[0.08em] rounded-full border transition-colors inline-flex items-center justify-center leading-tight ${
                          avgLineEnabled
                            ? "chip-mint border-transparent font-medium"
                            : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
                        }`}
                        style={avgLineEnabled ? activeControlStyle : undefined}
                        title={
                          avgLineEnabled && singleSeriesOverlaySpec && Number.isFinite(singleSeriesOverlaySpec.average)
                            ? `Průměr: ${fmtCompact(singleSeriesOverlaySpec.average)}${(unit || "").trim() ? ` ${String(unit).trim()}` : ""} (u čáry v grafu)`
                            : "Průměrná linka — číslo u čáry v grafu"
                        }
                      >
                        AVG
                      </button>
                    </span>
                    <span className="inline-flex items-stretch gap-1 shrink-0">
                      <button
                        type="button"
                        onClick={() => setOverlayMedian(!medianLineEnabled)}
                        className={`h-5 px-1.5 text-[9px] uppercase tracking-[0.08em] rounded-full border transition-colors inline-flex items-center justify-center leading-tight ${
                          medianLineEnabled
                            ? "chip-mint border-transparent font-medium"
                            : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
                        }`}
                        style={medianLineEnabled ? activeControlStyle : undefined}
                        title={
                          medianLineEnabled && singleSeriesOverlaySpec && Number.isFinite(singleSeriesOverlaySpec.median)
                            ? `Medián: ${fmtCompact(singleSeriesOverlaySpec.median)}${(unit || "").trim() ? ` ${String(unit).trim()}` : ""} (u čáry v grafu)`
                            : "Mediánová linka — číslo u čáry v grafu"
                        }
                      >
                        MED
                      </button>
                    </span>
                    <span className="inline-flex items-stretch gap-1 shrink-0">
                      <button
                        type="button"
                        onClick={() => setOverlayTrend(!trendLineEnabled)}
                        className={`h-5 px-1.5 text-[9px] uppercase tracking-[0.08em] rounded-full border transition-colors inline-flex items-center justify-center leading-tight ${
                          trendLineEnabled
                            ? "chip-mint border-transparent font-medium"
                            : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
                        }`}
                        style={trendLineEnabled ? activeControlStyle : undefined}
                        title={
                          trendLineEnabled &&
                          singleSeriesOverlaySpec &&
                          singleSeriesOverlaySpec.trendSlopePerStep != null &&
                          Number.isFinite(singleSeriesOverlaySpec.trendSlopePerStep)
                            ? `Sklon: ${fmtTrendSlopePerStep(singleSeriesOverlaySpec.trendSlopePerStep)}${(unit || "").trim() ? ` ${String(unit).trim()}` : ""} / krok (u čáry v grafu)`
                            : "Trendová linka — sklon u čáry v grafu"
                        }
                      >
                        trend
                      </button>
                    </span>
                    {renderManualTrendControls({ inline: true })}
                    {renderLegendToggle({ inline: true })}
                  </>
                )}
              </>
            )}
            {!showChartKind && showChartOverlayControls && (
              <>
                <span className="hidden md:inline w-px h-3.5 bg-border/50 shrink-0 mx-0.5" aria-hidden />
                <span className="text-[9px] uppercase tracking-wider text-slate-400 shrink-0">Linky</span>
                <span className="inline-flex items-stretch gap-1 shrink-0">
                  <button
                    type="button"
                    onClick={() => setOverlayAvg(!avgLineEnabled)}
                    className={`h-5 px-1.5 text-[9px] uppercase tracking-[0.08em] rounded-full border transition-colors inline-flex items-center justify-center leading-tight ${
                      avgLineEnabled
                        ? "chip-mint border-transparent font-medium"
                        : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
                    }`}
                    style={avgLineEnabled ? activeControlStyle : undefined}
                    title="Průměrná linka - číslo u čáry v grafu"
                  >
                    AVG
                  </button>
                </span>
                <span className="inline-flex items-stretch gap-1 shrink-0">
                  <button
                    type="button"
                    onClick={() => setOverlayMedian(!medianLineEnabled)}
                    className={`h-5 px-1.5 text-[9px] uppercase tracking-[0.08em] rounded-full border transition-colors inline-flex items-center justify-center leading-tight ${
                      medianLineEnabled
                        ? "chip-mint border-transparent font-medium"
                        : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
                    }`}
                    style={medianLineEnabled ? activeControlStyle : undefined}
                    title="Mediánová linka - číslo u čáry v grafu"
                  >
                    MED
                  </button>
                </span>
                <span className="inline-flex items-stretch gap-1 shrink-0">
                  <button
                    type="button"
                    onClick={() => setOverlayTrend(!trendLineEnabled)}
                    className={`h-5 px-1.5 text-[9px] uppercase tracking-[0.08em] rounded-full border transition-colors inline-flex items-center justify-center leading-tight ${
                      trendLineEnabled
                        ? "chip-mint border-transparent font-medium"
                        : "border-border/60 hover:bg-[hsl(var(--primary-soft))] text-slate-600"
                    }`}
                    style={trendLineEnabled ? activeControlStyle : undefined}
                    title="Trendová linka - sklon u čáry v grafu"
                  >
                    trend
                  </button>
                </span>
                {renderManualTrendControls({ inline: true })}
                {renderLegendToggle({ inline: true })}
              </>
            )}
          </div>
        )}

      </div>

      <div
        className={`flex-1 min-h-0 flex flex-col${isGeoMapChart ? "" : " overflow-hidden"}${miniChartMode ? " pb-1.5" : ""}`}
        style={{ background: chartTheme.bodyBg }}
      >
        {rowsRaw.length === 0 ? (
          <div className="flex-1 min-h-[120px] flex items-center justify-center px-6 py-8 text-sm text-slate-500 font-mono text-center">
            {isUserUploadChart
              ? (isMultiSeries
                ? "Pro tento složený graf z vlastních dat nejsou dostupné hodnoty. Zkontrolujte výběr číselných sloupců Y."
                : "Pro tento graf z vlastních dat nejsou dostupné hodnoty. Zkontrolujte mapování sloupců X/Y.")
              : (isMultiSeries
                ? "Pro tento složený graf nejsou žádná sloučená data. Zkontrolujte synchronizaci zdrojů a definici řad."
                : "Pro tento indikátor nejsou v lokální databázi žádné záznamy. Spusťte sync zdroje.")}
          </div>
        ) : filtered.length === 0 ? (
          <div className="flex-1 min-h-[120px] flex items-center justify-center px-6 py-8 text-sm text-slate-500 font-mono text-center">
            Pro zvolené období nejsou žádná data. Zvolte delší období.
          </div>
        ) : (
          <>
          <div
            className={`flex-1 min-h-0 w-full ${isGeoMapChart ? "" : "overflow-hidden"} ${showSplitLayout ? "flex flex-row" : "flex flex-col"}`}
            data-testid={showSplitLayout ? "arad-view-split-layout" : undefined}
          >
            {effectiveShowChartPanel ? (
          <div
            className={showSplitLayout ? "flex-1 min-w-0 min-h-0 flex flex-col" : "flex-1 min-h-0 flex flex-col w-full"}
            ref={showChartAnalystSplit ? chartAnalystSplitRef : undefined}
            data-testid={showChartAnalystSplit ? "arad-chart-analyst-split" : undefined}
          >
            {showStaleNoticeInChart ? (
              <div className="px-2 pt-2 shrink-0" data-export-ignore="true">
                <ChartStaleDataNotice
                  meta={conceptExplainMeta}
                  rows={rowsRaw}
                  timeField={hasDates ? "date" : effectiveData?.x_field || effectiveData?.time_field}
                  valueField={effectiveData?.y_field || effectiveData?.value_field}
                  onFindInCatalogSearch={handleStaleFindInCatalog}
                />
              </div>
            ) : null}
          <div className={showChartAnalystSplit ? "flex min-h-0 flex-1 flex-col overflow-hidden" : "contents"}>
          <div
            ref={chartCaptureRef}
            data-chart-export-root="1"
            data-chart-kind={isGeoMapChart ? "geo_map" : undefined}
            className={`chart-body-slot relative w-full min-w-0 max-w-[100%] ${
              isGeoMapChart
                ? `flex min-h-0 flex-1 flex-col overflow-hidden${fsExpand ? " h-full" : ""}`
                : "grid h-full min-h-0 flex-1 overflow-hidden"
            } ${
              miniChartMode
                ? "pl-1.5 pr-2 pb-3 pt-2"
                : fsExpand
                  ? "pl-4 pr-5 pb-2 pt-4"
                  : catalogSuppressChartInsights
                    ? "pl-2 pr-2 pb-0 pt-1"
                    : catalogInlineSearchPreview
                      ? "pl-1 pr-1 pb-0 pt-1"
                      : isMobileChartUi
                        ? "pl-1.5 pr-2 pb-0 pt-2"
                        : "pl-3 pr-4 pb-1 pt-3"
            }`}
            style={{
              ...(isGeoMapChart
                ? {
                    minHeight: resolvedChartBodyMinHeight,
                    ...(fsExpand ? { height: "100%" } : {}),
                  }
                : {
                    minHeight: resolvedChartBodyMinHeight,
                    height: chartBodyFixedHeight,
                    /* Řádek grafu je čisté "1fr" (ne minmax s pevným minimem): hlavička/pata mají
                     * auto výšku a společně s pevnou height karty musí graf dostat přesně to, co
                     * zbyde. Dřívější minmax(Npx, 1fr) tvrdě vynucoval Npx i když nezbývalo tolik
                     * místa, grid pak přetekl přes overflow:hidden karty = zmizelá osa X. Recharts
                     * (přes SafeRechartsContainer) se vždy přizpůsobí naměřené výšce, i menší. */
                    gridTemplateRows: `${chartHeaderRows > 0 ? `repeat(${chartHeaderRows}, auto) ` : ""}1fr${
                      chartFooterRows > 0 ? ` repeat(${chartFooterRows}, auto)` : ""
                    }`,
                  }),
            }}
          >
            <AradViewChartInsightsPanel
              showValueCompareSummaryInChart={showValueCompareSummaryInChart}
              valueCompareStats={valueCompareStats}
              showTimeSeriesSummaryInChart={showTimeSeriesSummaryInChart}
              timeSeriesStats={timeSeriesStats}
              unit={unit}
              miniChartMode={miniChartMode}
              veryNarrowWidget={veryNarrowWidget}
              chartCompact={chartCompact}
              fsExpand={fsExpand}
              effectiveKpiSummaryMode={effectiveKpiSummaryMode}
              statistics={{
                showSeriesStatisticsPanel,
                isMultiSeries,
                singleSeriesStatistics,
                statsActions: resolveStatsActions(),
                chartStatsFocus,
                isMobileChartUi,
                onCloseStatistics: () => setChartStatsPanelOpen(false),
                multiSeriesStatistics,
                multiFrequencyAlignmentLabel,
                chartRowsZoomedCount: chartRowsZoomed.length,
              }}
            />
            <div
              ref={chartFrameRef}
              className={`${miniChartMode ? "mini-chart-frame " : ""}relative flex w-full min-w-0 max-w-[100%] flex-row ${
                isGeoMapChart ? "h-full min-h-0 flex-1" : "h-full min-h-0 flex-1"
              }`}
              style={{
                ...(!isGeoMapChart && miniChartMode ? { paddingBottom: 6 } : {}),
                ...(isGeoMapChart || miniChartMode ? {} : { gridRow: chartHeaderRows + 1 }),
              }}
            >
              {stickyYAxisSpec ? (
                <FrozenYAxisGutter spec={stickyYAxisSpec} chartTheme={chartTheme} />
              ) : null}
              <div
                ref={chartWheelRef}
                className={`min-w-0 max-w-[100%] ${
                  isGeoMapChart
                    ? "flex h-full min-h-0 w-full flex-1 flex-col overflow-hidden"
                    : "min-h-0 flex-1"
                } ${
                  chartScrollable
                    ? "overflow-x-auto overflow-y-hidden overscroll-x-contain [scrollbar-gutter:stable]"
                    : isGeoMapChart
                      ? "overflow-hidden"
                      : miniChartMode
                        ? "overflow-visible"
                        : "overflow-hidden"
                }${chartScrollable && chartKind === "bar" ? " chart-bar-h-scroll" : ""}${manualTrendMode ? " cursor-crosshair" : ""}`}
                onClick={handleManualTrendChartClick}
                onDoubleClick={() => setXZoom(null)}
                title={
                  manualTrendMode
                    ? manualTrendDraft
                      ? "Klikněte druhý bod vlastní trendové čáry"
                      : "Klikněte první bod vlastní trendové čáry"
                    : !isGeoMapChart && chartKind !== "pie" && chartRows.length >= 10
                      ? "Dvojklik: celé období"
                      : undefined
                }
              >
              {showCustomChartView ? (
                <div
                  className={`w-full ${isGeoMapChart ? "flex h-full min-h-0 flex-1 flex-col" : "h-full overflow-hidden"}`}
                  style={isGeoMapChart ? undefined : { minHeight: rechartsMinHeight }}
                >
                  <Suspense
                    fallback={
                      <div className="flex h-full min-h-[160px] items-center justify-center text-xs text-slate-500">
                        Načítám graf…
                      </div>
                    }
                  >
                    <SpecialChartView
                      kind={chartKind}
                      rows={geoMapChartRows}
                      unit={unit}
                      compact={chartCompact}
                      miniChartMode={miniChartMode}
                      veryNarrowWidget={veryNarrowWidget}
                      fsExpand={fsExpand}
                      kpiMode={geoMapKpiMode}
                      visualConfig={specialVisualConfig}
                    />
                  </Suspense>
                </div>
              ) : chartScrollable ? (
                <div
                  className="h-full min-h-[72px]"
                  style={{
                    minWidth: chartScrollMinWidth,
                    width: chartScrollMinWidth ? `max(100%, ${chartScrollMinWidth}px)` : "100%",
                  }}
                >
                  <SafeRechartsContainer
                    height="100%"
                    minHeight={rechartsMinHeight}
                    debounce={fsExpand ? 0 : 50}
                    key={`rc-scroll-${widget?.id || "w"}-${widget?.width || "full"}-${chartBodyMinHeight ?? "auto"}-${view}-${chartKind}-${chartDataSig}-fs${fsExpand ? 1 : 0}`}
                  >
                    {isMultiSeries
                      ? renderMultiComposedChart(
                          seriesList,
                          chartRowsZoomed,
                          unit,
                          chartCompact,
                          chartTheme,
                          chartRenderWidth,
                          miniChartMode,
                          Boolean(stickyYAxisSpec),
                          stickyAxisOverride || visibleAxisSpec?.left || null,
                          visibleAxisSpec?.right || null,
                          false,
                          visibleXDomain,
                          mobileDenseChart,
                          multiSeriesOverlaySpec,
                          targetFreq || currentFreq,
                          chartKind,
                          !legendHidden,
                          periodAnnotations
                        )
                      : renderChart(
                          chartKind,
                          chartRowsZoomed,
                          unit,
                          chartCompact,
                          widget?.config?.chart_color,
                          chartTheme,
                          chartRenderWidth,
                          miniChartMode,
                          barMultiColor,
                          latestDataMode,
                          showBarLabels,
                          Boolean(stickyYAxisSpec) && chartKind !== "bar",
                          barOrientation,
                          pieVariant,
                          stickyAxisOverride || visibleAxisSpec?.left || null,
                          false,
                          visibleXDomain,
                          valueCompareBarMode,
                          singleSeriesOverlaySpec,
                          sharedBarYAxisSpec,
                          highlightSpec,
                          mobileDenseChart,
                          targetFreq || currentFreq,
                          periodAnnotations
                        )}
                  </SafeRechartsContainer>
                </div>
              ) : (
                <SafeRechartsContainer
                  height="100%"
                  minHeight={rechartsMinHeight}
                  debounce={miniChartMode ? 0 : fsExpand ? 0 : 50}
                  key={`rc-${widget?.id || "w"}-${widget?.width || "full"}-${chartBodyMinHeight ?? "auto"}-${view}-${chartKind}-${chartDataSig}-fs${fsExpand ? 1 : 0}-mini${miniChartMode ? 1 : 0}`}
                >
                  {isMultiSeries
                    ? renderMultiComposedChart(
                        seriesList,
                        chartRowsZoomed,
                        unit,
                        chartCompact,
                        chartTheme,
                        chartRenderWidth,
                        miniChartMode,
                        Boolean(stickyYAxisSpec) && chartKind !== "bar" && !miniChartMode,
                        stickyAxisOverride || visibleAxisSpec?.left || null,
                        visibleAxisSpec?.right || null,
                        false,
                        visibleXDomain,
                        mobileDenseChart,
                        multiSeriesOverlaySpec,
                        targetFreq || currentFreq,
                        chartKind,
                        !legendHidden,
                        periodAnnotations
                      )
                    : renderChart(
                        chartKind,
                        chartRowsZoomed,
                        unit,
                        chartCompact,
                        widget?.config?.chart_color,
                        chartTheme,
                        chartRenderWidth,
                        miniChartMode,
                        barMultiColor,
                        latestDataMode,
                        showBarLabels,
                        false,
                        barOrientation,
                        pieVariant,
                        stickyAxisOverride || visibleAxisSpec?.left || null,
                        false,
                        visibleXDomain,
                        valueCompareBarMode,
                        singleSeriesOverlaySpec,
                        sharedBarYAxisSpec,
                        highlightSpec,
                        mobileDenseChart,
                        targetFreq || currentFreq,
                        periodAnnotations
                      )}
                </SafeRechartsContainer>
              )}
              </div>
            </div>
            {showSingleSeriesLegend ? (
              <div
                className={`shrink-0 flex flex-wrap items-center gap-x-2 gap-y-0.5 px-1 text-slate-600 border-b border-border/25 ${
                  chartCompact ? "py-0.5 text-[8px] leading-tight" : "py-1 text-[9px]"
                }`}
                title="Řada v grafu"
              >
                <span className="inline-flex min-w-0 max-w-full items-center gap-1.5">
                  <span
                    className="h-2 w-2 shrink-0 rounded-full"
                    style={{ backgroundColor: singleSeriesLegendColor }}
                  />
                  <span
                    className={`min-w-0 max-w-full font-medium text-slate-700 ${chartCompact ? "line-clamp-2 [overflow-wrap:break-word] [word-break:normal]" : "truncate"}`}
                    title={heading}
                  >
                    {heading}
                  </span>
                </span>
              </div>
            ) : null}
            {showLatestBarLegend ? (
              <div
                className={`shrink-0 flex flex-wrap items-center gap-x-2 gap-y-0.5 overflow-y-auto overscroll-contain px-1 text-slate-600 ${
                  chartCompact ? "max-h-[3.4rem] py-0.5 text-[8px] leading-tight" : "max-h-[4rem] pt-1 text-[9px]"
                }`}
                title="Legenda barev sloupců"
              >
                {latestBarLegendItems.map((row, idx) => (
                  <span
                    key={`${row.x}-${idx}`}
                    className="inline-flex max-w-[7rem] items-center gap-1 min-w-0"
                    title={String(row.x ?? "")}
                  >
                    <span
                      className="h-2 w-2 rounded-sm shrink-0"
                      style={{ backgroundColor: PIE_COLORS[idx % PIE_COLORS.length] }}
                    />
                    <span className="truncate">{ellipsizeLabel(row.x, 18)}</span>
                  </span>
                ))}
                {chartRowsZoomed.length > latestBarLegendItems.length ? (
                  <span className="text-slate-400">+{chartRowsZoomed.length - latestBarLegendItems.length}</span>
                ) : null}
              </div>
            ) : null}
            {showMultiSeriesLegend ? (
              <div
                className={`shrink-0 min-h-0 max-h-[3.6rem] overflow-y-auto overscroll-contain border-t border-border/30 flex flex-wrap items-start gap-x-2 gap-y-0.5 px-1 pr-2 text-slate-600 ${
                  chartCompact ? "py-0.5 text-[8px] leading-tight" : "py-1 text-[9px]"
                }`}
                title="Legenda řad"
              >
                {multiSeriesLegendItems.map((s, idx) => {
                  const baseName = s?.name || s?.label || s?.indicator_id || `Řada ${idx + 1}`;
                  const label =
                    String(s?.y_axis || "").toLowerCase() === "right"
                      ? `${baseName} (pravá osa)`
                      : baseName;
                  return (
                    <span
                      key={s?.key || s?.indicator_id || `${label}-${idx}`}
                      className="inline-flex max-w-[8rem] items-center gap-1 min-w-0"
                      title={label}
                    >
                      <span
                        className="h-2 w-2 rounded-full shrink-0"
                        style={{ backgroundColor: PIE_COLORS[idx % PIE_COLORS.length] }}
                      />
                      <span className="truncate">{ellipsizeLabel(label, chartCompact ? 18 : 24)}</span>
                    </span>
                  );
                })}
                {seriesList.length > multiSeriesLegendItems.length ? (
                  <span className="text-slate-400">+{seriesList.length - multiSeriesLegendItems.length}</span>
                ) : null}
              </div>
            ) : null}
            {showXZoomWheelHint && (
              <div
                ref={chartHintRef}
                data-export-ignore="1"
                className={`shrink-0 flex flex-wrap items-center justify-between gap-x-2 gap-y-0.5 px-1 text-slate-500 ${
                  chartCompact ? "py-0.5 text-[9px] leading-tight" : "pt-1 text-[10px]"
                }`}
                title="Kolečko myši v grafu přibližuje osu X (dvojklik = celé období)."
              >
                <span className={chartCompact ? "line-clamp-2 [overflow-wrap:anywhere]" : ""}>
                  {chartCompact
                    ? "Kolečko: zoom osy X · dvojklik = celé období"
                    : "Kolečko myši v grafu přibližuje osu X (dvojklik = celé období)."}
                </span>
                {xZoom ? (
                  <button
                    type="button"
                    className="shrink-0 font-medium text-slate-700 underline decoration-slate-400/80 hover:text-slate-900"
                    onClick={() => setXZoom(null)}
                  >
                    Celé období
                  </button>
                ) : null}
              </div>
            )}
            {showXZoomSlider ? (
              <div
                data-export-ignore="1"
                className={`shrink-0 flex items-center gap-2 px-1 text-slate-500 ${
                  chartCompact ? "pb-0.5 text-[9px]" : "pb-1 text-[10px]"
                }`}
                title="Posuvník výřezu osy X (při zoomu)."
              >
                <span className="shrink-0 font-mono text-[0.95em] text-slate-400">X</span>
                <input
                  type="range"
                  min={0}
                  max={xZoomPanMax}
                  step={1}
                  value={xZoomPanValue}
                  onChange={(e) => handleXZoomPan(e.target.value)}
                  className="h-1.5 w-full accent-sky-600"
                  aria-label="Posun výřezu osy X"
                />
                <span
                  className={`shrink-0 text-right text-slate-400 ${chartCompact ? "max-w-[8rem]" : "max-w-[14rem]"}`}
                  style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
                >
                  {xZoomRangeLabel}
                </span>
              </div>
            ) : null}
            {sourceLabel && (!chartCompact || fsExpand) && (
              <div className="shrink-0 flex justify-end px-1 pt-0.5">
                <span
                  className="pointer-events-none select-none rounded-full border border-border/50 bg-white/80 px-1.5 py-0.5 font-mono leading-none shadow-[0_1px_2px_hsl(var(--foreground)/0.05)]"
                  style={{
                    fontSize: miniChartMode ? "7px" : chartCompact ? "8px" : "9px",
                    color: "hsl(var(--foreground)/0.48)",
                    letterSpacing: "0.04em",
                  }}
                >
                  {sourceLabel}
                </span>
              </div>
            )}
            {chartDataPanelOpen && !showSplitLayout && !catalogSuppressInternalTable ? (
              <AradViewChartDataPanel
                contract={aradChartContract}
                compact={chartCompact}
                mode={chartDataPanelMode}
                olapPackage={olapPackage}
              />
            ) : null}
          </div>
          </div>
          {showChartAnalystSplit ? (
            <>
              <VerticalResizeHandle
                onPointerDown={onChartAnalystSplitPointerDown}
                label="Změnit poměr grafu a AI chatu"
              />
              <div
                className="shrink-0 overflow-hidden px-3 pb-2 pt-0 min-h-0"
                style={{ height: chartAnalystPanelHeight }}
                data-export-ignore="true"
                data-testid="chart-analyst-split-panel"
              >
                <ChartAnalystPanel
                  chartContract={chartAnalystContract}
                  olapPackage={olapPackage}
                  olapActive={Boolean(olapPackage)}
                  conceptMeta={conceptExplainMeta}
                  disabled={!showChartPanel || (!aradChartContract?.data?.length && !conceptExplainMeta?.title)}
                  onApplyActions={applyChartAnalystActions}
                  onFindInCatalogSearch={handleStaleFindInCatalog}
                  onClose={() => setChartAnalystPanelOpen(false)}
                  embedded
                  fillHeight
                />
              </div>
            </>
          ) : null}
          </div>
            ) : null}
            {effectiveShowTablePanel && chartDataPanelOpen && chartDataPanelMode === "olap" ? (
              <AradViewChartDataPanel
                contract={aradChartContract}
                compact={chartCompact}
                mode="olap"
                olapPackage={olapPackage}
                besideChart={showSplitLayout}
              />
            ) : effectiveShowTablePanel ? (
              <AradViewDataTablePanel
                besideChart={showSplitLayout}
                fsExpand={fsExpand}
                chartCompact={chartCompact}
                tableBodyHeight={tableBodyHeight}
                chartTableTransposed={chartTableTransposed}
                isMultiSeries={isMultiSeries}
                chartTheme={chartTheme}
                unit={unit}
                latestDataMode={latestDataMode}
                seriesList={seriesList}
                seriesTableLabels={seriesTableLabels}
                tableRows={tableRows}
              />
            ) : null}
          </div>
            <AradViewCaptionNotesPanel
              showCaption={!(fsExpand && chartAnalystPanelOpen)}
              showNotes={false}
              caption={{
                captionDisplayText,
                captionAnchorRef,
                canExpandCaption,
                onExpandCaption: () => setCaptionExpanded(true),
                compact: fsExpand,
                hidden: fsExpand && fullscreenCaptionHidden,
                onHide: fsExpand ? () => setFullscreenCaptionHidden(true) : undefined,
                onShow: fsExpand ? () => setFullscreenCaptionHidden(false) : undefined,
              }}
              chartTheme={chartTheme}
              notes={{ miniChartMode, isMobileEmbed, hasAiAnalysisDatasets, aiAnalysisDatasets, fallbackCaption }}
            />
          </>
        )}
      </div>
      <AradViewCaptionNotesPanel showCaption={false} chartTheme={chartTheme} notes={{ miniChartMode, isMobileEmbed, hasAiAnalysisDatasets, aiAnalysisDatasets, fallbackCaption }} />
      {captionExpanded && canExpandCaption && (
        <CaptionPopover
          anchorRef={captionAnchorRef}
          text={captionDisplayText}
          accent={chartTheme.accent}
          headerBg={chartTheme.tableHeaderBg || chartTheme.captionBg}
          onClose={() => setCaptionExpanded(false)}
          overlayZ={isMobileEmbed ? 320 : 260}
          dialogZ={isMobileEmbed ? 330 : 261}
        />
      )}
      {showMobileChrome && controlsOpen && !compareModalOpen && typeof document !== "undefined"
        ? createPortal(
            <div
              className={`fixed inset-0 z-[260] flex flex-col justify-end${isMobileEmbed ? "" : " md:hidden"}`}
            >
              <button
                type="button"
                aria-label="Zavřít dialog"
                className="absolute inset-0 bg-[hsl(var(--foreground)/0.12)] backdrop-blur-[2px]"
                onClick={() => setControlsOpen(false)}
              />
              <div
                role="dialog"
                aria-label="Možnosti grafu"
                className="relative z-[1] m-2 mb-[max(env(safe-area-inset-bottom),12px)] rounded-2xl border border-[hsl(var(--border)/0.85)] bg-[hsl(var(--card))] text-[hsl(var(--card-foreground))] shadow-2xl max-h-[min(80vh,720px)] flex flex-col overflow-hidden min-h-0"
              >
                <div className="flex items-center justify-between gap-2 px-4 py-3 border-b border-[hsl(var(--border)/0.45)] shrink-0">
                  <span className="text-sm font-semibold">Možnosti grafu</span>
                  <button
                    type="button"
                    className="h-9 w-9 inline-flex items-center justify-center rounded-xl border border-[hsl(var(--border)/0.6)] bg-[hsl(var(--muted)/0.35)] text-[hsl(var(--foreground))]"
                    aria-label="Zavřít"
                    onClick={() => setControlsOpen(false)}
                  >
                    <XIcon className="h-5 w-5" strokeWidth={2} />
                  </button>
                </div>
                <div className="overflow-y-auto overscroll-contain px-4 py-4 text-left touch-pan-y min-h-0">
                  {renderAdvancedControlsPanels(true)}
                </div>
              </div>
            </div>,
            document.body
          )
        : null}
      {!showMobileChrome &&
      controlsInOptionsPanel &&
      showInteractiveControls &&
      controlsOpen &&
      !compareModalOpen &&
      compactControlsPanelPos &&
      typeof document !== "undefined"
        ? createPortal(
            <>
              <button
                type="button"
                className="fixed inset-0 z-[260] cursor-default bg-[hsl(var(--foreground)/0.06)]"
                aria-label="Zavřít panel možností"
                onClick={() => setControlsOpen(false)}
              />
              <div
                role="dialog"
                aria-label="Možnosti grafu"
                className="fixed z-[261] rounded-xl border border-border/80 p-3 pt-7 shadow-2xl text-left overflow-y-auto overscroll-contain"
                style={{
                  top: compactControlsPanelPos.top,
                  left: compactControlsPanelPos.left,
                  width: compactControlsPanelPos.width,
                  maxHeight: compactControlsPanelPos.maxHeight,
                  background: "#ffffff",
                  backdropFilter: "blur(6px)",
                }}
              >
                <button
                  type="button"
                  onClick={() => setControlsOpen(false)}
                  className="absolute top-1.5 right-1.5 h-6 w-6 inline-flex items-center justify-center rounded-full border border-border/60 bg-white text-slate-500 hover:text-slate-700 hover:border-border"
                  title="Zavřít"
                  aria-label="Zavřít možnosti"
                >
                  <XIcon className="h-3 w-3" strokeWidth={2} />
                </button>
                {renderAdvancedControlsPanels(true)}
              </div>
            </>,
            document.body
          )
        : null}
      {canEditAradCompare || canViewerAradCompare ? (
        <AradChartCompareModal
          open={compareModalOpen}
          onClose={closeCompareModal}
          mainSourceId={widget?.config?.source_id || widget?.config?.set_id}
          mainIndicatorId={widget?.config?.indicator_id || widget?.config?.selected_indicator}
          initialCompareList={aradCompareInitialList}
          initialPrimaryYAxis={widget?.config?.primary_y_axis}
          onSave={handleAradCompareSave}
          compositeAllowed={feCompositeCharts.allowed}
          compositeMessage={feCompositeCharts.message}
        />
      ) : null}
      {canEditDatasetCompare || canViewerDatasetCompare ? (
        <DatasetChartCompareModal
          open={compareModalOpen}
          onClose={closeCompareModal}
          mainSourceId={datasetCompareBaseline.sid}
          mainSeriesField={datasetCompareBaseline.sf}
          mainSeriesValue={datasetCompareBaseline.sv}
          initialCompareList={widget?.config?.chart_compare_with}
          initialPrimaryYAxis={widget?.config?.primary_y_axis}
          onSave={handleDatasetCompareSave}
          compositeAllowed={feCompositeCharts.allowed}
          compositeMessage={feCompositeCharts.message}
        />
      ) : null}
      {canEditExternalCatalogCompare ||
      canViewerExternalCatalogCompare ||
      canEditExternalAradCatalogCompare ||
      canViewerExternalAradCatalogCompare ||
      (localCompareEnabled &&
        !(canEditAradCompare || canViewerAradCompare) &&
        !(canEditDatasetCompare || canViewerDatasetCompare)) ? (
        <UnifiedChartCompareModal
          open={compareModalOpen}
          onClose={closeCompareModal}
          mainLabel={widget?.config?.title || data?.title || externalCatalogCompareBaseline.mainLabel}
          currentCatalog={externalCatalogCompareBaseline.catalog}
          currentSetId={externalCatalogCompareBaseline.setId}
          initialCompareList={widget?.config?.chart_compare_with}
          initialPrimaryYAxis={widget?.config?.primary_y_axis}
          onSave={handleUnifiedCompareSave}
          compositeAllowed={feCompositeCharts.allowed}
          compositeMessage={feCompositeCharts.message}
        />
      ) : null}
      {canEditUploadSeries && uploadSeriesOpen ? (
        <div className="absolute bottom-2 right-2 z-30 w-[min(22rem,calc(100%-1rem))]">
          <UploadSeriesPanel
            uploadId={widget?.config?.user_upload_id}
            currentConfig={widget?.config || {}}
            compositeAllowed={feCompositeCharts.allowed}
            compositeMessage={feCompositeCharts.message}
            onSave={handleUploadSeriesSave}
            onClose={() => setUploadSeriesOpen(false)}
          />
        </div>
      ) : null}
      {titleExpanded && canExpandTitle && (
        <CaptionPopover
          anchorRef={titleAnchorRef}
          text={heading}
          accent={chartTheme.accent}
          headerBg={chartTheme.tableHeaderBg || chartTheme.captionBg}
          label="Název"
          onClose={() => setTitleExpanded(false)}
        />
      )}
    </div>
  );
  if (fsExpand && typeof document !== "undefined") {
    return createPortal(
      <>
        <button
          type="button"
          className="fixed inset-0 z-[240] cursor-default border-0 bg-slate-900/20 p-0 backdrop-blur-[2px]"
          aria-label="Zavřít pracovní plochu"
          onClick={() => setChartFullscreenOpen(false)}
        />
        <div
          className={`fixed inset-0 z-[250] pointer-events-none overflow-hidden ${
            isMobileChartUi && !applyMobileLandscape
              ? "flex items-stretch justify-stretch p-0"
              : isMobileChartUi
                ? "p-0"
                : "flex items-stretch justify-stretch p-0"
          }`}
        >
          {applyMobileLandscape ? (
            <MobileChartLandscapeShell className="pointer-events-auto shadow-2xl">
              {aradCard}
            </MobileChartLandscapeShell>
          ) : (
            <div
              className={`pointer-events-auto flex min-w-0 flex-col overflow-hidden shadow-2xl ${
                isMobileChartUi
                  ? "h-[100dvh] w-[100vw] max-h-[100dvh] max-w-[100vw] rounded-none"
                  : "h-[100dvh] w-[100vw] max-h-[100dvh] max-w-[100vw] rounded-none"
              }`}
            >
              {aradCard}
            </div>
          )}
        </div>
      </>,
      document.body
    );
  }
  if (isMobileEmbed && applyMobileLandscape) {
    return (
      <MobileChartLandscapeShell className="fixed inset-0 z-[1]">
        {aradCard}
      </MobileChartLandscapeShell>
    );
  }
  return aradCard;
}

/**
 * Floating popover s plným zněním popisku.
 * Renderujeme přes portál do <body>, abychom unikli `overflow-hidden` nadřazené karty.
 * Ukotvíme se k captionu (nad nebo pod ním podle dostupného místa) a klidně překryjeme
 * sousední widgety – uživatel chce kompletní text v elegantním okně.
 */
function CaptionPopover({
  anchorRef,
  text,
  accent,
  headerBg,
  onClose,
  label = "Popisek",
  overlayZ = 80,
  dialogZ = 90,
}) {
  const [pos, setPos] = useState(null);

  useEffect(() => {
    function updatePosition() {
      const node = anchorRef?.current;
      if (!node) return;
      const rect = node.getBoundingClientRect();
      const margin = 12;
      const desiredWidth = Math.min(420, Math.max(280, rect.width + 80));
      const vw = window.innerWidth;
      const vh = window.innerHeight;
      let left = rect.left + rect.width / 2 - desiredWidth / 2;
      left = Math.max(margin, Math.min(vw - desiredWidth - margin, left));
      const spaceAbove = rect.top - margin;
      const spaceBelow = vh - rect.bottom - margin;
      const placeAbove = spaceAbove > spaceBelow;
      setPos({
        left,
        width: desiredWidth,
        top: placeAbove ? undefined : Math.min(rect.bottom + 8, vh - margin - 80),
        bottom: placeAbove ? Math.max(margin, vh - rect.top + 8) : undefined,
        maxHeight: placeAbove ? spaceAbove - 16 : spaceBelow - 16,
      });
    }
    updatePosition();
    window.addEventListener("resize", updatePosition);
    window.addEventListener("scroll", updatePosition, true);
    return () => {
      window.removeEventListener("resize", updatePosition);
      window.removeEventListener("scroll", updatePosition, true);
    };
  }, [anchorRef]);

  useEffect(() => {
    function onKey(e) {
      if (e.key === "Escape") onClose?.();
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  if (typeof document === "undefined" || !pos) return null;

  return createPortal(
    <>
      <div
        onClick={onClose}
        className="fixed inset-0 bg-slate-900/15 backdrop-blur-[2px]"
        style={{ zIndex: overlayZ }}
        aria-hidden="true"
      />
      <div
        role="dialog"
        aria-label="Plné znění popisku"
        className="fixed rounded-2xl border border-border/80 shadow-2xl text-left flex flex-col overflow-hidden"
        style={{
          zIndex: dialogZ,
          left: pos.left,
          width: pos.width,
          top: pos.top,
          bottom: pos.bottom,
          maxHeight: Math.max(160, pos.maxHeight || 320),
          background: "#ffffff",
        }}
      >
        <div
          className="flex items-center justify-between gap-2 px-4 py-2 border-b border-border/60"
          style={{ background: headerBg, color: accent }}
        >
          <span className="text-[10px] uppercase tracking-[0.14em] font-semibold">{label}</span>
          <button
            type="button"
            onClick={onClose}
            className="h-6 w-6 inline-flex items-center justify-center rounded-full hover:bg-white/60"
            title="Zavřít"
            aria-label="Zavřít"
            style={{ color: accent }}
          >
            <XIcon className="h-3.5 w-3.5" strokeWidth={2} />
          </button>
        </div>
        <div className="px-4 py-3 text-[12.5px] leading-relaxed text-slate-700 overflow-y-auto text-normal-wrap">
          {text}
        </div>
      </div>
    </>,
    document.body
  );
}
