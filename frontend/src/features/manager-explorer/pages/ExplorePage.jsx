import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useTranslation } from "react-i18next";
import { useSearchParams, useNavigate } from "react-router-dom";
import {
  ChevronDown,
  ChevronRight,
  Expand,
  ExternalLink,
  FileDown,
  Loader2,
  Plus,
  RotateCcw,
  Search,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  Upload,
  X,
} from "lucide-react";
import AppShell from "@/components/layout/AppShell";
import ExploreSectionFollowup from "@/components/explore/ExploreSectionFollowup";
import ExploreDetailAnalysisLoader, {
  DETAIL_JOB_FAILED_USER_MESSAGE,
} from "@/components/explore/ExploreDetailAnalysisLoader";
import ExploreReportChart from "@/components/explore/ExploreReportChart";
import ExploreInteractiveSeriesDetail from "@/features/manager-explorer/components/explore/ExploreInteractiveSeriesDetail";
import ExploreManagerChartCard from "@/components/explore/ExploreManagerChartCard";
import ExploreManagerReportCover from "@/components/explore/ExploreManagerReportCover";
import SeriesConceptExplainTrigger, { buildExploreChartConceptExplainMeta } from "@/components/explore/SeriesConceptExplainTrigger";
import ChartStaleDataNotice from "@/components/catalog/ChartStaleDataNotice";
import ManagerSectorHierarchyEditor from "@/components/explore/ManagerSectorHierarchyEditor";
import ExploreCountrySelect from "@/components/explore/ExploreCountrySelect";
import ExploreCommentText from "@/components/explore/ExploreCommentText";
import ExploreUserUploadRow from "@/components/explore/ExploreUserUploadRow";
import ExploreIndicatorCard from "@/components/explore/ExploreIndicatorCard";
import ManagerQuickDataPreview from "@/components/explore/ManagerQuickDataPreview";
import VoiceInputButton from "@/components/common/VoiceInputButton";
import {
  ExploreAnalysisSectionHeader,
  ExploreCompositeScoreHero,
  ExploreQuestionDrivers,
} from "@/components/explore/ExploreAnalysisInsights";
import ExploreManagerInterpretationPanel, {
  ExploreManagerRecommendedChartsHeader,
} from "@/components/explore/ExploreManagerInterpretationPanel";
import {
  formatScore,
  isPlaceholderExploreSectionText,
  matchSeriesRefToChartSeries,
  resolveAiFallbackMessage,
  resolveManagerChartDisplayPlan,
  resolveManagerRunTraceDisplay,
  resolveManagerVerdict,
  resolveScoringDisplay,
  shouldSuppressSectionPrimaryScore,
} from "@/lib/exploreManagerInterpretation";
import {
  buildSeriesCoverageIndex,
  enrichUsedSeriesRow,
  normalizeUsedSeriesRow,
} from "@/lib/exploreSeriesCoverage";
import { applySourceStatusEvent, sourceStatusIssues } from "@/lib/exploreSourceProgress";
import api, {
  EXPLORE_LONG_REQUEST_TIMEOUT_MS,
  EXPLORE_SUMMARIZE_POLL_MAX_MS,
  formatApiErrorFromAxios,
  isAxiosRequestAborted,
  isAxiosRequestTimeout,
  postFormData,
} from "@/lib/api";
import {
  runExploreSectorStream,
  shouldFallbackToExploreSectorPost,
} from "@/lib/exploreSectorStream";
import {
  localRelatedSegmentRows,
  localRelatedSegmentSuggestions,
  mapRelationshipRowToLinkedSector,
} from "@/lib/exploreRelatedPresets";
import { buildExploreChartSectionGroups, chartSupportsFxCompare } from "@/lib/exploreChartSections";
import { formatExploreChartSource } from "@/lib/exploreChartSource";
import { buildChartTheme } from "@/lib/chartTheme";
import { DASHBOARD_SERIES_COLORS } from "@/lib/dashboardChartStyle";
import { CHART_SIZE_VARIANTS } from "@/charts/chartTypes";
import {
  buildUnifiedRelatedSegmentItems,
  normalizeExploreSegmentValues,
  sortExploreSegmentLabels,
  segmentLabelKey,
  splitExploreRelatedValues,
  swapPrimaryWithRelated,
} from "@/lib/exploreSectorHierarchy";
import { groupExploreCountryOptions, sortExploreCountryOptions } from "@/lib/exploreGeoOptions";
import {
  buildExploreComparePreviewBody,
  buildExploreFxOverlayPreviewBody,
  compareChartPeriods,
  indexChartRows,
  previewToChartRows,
} from "@/lib/exploreChartCompare";
import { EXPLORE_HERO_SCORE_AREAS } from "@/lib/exploreAnalysisInsights";
import {
  filterCompareCountriesForSource,
  mapCompareCountryToImfCode,
} from "@/lib/exploreCompareGeo";
import { normalizePreviewPayload } from "@/lib/previewNormalizer";
import {
  mergeExploreManagerPayloads,
  parseExploreManagerPayload,
  sectionFullySelected,
  seriesKey,
} from "@/lib/exploreManagerPayload";
import {
  buildGeoPayloadFromQueryUnderstanding,
  countryCodesFromQueryUnderstanding,
  exploreClarificationFallbackSegment,
  exploreClarificationMessage,
  exploreClarificationOptions,
  isExploreNeedsClarification,
  isProductionTypeClarification,
  resolveSectorLabelForClarificationOption,
} from "@/lib/exploreManagerClarification";
import { exportExploreReportToPdf } from "@/lib/exploreReportExport";
import {
  adaptSummarizeEstimateFromServerHint,
  estimateExplorePipelineSec,
  estimateSummarizeDurationSec,
  formatExploreCountdownSec,
  formatExploreEtaSec,
  formatPipelineFetchLine,
  parseFetchTotalFromServerHint,
} from "@/lib/exploreAnalysisEstimate";

const SUMMARIZE_PHASES = [
  "Načítám aktuální data vybraných řad…",
  "Doplňuji makro a geo kontext…",
  "Počítám trendy a meziroční změny…",
  "Sestavuji podklady pro AI…",
  "Formuluji odpověď na váš dotaz…",
];

const SUMMARIZE_PROGRESS_CAP = 92;

const EXPLORE_SUMMARIZE_FAST_MODE_LIMITATION =
  "Analýza používá zúžený výběr nejrelevantnějších veřejných řad; nejde o plné vyhodnocení všech dostupných dat.";

const DEFAULT_SUMMARIZE_MODE = "instant_then_detail_v2";
const INSTANT_CARD_TITLE = "Rychlý orientační náhled";
const INSTANT_CARD_DISCLAIMER = "Toto není finální analytický závěr — jde o rychlý orientační náhled.";
const DETAIL_SECTION_SUBTITLE =
  "Finální analytický závěr založený na širším datovém kontextu.";
const EXPERT_SETTINGS_HELP =
  "Použijte jen pro testování nebo ruční přepsání automatického výběru. Běžně aplikace segment, geografii a související data vybírá automaticky.";

const DETAIL_RECONCILIATION_MESSAGES = {
  confirm: "Detailní analýza potvrzuje rychlý náhled.",
  refine: "Detailní analýza zpřesnila rychlý náhled.",
  direction_conflict: "Detailní analýza změnila rychlý náhled — preferujte detailní závěr.",
};

const INSTANT_VERDICT_LABELS = {
  yes: "Ano",
  rather_yes: "Spíše ano",
  neutral: "Smíšené",
  rather_no: "Spíše ne",
  no: "Ne",
  unknown: "Neurčeno",
};

function resolveExploreExpertMode(searchParams, expertPanelOpen) {
  if (String(import.meta.env.VITE_EXPLORE_EXPERT_MODE || "").trim().toLowerCase() === "true") {
    return true;
  }
  const expertQuery =
    searchParams?.get("expert") === "1" ||
    searchParams?.get("expertMode") === "1" ||
    searchParams?.get("devMode") === "1";
  if (expertQuery) return true;
  return expertPanelOpen;
}

function resolveDetailReconciliationMessage(result) {
  const reconciliationType = String(result?.detail_verdict_reconciliation || "").trim();
  if (reconciliationType && DETAIL_RECONCILIATION_MESSAGES[reconciliationType]) {
    return DETAIL_RECONCILIATION_MESSAGES[reconciliationType];
  }
  return String(result?.detail_reconciliation_message || "").trim() || null;
}

// ETAPA 7: `result.top_drivers` was removed from this extraction - the backend never produces
// it for Manager Explorer (the only real "top_drivers" writer in the codebase is the unrelated
// ForecastModelEngine), so the loop that used to read it here could never contribute anything.
// `analysis_score.{top_positive_for_question,top_negative_for_question,score_drivers}` are kept:
// they ARE an intentional, named placeholder for a not-yet-built decision-driver-attribution
// feature (see ExploreSectorContract#emptyAnalysisScore) rather than orphaned dead code, so the
// contract and this read stay in place, clearly marked, for whenever that feature is built.
function extractInstantDrivers(result, limit = 3) {
  const out = [];
  const pushLine = (line) => {
    const text = String(line || "").trim();
    if (text && !out.includes(text)) out.push(text);
  };
  const score = result?.analysis_score && typeof result.analysis_score === "object" ? result.analysis_score : {};
  for (const key of ["top_positive_for_question", "top_negative_for_question", "score_drivers"]) {
    for (const row of Array.isArray(score[key]) ? score[key] : []) {
      if (!row || typeof row !== "object") continue;
      pushLine(row.summary || row.title || row.indicator_name);
      if (out.length >= limit) return out;
    }
  }
  return out.slice(0, limit);
}

function extractMainLimitation(result) {
  const raw = String(
    result?.limitations_cz || result?.limitations || result?.disclosure?.limitations_cz || ""
  ).trim();
  if (!raw) return null;
  const first = raw.split(/(?<=[.!?])\s+/)[0]?.trim();
  return (first || raw).slice(0, 280);
}

function isInstantSummarizeMode(mode) {
  const m = String(mode || "").trim().toLowerCase();
  return m === "instant_then_detail_v2" || m === "instant_then_detail" || m === "instant";
}

function resolveEffectiveSummarizeMode(requestedMode) {
  const mode = String(requestedMode || DEFAULT_SUMMARIZE_MODE).trim().toLowerCase();
  if (mode === "full" || mode === "detail") return "full";
  if (mode === "instant_then_detail_v2") return "instant_then_detail_v2";
  if (isInstantSummarizeMode(mode)) return "instant_then_detail";
  return "fast";
}

function ExploreInstantPreviewCard({ result, detailLoading = false }) {
  if (!result || typeof result !== "object") return null;
  const isV2 = String(result.summarize_mode || "").trim().toLowerCase() === "instant_then_detail_v2";
  const instantText = String(result.instant_answer || result.assistant_answer_cz || "").trim();
  if (!instantText && !result.instant_ready) return null;

  const version = result.instant_version || result?.disclosure?.instant_version;
  const elapsed = result.instant_elapsed_sec;
  const whHits =
    result.instant_warehouse_hit_count ??
    result?.disclosure?.warehouse_hit_count ??
    result?.debug_metadata?.warehouse_trace?.warehouse_hit_count;
  const detailStatus = result.detail_status;
  const instantScore =
    result.instant_score_value ??
    result.score_value ??
    result?.analysis_score?.decision_score ??
    result?.analysis_score?.composite;
  const confidence = result.instant_confidence;
  const guardTriggered = Boolean(result.instant_guard_triggered);
  const guardMessage = result.instant_guard_message;
  const detailReady = result.detail_ready || detailStatus === "completed";
  const seriesCount = result.instant_series_count;
  const drivers = extractInstantDrivers(result, 3);
  const mainLimitation = extractMainLimitation(result);
  const verdictKey = String(result.instant_verdict_category || "").trim().toLowerCase();
  const verdictLabel = INSTANT_VERDICT_LABELS[verdictKey] || verdictKey || null;
  const showDetailLoadingBadge = detailLoading && !detailReady;

  const title = isV2 ? INSTANT_CARD_TITLE : "Okamžitá odpověď";
  const subtitle = isV2
    ? "Detailní analýza se dopočítává a může závěr zpřesnit."
    : "Detailní analýza běží na pozadí.";

  const debugMeta = [
    version ? `verze ${version}` : null,
    typeof elapsed === "number" ? `${elapsed}s` : null,
    whHits != null ? `WH ${whHits}` : null,
    seriesCount != null ? `${seriesCount} řad` : null,
    confidence ? `jistota ${confidence}` : null,
  ].filter(Boolean);

  return (
    <div
      className={`rounded-xl border px-4 py-3 text-sm ${
        guardTriggered
          ? "border-amber-300 bg-amber-50/80 text-amber-950"
          : "border-emerald-200/60 bg-emerald-50/40 text-emerald-950"
      }`}
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <div className="font-semibold">{title}</div>
          {isV2 ? <p className="mt-0.5 text-[12px] opacity-90">{subtitle}</p> : null}
        </div>
        {showDetailLoadingBadge ? (
          <span className="inline-flex items-center gap-1.5 rounded-full border border-indigo-300/70 bg-indigo-50 px-2.5 py-1 text-[11px] font-medium text-indigo-900">
            <Loader2 className="h-3 w-3 animate-spin" />
            Detail se načítá…
          </span>
        ) : null}
      </div>
      {isV2 ? (
        <p className="mt-2 text-[11px] italic opacity-80">{INSTANT_CARD_DISCLAIMER}</p>
      ) : null}
      {guardTriggered && guardMessage ? (
        <p className="mt-2 text-[12px] font-semibold text-amber-900">{guardMessage}</p>
      ) : null}
      {(verdictLabel || typeof instantScore === "number") && isV2 ? (
        <div className="mt-3 grid gap-2 sm:grid-cols-2">
          {verdictLabel ? (
            <div className="rounded-lg border border-emerald-200/80 bg-white/60 px-3 py-2">
              <div className="text-[10px] uppercase tracking-wide opacity-70">Orientační verdikt</div>
              <div className="text-sm font-semibold">{verdictLabel}</div>
            </div>
          ) : null}
          {typeof instantScore === "number" ? (
            <div className="rounded-lg border border-emerald-200/80 bg-white/60 px-3 py-2">
              <div className="text-[10px] uppercase tracking-wide opacity-70">Orientační skóre</div>
              <div className="text-sm font-semibold tabular-nums">{formatScore(instantScore)}/10</div>
            </div>
          ) : null}
        </div>
      ) : typeof instantScore === "number" ? (
        <div className="mt-2 text-[12px] font-medium">
          {isV2 ? "Orientační skóre" : "Skóre"}:{" "}
          <span className="tabular-nums font-semibold">{formatScore(instantScore)}/10</span>
        </div>
      ) : null}
      {drivers.length ? (
        <div className="mt-3 space-y-1.5">
          <div className="text-[10px] uppercase tracking-wide opacity-70">Hlavní důvody</div>
          <ul className="space-y-1 text-[12px] leading-relaxed list-disc pl-4">
            {drivers.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        </div>
      ) : null}
      {mainLimitation ? (
        <div className="mt-3 rounded-lg border border-amber-200/70 bg-amber-50/70 px-3 py-2 text-[12px] text-amber-950">
          <span className="font-medium">Hlavní limitace:</span> {mainLimitation}
        </div>
      ) : null}
      <p className="mt-3 whitespace-pre-wrap text-[13px] leading-relaxed opacity-95">{instantText}</p>
      {debugMeta.length ? (
        <details className="mt-2">
          <summary className="cursor-pointer text-[10px] text-slate-500 hover:text-slate-700">
            Technické detaily
          </summary>
          <div className="mt-1 text-[10px] opacity-75 font-mono">{debugMeta.join(" · ")}</div>
        </details>
      ) : null}
    </div>
  );
}

function extractFinalVerdictLabel(result) {
  const detailVerdict = String(result?.detail_verdict_category || "").trim().toLowerCase();
  if (detailVerdict && INSTANT_VERDICT_LABELS[detailVerdict]) {
    return INSTANT_VERDICT_LABELS[detailVerdict];
  }
  const scoreObj = result?.analysis_score && typeof result.analysis_score === "object" ? result.analysis_score : {};
  const label = String(scoreObj.decision_label || scoreObj.label || "").trim();
  if (label) return label;
  const score = scoreObj.decision_score ?? scoreObj.composite;
  if (typeof score === "number") return `${formatScore(score)}/10`;
  return null;
}

function ExploreDetailAnalysisSection({ result, children }) {
  const reconciliationMessage = resolveDetailReconciliationMessage(result);
  const directionConflict = Boolean(result?.detail_direction_conflict);
  const finalVerdict = extractFinalVerdictLabel(result);
  const finalScore =
    result?.analysis_score?.decision_score ?? result?.analysis_score?.composite ?? result?.score_value;
  return (
    <div className="space-y-3">
      <div className="rounded-xl border border-slate-200 bg-white px-4 py-3">
        <h2 className="text-sm font-semibold text-slate-900">Detailní analýza</h2>
        <p className="mt-1 text-[12px] text-slate-600">{DETAIL_SECTION_SUBTITLE}</p>
        {(finalVerdict || typeof finalScore === "number") ? (
          <div className="mt-3 grid gap-2 sm:grid-cols-2">
            {finalVerdict ? (
              <div className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
                <div className="text-[10px] uppercase tracking-wide text-slate-500">Finální verdikt</div>
                <div className="text-sm font-semibold text-slate-900">{finalVerdict}</div>
              </div>
            ) : null}
            {typeof finalScore === "number" ? (
              <div className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
                <div className="text-[10px] uppercase tracking-wide text-slate-500">Finální skóre</div>
                <div className="text-sm font-semibold text-slate-900 tabular-nums">{formatScore(finalScore)}/10</div>
              </div>
            ) : null}
          </div>
        ) : null}
        {reconciliationMessage ? (
          <p
            className={`mt-3 text-[12px] leading-relaxed ${
              directionConflict ? "text-red-900 font-medium" : "text-slate-600"
            }`}
          >
            {reconciliationMessage}
          </p>
        ) : null}
      </div>
      {children}
    </div>
  );
}

function formatUploadSize(n) {
  if (n == null || Number.isNaN(Number(n))) return "—";
  const v = Number(n);
  if (v < 1024) return `${v} B`;
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`;
  return `${(v / (1024 * 1024)).toFixed(1)} MB`;
}

/** Real-status labels/colors per lane outcome - "empty" is deliberately NOT styled as an error:
 * a source legitimately having no data for this query is a normal result, not a failure. */
const SOURCE_STATUS_DISPLAY = {
  running: { label: "prohledávám…", className: "text-teal-300/90" },
  ok: { label: null, className: "text-emerald-300/95" }, // label filled in with candidate count
  empty: { label: "bez výsledků", className: "text-slate-400/80" },
  skipped: { label: "přeskočeno", className: "text-slate-400/70" },
  timeout: { label: "vypršel časový limit", className: "text-amber-300/90" },
  error: { label: "chyba", className: "text-rose-400/90" },
};

function ExploreSectorScanLoader({ sourceStatuses, active, compact = false }) {
  const [elapsedSec, setElapsedSec] = useState(0);

  useEffect(() => {
    if (!active) {
      setElapsedSec(0);
      return undefined;
    }
    setElapsedSec(0);
    const countdown = setInterval(() => setElapsedSec((prev) => prev + 1), 1000);
    return () => clearInterval(countdown);
  }, [active]);

  if (!active) return null;

  const rows = Array.isArray(sourceStatuses) ? sourceStatuses : [];
  const totalCount = rows.length;
  const runningRows = rows.filter((row) => row.status === "running");
  const finishedCount = totalCount - runningRows.length;
  // No sources reported yet (still resolving segment/geo before discovery even starts) -
  // indeterminate progress instead of a misleading 0%.
  const progressKnown = totalCount > 0;
  const progressPct = progressKnown ? Math.round((finishedCount / totalCount) * 100) : 0;
  const headerLabel =
    totalCount === 0
      ? "Připravuji vyhledávání…"
      : runningRows.length > 0
        ? "Prohledávám katalogy…"
        : "Zpracovávám výsledky…";
  // Deliberately no fake ETA in seconds (see MANAGER_EXPLORER_AUDIT_V2.md section 4.1) - just
  // real elapsed time and real progress out of the real number of sources being searched.
  const countdownLabel = progressKnown
    ? `${finishedCount} z ${totalCount} zdrojů · ${elapsedSec} s`
    : `${elapsedSec} s`;

  return (
    <div
      className={`rounded-xl border border-teal-400/40 bg-slate-950 shadow-lg overflow-hidden ${
        compact ? "max-w-full" : "max-w-xl"
      }`}
      role="status"
      aria-live="polite"
      aria-label={`${headerLabel} — ${countdownLabel}`}
    >
      <div className="flex items-center justify-between gap-2 px-3 py-2 border-b border-teal-500/25 bg-slate-900/95">
        <div className="flex items-center gap-2 min-w-0">
          <span className="relative flex h-2 w-2 shrink-0">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-60" />
            <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-400" />
          </span>
          <span className="text-[11px] font-mono uppercase tracking-wide text-teal-300/95 truncate">
            {headerLabel}
          </span>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <span
            className="text-[10px] font-mono tabular-nums whitespace-nowrap text-emerald-300/90"
            title="Skutečný počet dokončených zdrojů a uplynulý čas"
          >
            {countdownLabel}
          </span>
          <Loader2 className="h-3.5 w-3.5 animate-spin text-teal-400" />
        </div>
      </div>

      <div className="h-0.5 bg-slate-800" aria-hidden>
        <div
          className={`h-full bg-gradient-to-r from-teal-600 to-emerald-400 transition-[width] duration-1000 ease-linear ${
            progressKnown ? "" : "animate-pulse"
          }`}
          style={{ width: progressKnown ? `${progressPct}%` : "20%" }}
        />
      </div>

      <div className="relative h-[132px] overflow-y-auto px-3 py-2 font-mono text-[11px] leading-relaxed">
        {rows.length === 0 ? (
          <div className="text-slate-500/80 text-[11px]">Zjišťuji segment a zdroje…</div>
        ) : (
          <ul className="space-y-1">
            {rows.map((row) => {
              const display = SOURCE_STATUS_DISPLAY[row.status] || SOURCE_STATUS_DISPLAY.running;
              const label =
                row.status === "ok" ? `${row.candidates} nalezeno` : display.label;
              return (
                <li key={row.source} className="flex items-center gap-2 truncate">
                  <span className="shrink-0 text-teal-400/90 w-[5.5rem] uppercase">{row.source}</span>
                  <span className={`truncate ${display.className}`}>{label}</span>
                </li>
              );
            })}
          </ul>
        )}
      </div>

      <div className="h-1 bg-slate-800 overflow-hidden">
        <div className="h-full w-1/3 bg-gradient-to-r from-transparent via-teal-400 to-transparent animate-[explore-scan_1.1s_ease-in-out_infinite]" />
      </div>
    </div>
  );
}

function ExploreSourceIssueSummary({ sourceStatuses }) {
  const issues = sourceStatusIssues(sourceStatuses);
  if (!issues.length) return null;

  return (
    <div
      className="rounded-xl border border-amber-300/70 bg-amber-50 px-4 py-3 text-sm text-amber-950"
      role="status"
      aria-live="polite"
    >
      <p className="font-medium">Výsledek je částečný</p>
      <p className="mt-1">
        {issues.map((row) => row.source).join(", ")} {issues.length === 1 ? "neodpověděl" : "neodpověděly"} správně.
        Ostatní dostupné zdroje byly zpracovány a výsledek zůstává použitelný.
      </p>
    </div>
  );
}

function ExploreSummarizeLoader({
  active,
  question,
  estimateSec,
  refreshing = false,
  serverHint = "",
}) {
  const initialEstimate = Math.max(75, Number(estimateSec) || 120);
  const [phaseIdx, setPhaseIdx] = useState(0);
  const [estimateTotalSec, setEstimateTotalSec] = useState(initialEstimate);
  const [secondsLeft, setSecondsLeft] = useState(initialEstimate);
  const [elapsedSec, setElapsedSec] = useState(0);

  const questionPreview = String(question || "").trim();
  const shortQuestion =
    questionPreview.length > 72 ? `${questionPreview.slice(0, 69).trim()}…` : questionPreview;

  useEffect(() => {
    if (!active) {
      setPhaseIdx(0);
      setEstimateTotalSec(initialEstimate);
      setSecondsLeft(initialEstimate);
      setElapsedSec(0);
      return undefined;
    }
    setEstimateTotalSec(initialEstimate);
    setSecondsLeft(initialEstimate);
    setElapsedSec(0);
    const slow = setInterval(() => {
      setPhaseIdx((p) => (p + 1) % SUMMARIZE_PHASES.length);
    }, 2400);
    const countdown = setInterval(() => {
      setElapsedSec((prev) => prev + 1);
      setSecondsLeft((prev) => Math.max(0, prev - 1));
    }, 1000);
    return () => {
      clearInterval(slow);
      clearInterval(countdown);
    };
  }, [active, initialEstimate]);

  useEffect(() => {
    if (!active || !serverHint) return;
    const adapted = adaptSummarizeEstimateFromServerHint(serverHint, {
      elapsedSec,
      initialEstimateSec: estimateTotalSec,
    });
    if (adapted != null && adapted > estimateTotalSec) {
      setEstimateTotalSec(adapted);
      setSecondsLeft((prev) => Math.max(prev, adapted - elapsedSec));
    }
  }, [active, serverHint, elapsedSec, estimateTotalSec]);

  if (!active) return null;

  const inOvertime = elapsedSec >= estimateTotalSec;
  const progressPct = inOvertime
    ? Math.min(98, SUMMARIZE_PROGRESS_CAP + Math.floor((elapsedSec - estimateTotalSec) / 3))
    : Math.min(
        SUMMARIZE_PROGRESS_CAP,
        Math.round((elapsedSec / estimateTotalSec) * SUMMARIZE_PROGRESS_CAP)
      );
  const countdownLabel = inOvertime
    ? `stále analyzuji · ${formatExploreEtaSec(elapsedSec).replace("~", "")}`
    : `zbývá ${formatExploreCountdownSec(secondsLeft)}`;
  const countdownHint = inOvertime
    ? "Analýza trvá déle než odhad — AI stále zpracovává data na serveru"
    : `Orientační odhad ${formatExploreEtaSec(estimateTotalSec)} — u stovek řad běžně 2–4 min`;

  return (
    <div
      className="rounded-xl border border-violet-400/45 bg-slate-950 shadow-lg overflow-hidden"
      role="status"
      aria-live="polite"
      aria-label={`Probíhá AI analýza, ${countdownLabel}`}
    >
      <div className="flex items-center justify-between gap-2 px-3 py-2 border-b border-violet-500/25 bg-slate-900/95">
        <div className="flex items-center gap-2 min-w-0">
          <span className="relative flex h-2 w-2 shrink-0">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-violet-400 opacity-60" />
            <span className="relative inline-flex h-2 w-2 rounded-full bg-violet-400" />
          </span>
          <span className="text-[11px] font-mono uppercase tracking-wide text-violet-200/95 truncate">
            {refreshing ? "Aktualizuji odpověď…" : SUMMARIZE_PHASES[phaseIdx]}
          </span>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <span
            className={`text-[10px] font-mono tabular-nums whitespace-nowrap ${
              inOvertime ? "text-amber-300/95" : "text-violet-200/90"
            }`}
            title={countdownHint}
          >
            {countdownLabel}
          </span>
          <Sparkles className="h-3.5 w-3.5 text-violet-300 animate-pulse" />
        </div>
      </div>

      {shortQuestion ? (
        <div className="px-3 py-1.5 border-b border-violet-500/15 bg-violet-950/40 text-[11px] text-violet-100/90 truncate">
          <span className="text-violet-400/90 font-medium">Dotaz:</span> {shortQuestion}
        </div>
      ) : null}
      {serverHint ? (
        <div className="px-3 py-1.5 border-b border-violet-500/15 bg-violet-950/30 text-[11px] text-violet-100/85">
          <span className="text-violet-300/90 font-medium">Server:</span> {serverHint}
        </div>
      ) : null}

      <div className="h-0.5 bg-slate-800" aria-hidden>
        <div
          className="h-full bg-gradient-to-r from-violet-600 via-fuchsia-500 to-emerald-400 transition-[width] duration-1000 ease-linear"
          style={{ width: `${progressPct}%` }}
        />
      </div>

      <div className="px-3 py-3 text-[11px] text-violet-100/85 leading-relaxed">
        AI skládá interpretaci z načtených dat — náhled jednotlivých řad se během analýzy nezobrazuje.
      </div>

      <div className="h-1 bg-slate-800 overflow-hidden">
        <div className="h-full w-1/3 bg-gradient-to-r from-transparent via-violet-400 to-transparent animate-[explore-scan_1.1s_ease-in-out_infinite]" />
      </div>
    </div>
  );
}

const ANALYSIS_MODE_LABELS = {
  single_sector: "Single-sector",
  sector_ecosystem: "Sector ecosystem",
  multi_sector_comparison: "Multi-sector comparison",
  driver_exposure_ranking: "Driver exposure ranking",
};

function formatAnalysisModeLabel(mode) {
  return ANALYSIS_MODE_LABELS[String(mode || "").trim()] || String(mode || "").trim();
}

function renderSectorBrief(row) {
  if (!row || typeof row !== "object") return "";
  const name = String(row.sector_name_cs || row.name_cs || row.name || row.sector_id || "").trim();
  const rel = String(row.relationship_type || "").trim();
  return rel ? `${name} (${rel})` : name;
}

function ManagerDiscoverySummary({ meta, expertMode = false }) {
  const linked = Array.isArray(meta?.linkedSectors) ? meta.linkedSectors : [];
  const ranking = Array.isArray(meta?.driverExposureRanking?.ranking) ? meta.driverExposureRanking.ranking : [];
  const comparisonSectors = Array.isArray(meta?.multiSectorComparison?.sectors) ? meta.multiSectorComparison.sectors : [];
  const missingData = Array.isArray(meta?.missingData)
    ? meta.missingData.filter((msg) => {
        const text = String(msg || "").trim().toLowerCase();
        if (!text) return false;
        return !(
          text.includes("needs_filters")
          || text.includes("vyžaduje doplnění filtrů")
          || text.includes("vyzaduje doplneni filtru")
          || text.includes("doplnění filtrů")
          || text.includes("doplneni filtru")
        );
      })
    : [];
  const showTechnicalDetails = Boolean(
    expertMode && (meta?.driverFocus || ranking.length || meta?.analysisMode || meta?.discoverySource)
  );
  if (
    !meta?.sectorLabel
    && !meta?.geoScope
    && !(meta?.topSources?.length)
    && !linked.length
    && !comparisonSectors.length
    && !missingData.length
    && !showTechnicalDetails
  ) {
    return null;
  }
  return (
    <div className="rounded-xl border border-teal-200/80 bg-teal-50/50 px-4 py-3 space-y-2">
      {meta.sectorLabel ? (
        <p className="text-sm text-teal-950">
          <span className="font-semibold">Manažerský sektor:</span> {meta.sectorLabel}
          {meta.subsegment ? ` · ${meta.subsegment}` : ""}
        </p>
      ) : null}
      {meta.geoScope ? (
        <p className="text-[11px] text-teal-900/85">
          <span className="font-medium">Geo:</span> {meta.geoScope}
        </p>
      ) : null}
      {linked.length ? (
        <div className="space-y-1">
          <div className="text-[11px] font-semibold text-slate-700">Navázané sektory</div>
          <div className="flex flex-wrap gap-1.5">
            {linked.slice(0, 6).map((row, idx) => (
              <span
                key={`${row.sector_id || row.sector_name_cs || idx}`}
                className="px-2 py-0.5 rounded-full bg-white border border-sky-200 text-sky-900 text-[11px]"
              >
                {renderSectorBrief(row)}
              </span>
            ))}
          </div>
        </div>
      ) : null}
      {comparisonSectors.length ? (
        <div className="space-y-1">
          <div className="text-[11px] font-semibold text-slate-700">Porovnávané sektory</div>
          <div className="flex flex-wrap gap-1.5">
            {comparisonSectors.slice(0, 6).map((row) => (
              <span
                key={row.sector_id}
                className="px-2 py-0.5 rounded-full bg-white border border-violet-200 text-violet-900 text-[11px]"
              >
                {row.sector_name_cs}
              </span>
            ))}
          </div>
        </div>
      ) : null}
      {missingData.length ? (
        <ul className="text-[11px] text-amber-900 list-disc pl-4 space-y-0.5">
          {missingData.slice(0, 4).map((m) => (
            <li key={m}>{m}</li>
          ))}
        </ul>
      ) : null}
      {showTechnicalDetails ? (
        <details className="rounded-lg border border-slate-200 bg-white/70 px-3 py-2">
          <summary className="cursor-pointer select-none text-[11px] font-semibold text-slate-700">
            Technické detaily
          </summary>
          <div className="mt-2 space-y-2">
            <div className="flex flex-wrap gap-2 text-[11px]">
              {meta.analysisMode ? (
                <span className="px-2 py-0.5 rounded-full bg-white border border-indigo-200 text-indigo-900">
                  Režim: {formatAnalysisModeLabel(meta.analysisMode)}
                </span>
              ) : null}
              {meta.driverFocus ? (
                <span className="px-2 py-0.5 rounded-full bg-white border border-amber-200 text-amber-900">
                  Driver: {meta.driverFocus}
                </span>
              ) : null}
              {meta.discoverySource ? (
                <span className="px-2 py-0.5 rounded-full bg-white border border-slate-200 text-slate-700">
                  Zdroj: {meta.discoverySource}
                </span>
              ) : null}
              {(meta.topSources || []).slice(0, 6).map((src) => (
                <span key={src} className="px-2 py-0.5 rounded-full bg-white border border-slate-200 text-slate-700 uppercase">
                  {src}
                </span>
              ))}
            </div>
            {ranking.length ? (
              <div className="space-y-1">
                <div className="text-[11px] font-semibold text-slate-700">Ranking expozice driveru</div>
                <ul className="text-[11px] text-slate-700 list-disc pl-4 space-y-0.5">
                  {ranking.slice(0, 4).map((row) => (
                    <li key={`${row.rank}-${row.sector_id}`}>
                      {row.sector_name_cs}: {Number(row.exposure_score ?? 0).toFixed(2)}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </div>
        </details>
      ) : null}
    </div>
  );
}

const CHART_PERIODS = [
  { id: "12", label: "12", count: 12 },
  { id: "36", label: "36", count: 36 },
  { id: "120", label: "120", count: 120 },
  { id: "all", label: "Vše", count: null },
];

const EXPLORE_CHART_THEME = buildChartTheme(null);

const CHART_LINE_COLORS = DASHBOARD_SERIES_COLORS;

const SECTION_CARD_STYLES = {
  economic_briefing: "border-blue-300/70 bg-blue-50/70",
  conclusion: "border-emerald-300/60 bg-emerald-50/80",
  company: "border-emerald-300/60 bg-emerald-50/60",
  sector: "border-border/70 bg-card/90",
  related_sectors: "border-violet-300/60 bg-violet-50/45",
  commodities: "border-amber-300/60 bg-amber-50/50",
  financial_markets: "border-fuchsia-300/60 bg-fuchsia-50/50",
  demographics: "border-rose-300/60 bg-rose-50/50",
  fx: "border-cyan-300/60 bg-cyan-50/50",
  macro: "border-teal-300/60 bg-teal-50/50",
  political_situation: "border-stone-400/60 bg-stone-50/60",
  neighbors: "border-violet-300/60 bg-violet-50/50",
  partners: "border-sky-300/60 bg-sky-50/50",
  eu: "border-indigo-300/60 bg-indigo-50/50",
  global: "border-slate-400/60 bg-slate-50/80",
};

function filterChartRows(rows, periodId) {
  const period = CHART_PERIODS.find((p) => p.id === periodId);
  if (!period || period.count == null) return rows;
  return rows.slice(-period.count);
}

function mergeChartLinesForPeriod(lines, periodId) {
  const filtered = (lines || [])
    .map((line, idx) => {
      const key = `y${idx}`;
      return {
        key,
        name: line.name,
        color: line.color || CHART_LINE_COLORS[idx % CHART_LINE_COLORS.length],
        rows: filterChartRows(line.rows, periodId),
      };
    })
    .filter((line) => line.rows.length > 0);
  if (!filtered.length) return { merged: [], series: [] };

  const byX = new Map();
  for (const line of filtered) {
    for (const row of line.rows) {
      const x = String(row.x || "").trim();
      if (!x) continue;
      if (!byX.has(x)) byX.set(x, { x });
      byX.get(x)[line.key] = row.y;
    }
  }
  const merged = [...byX.values()].sort((a, b) => compareChartPeriods(a.x, b.x));
  return { merged, series: filtered };
}

function compareCountryKey(code) {
  return `country:${String(code || "").trim().toUpperCase()}`;
}

function compareFxKey(id) {
  return `fx:${String(id || "").trim()}`;
}

function ExploreChartCard({ series, compareCountries = [], compareFxPairs = [], compact = false }) {
  const navigate = useNavigate();
  const fxPairsForChart = useMemo(() => {
    if (!Array.isArray(compareFxPairs) || !compareFxPairs.length) return [];
    if (!chartSupportsFxCompare(series)) return [];
    return compareFxPairs;
  }, [compareFxPairs, series]);

  const [period, setPeriod] = useState("36");
  const [expanded, setExpanded] = useState(false);
  const [interactiveOpen, setInteractiveOpen] = useState(false);
  const [activeCompare, setActiveCompare] = useState(() => new Set());
  const [compareLines, setCompareLines] = useState({});
  const [compareLoading, setCompareLoading] = useState(() => new Set());
  const [compareError, setCompareError] = useState("");

  useEffect(() => {
    if (!expanded || typeof document === "undefined") return undefined;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, [expanded]);

  const primaryCode = String(
    series.primaryCountryCode || series.compareRef?.primary_country_code || ""
  ).trim().toUpperCase();

  const compareOptions = useMemo(() => {
    const allowedRaw = series.compareRef?.allowed_compare_codes;
    const allowed = Array.isArray(allowedRaw) && allowedRaw.length
      ? new Set(
          allowedRaw
            .map((c) => String(c || "").trim().toUpperCase())
            .filter(Boolean)
        )
      : null;
    const seen = new Set();
    const out = [];
    const sourceType = String(series.compareRef?.source_type || series.source || "").trim().toLowerCase();
    const primaryImf = mapCompareCountryToImfCode(primaryCode);
    for (const row of filterCompareCountriesForSource(compareCountries, sourceType)) {
      const code = String(row?.code || "").trim().toUpperCase();
      if (!code || seen.has(code)) continue;
      if (allowed && !allowed.has(code)) continue;
      seen.add(code);
      if (primaryCode && code === primaryCode) continue;
      if (sourceType === "imf" && primaryImf && mapCompareCountryToImfCode(code) === primaryImf) continue;
      out.push({
        code,
        name: String(row?.name || code).trim(),
        scope: String(row?.scope || "").trim(),
        eurostatGeo: String(row?.eurostat_geo || "").trim().toUpperCase() || null,
      });
    }
    return out;
  }, [compareCountries, primaryCode, series.compareRef, series.source]);

  const canCompareCountries = Boolean(series.compareRef) && compareOptions.length > 0;

  const hasFxOverlay = useMemo(
    () => [...activeCompare].some((key) => String(key).startsWith("fx:")),
    [activeCompare]
  );

  const chartPlot = useMemo(() => {
    const primaryRows = hasFxOverlay ? indexChartRows(series.rows) : series.rows;
    const primaryName = hasFxOverlay ? `${series.name} (index 100)` : series.name;
    const lines = [
      {
        name: primaryName,
        rows: primaryRows,
        color: CHART_LINE_COLORS[0],
      },
    ];
    for (const key of activeCompare) {
      const cached = compareLines[key];
      if (cached?.rows?.length) {
        lines.push({
          name: cached.name,
          rows: cached.rows,
          color: cached.color,
        });
      }
    }
    return mergeChartLinesForPeriod(lines, period);
  }, [series.name, series.rows, period, activeCompare, compareLines, hasFxOverlay]);

  const toggleCompare = async (country) => {
    if (!series.compareRef) return;
    const code = String(country?.code || "").trim().toUpperCase();
    if (!code) return;
    setCompareError("");
    const key = compareCountryKey(code);
    if (activeCompare.has(key)) {
      setActiveCompare((prev) => {
        const next = new Set(prev);
        next.delete(key);
        return next;
      });
      return;
    }
    if (compareLines[key]?.rows?.length) {
      setActiveCompare((prev) => new Set(prev).add(key));
      return;
    }
    const body = buildExploreComparePreviewBody(series.compareRef, code, {
      eurostatGeo: country.eurostatGeo,
    });
    if (!body) {
      setCompareError("Srovnání pro tento ukazatel není k dispozici.");
      return;
    }
    setCompareLoading((prev) => new Set(prev).add(key));
    try {
      const { data } = await api.post("/catalog/preview", body, { timeout: 45000 });
      const normalized = normalizePreviewPayload(data, body.source_type);
      const rows = previewToChartRows(normalized);
      if (!rows.length) {
        const src = String(series.compareRef?.source_type || series.source || "").toLowerCase();
        if (src === "eurostat") {
          setCompareError(
            `${country.name} u tohoto Eurostat ukazatele není dostupné — zkuste jinou zemi ze seznamu (např. sousední stát v EU).`
          );
        } else {
          setCompareError(`Pro ${country.name} nejsou dostupná data.`);
        }
        return;
      }
      const colorIdx = (activeCompare.size + 1) % CHART_LINE_COLORS.length;
      setCompareLines((prev) => ({
        ...prev,
        [key]: {
          name: country.name,
          rows,
          color: CHART_LINE_COLORS[colorIdx],
        },
      }));
      setActiveCompare((prev) => new Set(prev).add(key));
    } catch (e) {
      const msg = formatApiErrorFromAxios(e) || "Načtení srovnání se nezdařilo.";
      if (msg.includes("není pro vybranou zemi")) {
        setCompareError(
          `${country.name} u tohoto ukazatele v ECB není dostupné — zkuste jinou zemi ze seznamu.`
        );
      } else {
        setCompareError(msg);
      }
    } finally {
      setCompareLoading((prev) => {
        const next = new Set(prev);
        next.delete(key);
        return next;
      });
    }
  };


  const toggleCompareFx = async (fxPair) => {
    const key = compareFxKey(fxPair?.id);
    if (!key || key === "fx:") return;
    setCompareError("");
    if (activeCompare.has(key)) {
      setActiveCompare((prev) => {
        const next = new Set(prev);
        next.delete(key);
        return next;
      });
      return;
    }
    if (compareLines[key]?.rows?.length) {
      setActiveCompare((prev) => new Set(prev).add(key));
      return;
    }
    const body = buildExploreFxOverlayPreviewBody(fxPair);
    if (!body) {
      setCompareError("Kurzový pár nelze načíst.");
      return;
    }
    setCompareLoading((prev) => new Set(prev).add(key));
    try {
      const { data } = await api.post("/catalog/preview", body, { timeout: 45000 });
      const normalized = normalizePreviewPayload(data, body.source_type);
      const rows = indexChartRows(previewToChartRows(normalized));
      if (!rows.length) {
        setCompareError(`Pro ${fxPair.label || fxPair.pair} nejsou dostupná data.`);
        return;
      }
      const colorIdx = (activeCompare.size + 1) % CHART_LINE_COLORS.length;
      const label = String(fxPair.label || fxPair.pair || "Kurz").trim();
      setCompareLines((prev) => ({
        ...prev,
        [key]: {
          name: `${label} (index 100)`,
          rows,
          color: CHART_LINE_COLORS[colorIdx],
        },
      }));
      setActiveCompare((prev) => new Set(prev).add(key));
    } catch (e) {
      setCompareError(formatApiErrorFromAxios(e) || "Načtení kurzu se nezdařilo.");
    } finally {
      setCompareLoading((prev) => {
        const next = new Set(prev);
        next.delete(key);
        return next;
      });
    }
  };

  const chartSource = useMemo(() => formatExploreChartSource(series), [series]);
  const conceptExplainMeta = useMemo(
    () => buildExploreChartConceptExplainMeta(series, series.rows),
    [series],
  );
  const handleStaleFindInCatalog = useCallback(
    (query) => {
      const q = String(query || "").trim();
      if (!q) return;
      navigate(`/search/catalog?q=${encodeURIComponent(q)}`);
    },
    [navigate],
  );

  const roleLabel =
    series.summarizeRole === "sector"
      ? "Odvětví"
      : series.summarizeRole === "commodity"
        ? "Komodita"
        : series.summarizeRole === "demographics"
          ? "Demografie"
          : series.summarizeRole === "fx"
            ? "Kurzy"
            : series.summarizeRole === "partner"
          ? "Partner"
          : series.summarizeRole === "neighbor"
            ? "Soused"
            : series.summarizeRole === "eu"
              ? "Region / EU"
              : series.summarizeRole === "continent"
                ? "Region"
                : series.summarizeRole === "global"
                  ? "Globální"
                  : series.summarizeRole === "macro"
                    ? "Makro"
                    : null;

  return (
    <>
      <div
        className="explore-dashboard-chart-card widget-panel-white widget-infographic-light flex flex-col overflow-hidden h-full"
        data-explore-chart-export
        data-chart-title={series.name}
        data-chart-note={series.chartNote || ""}
        data-chart-source={chartSource.line}
      >
        <div
          className="px-3 py-2.5 border-b shrink-0"
          style={{ background: EXPLORE_CHART_THEME.headerBg, borderColor: EXPLORE_CHART_THEME.border }}
        >
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0 flex-1">
              <h4
                className="text-[11px] sm:text-xs font-extrabold leading-snug tracking-wide uppercase line-clamp-3 break-words"
                style={{ color: EXPLORE_CHART_THEME.accent }}
                title={series.name}
              >
                {series.name}
              </h4>
              <p className="text-[10px] text-slate-600 leading-snug mt-1" title={chartSource.line}>
                <span className="font-semibold text-slate-700">Zdroj:</span> {chartSource.label}
                {chartSource.datasetId ? (
                  <span className="text-muted-foreground"> · {chartSource.datasetId}</span>
                ) : null}
              </p>
              <div className="flex flex-wrap items-center gap-1.5 mt-1">
                {roleLabel ? (
                  <span className="text-[9px] uppercase tracking-wider text-slate-500">{roleLabel}</span>
                ) : null}
                {series.geoDisplayLabel ? (
                  <span className="text-[9px] font-medium uppercase tracking-wide px-1.5 py-0.5 rounded bg-slate-100 text-slate-600">
                    {series.geoDisplayLabel}
                  </span>
                ) : null}
              </div>
            </div>
            <div className="flex items-center gap-1 shrink-0">
              {conceptExplainMeta ? (
                <SeriesConceptExplainTrigger
                  meta={conceptExplainMeta}
                  onFindInCatalogSearch={handleStaleFindInCatalog}
                  buttonClassName="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-md border border-violet-200/70 bg-violet-50/80 text-violet-800 hover:bg-violet-100"
                />
              ) : null}
              <button
                type="button"
                className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-md border border-sky-200/80 bg-sky-50 text-sky-800 hover:bg-sky-100"
                title="Otevřít interaktivní graf, dimenze a analytiku"
                aria-label="Otevřít interaktivní graf, dimenze a analytiku"
                data-export-ignore="true"
                onClick={() => setInteractiveOpen(true)}
              >
                <SlidersHorizontal className="h-3.5 w-3.5" />
              </button>
              <button
                type="button"
                className="shrink-0 rounded-md p-1 text-slate-500 hover:bg-white/70 hover:text-slate-800"
                title="Zvětšit graf"
                data-export-ignore="true"
                onClick={() => setExpanded(true)}
              >
                <Expand className="h-3.5 w-3.5" />
              </button>
            </div>
          </div>
        </div>

        <div className="px-3 py-2 border-b border-border/40 shrink-0 space-y-2" data-export-ignore="true">
          <div className="flex flex-wrap items-center gap-1">
            <span className="text-[9px] uppercase tracking-wider text-slate-400 mr-0.5">Období</span>
            {CHART_PERIODS.map((p) => (
              <button
                key={p.id}
                type="button"
                className={`h-5 px-1.5 text-[10px] rounded border font-mono transition-colors ${
                  period === p.id
                    ? "chip-mint border-transparent font-semibold"
                    : "border-border/60 text-slate-600 hover:bg-[hsl(var(--primary-soft))]"
                }`}
                onClick={() => setPeriod(p.id)}
              >
                {p.label}
              </button>
            ))}
          </div>
          {canCompareCountries ? (
            <div className="flex flex-wrap gap-1">
              <span className="text-[9px] uppercase tracking-wider text-slate-400 self-center mr-0.5">Srovnat</span>
              {compareOptions.map((country) => {
                const cKey = compareCountryKey(country.code);
                const active = activeCompare.has(cKey);
                const loading = compareLoading.has(cKey);
                return (
                  <button
                    key={country.code}
                    type="button"
                    disabled={loading}
                    title={active ? "Odebrat ze srovnání" : "Přidat ke srovnání"}
                    className={`h-5 px-1.5 text-[10px] rounded border font-mono ${
                      active
                        ? "chip-mint border-transparent font-semibold"
                        : "border-border/60 text-slate-600 hover:bg-[hsl(var(--primary-soft))]"
                    }`}
                    onClick={() => void toggleCompare(country)}
                  >
                    {loading ? "…" : active ? `− ${country.name}` : `+ ${country.name}`}
                  </button>
                );
              })}
            </div>
          ) : null}
          {fxPairsForChart.length > 0 ? (
            <div className="flex flex-wrap gap-1">
              <span className="text-[9px] uppercase tracking-wider text-slate-400 self-center mr-0.5">Kurzy</span>
              {fxPairsForChart.map((fx) => {
                const fKey = compareFxKey(fx.id);
                const active = activeCompare.has(fKey);
                const loading = compareLoading.has(fKey);
                return (
                  <button
                    key={fx.id || fx.pair}
                    type="button"
                    disabled={loading}
                    title={active ? "Odebrat kurz ze srovnání" : "Přidat kurz (index 100)"}
                    className={`h-5 px-1.5 text-[10px] rounded border font-mono ${
                      active
                        ? "chip-mint border-transparent font-semibold"
                        : "border-border/60 text-slate-600 hover:bg-[hsl(var(--primary-soft))]"
                    }`}
                    onClick={() => void toggleCompareFx(fx)}
                  >
                    {loading ? "…" : active ? `− ${fx.label || fx.pair}` : `+ ${fx.label || fx.pair}`}
                  </button>
                );
              })}
            </div>
          ) : null}
          {compareError ? <p className="text-[10px] text-amber-800">{compareError}</p> : null}
        </div>

        <div
          className="flex-1 px-2 py-2 min-h-0 flex flex-col gap-2"
          style={{ background: EXPLORE_CHART_THEME.bodyBg }}
        >
          {conceptExplainMeta ? (
            <ChartStaleDataNotice
              meta={conceptExplainMeta}
              rows={series.rows}
              timeField="x"
              valueField="y"
              onFindInCatalogSearch={handleStaleFindInCatalog}
            />
          ) : null}
          <ExploreReportChart
            merged={chartPlot.merged}
            series={chartPlot.series}
            height={compact ? 156 : 208}
            unit={hasFxOverlay ? "index 100" : series.unit || ""}
            compact={compact}
          />
        </div>

        {series.chartNote ? (
          <div
            className="shrink-0 border-t px-3 py-2 text-[10px] leading-relaxed text-slate-600 italic"
            style={{ background: EXPLORE_CHART_THEME.captionBg, borderColor: EXPLORE_CHART_THEME.border }}
          >
            <ExploreCommentText text={series.chartNote} />
          </div>
        ) : null}
      </div>

      {expanded && typeof document !== "undefined" ? createPortal((
        <div
          className="fixed inset-0 z-[420] flex items-center justify-center bg-slate-950/60 p-3 sm:p-5"
          role="dialog"
          aria-modal="true"
          aria-label={`Graf: ${series.name}`}
          onClick={() => setExpanded(false)}
        >
          <div
            className="flex h-[min(92vh,820px)] w-[min(96vw,1440px)] flex-col explore-dashboard-chart-card widget-panel-white widget-infographic-light overflow-hidden shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div
              className="px-4 py-3 border-b flex items-start justify-between gap-3"
              style={{ background: EXPLORE_CHART_THEME.headerBg, borderColor: EXPLORE_CHART_THEME.border }}
            >
              <div className="min-w-0">
                <h3
                  className="text-sm font-extrabold uppercase tracking-wide leading-snug"
                  style={{ color: EXPLORE_CHART_THEME.accent }}
                >
                  {series.name}
                </h3>
                <p className="text-[11px] text-slate-600 mt-1" title={chartSource.line}>
                  <span className="font-semibold text-slate-700">Zdroj:</span> {chartSource.label}
                  {chartSource.datasetId ? (
                    <span className="text-muted-foreground"> · {chartSource.datasetId}</span>
                  ) : null}
                </p>
              </div>
              <div className="flex items-center gap-1 shrink-0">
                {conceptExplainMeta ? (
                  <SeriesConceptExplainTrigger
                    meta={conceptExplainMeta}
                    onFindInCatalogSearch={handleStaleFindInCatalog}
                  />
                ) : null}
                <button
                  type="button"
                  className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-sky-200 bg-sky-50 text-sky-800 hover:bg-sky-100"
                  title="Otevřít interaktivní graf, dimenze a analytiku"
                  aria-label="Otevřít interaktivní graf, dimenze a analytiku"
                  onClick={() => {
                    setExpanded(false);
                    setInteractiveOpen(true);
                  }}
                >
                  <SlidersHorizontal className="h-4 w-4" />
                </button>
                <button
                  type="button"
                  className="rounded-md p-1.5 text-slate-500 hover:bg-white/70"
                  onClick={() => setExpanded(false)}
                >
                  <X className="h-4 w-4" />
                </button>
              </div>
            </div>
            <div className="px-4 py-3 space-y-3 border-b border-border/40">
              <div className="flex flex-wrap gap-1">
                <span className="text-[9px] uppercase tracking-wider text-slate-400 self-center mr-0.5">Období</span>
                {CHART_PERIODS.map((p) => (
                  <button
                    key={`modal-${p.id}`}
                    type="button"
                    className={`h-6 px-2 text-[10px] rounded border font-mono ${
                      period === p.id
                        ? "chip-mint border-transparent font-semibold"
                        : "border-border/60 text-slate-600 hover:bg-[hsl(var(--primary-soft))]"
                    }`}
                    onClick={() => setPeriod(p.id)}
                  >
                    {p.label}
                  </button>
                ))}
              </div>
              {canCompareCountries ? (
                <div className="flex flex-wrap gap-1">
                  <span className="text-[10px] text-muted-foreground self-center mr-0.5">Srovnat:</span>
                  {compareOptions.map((country) => {
                    const cKey = compareCountryKey(country.code);
                    const active = activeCompare.has(cKey);
                    const loading = compareLoading.has(cKey);
                    return (
                      <button
                        key={`modal-${country.code}`}
                        type="button"
                        disabled={loading}
                        className={`h-6 px-2 text-[10px] rounded border font-mono ${
                          active
                            ? "chip-mint border-transparent font-semibold"
                            : "border-border/60 text-slate-600 hover:bg-[hsl(var(--primary-soft))]"
                        }`}
                        onClick={() => void toggleCompare(country)}
                      >
                        {loading ? "…" : active ? `− ${country.name}` : `+ ${country.name}`}
                      </button>
                    );
                  })}
                </div>
              ) : null}
            </div>
            <div className="min-h-[400px] flex-1 px-3 py-3" style={{ background: EXPLORE_CHART_THEME.bodyBg }}>
              <ExploreReportChart
                merged={chartPlot.merged}
                series={chartPlot.series}
                height="100%"
                size={CHART_SIZE_VARIANTS.FULLSCREEN}
                unit={hasFxOverlay ? "index 100" : series.unit || ""}
              />
            </div>
            {series.chartNote ? (
              <div
                className="border-t px-4 py-2.5 text-[11px] leading-relaxed text-slate-600 italic"
                style={{ background: EXPLORE_CHART_THEME.captionBg, borderColor: EXPLORE_CHART_THEME.border }}
              >
                <ExploreCommentText text={series.chartNote} />
              </div>
            ) : null}
          </div>
        </div>
      ), document.body) : null}
      <ExploreInteractiveSeriesDetail
        open={interactiveOpen}
        series={series}
        onClose={() => setInteractiveOpen(false)}
      />
    </>
  );
}

function chartSeriesList(chartPayload) {
  if (!chartPayload || typeof chartPayload !== "object" || !Array.isArray(chartPayload.series)) {
    return [];
  }
  return chartPayload.series.flatMap((s, idx) => {
    if (!s || typeof s !== "object" || !Array.isArray(s.data) || !s.data.length) return [];
    const name = String(s.name || "").trim() || `Řada ${idx + 1}`;
    const rows = s.data
      .filter((p) => p && typeof p === "object")
      .map((p) => ({
        x: String(p.x ?? "").trim(),
        y: Number(p.y),
      }))
      .filter((p) => p.x && !Number.isNaN(p.y))
      .sort((a, b) => compareChartPeriods(a.x, b.x));
    if (rows.length < 2) return [];
    return [{
      name,
      rows,
      source: String(s.source || "").trim(),
      sourceLabel: String(s.source_label || "").trim(),
      setId: String(s.set_id || "").trim(),
      seriesId: String(s.series_id || s.set_id || "").trim(),
      id: String(s.id || "").trim(),
      metricId: String(s.metric_id || "").trim(),
      labelCs: String(s.label_cs || s.label || "").trim(),
      labelEn: String(s.label_en || "").trim(),
      aliases: Array.isArray(s.aliases) ? s.aliases.map((x) => String(x)) : [],
      geo: String(s.geo || s.country_code || s.primary_country_code || "").trim(),
      segmentId: String(s.segment_id || s.linked_sector_id || "").trim(),
      signal: String(s.signal_id || s.signal || "").trim(),
      unit: String(s.unit || "").trim(),
      freq: String(s.freq || s.frequency || "").trim(),
      summarizeRole: String(s.summarize_role || "").trim(),
      contextScope: String(s.context_scope || "").trim(),
      chartSectionId: String(s.chart_section_id || "").trim(),
      compareCapable: Boolean(s.compare_capable),
      compareRef: s.compare_ref && typeof s.compare_ref === "object" ? s.compare_ref : null,
      primaryCountryCode: String(
        s.primary_country_code || s.compare_ref?.primary_country_code || ""
      ).trim(),
      geoDisplayLabel: String(s.geo_display_label || "").trim(),
      chartNote: String(s.chart_note || "").trim(),
    }];
  });
}

function normalizeKeyNumberItems(keyNumbers) {
  if (keyNumbers == null) return [];

  if (typeof keyNumbers === "string") {
    const text = keyNumbers.trim();
    if (!text) return [];
    const parts = text.split(/\n|;\s*/).map((x) => x.trim()).filter(Boolean);
    if (parts.length <= 1) {
      const colon = text.match(/^([^:–—-]+)[:–—-]\s*(.+)$/);
      if (colon) return [{ label: colon[1].trim(), value: colon[2].trim() }];
      return [{ label: "", value: text }];
    }
    return parts.map((part, idx) => {
      const colon = part.match(/^([^:–—-]+)[:–—-]\s*(.+)$/);
      if (colon) return { label: colon[1].trim(), value: colon[2].trim() };
      return { label: `Položka ${idx + 1}`, value: part };
    });
  }

  if (Array.isArray(keyNumbers)) {
    return keyNumbers.flatMap((item, idx) => {
      if (item == null) return [];
      if (typeof item === "object" && !Array.isArray(item)) {
        const label = String(item.label || item.name || item.indicator || "").trim();
        const value = String(item.value ?? "").trim();
        const description = String(item.description || item.meaning || item.caption || "").trim();
        if (label && value) {
          return [{ label, value, description }];
        }
        return Object.entries(item)
          .filter(([k, v]) => v != null && String(v).trim() && !["description", "meaning", "caption", "period", "trend", "trend_dir"].includes(k))
          .map(([lbl, val]) => ({
            label: String(lbl),
            value: String(val),
            description: description || "",
          }));
      }
      if (typeof item === "string") {
        const text = item.trim();
        if (!text) return [];
        const colon = text.match(/^([^:–—-]+)[:–—-]\s*(.+)$/);
        if (colon) return [{ label: colon[1].trim(), value: colon[2].trim(), description: "" }];
        return [{ label: `Položka ${idx + 1}`, value: text, description: "" }];
      }
      return [{ label: `Položka ${idx + 1}`, value: String(item), description: "" }];
    });
  }

  if (typeof keyNumbers === "object") {
    return Object.entries(keyNumbers)
      .filter(([, value]) => value != null && String(value).trim())
      .map(([label, value]) => ({ label: String(label), value: String(value) }));
  }

  return [];
}

function resolveSummarizeLimitations(result) {
  const base = String(result?.limitations || result?.limitations_cz || "").trim();
  if (result?.fast_mode && !base.includes(EXPLORE_SUMMARIZE_FAST_MODE_LIMITATION)) {
    return [base, EXPLORE_SUMMARIZE_FAST_MODE_LIMITATION].filter(Boolean).join(" ");
  }
  return base;
}

function matchChartSeriesForRow(row, chartSeries) {
  const normalized = normalizeUsedSeriesRow(row);
  if (!Array.isArray(chartSeries) || !chartSeries.length) return null;
  const expectedId = String(normalized.series_id || normalized.set_id || normalized.dataset_id || "").trim();
  const expectedSource = String(normalized.source_type || "").trim().toLowerCase();
  if (expectedId) {
    const exact = chartSeries.find((series) => {
      const ids = [series.seriesId, series.setId, series.id, series.metricId]
        .map((value) => String(value || "").trim())
        .filter(Boolean);
      const source = String(series.source || "").trim().toLowerCase();
      return ids.includes(expectedId) && (!expectedSource || !source || source === expectedSource);
    });
    if (exact) return exact;
  }
  const ref = {
    series_id: normalized.series_id || normalized.dataset_id || normalized.set_id || normalized.title,
    chart_payload_set_id: normalized.dataset_id || normalized.set_id || null,
    source_series_id: normalized.series_id || null,
    label: normalized.title || null,
    accept_fuzzy_match: true,
  };
  const { series } = matchSeriesRefToChartSeries(ref, ref.series_id, chartSeries);
  return series || null;
}

function ExploreSeriesCoverageRow({ row, chartSeries, compareCountries, compareFxPairs }) {
  const [open, setOpen] = useState(false);
  const normalized = useMemo(() => normalizeUsedSeriesRow(row), [row]);
  const title = String(normalized.title || "Řada").trim();
  const matched = useMemo(
    () => matchChartSeriesForRow(normalized, chartSeries),
    [normalized, chartSeries]
  );
  const failed = String(normalized.status || "") === "failed";
  const canOpen = Boolean(matched);

  const toggleOpen = () => {
    if (canOpen) setOpen((prev) => !prev);
  };

  return (
    <div
      className={`rounded-lg border px-3 py-2 ${
        failed ? "border-amber-300/70 bg-amber-50/80" : "border-border/60 bg-white/80"
      } ${canOpen ? "hover:border-teal-300/70" : ""}`}
    >
      <div className="flex items-start justify-between gap-2">
        <button
          type="button"
          className={`min-w-0 flex-1 text-left ${canOpen ? "cursor-pointer hover:opacity-90" : "cursor-default"}`}
          onClick={toggleOpen}
          disabled={!canOpen}
          aria-expanded={canOpen ? open : undefined}
        >
          <div className="font-semibold text-slate-800 leading-snug">{title}</div>
          {normalized.fact ? <div className="text-muted-foreground mt-0.5 leading-snug">{normalized.fact}</div> : null}
          {normalized.question_link ? (
            <div className="text-slate-600 mt-1 leading-snug">{normalized.question_link}</div>
          ) : null}
          {failed && normalized.reason ? (
            <div className="text-amber-900 mt-0.5">Důvod: {normalized.reason}</div>
          ) : null}
        </button>
        {canOpen ? (
          <button
            type="button"
            className="shrink-0 inline-flex items-center gap-0.5 text-[10px] font-semibold text-teal-800 underline-offset-2 hover:underline"
            onClick={toggleOpen}
            aria-expanded={open}
          >
            {open ? "Skrýt graf" : "Graf"}
            <ChevronDown className={`h-3 w-3 transition-transform ${open ? "rotate-180" : ""}`} />
          </button>
        ) : (
          <span className="shrink-0 text-[10px] text-slate-400">bez grafu</span>
        )}
      </div>
      {open && matched ? (
        <div className="mt-3 rounded-lg border border-teal-200/60 bg-white overflow-x-auto">
          <ExploreChartCard
            series={matched}
            compareCountries={compareCountries}
            compareFxPairs={compareFxPairs}
            compact
          />
        </div>
      ) : null}
    </div>
  );
}

function UsedSeriesGroup({
  title,
  items,
  coverageIndex,
  chartSeries,
  compareCountries,
  compareFxPairs,
  keyPrefix,
  itemClassName = "",
}) {
  if (!Array.isArray(items) || !items.length) return null;
  const sortedRows = items
    .map((raw) => enrichUsedSeriesRow(raw, coverageIndex))
    .sort((a, b) =>
      String(a?.title || a?.indicator_name || "").localeCompare(
        String(b?.title || b?.indicator_name || ""),
        "cs",
        { sensitivity: "base" }
      )
    );
  return (
    <div>
      <div className="text-[11px] font-semibold uppercase tracking-wide text-slate-500 mb-1.5">
        {title} ({items.length})
      </div>
      <ul className="space-y-1.5">
        {sortedRows.map((row, idx) => {
          return (
            <li key={`${keyPrefix}-${row.series_id || row.title}-${idx}`} className={itemClassName || undefined}>
              <ExploreSeriesCoverageRow
                row={row}
                chartSeries={chartSeries}
                compareCountries={compareCountries}
                compareFxPairs={compareFxPairs}
              />
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function mergeExploreSeriesRefs(baseRows, extraRows) {
  const seenIdx = new Map();
  const out = [];
  const push = (row) => {
    const key = seriesKey(row);
    if (!key) return;
    if (seenIdx.has(key)) {
      // Výslovný výběr uživatele povýší ochranu i na již přítomný (planner) řádek.
      if (row.user_selected) out[seenIdx.get(key)].user_selected = true;
      return;
    }
    seenIdx.set(key, out.length);
    out.push(row);
  };
  for (const row of baseRows || []) push(row);
  for (const row of extraRows || []) {
    push({
      source: row.source_type || row.source,
      dataset_id: row.set_id || row.dataset_id,
      indicator_name: row.title || row.indicator_name || row.set_id,
      filters_used: row.query_params || row.filters_used || {},
      // user_selected MUSÍ přežít merge — jinak by se ztratila 100% ochrana výběru.
      user_selected: Boolean(row.user_selected),
    });
  }
  return out;
}

// Jen grafy, které se reálně vykreslí — rozklad skóre bez řádků a řady bez
// payloadu vrací null. Sekce s hlavičkou a nulou grafů je horší než žádná.
function managerRenderableCharts(chartPlan) {
  if (chartPlan?.mode !== "manager") return [];
  return (chartPlan.validCharts || []).filter((row) =>
    row.isScoreBreakdown ? (row.breakdownRows || []).length > 0 : Boolean(row.renderPayload)
  );
}

function ExploreManagerRecommendedCharts({ result, chartSeries }) {
  const chartPlan = useMemo(
    () => resolveManagerChartDisplayPlan(result, chartSeries),
    [result, chartSeries]
  );

  const renderableCharts = managerRenderableCharts(chartPlan);
  if (!renderableCharts.length) return null;

  return (
    <section className="explore-report-charts-block space-y-4">
      <ExploreManagerRecommendedChartsHeader />
      <div className="grid gap-4 sm:grid-cols-2">
        {renderableCharts.map((row) => {
          const spec = row.spec || {};
          if (row.isScoreBreakdown) {
            const breakdownRows = row.breakdownRows || [];
            if (!breakdownRows.length) return null;
            return (
              <div
                key={spec.chart_id || "score_breakdown"}
                className="explore-report-panel widget-panel-white widget-infographic-light px-5 py-4 space-y-2 sm:col-span-2"
              >
                <h4 className="text-xs font-extrabold uppercase tracking-[0.1em] text-[hsl(218_65%_28%)]">
                  {spec.title || "Rozklad skóre"}
                </h4>
                {spec.manager_message ? (
                  <p className="text-[11px] text-slate-600 leading-relaxed">{spec.manager_message}</p>
                ) : null}
                <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                  {breakdownRows.map((br) => (
                    <div key={br.id} className="rounded-lg border border-border/60 bg-white/80 px-3 py-2">
                      <div className="text-[10px] font-semibold text-slate-600">{br.label}</div>
                      <div className="text-lg font-bold tabular-nums text-slate-900">
                        {br.score?.toFixed?.(1) ?? br.score}/10
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            );
          }

          if (!row.renderPayload) return null;

          return (
            <div key={spec.chart_id || row.renderPayload.title} className="space-y-1">
              <ExploreManagerChartCard renderPayload={row.renderPayload} />
            </div>
          );
        })}
      </div>
      {process.env.NODE_ENV === "development" && chartPlan.invalidCharts?.length ? (
        <details className="rounded-xl border border-border/60 bg-muted/20 px-4 py-3 text-[11px] text-slate-600" data-export-ignore="true">
          <summary className="cursor-pointer font-medium">Debug: nespárované doporučené grafy ({chartPlan.invalidCharts.length})</summary>
          <ul className="mt-2 space-y-1 list-disc pl-4">
            {chartPlan.invalidCharts.map((row) => (
              <li key={row.spec?.chart_id || row.validation?.reason}>
                <span className="font-medium">{row.validation?.chart_id || row.spec?.chart_id || row.spec?.title}</span>
                {row.validation?.chart_type ? ` · ${row.validation.chart_type}` : ""}
                {`: ${row.validation?.reason}`}
                {row.validation?.missing_series_ids?.length
                  ? ` · chybí ${row.validation.missing_series_ids.join(", ")}`
                  : ""}
                {row.validation?.matched_series_ids?.length
                  ? ` · spárováno ${row.validation.matched_series_ids.join(", ")}`
                  : ""}
                {Number.isFinite(row.validation?.common_observation_count)
                  ? ` · společných pozorování ${row.validation.common_observation_count}`
                  : ""}
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </section>
  );
}

const WEB_SOURCE_TIER_BADGE = {
  official: { label: "Oficiální zdroj", className: "bg-emerald-100 text-emerald-800" },
  press: { label: "Tisk", className: "bg-amber-100 text-amber-800" },
};

/**
 * Manager Explorer's web-research fallback (backend: WebResearchService#researchSectorContext,
 * triggered only when the catalog found no indicators at all) - a distinct report section, never
 * mixed into sector_indicators/macro_indicators, so the user always sees what came from official
 * catalog data vs. what came from the web. Also renders a plain status line when web research was
 * attempted but found nothing, so the app is honest about having tried rather than staying silent.
 */
function ExploreWebSourcesSection({ result }) {
  const webSources = Array.isArray(result?.web_sources) ? result.web_sources : [];
  const status = result?.web_research_status;

  if (!webSources.length) {
    if (status === "empty" || status === "failed") {
      return (
        <div className="explore-report-panel widget-panel-white widget-infographic-light px-5 py-4 space-y-1">
          <h3 className="explore-report-section-title">Zjištění z webu (mimo interní katalog)</h3>
          <p className="text-sm text-slate-600 leading-relaxed">
            Katalog k tomuto dotazu nenašel vlastní data a hledání na webu nenašlo nic dostatečně doloženého.
          </p>
        </div>
      );
    }
    return null;
  }

  return (
    <section className="explore-report-panel widget-panel-white widget-infographic-light px-5 py-5 space-y-3">
      <h3 className="explore-report-section-title">Zjištění z webu (mimo interní katalog)</h3>
      <p className="text-[11px] text-slate-500">
        Katalog k tomuto dotazu nenašel vlastní data — následující zjištění pochází z webu a je odděleno od
        ověřených katalogových čísel výše.
      </p>
      <div className="space-y-2">
        {webSources.slice(0, 8).map((item, idx) => {
          const badge = WEB_SOURCE_TIER_BADGE[item?.source_tier];
          const sourceUrls = Array.isArray(item?.source_urls) ? item.source_urls : [];
          return (
            <div
              key={`${item?.url || "web-source"}-${idx}`}
              className="rounded-lg border border-sky-100 bg-sky-50/40 px-3 py-2.5 space-y-1"
            >
              <div className="flex items-start justify-between gap-2">
                <div className="text-[13px] font-semibold text-slate-900">{item?.title}</div>
                {badge ? (
                  <span className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-medium ${badge.className}`}>
                    {badge.label}
                  </span>
                ) : null}
              </div>
              {item?.value_text ? (
                <div className="text-[13px] text-slate-800">
                  {item.value_text}
                  {item?.period ? ` (${item.period})` : ""}
                </div>
              ) : null}
              {item?.summary_cz ? <div className="text-[12px] text-slate-600">{item.summary_cz}</div> : null}
              {sourceUrls.length ? (
                <details className="mt-1 rounded-lg border border-sky-100 bg-white/60 px-2 py-1.5">
                  <summary className="cursor-pointer text-[10px] font-semibold uppercase tracking-wide text-sky-900">
                    Zdroje ({sourceUrls.length})
                  </summary>
                  <div className="mt-1 space-y-1">
                    {sourceUrls.slice(0, 4).map((url, srcIdx) => (
                      <a
                        key={`${url}-${srcIdx}`}
                        href={String(url)}
                        target="_blank"
                        rel="noreferrer noopener"
                        className="block truncate text-[11px] font-medium text-sky-800 underline decoration-sky-300 underline-offset-2 hover:text-sky-950"
                        title={String(url)}
                      >
                        {url}
                      </a>
                    ))}
                  </div>
                </details>
              ) : null}
            </div>
          );
        })}
      </div>
    </section>
  );
}

function ExploreReportCharts({ result, chartSeries, compareCountries, compareFxPairs, chartsMismatch = false }) {
  const chartPlan = useMemo(
    () => resolveManagerChartDisplayPlan(result, chartSeries),
    [result, chartSeries]
  );

  if (chartsMismatch) {
    return (
      <div className="rounded-xl border border-amber-200/80 bg-amber-50/90 px-4 py-3 text-sm text-amber-950 leading-relaxed">
        Pro hlavní segment nebyly načteny relevantní grafy.
      </div>
    );
  }

  if (chartPlan.mode === "manager" && managerRenderableCharts(chartPlan).length) {
    return (
      <ExploreManagerRecommendedCharts
        result={result}
        chartSeries={chartSeries}
        compareCountries={compareCountries}
        compareFxPairs={compareFxPairs}
      />
    );
  }

  // Doporučené grafy se nepodařilo sestavit → ukaž rovnou základní grafy
  // načtených řad, bez technických omluv.
  if (
    (chartPlan.mode === "manager")
    || (chartPlan.mode === "fallback" && chartPlan.useFallbackChartPayload)
  ) {
    return (
      <ExploreChartSectionGroups
        result={result}
        chartSeries={chartSeries}
        compareCountries={compareCountries}
        compareFxPairs={compareFxPairs}
      />
    );
  }

  return null;
}

function ExploreChartSectionGroups({ result, chartSeries, compareCountries, compareFxPairs }) {
  const sections = useMemo(
    () => buildExploreChartSectionGroups(result, chartSeries),
    [result, chartSeries]
  );
  if (!sections.length) return null;

  return (
    <section className="explore-report-charts-block space-y-5">
      <div className="explore-report-panel widget-panel-white widget-infographic-light px-5 py-4">
        <h3 className="explore-report-section-title mb-1">Podklady — grafy vybraných řad</h3>
        <p className="text-[12px] text-slate-600 leading-relaxed">
          Grafy ve stejném stylu jako dashboard — důkazní materiál k analýze, vhodný pro PDF a prezentaci.
        </p>
      </div>
      {sections.map((section) => (
        <div key={section.id} className="explore-report-panel widget-panel-white widget-infographic-light px-4 py-4 sm:px-5 sm:py-5 space-y-4">
          <h3 className="text-xs font-extrabold uppercase tracking-[0.1em] text-[hsl(218_65%_28%)]">
            {section.title}
            <span className="ml-2 font-medium normal-case tracking-normal text-slate-500">
              ({section.charts.length} {section.charts.length === 1 ? "řada" : "řad"})
            </span>
          </h3>
          {section.charts.length > 0 ? (
            <div className="grid gap-4 sm:grid-cols-2">
              {section.charts.map((item) => (
                <ExploreChartCard
                  key={`${section.id}-${item.name}-${item.setId || item.source}`}
                  series={item}
                  compareCountries={compareCountries}
                  compareFxPairs={chartSupportsFxCompare(item, section.id) ? compareFxPairs : []}
                />
              ))}
            </div>
          ) : null}
        </div>
      ))}
    </section>
  );
}

function ExploreManagerVerdictCard({ verdict }) {
  const hasStructured = Boolean(verdict?.hasStructuredVerdict && verdict?.headline);
  if (!hasStructured) return null;

  return (
    <section className="explore-report-panel explore-report-panel-featured widget-panel-white widget-infographic-light px-5 py-6 sm:px-7 sm:py-7 space-y-5 border border-[hsl(205_55%_78%/0.55)] shadow-sm">
      <div className="space-y-2">
        <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-[hsl(218_55%_38%)]">
          Manažerský verdikt
        </p>
        <h2 className="text-xl sm:text-2xl font-bold text-[hsl(218_65%_22%)] leading-snug tracking-tight">
          <ExploreCommentText text={verdict.headline} />
        </h2>
      </div>

      {verdict.businessConclusion ? (
        <p className="text-[1.05rem] sm:text-[1.08rem] leading-[1.75] text-slate-800">
          <ExploreCommentText text={verdict.businessConclusion} />
        </p>
      ) : null}

      {verdict.topReasons.length > 0 ? (
        <div className="space-y-3">
          <h3 className="text-sm font-semibold text-slate-900">Tři hlavní důvody z dat</h3>
          <ol className="grid gap-2.5 md:grid-cols-1">
            {verdict.topReasons.slice(0, 3).map((row, idx) => (
              <li
                key={`reason-${idx}`}
                className="rounded-xl border border-[hsl(205_45%_84%)] bg-gradient-to-br from-[hsl(205_75%_97%)] to-white px-4 py-3"
              >
                <div className="flex items-start gap-3">
                  <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[hsl(205_70%_42%)] text-[11px] font-bold text-white">
                    {idx + 1}
                  </span>
                  <div className="min-w-0">
                    <div className="text-sm font-semibold text-slate-900 leading-snug">
                      {String(row?.title || row?.label || "Důvod")}
                    </div>
                    {row?.value ? (
                      <div className="text-lg font-bold text-[hsl(218_65%_28%)] tabular-nums mt-0.5">{row.value}</div>
                    ) : null}
                    <div className="text-[11px] text-slate-600 mt-1 leading-relaxed">
                      {[row?.period, row?.change, row?.source].filter(Boolean).join(" · ")}
                    </div>
                  </div>
                </div>
              </li>
            ))}
          </ol>
        </div>
      ) : null}

      {verdict.recommendation ? (
        <div className="rounded-xl border border-emerald-200/80 bg-emerald-50/90 px-4 py-3.5">
          <div className="text-[10px] font-bold uppercase tracking-wider text-emerald-900">Co bych udělal</div>
          <p className="text-sm text-emerald-950 leading-relaxed mt-1">
            <ExploreCommentText text={verdict.recommendation} />
          </p>
        </div>
      ) : null}

      {verdict.keyRisks.length > 0 ? (
        <div className="rounded-xl border border-amber-200/80 bg-amber-50/85 px-4 py-3.5 space-y-1.5">
          <div className="text-[10px] font-bold uppercase tracking-wider text-amber-950">Hlavní rizika</div>
          <ul className="space-y-1 text-sm text-amber-950 leading-relaxed list-disc pl-4">
            {verdict.keyRisks.slice(0, 2).map((risk, idx) => (
              <li key={`risk-${idx}`}>
                <ExploreCommentText text={risk} />
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {verdict.decisionTriggers.length > 0 ? (
        <div className="space-y-2">
          <h3 className="text-sm font-semibold text-slate-900">Co by změnilo rozhodnutí</h3>
          <ul className="grid gap-2 sm:grid-cols-2">
            {verdict.decisionTriggers.slice(0, 3).map((trigger, idx) => (
              <li
                key={`trigger-${idx}`}
                className="rounded-lg border border-slate-200/80 bg-slate-50/90 px-3 py-2 text-sm text-slate-800 leading-snug"
              >
                <ExploreCommentText text={trigger} />
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {verdict.watchNext.length > 0 ? (
        <div className="text-xs text-slate-600 leading-relaxed">
          <span className="font-semibold text-slate-800">Sledovat dál:</span>{" "}
          {verdict.watchNext.join(" · ")}
        </div>
      ) : null}

      {verdict.briefLimitations ? (
        <p className="text-[11px] text-slate-500 leading-relaxed border-t border-slate-200/70 pt-3">
          <ExploreCommentText text={verdict.briefLimitations} />
        </p>
      ) : null}
    </section>
  );
}

function SummarizeResultDisplay({
  result,
  exploreMeta,
  onResultPatch,
  onRequestFullRefresh,
  onRequestDetailAnalysis,
  currentSummarizeMode = "fast",
  detailAnalysisAvailable = true,
  chartSeries,
  compareCountries,
  compareFxPairs,
  expertMode = false,
}) {
  const reportRef = useRef(null);
  const [exportingPdf, setExportingPdf] = useState(false);
  const [exportError, setExportError] = useState("");
  const [exportNotice, setExportNotice] = useState("");

  const keyItems = useMemo(() => normalizeKeyNumberItems(result?.key_numbers), [result?.key_numbers]);
  const limitations = resolveSummarizeLimitations(result);
  const sectionScoreDetails = useMemo(() => {
    if (result?.analysis_score?.section_scores_detail && typeof result.analysis_score.section_scores_detail === "object") {
      return result.analysis_score.section_scores_detail;
    }
    if (result?.section_scores_detail && typeof result.section_scores_detail === "object") {
      return result.section_scores_detail;
    }
    return {};
  }, [result?.analysis_score?.section_scores_detail, result?.section_scores_detail]);
  const sections = useMemo(() => {
    if (!Array.isArray(result?.analysis_sections)) return [];
    return result.analysis_sections
      .map((section, idx) => {
        const sectionId = String(section?.id || `section-${idx}`);
        const rawDetail = sectionScoreDetails?.[sectionId];
        const scoreMeta =
          rawDetail && typeof rawDetail === "object"
            ? (({ score_explanation: _scoreExplanation, decision_context_explanation: _decisionContext, ...rest }) => rest)(
                rawDetail
              )
            : {};
        const text = String(section?.text || rawDetail?.score_explanation || "").trim();
        return {
          id: sectionId,
          title: String(section?.title || "").trim(),
          text,
          score: section?.score ?? rawDetail?.score,
          highlights: Array.isArray(section?.highlights) ? section.highlights : [],
          seriesRefs: Array.isArray(section?.series_refs) ? section.series_refs : [],
          sourceUrls: Array.isArray(section?.source_urls) ? section.source_urls : [],
          ...scoreMeta,
        };
      })
      .filter((section) => section.title);
  }, [result?.analysis_sections, sectionScoreDetails]);
  const detailSections = useMemo(() => {
    const heroIds = EXPLORE_HERO_SCORE_AREAS.map((area) => area.id);
    const macroIdx = heroIds.indexOf("macro");
    const order =
      macroIdx >= 0
        ? [...heroIds.slice(0, macroIdx + 1), "political_situation", ...heroIds.slice(macroIdx + 1)]
        : [...heroIds, "political_situation"];
    const byId = Object.fromEntries(sections.map((section) => [String(section.id || "").trim().toLowerCase(), section]));
    const fromOrder = order
      .map((id) => {
        const existing = byId[id];
        if (existing?.text && !isPlaceholderExploreSectionText(existing.text)) return existing;
        const rawDetail = sectionScoreDetails?.[id];
        const fallbackText = String(
          existing?.text
            || rawDetail?.score_explanation
            || rawDetail?.decision_context_explanation
            || ""
        ).trim();
        if (!fallbackText || isPlaceholderExploreSectionText(fallbackText)) return null;
        const area = EXPLORE_HERO_SCORE_AREAS.find((row) => row.id === id);
        const titleFallback = id === "political_situation" ? "Politická situace" : id;
        return {
          id,
          title: String(existing?.title || area?.label || titleFallback),
          text: fallbackText,
          score: existing?.score ?? rawDetail?.decision_score ?? rawDetail?.score,
          highlights: Array.isArray(existing?.highlights) ? existing.highlights : [],
          sourceUrls: Array.isArray(existing?.sourceUrls) ? existing.sourceUrls : [],
          ...(existing || {}),
        };
      })
      .filter(Boolean);
    const seen = new Set(fromOrder.map((s) => String(s.id || "").toLowerCase()));
    const extras = sections.filter((s) => {
      const id = String(s.id || "").toLowerCase();
      return id && !seen.has(id) && s.text && !isPlaceholderExploreSectionText(s.text);
    });
    return [...fromOrder, ...extras];
  }, [sections, sectionScoreDetails]);
  const seriesUsed = useMemo(
    () =>
      Array.isArray(result?.series_used)
        ? result.series_used.map((x) => String(x || "").trim()).filter(Boolean)
        : [],
    [result?.series_used]
  );
  const seriesCoverageIndex = useMemo(
    () => buildSeriesCoverageIndex(result?.series_coverage),
    [result?.series_coverage]
  );
  const sectionChartRows = useMemo(() => {
    const out = new Map();
    for (const section of detailSections) {
      const refs = Array.isArray(section?.seriesRefs) ? section.seriesRefs : [];
      const rows = refs
        .map((ref) => ({ ref, chart: matchChartSeriesForRow(ref, chartSeries) }))
        .filter((row) => row.chart);
      out.set(section.id, rows);
    }
    return out;
  }, [detailSections, chartSeries]);
  const answer = String(result?.assistant_answer_cz || "").trim();
  const isInstantV2DetailComplete =
    String(result?.summarize_mode || "").trim().toLowerCase() === "instant_then_detail_v2"
    && (result?.detail_ready || result?.final_answer_source === "detail_job" || result?.detail_status === "completed");
  const mainAnswerTitle = isInstantV2DetailComplete ? "Shrnutí" : "Hlavní komentář";
  const scoringDisplay = useMemo(() => resolveScoringDisplay(result), [result]);
  const isFastMode = Boolean(result?.fast_mode || currentSummarizeMode === "fast");
  const selectedSeriesCount = Number(result?.selected_series_count || result?.series_count_used || 0);
  const originalSeriesCount = Number(result?.original_refined_count || result?.series_count_total || 0);
  const runTraceDisplay = useMemo(() => resolveManagerRunTraceDisplay(result), [result]);
  const managerVerdict = useMemo(() => resolveManagerVerdict(result), [result]);
  const hasReportContent = Boolean(
    answer
    || managerVerdict.hasStructuredVerdict
    || sections.length
    || keyItems.length
    || scoringDisplay.showManagerPanel
    || scoringDisplay.showLegacyHero
  );
  const showStandaloneKeyNumbers = keyItems.length > 0 && managerVerdict.topReasons.length === 0;
  const managerResultChatSection = useMemo(() => {
    const parts = [];
    if (answer) parts.push(`Hlavní komentář:\n${answer}`);
    const briefing = String(result?.economic_briefing_cz || result?.analysis_score?.economic_briefing?.full_text_cs || "").trim();
    if (briefing) parts.push(`Ekonomický komentář sektoru:\n${briefing}`);
    if (keyItems.length > 0) {
      parts.push(
        `Klíčová čísla:\n${keyItems
          .slice(0, 12)
          .map((item) => `${item.label || "Ukazatel"}: ${item.value || ""}${item.description ? ` (${item.description})` : ""}`)
          .join("\n")}`
      );
    }
    const sectionText = [...sections, ...detailSections]
      .filter((section) => section?.title && section?.text)
      .slice(0, 12)
      .map((section) => `${section.title}:\n${section.text}`)
      .join("\n\n");
    if (sectionText) parts.push(sectionText);
    const seriesCoverage = Array.isArray(result?.series_coverage)
      ? result.series_coverage
          .slice(0, 30)
          .map((row) => String(row?.title || row?.series_title || row?.set_id || "").trim())
          .filter(Boolean)
          .join("; ")
      : "";
    if (seriesCoverage) parts.push(`Použité řady:\n${seriesCoverage}`);
    return {
      id: "manager_result",
      title: "Celý výsledek Manager Exploreru",
      text: (parts.join("\n\n---\n\n") || answer || String(result?.assistant_answer_cz || "")).slice(0, 18000),
    };
  }, [answer, result, keyItems, sections, detailSections]);
  if (!hasReportContent && !runTraceDisplay.consistencyError) return null;

  if (runTraceDisplay.consistencyError) {
    return (
      <div className="space-y-4 pt-2">
        <div className="rounded-xl border border-rose-300/80 bg-rose-50 px-5 py-4 text-sm text-rose-950 leading-relaxed">
          <p className="font-semibold text-rose-900">
            Analýza byla zastavena kvůli nekonzistenci datového kontextu. Hlavní segment reportu neodpovídá vybranému segmentu.
          </p>
          <dl className="mt-3 grid gap-2 text-xs sm:grid-cols-2">
            <div>
              <dt className="font-semibold uppercase tracking-wide text-rose-800/80">primary_segment</dt>
              <dd className="font-mono mt-0.5">{runTraceDisplay.primarySegment || "—"}</dd>
            </div>
            <div>
              <dt className="font-semibold uppercase tracking-wide text-rose-800/80">main_section_segment</dt>
              <dd className="font-mono mt-0.5">{runTraceDisplay.mainSectionSegment || "—"}</dd>
            </div>
            <div>
              <dt className="font-semibold uppercase tracking-wide text-rose-800/80">key_numbers_segments</dt>
              <dd className="font-mono mt-0.5">{runTraceDisplay.keyNumbersSegments.join(", ") || "—"}</dd>
            </div>
            <div>
              <dt className="font-semibold uppercase tracking-wide text-rose-800/80">chart_segments</dt>
              <dd className="font-mono mt-0.5">{runTraceDisplay.chartSegments.join(", ") || "—"}</dd>
            </div>
          </dl>
        </div>
        {expertMode && runTraceDisplay.trace ? (
          <details className="rounded-xl border border-border/70 bg-muted/20 overflow-hidden">
            <summary className="cursor-pointer select-none px-4 py-3 text-sm font-medium text-slate-800">
              manager_run_trace (dev)
            </summary>
            <pre className="border-t border-border/60 px-4 py-3 text-[11px] leading-relaxed overflow-x-auto font-mono text-slate-800">
              {JSON.stringify(runTraceDisplay.trace, null, 2)}
            </pre>
          </details>
        ) : null}
      </div>
    );
  }

  const handleSectionUpdate = (sectionId, newText) => {
    if (!onResultPatch || !newText) return;
    onResultPatch((prev) => {
      if (!prev || typeof prev !== "object") return prev;
      const nextSections = Array.isArray(prev.analysis_sections)
        ? prev.analysis_sections.map((section) =>
            String(section?.id || "") === String(sectionId)
              ? { ...section, text: newText }
              : section
          )
        : [];
      const next = { ...prev, analysis_sections: nextSections };
      if (String(sectionId) === "conclusion") {
        next.assistant_answer_cz = newText;
      }
      return next;
    });
  };

  const handleExportPdf = async () => {
    setExportError("");
    setExportNotice("");
    setExportingPdf(true);
    try {
      const stats = await exportExploreReportToPdf({
        reportEl: reportRef.current,
        result,
        exploreMeta,
      });
      if (stats?.partialCharts) {
        const parts = [];
        if (stats.chartsSkipped > 0) {
          parts.push(`${stats.chartsSkipped} grafů vynecháno (limit exportu)`);
        }
        if (stats.chartsFailed > 0) {
          parts.push(`${stats.chartsFailed} grafů se nepodařilo vykreslit`);
        }
        setExportNotice(
          `PDF je připraveno (${stats.chartCount} grafů). ${parts.join(", ")} — text analýzy je kompletní.`
        );
      } else if (stats?.chartCount > 0) {
        setExportNotice(`Tiskový dialog otevřen — zvolte „Uložit jako PDF“ (${stats.chartCount} grafů).`);
      } else {
        setExportNotice("Tiskový dialog otevřen — zvolte „Uložit jako PDF“.");
      }
    } catch (err) {
      setExportError(String(err?.message || "Export se nepodařil."));
    } finally {
      setExportingPdf(false);
    }
  };

  return (
    <div className="space-y-4 pt-2">
      <div
        className="explore-report-export-bar widget-panel-white widget-infographic-light flex flex-wrap items-center justify-between gap-3 px-4 py-3"
        data-export-ignore="true"
      >
        <div className="min-w-0">
          <p className="text-sm font-semibold text-slate-800">Uložit nebo sdílet report</p>
          <p className="text-xs text-slate-600 mt-0.5 leading-relaxed">
            Export do PDF — vhodné pro archiv, e-mail nebo vložení stránek do PowerPointu / Google Slides.
          </p>
        </div>
        <button
          type="button"
          className="h-10 px-4 rounded-xl border border-[hsl(202_90%_52%/0.35)] bg-gradient-to-r from-[hsl(205_75%_96%)] to-white text-[hsl(218_65%_28%)] hover:from-[hsl(205_75%_94%)] text-sm font-semibold inline-flex items-center gap-2 disabled:opacity-50 shadow-sm shrink-0"
          disabled={exportingPdf}
          onClick={() => void handleExportPdf()}
        >
          {exportingPdf ? <Loader2 className="h-4 w-4 animate-spin" /> : <FileDown className="h-4 w-4" />}
          {exportingPdf ? "Připravuji PDF…" : "Exportovat do PDF"}
        </button>
      </div>
      {exportError ? (
        <div className="rounded-xl border border-rose-300/70 bg-rose-50 px-4 py-2 text-sm text-rose-950">
          {exportError}
        </div>
      ) : null}
      {exportNotice ? (
        <div className="rounded-xl border border-teal-300/70 bg-teal-50 px-4 py-2 text-sm text-teal-950">
          {exportNotice}
        </div>
      ) : null}

      <div ref={reportRef} className="explore-manager-report explore-manager-report-document space-y-5">
      <ExploreManagerReportCover exploreMeta={exploreMeta} result={result} generatedAt={Date.now()} />

      <div className="explore-manager-report-body space-y-5">
      <div className="rounded-xl border border-indigo-200/80 bg-indigo-50/80 px-4 py-3 text-sm text-indigo-950 flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 max-w-2xl">
          <div className="text-[11px] font-bold uppercase tracking-wide text-indigo-800">
            {isFastMode ? "Rychlý režim pro UI" : "Detailní analýza"}
          </div>
          <p className="mt-1 leading-relaxed">
            {isFastMode
              ? EXPLORE_SUMMARIZE_FAST_MODE_LIMITATION
              : "Rozšířený režim analyzuje širší payload a při timeoutu vrací částečný detailní report místo prázdného selhání."}
          </p>
          {isFastMode && selectedSeriesCount > 0 && originalSeriesCount > selectedSeriesCount ? (
            <p className="mt-1 text-[12px] text-indigo-900/80">
              Vybráno {selectedSeriesCount} z {originalSeriesCount} připravených řad.
            </p>
          ) : null}
        </div>
        {detailAnalysisAvailable && isFastMode ? (
          <button
            type="button"
            className="h-9 px-3 rounded-xl border border-indigo-700/30 bg-white hover:bg-indigo-50 text-indigo-950 text-xs font-semibold shrink-0"
            onClick={() => onRequestDetailAnalysis?.()}
          >
            Detailní analýza
          </button>
        ) : null}
      </div>

      {result?.fallback && resolveAiFallbackMessage(result) ? (
        <div className="rounded-xl border border-amber-300/70 bg-amber-50 px-4 py-3 text-sm text-amber-950">
          {resolveAiFallbackMessage(result)}
        </div>
      ) : null}

      {result?.fetch_summary && typeof result.fetch_summary === "object" ? (
        <div className="rounded-xl border border-slate-200/70 bg-slate-50/90 px-4 py-2.5 text-xs text-slate-700">
          <span className="font-semibold text-slate-800">Pokrytí dat:</span>{" "}
          {Number(result.fetch_summary.loaded) || 0} z {Number(result.fetch_summary.planned) || 0} řad ve zpracování
          má v reportu výstup (graf, highlight nebo záznam)
          {Number(result.fetch_summary.failed) > 0
            ? ` · ${result.fetch_summary.failed} bez dostupných dat`
            : ""}
          {Number(result.fetch_summary.charts) > 0 ? ` · ${result.fetch_summary.charts} grafů` : ""}
        </div>
      ) : null}

      <ExploreManagerVerdictCard verdict={managerVerdict} />

      {exploreMeta && managerResultChatSection.text ? (
        <section
          className="explore-report-panel widget-panel-white widget-infographic-light px-5 py-4 space-y-2"
          data-export-ignore="true"
        >
          <div className="flex items-center justify-between gap-3">
            <div>
              <h3 className="explore-report-section-title">AI chat nad výsledkem</h3>
              <p className="text-xs text-slate-600 mt-1">
                Ptejte se na závěry, rizika, použité řady nebo další data k celé analýze.
              </p>
            </div>
          </div>
          <ExploreSectionFollowup
            section={managerResultChatSection}
            exploreMeta={exploreMeta}
            priorResult={result}
            onRequestFullRefresh={onRequestFullRefresh}
            defaultOpen
            triggerLabel="Chat nad celým výsledkem"
          />
        </section>
      ) : null}

      {showStandaloneKeyNumbers ? (
        <section className="explore-report-panel widget-panel-white widget-infographic-light px-5 py-5 space-y-3">
          <h3 className="explore-report-section-title">Klíčová čísla</h3>
          <div className="grid gap-2 md:grid-cols-2 xl:grid-cols-3">
            {keyItems.map((item, idx) => (
              <div
                key={`${item.label}-${item.value}-${idx}`}
                className="rounded-xl border border-[hsl(205_45%_84%)] bg-gradient-to-br from-[hsl(205_75%_96%)] to-white px-3 py-2.5 min-w-0 shadow-sm"
              >
                {item.label ? (
                  <div className="text-[11px] font-semibold text-slate-800 leading-snug">{item.label}</div>
                ) : null}
                {item.description ? (
                  <div className="text-[10px] text-muted-foreground mt-1 leading-snug">{item.description}</div>
                ) : null}
                <div className="text-sm font-semibold text-slate-900 leading-snug mt-1 tabular-nums">{item.value}</div>
              </div>
            ))}
          </div>
        </section>
      ) : null}

      <ExploreManagerInterpretationPanel
        result={result}
        scoringDisplay={scoringDisplay}
        hideExecutiveVerdict={managerVerdict.hasStructuredVerdict}
      />

      {scoringDisplay.showLegacyHero ? (
        <ExploreCompositeScoreHero
          analysisScore={result?.analysis_score}
          sectorLabel={exploreMeta?.sector || exploreMeta?.countries || null}
        />
      ) : null}

      {scoringDisplay.legacy_score_hidden_due_to_manager_score ? (
        <details
          className="rounded-xl border border-border/60 bg-muted/20 px-4 py-3 text-[11px] text-slate-600"
          data-export-ignore="true"
        >
          <summary className="cursor-pointer font-medium">
            Legacy skóre (debug) — skryto kvůli manager final_score
          </summary>
          <div className="mt-2 tabular-nums">
            Sekční skóre:{" "}
            {formatScore(result?.analysis_score?.decision_score ?? result?.analysis_score?.composite)}/10
          </div>
        </details>
      ) : null}

      {!managerVerdict.hasStructuredVerdict &&
      String(result?.economic_briefing_cz || result?.analysis_score?.economic_briefing?.full_text_cs || "").trim() ? (
        <section className="explore-report-panel explore-report-panel-featured widget-panel-white widget-infographic-light px-5 py-5 sm:px-6 sm:py-6 space-y-3">
          <h3 className="explore-report-section-title">Ekonomický komentář sektoru</h3>
          {result?.analysis_score?.economic_briefing?.headline_cs ? (
            <p className="text-sm font-bold text-[hsl(218_65%_28%)] leading-snug">
              <ExploreCommentText text={String(result.analysis_score.economic_briefing.headline_cs)} />
            </p>
          ) : null}
          <p className="explore-report-body whitespace-pre-wrap text-[1.02rem] leading-[1.72]">
            <ExploreCommentText
              text={String(result?.economic_briefing_cz || result?.analysis_score?.economic_briefing?.full_text_cs || "").trim()}
            />
          </p>
          {Array.isArray(result?.analysis_score?.economic_briefing?.contradictory_signals) &&
          result.analysis_score.economic_briefing.contradictory_signals.length > 0 ? (
            <div className="rounded-xl border border-amber-200/80 bg-amber-50/80 px-4 py-3 space-y-2">
              <div className="text-[10px] font-bold uppercase tracking-wider text-amber-900">
                Protichůdné signály
              </div>
              <ul className="space-y-1.5 text-[12px] text-amber-950 leading-relaxed list-disc pl-4">
                {result.analysis_score.economic_briefing.contradictory_signals.slice(0, 4).map((row, idx) => (
                  <li key={`tension-${idx}`}>
                    <ExploreCommentText text={String(row?.detail_cs || row?.label_cs || "")} />
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </section>
      ) : null}

      {!managerVerdict.hasStructuredVerdict && answer ? (
        <section className="explore-report-panel widget-panel-white widget-infographic-light px-5 py-5 sm:px-6 sm:py-6 space-y-3">
          <h3 className="explore-report-section-title">{mainAnswerTitle}</h3>
          {isInstantV2DetailComplete ? (
            <p className="text-[12px] text-muted-foreground">
              Tento text je součástí detailní analýzy a má přednost před orientačním náhledem výše.
            </p>
          ) : null}
          <div className="explore-report-summary whitespace-pre-wrap">
            <ExploreCommentText text={answer} />
          </div>
          {exploreMeta ? (
            <ExploreSectionFollowup
              section={{ id: "conclusion", title: "Závěr", text: answer }}
              exploreMeta={exploreMeta}
              priorResult={result}
              onSectionUpdate={handleSectionUpdate}
              onRequestFullRefresh={onRequestFullRefresh}
            />
          ) : null}
        </section>
      ) : null}

      {detailSections.length > 0 ? (
        <details className="rounded-xl border border-border/70 bg-muted/15 overflow-hidden group" open={expertMode}>
          <summary className="cursor-pointer select-none px-4 py-3 text-sm font-medium text-slate-800 list-none flex items-center justify-between gap-2 [&::-webkit-details-marker]:hidden">
            <span>Podrobné sekční analýzy ({detailSections.length})</span>
            <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground transition-transform group-open:rotate-180" />
          </summary>
          <div className="border-t border-border/60 px-1 py-3 space-y-4">
          {detailSections.map((section) => (
            <div
              key={section.id}
              className={`explore-report-panel widget-panel-white widget-infographic-light px-5 py-5 ${
                SECTION_CARD_STYLES[section.id] ? `explore-report-panel-tint explore-report-panel-tint-${section.id}` : ""
              }`}
            >
              <ExploreAnalysisSectionHeader
                section={section}
                suppressPrimaryScore={shouldSuppressSectionPrimaryScore(result)}
              />
              <div
                className={`explore-report-body whitespace-pre-wrap ${
                  section.id === "sector" ? "text-[1.0625rem] sm:text-[1.125rem] leading-[1.72]" : ""
                }`}
              >
                <ExploreCommentText text={section.text} />
              </div>
              {section.id === "political_situation" && Array.isArray(section.sourceUrls) && section.sourceUrls.length > 0 ? (
                <ul className="mt-3 space-y-1 text-[11px] text-muted-foreground list-disc pl-4">
                  {section.sourceUrls.slice(0, 6).map((src, idx) => {
                    const url = String(src?.url || "").trim();
                    if (!url) return null;
                    const title = String(src?.title || url).trim();
                    return (
                      <li key={`pol-src-${idx}`}>
                        <a href={url} target="_blank" rel="noopener noreferrer" className="underline underline-offset-2 hover:text-foreground">
                          {title}
                        </a>
                      </li>
                    );
                  })}
                </ul>
              ) : null}
              <ExploreQuestionDrivers drivers={section.score_drivers} sectionId={section.id} />
              {(sectionChartRows.get(section.id) || []).length > 0 ? (
                <details className="group mt-4 rounded-xl border border-teal-200/70 bg-teal-50/40 overflow-hidden">
                  <summary className="cursor-pointer select-none px-4 py-3 text-sm font-semibold text-teal-950 list-none flex items-center justify-between gap-2 [&::-webkit-details-marker]:hidden">
                    <span>Datové podklady a grafy ({sectionChartRows.get(section.id).length})</span>
                    <ChevronDown className="h-4 w-4 shrink-0 transition-transform group-open:rotate-180" />
                  </summary>
                  <div className="border-t border-teal-200/60 p-3 space-y-2">
                    {sectionChartRows.get(section.id).map(({ ref, chart }, idx) => (
                      <ExploreSeriesCoverageRow
                        key={`${section.id}-${chart.seriesId || chart.setId || idx}`}
                        row={ref}
                        chartSeries={[chart]}
                        compareCountries={compareCountries}
                        compareFxPairs={compareFxPairs}
                      />
                    ))}
                  </div>
                </details>
              ) : null}
              {Array.isArray(section.missing_reasons) && section.missing_reasons.length > 0 ? (
                <ul className="mt-2 space-y-1 text-[11px] text-amber-900 list-disc pl-4">
                  {section.missing_reasons.slice(0, 3).map((reason) => (
                    <li key={reason}>{reason}</li>
                  ))}
                </ul>
              ) : null}
              {exploreMeta ? (
                <ExploreSectionFollowup
                  section={section}
                  exploreMeta={exploreMeta}
                  priorResult={result}
                  onSectionUpdate={handleSectionUpdate}
                  onRequestFullRefresh={onRequestFullRefresh}
                />
              ) : null}
            </div>
          ))}
          </div>
        </details>
      ) : null}

      <ExploreReportCharts
        result={result}
        chartSeries={chartSeries}
        compareCountries={compareCountries}
        compareFxPairs={compareFxPairs}
        chartsMismatch={runTraceDisplay.chartsMismatch}
      />

      <ExploreWebSourcesSection result={result} />

      {limitations && !managerVerdict.briefLimitations && !scoringDisplay.showManagerPanel ? (
        <div className="explore-report-panel widget-panel-white widget-infographic-light px-5 py-4 space-y-2">
          <h3 className="explore-report-section-title">Omezení a upozornění</h3>
          <p className="text-sm text-slate-600 leading-relaxed whitespace-pre-wrap">{limitations}</p>
        </div>
      ) : null}

      <footer className="explore-manager-report-footer text-center text-[11px] text-slate-500 px-2 pb-1">
        Bankoapp · Manager Explorer · Interní analýza, není investičním doporučením.
      </footer>
      </div>
      </div>

      {Array.isArray(result?.series_coverage) && result.series_coverage.length > 0 ? (
        <details className="rounded-xl border border-border/70 bg-muted/20 overflow-hidden group" data-export-ignore="true">
          <summary className="cursor-pointer select-none px-4 py-3 text-sm font-medium text-slate-800 list-none flex items-center justify-between gap-2 [&::-webkit-details-marker]:hidden">
            <span>Všechny řady ve zpracování ({result.series_coverage.length})</span>
            <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground transition-transform group-open:rotate-180" />
          </summary>
          <div className="border-t border-border/60 px-4 py-3 max-h-[min(70vh,640px)] overflow-y-auto space-y-2 text-xs text-slate-800">
            {result.series_coverage.map((row, idx) => (
              <ExploreSeriesCoverageRow
                key={`cov-${row?.title}-${row?.series_id || idx}`}
                row={row}
                chartSeries={chartSeries}
                compareCountries={compareCountries}
                compareFxPairs={compareFxPairs}
              />
            ))}
          </div>
        </details>
      ) : null}

      {seriesUsed.length > 0 ? (
        <details className="rounded-xl border border-border/70 bg-muted/20 overflow-hidden group" data-export-ignore="true">
          <summary className="cursor-pointer select-none px-4 py-3 text-sm font-medium text-slate-800 list-none flex items-center justify-between gap-2 [&::-webkit-details-marker]:hidden">
            <span>Zobrazit použité datové řady ({seriesUsed.length})</span>
            <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground transition-transform group-open:rotate-180" />
          </summary>
          <div className="border-t border-border/60 px-4 py-3 space-y-3 text-sm text-slate-800">
            <UsedSeriesGroup
              title="Firemní data"
              items={result?.company_series_used}
              coverageIndex={seriesCoverageIndex}
              chartSeries={chartSeries}
              compareCountries={compareCountries}
              compareFxPairs={compareFxPairs}
              keyPrefix="company"
            />
            <UsedSeriesGroup
              title="Makro — primární země"
              items={result?.macro_series_used}
              coverageIndex={seriesCoverageIndex}
              chartSeries={chartSeries}
              compareCountries={compareCountries}
              compareFxPairs={compareFxPairs}
              keyPrefix="macro"
            />
            <UsedSeriesGroup
              title="Odvětvové řady"
              items={result?.sector_series_used}
              coverageIndex={seriesCoverageIndex}
              chartSeries={chartSeries}
              compareCountries={compareCountries}
              compareFxPairs={compareFxPairs}
              keyPrefix="sector"
            />
            <UsedSeriesGroup
              title="Sousedé"
              items={result?.neighbor_series_used}
              coverageIndex={seriesCoverageIndex}
              chartSeries={chartSeries}
              compareCountries={compareCountries}
              compareFxPairs={compareFxPairs}
              keyPrefix="neighbor"
            />
            <UsedSeriesGroup
              title="Partneři / subdodavatelé"
              items={result?.partner_series_used}
              coverageIndex={seriesCoverageIndex}
              chartSeries={chartSeries}
              compareCountries={compareCountries}
              compareFxPairs={compareFxPairs}
              keyPrefix="partner"
            />
            <UsedSeriesGroup
              title="Globální kontext"
              items={result?.global_series_used}
              coverageIndex={seriesCoverageIndex}
              chartSeries={chartSeries}
              compareCountries={compareCountries}
              compareFxPairs={compareFxPairs}
              keyPrefix="global"
            />
            <UsedSeriesGroup
              title="Region / EU"
              items={result?.eu_series_used}
              coverageIndex={seriesCoverageIndex}
              chartSeries={chartSeries}
              compareCountries={compareCountries}
              compareFxPairs={compareFxPairs}
              keyPrefix="eu"
            />
            <UsedSeriesGroup
              title="Komodity"
              items={result?.commodity_series_used}
              coverageIndex={seriesCoverageIndex}
              chartSeries={chartSeries}
              compareCountries={compareCountries}
              compareFxPairs={compareFxPairs}
              keyPrefix="commodity"
            />
            <UsedSeriesGroup
              title="Finanční trhy"
              items={result?.financial_markets_series_used}
              coverageIndex={seriesCoverageIndex}
              chartSeries={chartSeries}
              compareCountries={compareCountries}
              compareFxPairs={compareFxPairs}
              keyPrefix="markets"
            />
            <UsedSeriesGroup
              title="Demografie"
              items={result?.demographics_series_used}
              coverageIndex={seriesCoverageIndex}
              chartSeries={chartSeries}
              compareCountries={compareCountries}
              compareFxPairs={compareFxPairs}
              keyPrefix="demographics"
            />
            <UsedSeriesGroup
              title="Kurzy"
              items={result?.fx_series_used}
              coverageIndex={seriesCoverageIndex}
              chartSeries={chartSeries}
              compareCountries={compareCountries}
              compareFxPairs={compareFxPairs}
              keyPrefix="fx"
            />
            {!result?.macro_series_used?.length &&
            !result?.company_series_used?.length &&
            !result?.neighbor_series_used?.length &&
            !result?.partner_series_used?.length &&
            !result?.financial_markets_series_used?.length &&
            !result?.global_series_used?.length &&
            !result?.eu_series_used?.length &&
            !result?.commodity_series_used?.length &&
            !result?.sector_series_used?.length &&
            !result?.demographics_series_used?.length &&
            !result?.fx_series_used?.length ? (
              <ul className="space-y-1.5">
                {seriesUsed.map((title, idx) => (
                  <li key={`${title}-${idx}`}>
                    <ExploreSeriesCoverageRow
                      row={{ title }}
                      chartSeries={chartSeries}
                      compareCountries={compareCountries}
                      compareFxPairs={compareFxPairs}
                    />
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        </details>
      ) : null}
    </div>
  );
}

/** Dočasně skryto — volná témata / AI návrhy duplikují sjednocený seznam segmentů. */
const SHOW_EXPLORE_RELATED_TOPIC_AI_SUGGESTIONS = false;

/** Dočasně skryto — rychlé chipy zemí duplikují výběr v dropdownu. */
const SHOW_EXPLORE_COUNTRY_QUICK_HINTS = false;

const EXPLORE_GEO_MODES = [
  { id: "none", label: "Svět" },
  { id: "countries", label: "Země" },
  { id: "continent", label: "Kontinent" },
];

const EXPLORE_CONTINENTS = [
  { id: "europe", label: "Evropa" },
  { id: "asia", label: "Asie" },
  { id: "north_america", label: "Severní Amerika" },
  { id: "south_america", label: "Jižní Amerika" },
  { id: "africa", label: "Afrika" },
  { id: "oceania", label: "Oceánie" },
  { id: "americas", label: "Amerika (obě)" },
];

const EXPLORE_COUNTRY_QUICK = [
  { code: "CZ", label: "Česko" },
  { code: "DE", label: "Německo" },
  { code: "AT", label: "Rakousko" },
  { code: "PL", label: "Polsko" },
  { code: "SK", label: "Slovensko" },
  { code: "FR", label: "Francie" },
  { code: "US", label: "USA" },
  { code: "JP", label: "Japonsko" },
];

function waitMs(ms) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function normalizeExploreCountryCodes(values) {
  const rawValues = Array.isArray(values) ? values : [values];
  const out = [];
  const seen = new Set();
  for (const value of rawValues) {
    const code = String(value || "").trim().toUpperCase();
    if (!code || seen.has(code)) continue;
    seen.add(code);
    out.push(code);
  }
  return out;
}

export default function ExplorePage() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const [expertPanelOpen, setExpertPanelOpen] = useState(false);
  const exploreExpertMode = useMemo(
    () => resolveExploreExpertMode(searchParams, expertPanelOpen),
    [searchParams, expertPanelOpen]
  );
  const openExpertSettings = useCallback(() => {
    setExpertPanelOpen(true);
  }, []);
  const steps = useMemo(
    () => [
      { id: 1, label: t("pages.explore.step1") },
      { id: 2, label: t("pages.explore.step2") },
    ],
    [t]
  );
  const [step, setStep] = useState(1);
  const [sector, setSector] = useState("");
  const [managerAnalysisMode, setManagerAnalysisMode] = useState("sector");
  const [supplementarySegmentSelections, setSupplementarySegmentSelections] = useState([""]);
  const [geoMode, setGeoMode] = useState("countries");
  const [selectedContinent, setSelectedContinent] = useState("europe");
  const [countrySelections, setCountrySelections] = useState([""]);
  const [countryOptions, setCountryOptions] = useState(() =>
    EXPLORE_COUNTRY_QUICK.map((item) => ({ code: item.code, label_cs: item.label }))
  );
  const [countryGroups, setCountryGroups] = useState([]);
  const [countryHints, setCountryHints] = useState(EXPLORE_COUNTRY_QUICK);
  const [loadingCountrySuggest, setLoadingCountrySuggest] = useState(false);
  const [countrySuggestError, setCountrySuggestError] = useState("");
  const [countrySuggestNotice, setCountrySuggestNotice] = useState("");
  const [loadingSupplementarySuggest, setLoadingSupplementarySuggest] = useState(false);
  const [supplementarySuggestError, setSupplementarySuggestError] = useState("");
  const [supplementarySuggestNotice, setSupplementarySuggestNotice] = useState("");
  const [relatedSegments, setRelatedSegments] = useState("");
  const [excludedRelatedKeys, setExcludedRelatedKeys] = useState([]);
  const [relatedSegmentOrderKeys, setRelatedSegmentOrderKeys] = useState([]);
  const [pendingRelatedSegmentAdd, setPendingRelatedSegmentAdd] = useState("");
  const [relationshipRelatedRows, setRelationshipRelatedRows] = useState([]);
  const [loadingRelationshipRows, setLoadingRelationshipRows] = useState(false);
  const [relatedSuggestions, setRelatedSuggestions] = useState([]);
  const [relatedSuggestError, setRelatedSuggestError] = useState("");
  const [loadingRelatedSuggest, setLoadingRelatedSuggest] = useState(false);
  const [question, setQuestion] = useState("");
  const [availableUploads, setAvailableUploads] = useState([]);
  const [selectedUploadIds, setSelectedUploadIds] = useState([]);
  // Kdy mají vlastní data vstoupit do analýzy: "final_compare" (default, jako dnes) | "upfront".
  const [userDataParticipation, setUserDataParticipation] = useState("final_compare");
  const [userDataPrivacyMode, setUserDataPrivacyMode] = useState("strict_private");
  const [managerAnalysisScope, setManagerAnalysisScope] = useState("auto");
  // Vlastní řady výslovně vybrané uživatelem (tvar ExplorePage refu + user_selected:true → 100% chráněno).
  const [userPickedSeries, setUserPickedSeries] = useState([]);
  const [seriesPickerOpen, setSeriesPickerOpen] = useState(false);
  const [seriesPickerSource, setSeriesPickerSource] = useState("eurostat");
  const [seriesPickerQuery, setSeriesPickerQuery] = useState("");
  const [seriesPickerHits, setSeriesPickerHits] = useState([]);
  const [seriesPickerLoading, setSeriesPickerLoading] = useState(false);
  const [seriesPickerError, setSeriesPickerError] = useState("");

  const pickedSeriesKey = useCallback(
    (ref) =>
      `${String(ref?.source || "").toLowerCase()}:${String(ref?.dataset_id || "")}:${JSON.stringify(ref?.filters_used || {})}`,
    [],
  );
  const catalogHitToPickedRef = useCallback(
    (row) => ({
      catalog_id: String(row?.catalog_id || seriesPickerSource || "").trim().toLowerCase(),
      source: String(row?.source_type || row?.catalog_id || seriesPickerSource || "").trim().toLowerCase(),
      dataset_id: String(row?.set_id || row?.series_id || row?.dataset_id || "").trim(),
      indicator_name: String(row?.title || row?.name || row?.indicator_name || row?.set_id || "").trim(),
      filters_used:
        row?.query_params && typeof row.query_params === "object" && !Array.isArray(row.query_params)
          ? { ...row.query_params }
          : {},
      user_selected: true,
    }),
    [seriesPickerSource],
  );
  const searchSeriesPicker = useCallback(async () => {
    const q = String(seriesPickerQuery || "").trim();
    if (q.length < 2) {
      setSeriesPickerError("Zadejte alespoň 2 znaky.");
      return;
    }
    setSeriesPickerLoading(true);
    setSeriesPickerError("");
    try {
      const { data } = await api.post("/catalog/search", {
        source: seriesPickerSource,
        query: q,
        limit: 20,
        metadata_mode: "classic",
      });
      const rows = Array.isArray(data?.results) ? data.results : Array.isArray(data?.hits) ? data.hits : [];
      setSeriesPickerHits(rows);
      if (!rows.length) setSeriesPickerError("Nic nenalezeno pro tento zdroj.");
    } catch (e) {
      setSeriesPickerError(String(e?.response?.data?.detail || e?.message || "Hledání selhalo."));
      setSeriesPickerHits([]);
    } finally {
      setSeriesPickerLoading(false);
    }
  }, [seriesPickerQuery, seriesPickerSource]);
  const openPickedSeriesInCatalog = useCallback(
    (raw) => {
      const ref = raw?.dataset_id || raw?.source ? raw : catalogHitToPickedRef(raw);
      const rawCatalog = String(ref?.catalog_id || ref?.source || seriesPickerSource || "").trim().toLowerCase();
      const catalog = (
        {
          ecb: "ecb2",
          oecd: "oecd4",
          world_bank_data360: "data360",
        }[rawCatalog] || rawCatalog
      );
      const setId = String(ref?.dataset_id || ref?.set_id || "").trim();
      const label = String(ref?.indicator_name || ref?.title || setId || seriesPickerQuery || "").trim();
      const params = new URLSearchParams();
      if (label) params.set("q", label);
      if (catalog) params.set("catalog", catalog);
      if (setId) {
        params.set("set_id", setId);
        params.set("preview", "1");
      }
      navigate(`/search/catalog?${params.toString()}`);
    },
    [catalogHitToPickedRef, navigate, seriesPickerQuery, seriesPickerSource],
  );
  const togglePickedSeries = useCallback(
    (ref) => {
      setUserPickedSeries((prev) => {
        const key = pickedSeriesKey(ref);
        return prev.some((r) => pickedSeriesKey(r) === key)
          ? prev.filter((r) => pickedSeriesKey(r) !== key)
          : [...prev, ref];
      });
    },
    [pickedSeriesKey],
  );
  const [loadingUserUploads, setLoadingUserUploads] = useState(false);
  const [uploadingUserFile, setUploadingUserFile] = useState(false);
  const [uploadNotice, setUploadNotice] = useState("");
  const [uploadError, setUploadError] = useState("");
  const [expandedUploadIds, setExpandedUploadIds] = useState([]);
  const [uploadPreviews, setUploadPreviews] = useState({});

  const [sectorIndicators, setSectorIndicators] = useState([]);
  const [macroIndicators, setMacroIndicators] = useState([]);
  const [indicatorSections, setIndicatorSections] = useState([]);
  const [managerMeta, setManagerMeta] = useState(null);
  const [managerRunTrace, setManagerRunTrace] = useState(null);
  const [quickDataPreview, setQuickDataPreview] = useState(null);
  const [quickDataPreviewLoading, setQuickDataPreviewLoading] = useState(false);
  // Real per-source discovery progress from SSE source_started/source_finished/source_timeout
  // events (see handleSourceStatus below) - replaces the old animation driven by hardcoded
  // source/category lists with no connection to what the backend is actually doing.
  const [sourceStatuses, setSourceStatuses] = useState([]);
  const [managerSectorHints, setManagerSectorHints] = useState([]);
  const [managerSectorById, setManagerSectorById] = useState(() => new Map());
  const [queryUnderstandingPreview, setQueryUnderstandingPreview] = useState(null);
  const [pendingClarification, setPendingClarification] = useState(null);
  const [assumptionFallbackActive, setAssumptionFallbackActive] = useState(false);
  const [exploreStepTimings, setExploreStepTimings] = useState(null);
  const [sectorOptionsNotice, setSectorOptionsNotice] = useState("");
  const [selectedKeys, setSelectedKeys] = useState(() => new Set());
  const [refinedSeries, setRefinedSeries] = useState([]);
  const [resolvedCountryCodes, setResolvedCountryCodes] = useState([]);

  const [loadingSector, setLoadingSector] = useState(false);
  const [loadingRefine, setLoadingRefine] = useState(false);
  const [loadingSummarize, setLoadingSummarize] = useState(false);
  const [summarizeJobId, setSummarizeJobId] = useState("");
  const [summarizePendingDetail, setSummarizePendingDetail] = useState("");
  const [summarizeMode, setSummarizeMode] = useState(DEFAULT_SUMMARIZE_MODE);
  const [detailJobId, setDetailJobId] = useState("");
  const [detailJobStatus, setDetailJobStatus] = useState("");
  const [detailProgressStep, setDetailProgressStep] = useState("queued");
  const [detailProgressPercent, setDetailProgressPercent] = useState(5);
  const [detailJobMessage, setDetailJobMessage] = useState("");
  const [, setLoadingDetailAnalysis] = useState(false);
  const [error, setError] = useState("");
  const [loadHint, setLoadHint] = useState("");
  const [pipelineEtaSec, setPipelineEtaSec] = useState(0);
  const [pipelineStartedAtMs, setPipelineStartedAtMs] = useState(0);
  const [pipelineSecondsLeft, setPipelineSecondsLeft] = useState(0);
  const [pipelineElapsedSec, setPipelineElapsedSec] = useState(0);
  const [matchedPresetLabel, setMatchedPresetLabel] = useState("");
  const [summarizeResult, setSummarizeResult] = useState(null);
  const [exploreExtraSeries, setExploreExtraSeries] = useState([]);
  const [imfPrefetchedItems, setImfPrefetchedItems] = useState([]);
  const [, setImfContextMeta] = useState(null);
  const sectorLoadAbortRef = useRef(null);
  const exploreRunIdRef = useRef(0);
  const summarizePollTokenRef = useRef(0);

  const loadUserUploads = useCallback(async ({ silent = false } = {}) => {
    setLoadingUserUploads(true);
    if (!silent) {
      setUploadError("");
    }
    try {
      const { data } = await api.get("/me/uploads", { timeout: 20000 });
      const rows = Array.isArray(data) ? data : Array.isArray(data?.uploads) ? data.uploads : [];
      setAvailableUploads(rows);
      setSelectedUploadIds((prev) =>
        prev.filter((id) => rows.some((row) => String(row?.id || "").trim() === String(id || "").trim()))
      );
    } catch (e) {
      if (!silent) {
        if (e?.response?.status === 403) {
          setUploadError("Vlastní soubory jsou dostupné jen pro uživatele s aktivovanou funkcí uploadu.");
        } else {
          setUploadError(formatApiErrorFromAxios(e) || "Seznam vlastních souborů se nepodařilo načíst.");
        }
      }
    } finally {
      setLoadingUserUploads(false);
    }
  }, []);

  const allIndicators = useMemo(() => {
    if (indicatorSections.length) return indicatorSections.flatMap((s) => s.items);
    return [...sectorIndicators, ...macroIndicators];
  }, [indicatorSections, sectorIndicators, macroIndicators]);

  const selectedUploadDocs = useMemo(
    () =>
      availableUploads.filter((row) =>
        selectedUploadIds.includes(String(row?.id || "").trim())
      ),
    [availableUploads, selectedUploadIds]
  );

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get("/explore/geo-options", { timeout: 12000 });
        if (cancelled) return;
        if (!data?.ok) {
          setSectorOptionsNotice(
            "Seznam segmentů nelze načíst ze serverového registru. Zkontrolujte, že backend běží."
          );
          return;
        }
        setSectorOptionsNotice("");
        const sectors = Array.isArray(data.manager_sectors) ? data.manager_sectors : [];
        const sectorLabels = sectors
          .map((s) => String(s.label_cs || s.sector_name_cs || s.id || "").trim())
          .filter(Boolean);
        const nextSectorById = new Map();
        sectors.forEach((s) => {
          const id = String(s.id || s.sector_id || "").trim();
          const label = String(s.label_cs || s.sector_name_cs || "").trim();
          if (id && label) nextSectorById.set(id, label);
        });
        if (nextSectorById.size) setManagerSectorById(nextSectorById);
        if (sectorLabels.length) {
          setManagerSectorHints(sectorLabels);
        } else {
          setSectorOptionsNotice(
            "Serverový registr nevrátil žádné manažerské segmenty. Zkontrolujte backend a soubor presetů."
          );
        }
        const nextCountryOptions = Array.isArray(data.all_countries) ? data.all_countries : [];
        if (nextCountryOptions.length) {
          setCountryOptions(
            sortExploreCountryOptions(
              nextCountryOptions.map((item) => ({
                code: String(item?.code || "").trim().toUpperCase(),
                label_cs: String(item?.label_cs || item?.code || "").trim(),
                continent_id: String(item?.continent_id || "other").trim() || "other",
              }))
            ).filter((item) => item.code)
          );
        }
        const nextCountryGroups = Array.isArray(data.country_groups) ? data.country_groups : [];
        if (nextCountryGroups.length) {
          setCountryGroups(nextCountryGroups);
        } else if (nextCountryOptions.length) {
          setCountryGroups(groupExploreCountryOptions(nextCountryOptions));
        }
        const nextCountryHints = Array.isArray(data.country_hints) ? data.country_hints : [];
        if (nextCountryHints.length) {
          setCountryHints(
            nextCountryHints
              .map((item) => ({
                code: String(item?.code || "").trim().toUpperCase(),
                label: String(item?.label_cs || item?.code || "").trim(),
              }))
              .filter((item) => item.code)
          );
        }
      } catch (err) {
        if (!cancelled) {
          // eslint-disable-next-line no-console
          console.warn("[Explore] geo-options failed:", err);
          setSectorOptionsNotice(
            "Backend nedostupný — segmenty z lokální zálohy. Spusťte API (typicky port 8000) a obnovte stránku."
          );
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    void loadUserUploads({ silent: true });
  }, [loadUserUploads]);

  useEffect(
    () => () => {
      summarizePollTokenRef.current += 1;
    },
    []
  );

  // ETAPA 8: leaving the page mid-discovery previously left the SSE stream (runExploreSectorStream)
  // and any in-flight axios calls running against an unmounted component - nothing invalidated
  // exploreRunIdRef/sectorLoadAbortRef on unmount (only stopAnalysis() and starting a new run did).
  useEffect(
    () => () => {
      exploreRunIdRef.current += 1;
      try {
        sectorLoadAbortRef.current?.abort();
      } catch  {
        /* noop */
      }
    },
    []
  );

  const chartSeries = useMemo(
    () => chartSeriesList(summarizeResult?.chart_payload),
    [summarizeResult]
  );

  const chartCompareCountries = useMemo(() => {
    const cc = summarizeResult?.context_countries;
    if (!cc || typeof cc !== "object") return [];
    return Array.isArray(cc.compare_countries) ? cc.compare_countries : [];
  }, [summarizeResult]);

  const chartCompareFxPairs = useMemo(() => {
    const cc = summarizeResult?.context_countries;
    if (!cc || typeof cc !== "object") return [];
    return Array.isArray(cc.compare_fx_pairs) ? cc.compare_fx_pairs : [];
  }, [summarizeResult]);

  const selectedSupplementarySegments = useMemo(
    () => normalizeExploreSegmentValues(supplementarySegmentSelections),
    [supplementarySegmentSelections]
  );

  const localRelationshipRelatedRows = useMemo(
    () => localRelatedSegmentRows(sector, { limit: 8 }),
    [sector]
  );

  const displayRelationshipRelatedRows = relationshipRelatedRows.length
    ? relationshipRelatedRows
    : localRelationshipRelatedRows;

  const unifiedRelatedItems = useMemo(
    () =>
      buildUnifiedRelatedSegmentItems({
        primarySector: sector,
        relationshipRows: displayRelationshipRelatedRows,
        excludedRelatedKeys,
        supplementarySegments: selectedSupplementarySegments,
        relatedSegmentsText: relatedSegments,
        orderKeys: relatedSegmentOrderKeys,
      }),
    [
      sector,
      displayRelationshipRelatedRows,
      excludedRelatedKeys,
      selectedSupplementarySegments,
      relatedSegments,
      relatedSegmentOrderKeys,
    ]
  );

  const combinedRelatedSegments = useMemo(
    () => unifiedRelatedItems.map((item) => item.label).join(", "),
    [unifiedRelatedItems]
  );

  const relatedSegmentsCount = useMemo(
    () => unifiedRelatedItems.length,
    [unifiedRelatedItems],
  );

  const pipelineSelectedCount = useMemo(() => {
    if (refinedSeries.length > 0) return refinedSeries.length;
    if (selectedKeys.size > 0) return selectedKeys.size;
    return 0;
  }, [refinedSeries.length, selectedKeys.size]);

  const exploreSummarizeEstimateSec = useMemo(
    () =>
      estimateSummarizeDurationSec({
        selectedCount: refinedSeries.length,
        geoMode,
        relatedSegmentsCount,
      }),
    [refinedSeries.length, geoMode, relatedSegmentsCount],
  );

  const pipelineFetchTotal = useMemo(() => {
    const fromServer = parseFetchTotalFromServerHint(summarizePendingDetail || "");
    if (fromServer != null) return fromServer;
    return null;
  }, [summarizePendingDetail]);

  const pipelineFetchLine = useMemo(
    () =>
      formatPipelineFetchLine({
        selectedCount: pipelineSelectedCount,
        geoMode,
        relatedSegmentsCount,
        actualFetchTotal: pipelineFetchTotal,
      }),
    [pipelineSelectedCount, geoMode, relatedSegmentsCount, pipelineFetchTotal],
  );

  const showStep2PipelineProgress = step === 2 && (loadingSector || loadingRefine || loadingSummarize);

  useEffect(() => {
    if (step !== 2 || !pipelineStartedAtMs || !pipelineEtaSec) return undefined;
    const tick = () => {
      const elapsed = Math.floor((Date.now() - pipelineStartedAtMs) / 1000);
      setPipelineElapsedSec(elapsed);
      setPipelineSecondsLeft(Math.max(0, pipelineEtaSec - elapsed));
    };
    tick();
    if (!loadingSector && !loadingRefine && !loadingSummarize) return undefined;
    const id = window.setInterval(tick, 1000);
    return () => window.clearInterval(id);
  }, [
    step,
    pipelineStartedAtMs,
    pipelineEtaSec,
    loadingSector,
    loadingRefine,
    loadingSummarize,
  ]);

  const relatedSegmentRankingPayload = useMemo(() => {
    if (!unifiedRelatedItems.length) return null;
    return JSON.stringify(
      unifiedRelatedItems.map((item) => ({
        key: item.key,
        label: item.label,
        sector_id: item.sector_id || null,
        rank: item.rank,
        weight: item.weight,
        relationship_type: item.relationship_type || null,
        source: item.source,
      }))
    );
  }, [unifiedRelatedItems]);

  const relatedSegmentsPayload = useMemo(() => {
    if (managerAnalysisMode !== "sector" || !String(sector || "").trim()) return null;
    return combinedRelatedSegments;
  }, [managerAnalysisMode, sector, combinedRelatedSegments]);

  const managerSectorOptions = useMemo(
    () =>
      sortExploreSegmentLabels([
        ...managerSectorHints.map((label) => String(label || "").trim()).filter(Boolean),
        sector,
      ]),
    [managerSectorHints, sector]
  );

  const handlePrimarySectorChange = (nextValue) => {
    setSector(String(nextValue || "").trim());
    setSupplementarySuggestError("");
    setSupplementarySuggestNotice("");
    setRelatedSuggestError("");
    resetGeoRefine();
  };

  const supplementarySegmentOptions = useMemo(() => {
    return sortExploreSegmentLabels([
      ...managerSectorHints.map((label) => String(label || "").trim()).filter(Boolean),
      ...supplementarySegmentSelections,
    ]);
  }, [managerSectorHints, supplementarySegmentSelections]);

  const exploreCountryGroups = useMemo(() => {
    if (countryGroups.length) return countryGroups;
    return groupExploreCountryOptions(countryOptions);
  }, [countryGroups, countryOptions]);

  const selectedCountryCodes = useMemo(
    () => normalizeExploreCountryCodes(countrySelections),
    [countrySelections]
  );

  const countryLabelMap = useMemo(() => {
    const map = new Map();
    [...countryOptions, ...countryHints].forEach((item) => {
      const code = String(item?.code || "").trim().toUpperCase();
      const label = String(item?.label_cs || item?.label || item?.code || "").trim();
      if (code && label && !map.has(code)) map.set(code, label);
    });
    return map;
  }, [countryOptions, countryHints]);

  const selectedCountryLabels = useMemo(
    () => selectedCountryCodes.map((code) => countryLabelMap.get(code) || code),
    [selectedCountryCodes, countryLabelMap]
  );

  const primaryCountryLabel = selectedCountryLabels[0] || "";
  const relatedCountryLabels = selectedCountryLabels.slice(1);

  const resolvedCountryLabels = useMemo(
    () =>
      normalizeExploreCountryCodes(resolvedCountryCodes).map(
        (code) => countryLabelMap.get(code) || code
      ),
    [resolvedCountryCodes, countryLabelMap]
  );

  const resolvedPrimaryCountryLabel = resolvedCountryLabels[0] || "";
  const resolvedRelatedCountryLabels = resolvedCountryLabels.slice(1);

  const buildGeoPayload = () => {
    if (geoMode === "none") {
      return { country: "", geo_mode: "none", continent: null };
    }
    if (geoMode === "continent") {
      return {
        country: "",
        geo_mode: "continent",
        continent: selectedContinent || "europe",
      };
    }
    return {
      country: selectedCountryCodes.join(", "),
      geo_mode: "countries",
      continent: null,
    };
  };

  const resetGeoRefine = () => {
    setRefinedSeries([]);
    setResolvedCountryCodes([]);
    setImfPrefetchedItems([]);
    setImfContextMeta(null);
  };

  const applySectorHierarchySwap = useCallback((relatedSectorLabel) => {
    const next = swapPrimaryWithRelated({
      primarySector: sector,
      relatedSector: relatedSectorLabel,
      supplementarySegments: selectedSupplementarySegments,
      relatedSegmentsText: relatedSegments,
    });
    if (!next) return;

    setSector(next.primarySector);
    setSupplementarySegmentSelections(next.supplementarySegmentSelections);
    setRelatedSegments(next.relatedSegmentsText);
    setExcludedRelatedKeys([]);
    setRelatedSegmentOrderKeys([]);
    setSupplementarySuggestError("");
    setSupplementarySuggestNotice("");
    setRelatedSuggestError("");
    setSectorIndicators([]);
    setMacroIndicators([]);
    setIndicatorSections([]);
    setSelectedKeys(new Set());
    resetGeoRefine();
    setSummarizeResult(null);
    setSummarizeJobId("");
    setSummarizePendingDetail("");
    setMatchedPresetLabel("");
    setLoadHint(
      `Hlavní sektor byl přehozen na „${next.primarySector}“. Původní hlavní sektor je nyní mezi related sektory. Zkontrolujte vstupy a znovu vyhodnoťte.`
    );
    setStep(1);
  }, [relatedSegments, sector, selectedSupplementarySegments]);

  const reorderUnifiedRelatedItem = useCallback((fromKey, toKey) => {
    const currentKeys = unifiedRelatedItems.map((item) => item.key);
    const fromIdx = currentKeys.indexOf(fromKey);
    const toIdx = currentKeys.indexOf(toKey);
    if (fromIdx < 0 || toIdx < 0 || fromIdx === toIdx) return;
    const next = [...currentKeys];
    next.splice(fromIdx, 1);
    next.splice(toIdx, 0, fromKey);
    setRelatedSegmentOrderKeys(next);
    resetGeoRefine();
  }, [unifiedRelatedItems]);

  const removeUnifiedRelatedItem = useCallback(
    (item) => {
      const key = segmentLabelKey(item?.key || item?.label);
      if (!key) return;
      if (item?.source === "predefined") {
        setExcludedRelatedKeys((prev) => [...new Set([...prev, key])]);
      } else if (item?.source === "manual") {
        setSupplementarySegmentSelections((prev) => {
          const next = prev.filter((label) => segmentLabelKey(label) !== key);
          return next.length ? next : [""];
        });
      } else {
        const parts = splitExploreRelatedValues(relatedSegments).filter(
          (part) => segmentLabelKey(part) !== key
        );
        setRelatedSegments(parts.join(", "));
      }
      setRelatedSegmentOrderKeys((prev) => prev.filter((entry) => segmentLabelKey(entry) !== key));
      setSupplementarySuggestError("");
      setSupplementarySuggestNotice("");
      setRelatedSuggestError("");
      resetGeoRefine();
    },
    [relatedSegments]
  );

  const addRelatedSegmentFromPicker = () => {
    const label = String(pendingRelatedSegmentAdd || "").trim();
    if (!label) return;
    const key = segmentLabelKey(label);
    setExcludedRelatedKeys((prev) => prev.filter((item) => item !== key));
    appendSupplementarySegments([label]);
    setPendingRelatedSegmentAdd("");
  };

  const clearAllRelatedSegments = () => {
    setExcludedRelatedKeys(
      displayRelationshipRelatedRows
        .map((row) => segmentLabelKey(row?.sector_id || row?.sector_name_cs))
        .filter(Boolean)
    );
    setSupplementarySegmentSelections([""]);
    setRelatedSegments("");
    setRelatedSegmentOrderKeys([]);
    setPendingRelatedSegmentAdd("");
    setSupplementarySuggestError("");
    setSupplementarySuggestNotice("");
    setRelatedSuggestError("");
    resetGeoRefine();
  };

  useEffect(() => {
    setExcludedRelatedKeys([]);
    setRelatedSegmentOrderKeys([]);
    setPendingRelatedSegmentAdd("");
  }, [sector]);

  const appendSupplementarySegments = (values) => {
    const merged = normalizeExploreSegmentValues([
      ...supplementarySegmentSelections,
      ...(Array.isArray(values) ? values : [values]),
    ]);
    setSupplementarySegmentSelections(merged.length ? merged : [""]);
    setSupplementarySuggestError("");
    setSupplementarySuggestNotice("");
    resetGeoRefine();
  };

  const startNewExploreQuery = () => {
    setStep(1);
    setSector("");
    setSupplementarySegmentSelections([""]);
    setGeoMode("countries");
    setSelectedContinent("europe");
    setCountrySelections([""]);
    setCountrySuggestError("");
    setCountrySuggestNotice("");
    setSupplementarySuggestError("");
    setSupplementarySuggestNotice("");
    setRelatedSegments("");
    setExcludedRelatedKeys([]);
    setRelatedSegmentOrderKeys([]);
    setPendingRelatedSegmentAdd("");
    setRelationshipRelatedRows([]);
    setRelatedSuggestions([]);
    setQuestion("");
    setQueryUnderstandingPreview(null);
    setPendingClarification(null);
    setSectorIndicators([]);
    setMacroIndicators([]);
    setSelectedKeys(new Set());
    setRefinedSeries([]);
    setResolvedCountryCodes([]);
    setLoadingSector(false);
    setLoadingRefine(false);
    setLoadingSummarize(false);
    setSummarizeJobId("");
    setSummarizePendingDetail("");
    setSummarizeMode("fast");
    setError("");
    setLoadHint("");
    setMatchedPresetLabel("");
    setSummarizeResult(null);
    setExploreExtraSeries([]);
    setUserPickedSeries([]);
    setImfPrefetchedItems([]);
    setImfContextMeta(null);
    setSelectedUploadIds([]);
    setUserDataPrivacyMode("strict_private");
    setManagerAnalysisScope("auto");
    setUserDataParticipation("final_compare");
    setUploadNotice("");
    setUploadError("");
    if (typeof window !== "undefined") {
      window.scrollTo({ top: 0, behavior: "smooth" });
    }
  };

  const toggleSelectedUpload = (uploadId) => {
    const normalized = String(uploadId || "").trim();
    if (!normalized) return;
    setSelectedUploadIds((prev) =>
      prev.includes(normalized) ? prev.filter((id) => id !== normalized) : [...prev, normalized]
    );
    setUploadNotice("");
    setUploadError("");
  };

  const loadUploadPreview = useCallback(async (uploadId) => {
    const normalized = String(uploadId || "").trim();
    if (!normalized) return;
    setUploadPreviews((prev) => {
      const current = prev[normalized];
      if (current?.status === "loading" || current?.status === "ready") return prev;
      return { ...prev, [normalized]: { status: "loading" } };
    });
    try {
      const { data } = await api.get(`/me/uploads/${normalized}/preview`, { timeout: 30000 });
      setUploadPreviews((prev) => ({
        ...prev,
        [normalized]: {
          status: "ready",
          columns: Array.isArray(data?.columns) ? data.columns : [],
          sample_rows: Array.isArray(data?.sample_rows) ? data.sample_rows : [],
          error: data?.error ? String(data.error) : "",
        },
      }));
    } catch (e) {
      setUploadPreviews((prev) => ({
        ...prev,
        [normalized]: {
          status: "error",
          error: formatApiErrorFromAxios(e) || "Náhled se nepodařil načíst.",
        },
      }));
    }
  }, []);

  const toggleUploadExpanded = useCallback(
    (uploadId) => {
      const normalized = String(uploadId || "").trim();
      if (!normalized) return;
      setExpandedUploadIds((prev) => {
        const open = prev.includes(normalized);
        if (open) return prev.filter((id) => id !== normalized);
        void loadUploadPreview(normalized);
        return [...prev, normalized];
      });
    },
    [loadUploadPreview]
  );

  const handleUserFileUpload = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    setUploadingUserFile(true);
    setUploadNotice("");
    setUploadError("");
    try {
      const body = new FormData();
      body.append("file", file, file.name);
      const { data } = await postFormData("/me/uploads", body);
      const newUploadId = String(data?.id || data?.upload_id || "").trim();
      await loadUserUploads({ silent: false });
      if (newUploadId) {
        setSelectedUploadIds((prev) => (prev.includes(newUploadId) ? prev : [newUploadId, ...prev]));
        setExpandedUploadIds((prev) => (prev.includes(newUploadId) ? prev : [newUploadId, ...prev]));
        void loadUploadPreview(newUploadId);
      }
      setUploadNotice(`${file.name} byl nahrán a bude zahrnut do finální analýzy i skóre.`);
    } catch (e) {
      setUploadError(formatApiErrorFromAxios(e) || "Soubor se nepodařilo nahrát.");
    } finally {
      setUploadingUserFile(false);
    }
  };

  const geoSummaryLabel = useMemo(() => {
    if (geoMode === "none") return "Svět (globální kontext)";
    if (geoMode === "continent") {
      return EXPLORE_CONTINENTS.find((c) => c.id === selectedContinent)?.label || selectedContinent;
    }
    const main = primaryCountryLabel || resolvedPrimaryCountryLabel;
    const related = relatedCountryLabels.length ? relatedCountryLabels : resolvedRelatedCountryLabels;
    if (!main) return "—";
    return related.length ? `Hlavní: ${main} · Přidružené: ${related.join(", ")}` : `Hlavní: ${main}`;
  }, [
    geoMode,
    selectedContinent,
    primaryCountryLabel,
    relatedCountryLabels,
    resolvedPrimaryCountryLabel,
    resolvedRelatedCountryLabels,
  ]);

  const validateGeoForSearch = () => {
    const q = String(question || "").trim();
    if (q.length >= 2 && geoMode === "countries" && !selectedCountryCodes.length) {
      return true;
    }
    if (geoMode === "countries" && !selectedCountryCodes.length) {
      setError("Zadejte alespoň jednu zemi, zadejte dotaz s zemí v textu, nebo zvolte „Svět“ / kontinent.");
      return false;
    }
    return true;
  };

  const buildRelatedSuggestionsPayload = () => ({
    sector: String(sector || "").trim(),
    related_segments: relatedSegmentsPayload,
    related_segment_ranking: relatedSegmentRankingPayload,
    ...buildGeoPayload(),
  });

  // `relatedSegmentsPayload`/`relatedSegmentRankingPayload` are derived (in part) from
  // `relationshipRelatedRows` — the very state this callback sets. Reading them via ref instead
  // of a closure/dependency means their value at call time is always current, without making
  // them part of `refreshRelationshipRelatedRows`'s own identity: including them in its deps
  // created a feedback loop (setting rows -> combinedRelatedSegments changes -> callback
  // recreated -> triggering effect refires -> sets rows again -> ...), observed live as dozens
  // of repeated POSTs to /explore/related-suggestions for a single sector selection.
  const relatedSuggestionsContextRef = useRef({ relatedSegmentsPayload, relatedSegmentRankingPayload });
  relatedSuggestionsContextRef.current = { relatedSegmentsPayload, relatedSegmentRankingPayload };
  const refreshRelationshipRelatedRowsInFlightRef = useRef(false);

  const refreshRelationshipRelatedRows = useCallback(async () => {
    const sec = String(sector || "").trim();
    if (!sec) {
      setRelationshipRelatedRows([]);
      return;
    }
    if (refreshRelationshipRelatedRowsInFlightRef.current) return;
    refreshRelationshipRelatedRowsInFlightRef.current = true;
    setRelationshipRelatedRows(localRelatedSegmentRows(sec, { limit: 8 }));
    setLoadingRelationshipRows(true);
    const { relatedSegmentsPayload: relSegPayload, relatedSegmentRankingPayload: relRankPayload } =
      relatedSuggestionsContextRef.current;
    const payload = {
      sector: sec,
      related_segments: relSegPayload,
      related_segment_ranking: relRankPayload,
      ...buildGeoPayload(),
    };
    try {
      let data = null;
      const paths = ["/explore/related-suggestions", "/explore/sector/related-suggestions"];
      for (const path of paths) {
        try {
          const res = await api.post(path, payload, { timeout: 20000 });
          data = res.data;
          break;
        } catch (e) {
          if (e?.response?.status !== 404) throw e;
        }
      }
      if (!data) {
        const res = await api.post(
          "/explore/sector",
          { ...payload, suggestions_only: true },
          { timeout: 20000 }
        );
        data = res.data;
      }
      // The API returns rows shaped as { related_segment_id, related_segment_name_cs, ... },
      // while the rest of this component (relatedRowKey/relatedRowLabel in
      // exploreSectorHierarchy.js) reads { sector_id, sector_name_cs, ... } — the same shape
      // mismatch mapRelationshipRowToLinkedSector already normalizes for the local-table path.
      // Without this, API rows silently looked unlabeled/unkeyed and were filtered out of
      // unifiedRelatedItems entirely, which is what made combinedRelatedSegments flip between
      // "" and the real value every time this ran, feeding the loop above.
      const rows = Array.isArray(data?.related_segment_rows)
        ? data.related_segment_rows.map(mapRelationshipRowToLinkedSector).filter(Boolean)
        : [];
      if (rows.length) {
        setRelationshipRelatedRows(rows);
      }
    } catch  {
      /* lokální fallback už je nastavený */
    } finally {
      setLoadingRelationshipRows(false);
      refreshRelationshipRelatedRowsInFlightRef.current = false;
    }
  }, [geoMode, sector, selectedContinent, selectedCountryCodes]);

  useEffect(() => {
    const sec = String(sector || "").trim();
    if (!sec) {
      setRelationshipRelatedRows([]);
      return undefined;
    }
    const timer = window.setTimeout(() => {
      void refreshRelationshipRelatedRows();
    }, 250);
    return () => window.clearTimeout(timer);
  }, [sector, refreshRelationshipRelatedRows]);

  const fetchSupplementarySuggestions = async () => {
    const sec = String(sector || "").trim();
    if (!sec) {
      setSupplementarySuggestError("Nejdřív zadejte hlavní segment.");
      return;
    }
    setLoadingSupplementarySuggest(true);
    setSupplementarySuggestError("");
    setSupplementarySuggestNotice("");

    const geoLabel =
      geoMode === "none"
        ? "Svět (globální kontext)"
        : geoMode === "continent"
          ? EXPLORE_CONTINENTS.find((c) => c.id === selectedContinent)?.label || selectedContinent
          : geoSummaryLabel;

    const local = localRelatedSegmentSuggestions(sec, { geoLabel, limit: 6 });
    let lastErr = null;
    try {
      let data = null;
      const payload = buildRelatedSuggestionsPayload();
      const paths = ["/explore/related-suggestions", "/explore/sector/related-suggestions"];
      for (const path of paths) {
        try {
          const res = await api.post(path, payload, { timeout: 25000 });
          data = res.data;
          break;
        } catch (e) {
          lastErr = e;
          if (e?.response?.status !== 404) throw e;
        }
      }
      if (!data) {
        const res = await api.post(
          "/explore/sector",
          { ...payload, suggestions_only: true },
          { timeout: 25000 }
        );
        data = res.data;
      }
      const list = Array.isArray(data?.suggestions) ? data.suggestions : local;
      const apiRows = Array.isArray(data?.related_segment_rows)
        ? data.related_segment_rows.map(mapRelationshipRowToLinkedSector).filter(Boolean)
        : [];
      if (apiRows.length) {
        setRelationshipRelatedRows(apiRows);
      }
      const allowedSegmentMap = new Map(
        managerSectorHints
          .map((label) => String(label || "").trim())
          .filter(Boolean)
          .map((label) => [label.toLowerCase(), label])
      );
      const nextSegments = normalizeExploreSegmentValues(list)
        .map((item) => allowedSegmentMap.get(String(item || "").trim().toLowerCase()) || "")
        .filter(Boolean);
      const added = nextSegments.filter(
        (item) => !selectedSupplementarySegments.some((existing) => existing.toLowerCase() === item.toLowerCase())
      );
      if (!added.length) {
        setSupplementarySuggestError("AI nenašla žádné nové doplňkové segmenty z našich předdefinovaných 20 segmentů.");
        return;
      }
      appendSupplementarySegments(added);
      setSupplementarySuggestNotice(`AI doplnila: ${added.join(", ")}`);
    } catch (e) {
      const status = e?.response?.status;
      if (status === 404) {
        setSupplementarySuggestError(
          "Endpoint pro návrhy není na backendu (404). Restartujte uvicorn s --reload."
        );
      } else {
        setSupplementarySuggestError(
          formatApiErrorFromAxios(e) ||
            formatApiErrorFromAxios(lastErr) ||
            "Doplňkové segmenty se nepodařilo navrhnout. Zkuste to znovu."
        );
      }
    } finally {
      setLoadingSupplementarySuggest(false);
    }
  };

  const fetchRelatedSuggestions = async () => {
    const sec = String(sector || "").trim();
    if (!sec) {
      setRelatedSuggestError("Nejdřív zadejte hlavní segment.");
      return;
    }
    setLoadingRelatedSuggest(true);
    setRelatedSuggestError("");

    const geoLabel =
      geoMode === "none"
        ? "Svět (globální kontext)"
        : geoMode === "continent"
          ? EXPLORE_CONTINENTS.find((c) => c.id === selectedContinent)?.label || selectedContinent
          : geoSummaryLabel;

    const local = localRelatedSegmentSuggestions(sec, { geoLabel, limit: 6 });
    if (local.length) {
      setRelatedSuggestions(local);
    }

    const payload = buildRelatedSuggestionsPayload();
    const paths = ["/explore/related-suggestions", "/explore/sector/related-suggestions"];
    let lastErr = null;
    try {
      let data = null;
      for (const path of paths) {
        try {
          const res = await api.post(path, payload, { timeout: 25000 });
          data = res.data;
          break;
        } catch (e) {
          lastErr = e;
          if (e?.response?.status !== 404) throw e;
        }
      }
      if (!data) {
        const res = await api.post(
          "/explore/sector",
          { ...payload, suggestions_only: true },
          { timeout: 25000 }
        );
        data = res.data;
      }
      const list = Array.isArray(data?.suggestions) ? data.suggestions : [];
      if (list.length) {
        setRelatedSuggestions(list);
      } else if (!local.length) {
        setRelatedSuggestError("AI nenavrhla žádná přidružená témata — zkuste doplnit ručně.");
      }
    } catch (e) {
      const status = e?.response?.status;
      if (status === 404) {
        setRelatedSuggestError(
          "Endpoint pro návrhy není na backendu (404). Restartujte uvicorn s --reload."
        );
      } else if (local.length) {
        setRelatedSuggestError(
          "Server neodpověděl včas — zobrazeny rychlé lokální návrhy. Pro AI doplnění zkuste znovu za chvíli."
        );
      } else {
        setRelatedSuggestError(
          formatApiErrorFromAxios(e) ||
            formatApiErrorFromAxios(lastErr) ||
            "Návrhy se nepodařily načíst. Zkuste to znovu."
        );
      }
    } finally {
      setLoadingRelatedSuggest(false);
    }
  };

  const appendRelatedSuggestion = (text) => {
    const t = String(text || "").trim();
    if (!t) return;
    const parts = splitExploreRelatedValues(relatedSegments);
    if (parts.some((p) => segmentLabelKey(p) === segmentLabelKey(t))) return;
    if (unifiedRelatedItems.some((item) => segmentLabelKey(item.label) === segmentLabelKey(t))) return;
    setRelatedSegments(parts.length ? `${parts.join(", ")}, ${t}` : t);
  };

  const updateCountrySelection = (index, nextCode) => {
    setCountrySelections((prev) => {
      const next = [...prev];
      next[index] = String(nextCode || "").trim().toUpperCase();
      return next;
    });
    setCountrySuggestError("");
    setCountrySuggestNotice("");
    resetGeoRefine();
  };

  const addCountrySelection = (prefill = "") => {
    setCountrySelections((prev) => [...prev, String(prefill || "").trim().toUpperCase()]);
    setCountrySuggestError("");
    setCountrySuggestNotice("");
    resetGeoRefine();
  };

  const removeCountrySelection = (index) => {
    setCountrySelections((prev) => {
      const next = prev.filter((_, idx) => idx !== index);
      return next.length ? next : [""];
    });
    setCountrySuggestError("");
    setCountrySuggestNotice("");
    resetGeoRefine();
  };

  const appendCountryCodes = (codes) => {
    const merged = normalizeExploreCountryCodes([...selectedCountryCodes, ...(codes || [])]);
    setCountrySelections(merged.length ? merged : [""]);
    setCountrySuggestError("");
    setCountrySuggestNotice("");
    resetGeoRefine();
  };

  const fetchCountrySuggestions = async () => {
    if (geoMode !== "countries") {
      setCountrySuggestError("AI doporučení zemí funguje jen při výběru konkrétních zemí.");
      return;
    }
    if (!selectedCountryCodes.length) {
      setCountrySuggestError("Nejdřív vyberte alespoň jednu zemi.");
      return;
    }
    setLoadingCountrySuggest(true);
    setCountrySuggestError("");
    setCountrySuggestNotice("");
    const payload = {
      country: selectedCountryCodes.join(", "),
      geo_mode: "countries",
      continent: null,
      sector: String(sector || "").trim() || null,
    };
    const paths = ["/explore/geo-country-suggestions", "/explore/country-suggestions"];
    try {
      let data = null;
      for (const path of paths) {
        try {
          const res = await api.post(path, payload, { timeout: 15000 });
          data = res.data;
          break;
        } catch (e) {
          if (e?.response?.status !== 404) throw e;
        }
      }
      const suggestions = Array.isArray(data?.suggestions) ? data.suggestions : [];
      const nextCodes = suggestions
        .map((item) => String(item?.code || "").trim().toUpperCase())
        .filter(Boolean);
      const before = selectedCountryCodes.length;
      appendCountryCodes(nextCodes);
      const added = normalizeExploreCountryCodes(nextCodes).filter(
        (code) => !selectedCountryCodes.includes(code)
      );
      if (!added.length) {
        setCountrySuggestError("AI nenašla žádné nové sousedy ani obchodní partnery k doplnění.");
      } else {
        const labels = added.map((code) => countryLabelMap.get(code) || code);
        setCountrySuggestNotice(`AI doplnila: ${labels.join(", ")}`);
      }
      if (!suggestions.length && before === selectedCountryCodes.length) {
        setCountrySuggestError("AI nenašla žádné nové sousedy ani obchodní partnery k doplnění.");
      }
    } catch (e) {
      setCountrySuggestError(
        formatApiErrorFromAxios(e) || "Doporučení zemí se nepodařilo načíst. Zkuste to znovu."
      );
    } finally {
      setLoadingCountrySuggest(false);
    }
  };

  const toggleQuickCountry = (code) => {
    const upper = String(code || "").trim().toUpperCase();
    const has = selectedCountryCodes.includes(upper);
    const next = has
      ? selectedCountryCodes.filter((item) => item !== upper)
      : [...selectedCountryCodes, upper];
    setCountrySelections(next.length ? next : [""]);
    setCountrySuggestError("");
    setCountrySuggestNotice("");
    resetGeoRefine();
  };

  const toggleSeries = (item) => {
    const key = seriesKey(item);
    setSelectedKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const toggleSectionSelection = (items, selectAll) => {
    setSelectedKeys((prev) => {
      const next = new Set(prev);
      for (const item of items || []) {
        const key = seriesKey(item);
        if (selectAll) next.add(key);
        else next.delete(key);
      }
      return next;
    });
  };

  const toggleAllIndicators = (selectAll) => {
    toggleSectionSelection(allIndicators, selectAll);
  };

  const applyInferredGeoFromQueryUnderstanding = useCallback((qu) => {
    const codes = countryCodesFromQueryUnderstanding(qu);
    if (codes.length) {
      setGeoMode("countries");
      setCountrySelections(codes);
      setCountrySuggestError("");
      setCountrySuggestNotice("");
      return;
    }
    const primary = String(qu?.geo?.primary_country_or_region || "").trim();
    if (!primary) return;
    const match = countryOptions.find(
      (item) =>
        String(item?.label_cs || "").trim().toLowerCase() === primary.toLowerCase() ||
        String(item?.code || "").trim().toUpperCase() === primary.toUpperCase()
    );
    if (match?.code) {
      setGeoMode("countries");
      setCountrySelections([String(match.code).trim().toUpperCase()]);
      setCountrySuggestError("");
      setCountrySuggestNotice("");
    }
  }, [countryOptions]);

  const applyExploreSectorPayload = (data, { partial = false, runId = null } = {}) => {
    if (runId != null && runId !== exploreRunIdRef.current) return;
    const parsed = parseExploreManagerPayload(data);
    const qu = data?.query_understanding;
    if (qu && typeof qu === "object") {
      setQueryUnderstandingPreview(qu);
      const inferredLabel = String(parsed.meta?.sectorLabel || data?.matched_preset_label || "").trim();
      if (!String(sector || "").trim() && inferredLabel) {
        handlePrimarySectorChange(inferredLabel);
      }
      if (!selectedCountryCodes.length) {
        applyInferredGeoFromQueryUnderstanding(qu);
      }
    }
    setSectorIndicators(parsed.sectorIndicators);
    setMacroIndicators(parsed.macroIndicators);
    setIndicatorSections(parsed.sections);
    setManagerMeta(parsed.meta);
    if (data?.manager_run_trace && typeof data.manager_run_trace === "object") {
      setManagerRunTrace(data.manager_run_trace);
    }
    setMatchedPresetLabel(parsed.meta.sectorLabel || String(data?.matched_preset_label || "").trim());
    const macroNote = String(data?.macro_enrichment_note || "").trim();
    if (macroNote && !partial) {
      setLoadHint(macroNote);
    }
    setSelectedKeys((prev) => {
      const next = new Set(partial ? prev : []);
      for (const key of parsed.preselectKeys) next.add(key);
      return next;
    });
    const defaultSelectedCount = parsed.preselectKeys.length;
    if (partial) {
      setLoadHint(
        parsed.sectorIndicators.length > 0 && parsed.macroIndicators.length > 0
          ? `Načteno ${defaultSelectedCount} předvybraných JSON řad (segment + makro) — doplňuji širší discovery…`
          : parsed.sectorIndicators.length > 0
            ? `Načteno ${defaultSelectedCount} předvybraných segmentových řad z JSON — doplňuji makro a širší discovery…`
            : "Čekám na první curated řady z JSON a na širší discovery…"
      );
      return;
    }
    const recCount = parsed.recommended.length;
    if (parsed.allRows.length) {
      const planHint = (data?.analysis_plan?.selection_explanation || []).slice(0, 2).join(" ");
      const stats = data?.analysis_plan?.selection_stats;
      const tierLine = stats
        ? ` must_have=${stats?.tier_counts?.must_have ?? 0}, medium=${stats?.tier_counts?.medium ?? 0}.`
        : "";
      setLoadHint(
        recCount > 0
          ? `Automaticky vybráno ${defaultSelectedCount} řad z ${parsed.allRows.length} (kurátorovaný plán).${tierLine}${planHint ? ` ${planHint}` : ""}`
          : `Automaticky vybráno ${defaultSelectedCount} řad z ${parsed.allRows.length}.${tierLine}`
      );
    } else {
      const sectorLabelForHint = String(
        parsed.meta?.sectorLabel || data?.matched_preset_label || data?.matched_manager_sector_name_cs || sector || ""
      ).trim();
      setLoadHint(
        String(data?.empty_hint || "").trim()
          || `Pro „${sectorLabelForHint || "vybraný segment"}“ jsme nenašli žádné ukazatele. Zkuste jiný název odvětví.`
      );
    }
  };

  const handleExploreClarificationPayload = (data) => {
    if (!isExploreNeedsClarification(data)) return null;
    const qu =
      data?.query_understanding && typeof data.query_understanding === "object"
        ? data.query_understanding
        : null;
    if (qu) setQueryUnderstandingPreview(qu);
    setPendingClarification(data);
    setError("");
    setLoadHint(exploreClarificationMessage(data));
    return { needsClarification: true, data };
  };

  const confirmClarificationChoice = async (option, { useFallbackAssumption = false } = {}) => {
    const segmentId = String(option?.segment_id || "").trim();
    if (segmentId === "other_manual") {
      openExpertSettings();
      setError("Upřesněte segment ručně v expertním nastavení nebo přepište dotaz (např. „automotive v Německu“).");
      return;
    }
    const sectorLabel = resolveSectorLabelForClarificationOption(option, managerSectorById);
    if (!sectorLabel) {
      setError("Nepodařilo se určit segment — zkuste expert override v nastavení.");
      return;
    }
    const qu =
      pendingClarification?.query_understanding && typeof pendingClarification.query_understanding === "object"
        ? pendingClarification.query_understanding
        : queryUnderstandingPreview;
    const geoOverride = buildGeoPayloadFromQueryUnderstanding(qu);
    setPendingClarification(null);
    setError("");
    setLoadHint("");
    setAssumptionFallbackActive(Boolean(useFallbackAssumption));
    handlePrimarySectorChange(sectorLabel);
    applyInferredGeoFromQueryUnderstanding(qu);
    await continueToQuestionPrepare({
      skipClarificationGate: true,
      sectorOverride: sectorLabel,
      geoOverride: geoOverride || undefined,
    });
  };

  const confirmClarificationFallback = async () => {
    const fallbackId = exploreClarificationFallbackSegment(pendingClarification || {});
    const options = exploreClarificationOptions(pendingClarification || {});
    const match =
      options.find((row) => String(row?.segment_id || "").trim() === fallbackId)
      || { segment_id: fallbackId, label: "Zpracovatelský průmysl obecně" };
    await confirmClarificationChoice(match, { useFallbackAssumption: true });
  };

  const probeQueryUnderstanding = async ({ questionText, signal, skipClarificationGate = false }) => {
    const q = String(questionText || "").trim();
    if (!q || skipClarificationGate) return null;
    try {
      const { data } = await api.post(
        "/explore/query-understanding",
        { query: q },
        { timeout: 45000, signal }
      );
      if (!data) return null;
      if (!skipClarificationGate) {
        const clarification = handleExploreClarificationPayload(data);
        if (clarification) return clarification;
      }
      const qu = data?.query_understanding;
      if (qu && typeof qu === "object") {
        setQueryUnderstandingPreview(qu);
        if (!String(sector || "").trim()) {
          const inferredLabel = String(
            managerSectorById.get(String(qu.primary_segment || "").trim()) || ""
          ).trim();
          if (inferredLabel) handlePrimarySectorChange(inferredLabel);
        }
        if (!selectedCountryCodes.length) {
          applyInferredGeoFromQueryUnderstanding(qu);
        }
      }
      return { ok: true, data };
    } catch  {
      return null;
    }
  };

  const stopAnalysis = () => {
    exploreRunIdRef.current += 1;
    summarizePollTokenRef.current += 1;
    try {
      sectorLoadAbortRef.current?.abort();
    } catch  {
      /* noop */
    }
    sectorLoadAbortRef.current = null;
    setLoadingSector(false);
    setLoadingRefine(false);
    setLoadingSummarize(false);
    setSummarizeJobId("");
    setSummarizePendingDetail("");
    setPipelineStartedAtMs(0);
    setPipelineEtaSec(0);
    setPipelineSecondsLeft(0);
    setPipelineElapsedSec(0);
    setQuickDataPreviewLoading(false);
    setLoadHint("Analýza zastavena");
  };

  const resetExplorePipelineProgress = () => {
    setPipelineStartedAtMs(0);
    setPipelineEtaSec(0);
    setPipelineSecondsLeft(0);
    setPipelineElapsedSec(0);
  };

  const handleExploreRunFailure = (err, { aborted = false } = {}) => {
    if (aborted || isAxiosRequestAborted(err)) return;
    const payloadErr = String(err?.payload?.error || "").trim();
    if (payloadErr) {
      setError(payloadErr);
      return;
    }
    if (isAxiosRequestTimeout(err)) {
      setError("Požadavek vypršel, spusťte analýzu znovu");
      setLoadHint("Požadavek vypršel, spusťte analýzu znovu");
      return;
    }
    const msg = formatApiErrorFromAxios(err) || String(err?.message || "").trim();
    if (msg) setError(msg);
  };

  const loadIndicators = async ({
    skipClarificationGate = false,
    sectorOverride = null,
    geoOverride = null,
    runId,
    abortController,
    isCurrentRun,
  }) => {
    const sec = String(sectorOverride ?? sector ?? "").trim();
    const q = String(question || "").trim();
    if (managerAnalysisMode === "sector" && !sec && !q) {
      setError("Zadejte dotaz — segment a zemi aplikace odvodí automaticky.");
      return null;
    }
    if (!validateGeoForSearch()) return null;

    setLoadingSector(true);
    setError("");
    const queryOnly = managerAnalysisMode === "sector" && !sec && q.length >= 2;
    setLoadHint(
      queryOnly && !skipClarificationGate
        ? "Analyzuji dotaz…"
        : "Načítám kurátorovaný plán řad — u rozsáhlých segmentů může analýza trvat i desítky minut."
    );
    setMatchedPresetLabel("");
    setSummarizeResult(null);
    setSummarizeJobId("");
    setSummarizePendingDetail("");
    setRefinedSeries([]);
    setResolvedCountryCodes([]);
    setImfPrefetchedItems([]);
    setImfContextMeta(null);
    setSectorIndicators([]);
    setMacroIndicators([]);
    setIndicatorSections([]);
    setManagerMeta(null);
    setManagerRunTrace(null);
    setQuickDataPreview(null);
    setQuickDataPreviewLoading(false);
    setSourceStatuses([]);
    if (!skipClarificationGate) {
      setAssumptionFallbackActive(false);
      setExploreStepTimings(null);
    }
    setSelectedKeys(new Set());

    const geoPayload = geoOverride ?? buildGeoPayload();
    const stepTimings = {
      query_understanding_ms: 0,
      clarification_render_ms: 0,
      curated_plan_ms: 0,
      macro_enrichment_ms: 0,
      deep_search_ms: 0,
    };

    let curatedPreviewPayload = null;
    let previewSector = sec;
    let previewGeoPayload = geoPayload;
    try {
      if (queryOnly && !skipClarificationGate) {
        const tQu0 = performance.now();
        const quProbe = await probeQueryUnderstanding({
          questionText: q,
          signal: abortController.signal,
          skipClarificationGate,
        });
        stepTimings.query_understanding_ms = Math.round(performance.now() - tQu0);
        if (abortController.signal.aborted || !isCurrentRun()) return null;
        if (quProbe?.needsClarification) {
          stepTimings.clarification_render_ms = Math.round(performance.now() - tQu0);
          setExploreStepTimings(stepTimings);
          return quProbe;
        }
        const inferred = quProbe?.data?.query_understanding;
        if (inferred && typeof inferred === "object") {
          if (!previewSector) {
            previewSector = String(
              managerSectorById.get(String(inferred.primary_segment || "").trim())
              || inferred.sector
              || ""
            ).trim();
          }
          previewGeoPayload = buildGeoPayloadFromQueryUnderstanding(inferred) || previewGeoPayload;
        }
      }

      const previewParams = new URLSearchParams({
        ...(previewSector ? { sector: previewSector } : {}),
        analysis_mode: managerAnalysisMode,
        ...(String(question || "").trim() ? { question: String(question || "").trim() } : {}),
        ...(previewGeoPayload.country ? { country: previewGeoPayload.country } : {}),
        ...(previewGeoPayload.geo_mode ? { geo_mode: previewGeoPayload.geo_mode } : {}),
        ...(previewGeoPayload.continent ? { continent: previewGeoPayload.continent } : {}),
      });
      try {
        const { data: previewData } = await api.get(
          `/explore/sector/preset-preview?${previewParams.toString()}`,
          { timeout: 45000, signal: abortController.signal }
        );
        if (!abortController.signal.aborted && previewData && isCurrentRun()) {
          if (!skipClarificationGate) {
            const clarification = handleExploreClarificationPayload(previewData);
            if (clarification) return clarification;
          }
          if (previewData?.ok) {
            curatedPreviewPayload = previewData;
            applyExploreSectorPayload(previewData, { partial: true, runId });
          }
        }
      } catch  {
        /* preset preview je volitelný — pokračujeme streamem */
      }

      if (abortController.signal.aborted || !isCurrentRun()) return null;

      if (queryOnly && !skipClarificationGate) {
        setLoadHint("Načítám kurátorovaný plán řad — u rozsáhlých segmentů může analýza trvat i desítky minut.");
      }
      setQuickDataPreviewLoading(true);

      const streamParams = new URLSearchParams({
        ...(sec ? { sector: sec } : {}),
        analysis_mode: managerAnalysisMode,
        ...(q ? { question: q } : {}),
        ...(geoPayload.country ? { country: geoPayload.country } : {}),
        ...(geoPayload.geo_mode ? { geo_mode: geoPayload.geo_mode } : {}),
        ...(geoPayload.continent ? { continent: geoPayload.continent } : {}),
      });
      const rel = relatedSegmentsPayload;
      if (managerAnalysisMode === "sector" && sec && rel !== null) {
        streamParams.set("related_segments", rel);
      }
      if (relatedSegmentRankingPayload) {
        streamParams.set("related_segment_ranking", relatedSegmentRankingPayload);
      }

      const tStream0 = performance.now();
      const streamResult = await runExploreSectorStream({
        params: streamParams,
        signal: abortController.signal,
        timeoutMs: EXPLORE_LONG_REQUEST_TIMEOUT_MS,
        acceptEvent: isCurrentRun,
        onPreset: (payload) => {
          applyExploreSectorPayload(payload, { partial: true, runId });
          if (isCurrentRun()) setQuickDataPreviewLoading(true);
        },
        onPartial: (payload) => applyExploreSectorPayload(payload, { partial: true, runId }),
        onQuickPreview: (payload) => {
          if (!isCurrentRun()) return;
          setQuickDataPreview(payload);
          setQuickDataPreviewLoading(false);
        },
        onSourceStatus: (msg) => {
          if (!isCurrentRun()) return;
          setSourceStatuses((prev) => applySourceStatusEvent(prev, msg));
        },
      });

      stepTimings.curated_plan_ms = Math.round(performance.now() - tStream0);
      if (streamResult?.earlyComplete) {
        stepTimings.macro_enrichment_ms = 0;
        stepTimings.deep_search_ms = 0;
      } else if (streamResult?.timedOut || streamResult?.disconnected) {
        stepTimings.deep_search_ms = stepTimings.curated_plan_ms;
      }
      if (isCurrentRun()) {
        setExploreStepTimings((prev) => ({ ...(prev || {}), ...stepTimings }));
      }

      if (abortController.signal.aborted || streamResult?.aborted || !isCurrentRun()) {
        return null;
      }

      let data = streamResult?.payload;
      if (!skipClarificationGate && data) {
        const clarification = handleExploreClarificationPayload(data);
        if (clarification) return clarification;
      }
      if (shouldFallbackToExploreSectorPost(streamResult) && isCurrentRun()) {
        const { data: postData } = await api.post(
          "/explore/sector",
          {
            ...(sec ? { sector: sec } : {}),
            analysis_mode: managerAnalysisMode,
            question: q || null,
            query_only: queryOnly,
            include_user_data: selectedUploadIds.length > 0,
            upload_ids: selectedUploadIds,
            user_data_participation: userDataParticipation,
            user_data_privacy_mode: userDataPrivacyMode,
            analysis_scope: managerAnalysisScope,
            ...geoPayload,
            related_segments: rel,
            related_segment_ranking: relatedSegmentRankingPayload,
          },
          { timeout: EXPLORE_LONG_REQUEST_TIMEOUT_MS, signal: abortController.signal }
        );
        data = postData;
      } else if ((!data || streamResult?.streamError) && isCurrentRun()) {
        setError("Spojení s průběžnou analýzou bylo přerušeno. Spusťte analýzu znovu.");
        setLoadHint("");
        return null;
      }

      if (abortController.signal.aborted || !isCurrentRun()) return null;

      if (!skipClarificationGate && data) {
        const clarification = handleExploreClarificationPayload(data);
        if (clarification) return clarification;
      }

      if (data) {
        const analysisPayload = mergeExploreManagerPayloads(curatedPreviewPayload, data);
        applyExploreSectorPayload(analysisPayload, { partial: false, runId });
        return analysisPayload;
      }
      if (streamResult?.timedOut || streamResult?.disconnected) {
        setError("Požadavek vypršel, spusťte analýzu znovu");
        setLoadHint("Požadavek vypršel, spusťte analýzu znovu");
      }
      return null;
    } catch (e) {
      if (abortController.signal.aborted || !isCurrentRun()) return null;
      handleExploreRunFailure(e);
      return null;
    } finally {
      if (isCurrentRun()) {
        setLoadingSector(false);
        setQuickDataPreviewLoading(false);
      }
    }
  };

  const continueToQuestionPrepare = async ({
    skipClarificationGate = false,
    sectorOverride = null,
    geoOverride = null,
  } = {}) => {
    if (String(question || "").trim().length < 2) {
      setError("Zadejte otázku už v prvním kroku, aby AI věděla, které řady a skóre jsou relevantní.");
      return;
    }
    if (!validateGeoForSearch()) return;
    setError("");
    setSummarizeResult(null);
    if (!skipClarificationGate) {
      setPendingClarification(null);
    }

    try {
      sectorLoadAbortRef.current?.abort();
    } catch  {
      /* noop */
    }
    const runId = ++exploreRunIdRef.current;
    const abortController = new AbortController();
    sectorLoadAbortRef.current = abortController;
    const isCurrentRun = () => exploreRunIdRef.current === runId;
    const signal = abortController.signal;
    const selectedOnlyRequested = String(managerAnalysisScope || "").trim().toLowerCase() === "selected_only";

    try {
      if (selectedOnlyRequested) {
        const manualCount = userPickedSeries.length + exploreExtraSeries.length + selectedUploadIds.length;
        if (!manualCount) {
          setError("Pro reĹľim pouze vybranĂ© pĹ™idejte alespoĹ jednu Ĺ™adu nebo vlastnĂ­ soubor.");
          resetExplorePipelineProgress();
          return;
        }
        setStep(2);
        setPipelineStartedAtMs(Date.now());
        const pipelineSec = estimateExplorePipelineSec({
          selectedCount: Math.max(1, manualCount),
          geoMode,
          relatedSegmentsCount: 0,
        });
        setPipelineEtaSec(pipelineSec);
        setPipelineSecondsLeft(pipelineSec);
        setLoadHint("");
        await runSummarize([], {
          preparedSeries: [],
          preparedImfItems: [],
          sectorOverride,
          geoOverride,
          runId,
          signal,
          analysisScope: "selected_only",
        });
        return;
      }
      const loadedPayload = await loadIndicators({
        skipClarificationGate,
        sectorOverride,
        geoOverride,
        runId,
        abortController,
        isCurrentRun,
      });
      if (!isCurrentRun() || signal.aborted) return;
      if (!loadedPayload) {
        resetExplorePipelineProgress();
        return;
      }
      if (loadedPayload.needsClarification) {
        resetExplorePipelineProgress();
        return;
      }
      setStep(2);
      setPipelineStartedAtMs(Date.now());
      const parsed = parseExploreManagerPayload(loadedPayload);
      const preselectedKeys = new Set(parsed.preselectKeys || []);
      const pickedItems = (parsed.allRows || []).filter((item) => preselectedKeys.has(seriesKey(item)));
      if (!pickedItems.length) {
        setError(
          String(loadedPayload?.clarification_message || loadedPayload?.empty_hint || "").trim()
            || "Pro dotaz nebyly nalezeny žádné použitelné řady. Upřesněte téma nebo geografii.",
        );
        setStep(1);
        resetExplorePipelineProgress();
        return;
      }
      const pipelineSec = estimateExplorePipelineSec({
        selectedCount: pickedItems.length,
        geoMode,
        relatedSegmentsCount,
      });
      setPipelineEtaSec(pipelineSec);
      setPipelineSecondsLeft(pipelineSec);
      setLoadHint("");
      const prepared = await refineFilters({
        pickedItems,
        sectorOverride,
        geoOverride,
        runId,
        signal,
      });
      if (!isCurrentRun() || signal.aborted) return;
      if (!prepared) {
        setStep(1);
        resetExplorePipelineProgress();
        return;
      }
      const refinedPipelineSec = estimateExplorePipelineSec({
        selectedCount: prepared.refined.length,
        geoMode,
        relatedSegmentsCount,
      });
      setPipelineEtaSec(refinedPipelineSec);
      setPipelineSecondsLeft(refinedPipelineSec);
      await runSummarize([], {
        preparedSeries: prepared.refined,
        preparedImfItems: prepared.imfItems,
        sectorOverride,
        geoOverride,
        runId,
        signal,
      });
    } catch (e) {
      if (!isCurrentRun() || signal.aborted) return;
      handleExploreRunFailure(e);
      resetExplorePipelineProgress();
    } finally {
      if (exploreRunIdRef.current === runId) {
        sectorLoadAbortRef.current = null;
      }
      if (isCurrentRun()) {
        setLoadingSector(false);
        setLoadingRefine(false);
        setLoadingSummarize(false);
        setQuickDataPreviewLoading(false);
      }
    }
  };

  const refineFilters = async ({
    pickedItems = null,
    sectorOverride = null,
    geoOverride = null,
    runId = exploreRunIdRef.current,
    signal = sectorLoadAbortRef.current?.signal,
  } = {}) => {
    if (!validateGeoForSearch()) return null;
    const geoPayload = geoOverride ?? buildGeoPayload();
    const picked = Array.isArray(pickedItems)
      ? pickedItems
      : allIndicators.filter((x) => selectedKeys.has(seriesKey(x)));
    if (!picked.length) {
      setError("Nejsou vybrané žádné řady.");
      return null;
    }
    setLoadingRefine(true);
    setError("");
    setLoadHint("");
    setImfPrefetchedItems([]);
    setImfContextMeta(null);
    try {
      const { data } = await api.post(
        "/explore/sector/refine",
        {
          sector: String(sectorOverride ?? sector ?? "").trim(),
          ...geoPayload,
          related_segments: relatedSegmentsPayload,
          related_segment_ranking: relatedSegmentRankingPayload,
          selected_series: picked.map((x) => ({
            source: x.source,
            dataset_id: x.dataset_id,
            indicator_name: x.indicator_name,
            filters_used: x.filters_used || {},
            topic_match: x.topic_match,
            from_segment_assignment: Boolean(x.from_segment_assignment),
            from_preset: Boolean(x.from_preset),
            indicator_role: x.indicator_role || x.segment_role || null,
            confidence_score: typeof x.confidence_score === "number" ? x.confidence_score : null,
            manager_category: x.manager_category || x.category || null,
            manager_series_tier: x.manager_series_tier || null,
            from_related_segment: Boolean(x.from_related_segment),
            sector_ids: Array.isArray(x.sector_ids) ? x.sector_ids : [],
            linked_sector_id: x.linked_sector_id || null,
            primary_sector_id: x.primary_sector_id || null,
            default_selected: Boolean(x.default_selected),
            geo_expanded: Boolean(x.geo_expanded),
            geo_expansion_anchor: x.geo_expansion_anchor || null,
            segment_source: x.segment_source || x.segment_id || null,
            segment_id: x.segment_id || x.segment_source || null,
            series_id: x.series_id || null,
            business_label: x.business_label || null,
          })),
          manager_run_trace: managerRunTrace,
        },
        { timeout: EXPLORE_LONG_REQUEST_TIMEOUT_MS, signal }
      );
      if (signal?.aborted || exploreRunIdRef.current !== runId) return null;
      if (!data?.ok) {
        if (exploreRunIdRef.current !== runId) return null;
        setError(data?.error || "Příprava řad se nepodařila.");
        return null;
      }
      const refined = Array.isArray(data?.selected_series) ? data.selected_series : [];
      if (!refined.length) {
        if (exploreRunIdRef.current !== runId) return null;
        setError("Po přípravě nezůstaly žádné řady — zkuste jiné geo nebo ukazatele.");
        setRefinedSeries([]);
        setResolvedCountryCodes([]);
        return null;
      }
      if (exploreRunIdRef.current !== runId) return null;
      setRefinedSeries(refined);
      setResolvedCountryCodes(Array.isArray(data?.country_codes) ? data.country_codes : []);
      if (data?.geo_mode) setGeoMode(String(data.geo_mode));
      if (data?.continent) setSelectedContinent(String(data.continent));
      const imfItems = Array.isArray(data?.imf_loaded_items) ? data.imf_loaded_items : [];
      setImfPrefetchedItems(imfItems);
      setImfContextMeta(data?.imf_context && typeof data.imf_context === "object" ? data.imf_context : null);
      if (data?.manager_run_trace && typeof data.manager_run_trace === "object") {
        setManagerRunTrace(data.manager_run_trace);
      }
      return { refined, imfItems };
    } catch (e) {
      if (signal?.aborted || exploreRunIdRef.current !== runId) return null;
      handleExploreRunFailure(e);
      return null;
    } finally {
      if (exploreRunIdRef.current === runId) {
        setLoadingRefine(false);
      }
    }
  };

  const buildSummarizePayload = (extraRefs = [], options = {}) => {
    const preparedSeries = Array.isArray(options.preparedSeries) ? options.preparedSeries : refinedSeries;
    const analysisScope = String(options.analysisScope || managerAnalysisScope || "auto").trim().toLowerCase();
    const selectedOnly = analysisScope === "selected_only";
    const baseSeries = selectedOnly ? [] : preparedSeries;
    const merged = mergeExploreSeriesRefs(baseSeries, [...userPickedSeries, ...exploreExtraSeries, ...extraRefs]);
    const geoPayload = options.geoOverride ?? buildGeoPayload();
    const requestedMode = String(options.summarizeMode || summarizeMode || DEFAULT_SUMMARIZE_MODE).trim().toLowerCase();
    const effectiveSummarizeMode = resolveEffectiveSummarizeMode(requestedMode);
    return {
      question: String(question || "").trim(),
      sector:
        managerAnalysisMode === "sector"
          ? String(options.sectorOverride ?? sector ?? "").trim() || null
          : null,
      analysis_mode: managerAnalysisMode,
      fast_mode: effectiveSummarizeMode === "fast",
      summarize_mode: effectiveSummarizeMode,
      countries: geoPayload.country || null,
      geo_mode: geoPayload.geo_mode,
      continent: geoPayload.continent,
      related_segments: relatedSegmentsPayload,
      related_segment_ranking: relatedSegmentRankingPayload,
      include_user_data: selectedUploadIds.length > 0,
      upload_ids: selectedUploadIds,
      user_data_participation: userDataParticipation,
      user_data_privacy_mode: userDataPrivacyMode,
      analysis_scope: selectedOnly ? "selected_only" : "auto",
      selected_series: merged.map((x) => ({
        source_type: x.source,
        set_id: x.dataset_id,
        title: x.indicator_name || x.dataset_id,
        query_params: x.filters_used || {},
        topic_match: x.topic_match,
        from_segment_assignment: Boolean(x.from_segment_assignment),
        from_preset: Boolean(x.from_preset),
        indicator_role: x.indicator_role || x.segment_role || null,
            confidence_score: typeof x.confidence_score === "number" ? x.confidence_score : null,
            manager_category: x.manager_category || x.category || null,
            manager_series_tier: x.manager_series_tier || null,
            from_related_segment: Boolean(x.from_related_segment),
            sector_ids: Array.isArray(x.sector_ids) ? x.sector_ids : [],
            linked_sector_id: x.linked_sector_id || null,
            primary_sector_id: x.primary_sector_id || null,
            segment_source: x.segment_source || x.segment_id || null,
            segment_id: x.segment_id || x.segment_source || null,
            series_id: x.series_id || x.dataset_id || null,
            business_label: x.business_label || null,
            user_selected: Boolean(x.user_selected),
          })),
      primary_segment: selectedOnly ? null : managerMeta?.sectorId || managerRunTrace?.query_understanding?.primary_segment || null,
      matched_manager_sector_id: selectedOnly ? null : managerMeta?.sectorId || null,
      sector_ecosystem_sector_id: selectedOnly ? null : managerMeta?.sectorId || null,
      geo_scope: selectedOnly ? null : managerMeta?.geoScope || null,
      manager_run_trace: selectedOnly ? null : managerRunTrace,
    };
  };

  const applySummarizeResponse = (payload) => {
    const finalPayload =
      payload?.status === "completed" && payload?.result && typeof payload.result === "object"
        ? payload.result
        : payload;
    if (!finalPayload || typeof finalPayload !== "object") {
      setSummarizeResult(null);
      setError("AI interpretace vrátila neplatnou odpověď.");
      return false;
    }
    const answer = String(finalPayload.assistant_answer_cz || "").trim();
    const sections = Array.isArray(finalPayload.analysis_sections) ? finalPayload.analysis_sections : [];
    if (finalPayload.ok === false && !answer && !sections.length) {
      setSummarizeResult(null);
      setError(
        String(finalPayload.assistant_answer_cz || finalPayload.error || "AI interpretaci se nepodařilo dokončit.")
      );
      return false;
    }
    setSummarizeResult(finalPayload);
    return true;
  };

  const mergeDetailIntoSummarizeResult = (detailData) => {
    const detailResult =
      detailData?.detail_result && typeof detailData.detail_result === "object"
        ? detailData.detail_result
        : detailData;
    if (!detailResult || typeof detailResult !== "object") return;
    setSummarizeResult((prev) => ({
      ...(prev && typeof prev === "object" ? prev : {}),
      ...detailResult,
      instant_answer: prev?.instant_answer || prev?.assistant_answer_cz,
      instant_score_value: prev?.instant_score_value ?? prev?.score_value,
      instant_verdict_category: prev?.instant_verdict_category,
      instant_confidence: prev?.instant_confidence,
      instant_basis: prev?.instant_basis,
      instant_is_final: false,
      instant_guard_triggered: prev?.instant_guard_triggered,
      instant_guard_message: prev?.instant_guard_message,
      detail_can_refine_verdict: prev?.detail_can_refine_verdict ?? true,
      detail_reconciliation_message:
        detailData?.detail_reconciliation_message ?? prev?.detail_reconciliation_message,
      detail_verdict_reconciliation:
        detailData?.detail_verdict_reconciliation ?? prev?.detail_verdict_reconciliation,
      detail_direction_conflict:
        detailData?.detail_direction_conflict ?? prev?.detail_direction_conflict,
      detail_verdict_category: detailData?.detail_verdict_category ?? prev?.detail_verdict_category,
      final_answer_source: detailData?.final_answer_source || "detail_job",
      partial: false,
      detail_status: "completed",
      detail_ready: true,
      summarize_mode: prev?.summarize_mode || summarizeMode,
    }));
  };

  const pollDetailJob = async (jobId, requestToken, pollOptions = {}) => {
    const { runId = exploreRunIdRef.current, signal = null } = pollOptions;
    const startedAt = Date.now();
    let nextDelay = 2500;
    while (summarizePollTokenRef.current === requestToken) {
      if (signal?.aborted || exploreRunIdRef.current !== runId) return null;
      if (nextDelay > 0) {
        await waitMs(nextDelay);
      }
      if (summarizePollTokenRef.current !== requestToken) return null;
      if (signal?.aborted || exploreRunIdRef.current !== runId) return null;
      const { data } = await api.get(`/explore/summarize/detail/${encodeURIComponent(jobId)}`, {
        timeout: 30000,
        signal,
      });
      setDetailJobStatus(String(data?.detail_status || data?.status || "running"));
      setDetailProgressStep(String(data?.progress_step || "queued"));
      setDetailProgressPercent(Number(data?.progress_percent) || 5);
      setDetailJobMessage(String(data?.detail || data?.progress_step_label || ""));
      // Progresivní grafy: backend je posílá hned po načtení dat — vykresli je,
      // ať uživatel nečeká na dokončení celé AI syntézy.
      const progressiveCharts = data?.chart_payload;
      if (
        progressiveCharts &&
        Array.isArray(progressiveCharts.series) &&
        progressiveCharts.series.length > 0 &&
        data?.detail_status !== "completed"
      ) {
        setSummarizeResult((prev) => {
          const prevSeries = prev?.chart_payload?.series;
          if (Array.isArray(prevSeries) && prevSeries.length >= progressiveCharts.series.length) {
            return prev;
          }
          return { ...(prev || {}), chart_payload: progressiveCharts, charts_ready: true };
        });
      }
      if (data?.detail_status === "completed" || data?.status === "completed") {
        mergeDetailIntoSummarizeResult(data);
        return data;
      }
      if (data?.detail_status === "failed" || data?.status === "failed") {
        setDetailJobMessage(DETAIL_JOB_FAILED_USER_MESSAGE);
        return data;
      }
      nextDelay = Math.max(2000, Number(data?.poll_after_ms) || 3000);
      if (EXPLORE_SUMMARIZE_POLL_MAX_MS > 0 && Date.now() - startedAt > EXPLORE_SUMMARIZE_POLL_MAX_MS) {
        setDetailJobMessage("Detailní analýza trvá déle než obvykle — rychlý výsledek zůstává k dispozici.");
        return null;
      }
    }
    return null;
  };

  const pollSummarizeJob = async (jobId, requestToken, pollOptions = {}) => {
    const { runId = exploreRunIdRef.current, signal = null } = pollOptions;
    const startedAt = Date.now();
    let nextDelay = 0;
    while (summarizePollTokenRef.current === requestToken) {
      if (signal?.aborted || exploreRunIdRef.current !== runId) return null;
      if (nextDelay > 0) {
        await waitMs(nextDelay);
      }
      if (summarizePollTokenRef.current !== requestToken) return null;
      if (signal?.aborted || exploreRunIdRef.current !== runId) return null;
      const { data } = await api.get(`/explore/summarize/status/${encodeURIComponent(jobId)}`, {
        timeout: 30000,
        signal,
      });
      if (data?.status === "completed" || (data && !data.status && data.assistant_answer_cz)) {
        return data;
      }
      if (data?.status === "failed" || data?.ok === false) {
        const err = new Error(
          String(data?.error || "AI interpretaci se nepodařilo dokončit.")
        );
        err.payload = data;
        throw err;
      }
      setSummarizePendingDetail(
        String(data?.detail || "AI stále zpracovává finální interpretaci z vybraných řad.")
      );
      nextDelay = Math.max(800, Number(data?.poll_after_ms) || 1800);
      const elapsedMin = Math.floor((Date.now() - startedAt) / 60000);
      if (elapsedMin >= 1) {
        setSummarizePendingDetail(
          `AI stále analyzuje ${elapsedMin} min — u stovek řad je to normální, prosím nechte běžet.`
        );
      }
      if (EXPLORE_SUMMARIZE_POLL_MAX_MS > 0 && Date.now() - startedAt > EXPLORE_SUMMARIZE_POLL_MAX_MS) {
        throw new Error(
          "AI interpretace trvá déle než nastavený limit — zkuste to prosím znovu nebo kontaktujte podporu."
        );
      }
    }
    return null;
  };

  const runSummarize = async (extraRefs = [], options = {}) => {
    const q = String(question || "").trim();
    const preparedSeries = Array.isArray(options.preparedSeries) ? options.preparedSeries : refinedSeries;
    const preparedImfItems = Array.isArray(options.preparedImfItems) ? options.preparedImfItems : imfPrefetchedItems;
    const requestedMode = String(options.summarizeMode || summarizeMode || DEFAULT_SUMMARIZE_MODE).trim().toLowerCase();
    const effectiveSummarizeMode = resolveEffectiveSummarizeMode(requestedMode);
    const pipelineRunId = options.runId ?? null;
    const signal = options.signal ?? sectorLoadAbortRef.current?.signal ?? null;
    const isPipelineRun = pipelineRunId != null;
    if (q.length < 2) {
      setError("Dotaz musí mít alespoň 2 znaky.");
      return;
    }
    if (loadingRefine) {
      setError("Počkejte na dokončení načítání IMF WEO (probíhá na pozadí)…");
      return;
    }
    if (
      !preparedSeries.length &&
      !(extraRefs || []).length &&
      !exploreExtraSeries.length &&
      !userPickedSeries.length &&
      !selectedUploadIds.length
    ) {
      setError("Chybí připravené řady — vraťte se do kroku 1 a znovu pokračujte k dotazu.");
      setStep(1);
      return;
    }
    if (extraRefs?.length) {
      setExploreExtraSeries((prev) => {
        const merged = mergeExploreSeriesRefs([], [...prev, ...extraRefs]);
        return merged.map((x) => ({
          source_type: x.source,
          set_id: x.dataset_id,
          title: x.indicator_name || x.dataset_id,
          query_params: x.filters_used || {},
        }));
      });
    }
    const requestToken = summarizePollTokenRef.current + 1;
    summarizePollTokenRef.current = requestToken;
    setSummarizeMode(effectiveSummarizeMode);
    setLoadingSummarize(true);
    setSummarizeJobId("");
    setSummarizePendingDetail("");
    setDetailJobId("");
    setDetailJobStatus("");
    setDetailProgressStep("queued");
    setDetailProgressPercent(5);
    setDetailJobMessage("");
    setLoadingDetailAnalysis(false);
    setSummarizeResult(null);
    setError("");
    try {
      if (signal?.aborted || (isPipelineRun && exploreRunIdRef.current !== pipelineRunId)) return;
      const payload = buildSummarizePayload(extraRefs, {
        preparedSeries,
        summarizeMode: effectiveSummarizeMode,
      });
      const { data } = await api.post(
        "/explore/summarize",
        {
          ...payload,
          imf_prefetched_items: preparedImfItems,
        },
        { timeout: EXPLORE_LONG_REQUEST_TIMEOUT_MS, signal },
      );
      if (summarizePollTokenRef.current !== requestToken) return;
      if (signal?.aborted || (isPipelineRun && exploreRunIdRef.current !== pipelineRunId)) return;

      if (
        isInstantSummarizeMode(effectiveSummarizeMode) &&
        (data?.instant_ready || data?.detail_job_id || isInstantSummarizeMode(data?.summarize_mode))
      ) {
        if (
          effectiveSummarizeMode === "instant_then_detail_v2" &&
          (data?.ok === false || !String(data?.instant_answer || data?.assistant_answer_cz || "").trim()) &&
          !options.skipInstantFallback
        ) {
          setSummarizePendingDetail("Instant v2 selhal — zkouším standardní okamžitou odpověď…");
          await runSummarize(extraRefs, {
            ...options,
            preparedSeries,
            preparedImfItems,
            summarizeMode: "instant_then_detail",
            skipInstantFallback: true,
          });
          return;
        }
        applySummarizeResponse(data);
        setLoadingSummarize(false);
        const detailId = String(data?.detail_job_id || "").trim();
        if (detailId && data?.detail_analysis_available !== false) {
          setDetailJobId(detailId);
          setDetailJobStatus(String(data?.detail_status || "running"));
          setLoadingDetailAnalysis(true);
          void pollDetailJob(detailId, requestToken, { runId: pipelineRunId ?? exploreRunIdRef.current, signal }).finally(() => {
            if (summarizePollTokenRef.current === requestToken) {
              setLoadingDetailAnalysis(false);
            }
          });
        }
        return;
      }

      if (data?.status === "queued" || data?.status === "running") {
        const jobId = String(data?.job_id || "").trim();
        if (!jobId) {
          throw new Error("Backend nevrátil ID AI úlohy pro dopollování.");
        }
        setSummarizeJobId(jobId);
        setSummarizePendingDetail(
          String(data?.detail || "AI stále zpracovává finální interpretaci z vybraných řad.")
        );
        const finalData = await pollSummarizeJob(jobId, requestToken, {
          runId: pipelineRunId ?? exploreRunIdRef.current,
          signal,
        });
        if (finalData && summarizePollTokenRef.current === requestToken) {
          setSummarizeJobId("");
          setSummarizePendingDetail("");
          applySummarizeResponse(finalData);
        }
        return;
      }
      setSummarizeJobId("");
      setSummarizePendingDetail("");
      applySummarizeResponse(data);
    } catch (e) {
      if (summarizePollTokenRef.current !== requestToken) return;
      if (signal?.aborted || (isPipelineRun && exploreRunIdRef.current !== pipelineRunId)) return;
      if (effectiveSummarizeMode === "instant_then_detail_v2" && !options.skipInstantFallback) {
        setSummarizePendingDetail("Instant v2 nedostupný — zkouším standardní okamžitou odpověď…");
        await runSummarize(extraRefs, {
          ...options,
          preparedSeries,
          preparedImfItems,
          summarizeMode: "instant_then_detail",
          skipInstantFallback: true,
        });
        return;
      }
      if (effectiveSummarizeMode === "full" && !options.skipFastFallback) {
        setSummarizePendingDetail("Detailní analýza nedoběhla — spouštím rychlý deterministický fallback.");
        await runSummarize(extraRefs, {
          ...options,
          summarizeMode: "fast",
          skipFastFallback: true,
        });
        return;
      }
      setSummarizeResult(null);
      setSummarizeJobId("");
      setSummarizePendingDetail("");
      handleExploreRunFailure(e);
    } finally {
      if (summarizePollTokenRef.current === requestToken) {
        setLoadingSummarize(false);
      }
    }
  };

  const handleExploreFullRefresh = (extraRefs) => {
    void runSummarize(extraRefs || [], { summarizeMode });
  };

  const handleExploreDetailAnalysis = () => {
    void runSummarize([], { summarizeMode: "full" });
  };

  const exploreMetaForFollowup = useMemo(
    () => ({
      sector: String(sector || "").trim() || null,
      countries: geoSummaryLabel,
      geoMode,
      continent: geoMode === "continent" ? selectedContinent : null,
      countryCodes: selectedCountryCodes.length ? selectedCountryCodes : resolvedCountryCodes,
      relatedSegments: combinedRelatedSegments || null,
      question: String(question || "").trim(),
      uploadIds: selectedUploadIds,
      uploadNames: selectedUploadDocs.map((row) => String(row?.original_name || "").trim()).filter(Boolean),
      analysisScope: managerAnalysisScope,
      userDataPrivacyMode,
      managerSectorId: managerMeta?.sectorId || null,
      geoScope: managerMeta?.geoScope || null,
      topSources: managerMeta?.topSources || [],
      optionalSeries: allIndicators
        .filter((x) => !refinedSeries.some((r) => seriesKey(r) === seriesKey(x)))
        .map((x) => ({
          source_type: x.source,
          set_id: x.dataset_id,
          title: x.indicator_name || x.dataset_id,
          query_params: x.filters_used || {},
        })),
      refreshing: loadingSummarize,
    }),
    [
      sector,
      geoSummaryLabel,
      geoMode,
      selectedContinent,
      selectedCountryCodes,
      resolvedCountryCodes,
      combinedRelatedSegments,
      question,
      selectedUploadIds,
      selectedUploadDocs,
      managerAnalysisScope,
      userDataPrivacyMode,
      managerMeta,
      allIndicators,
      refinedSeries,
      loadingSummarize,
    ]
  );

  return (
    <AppShell title={t("pages.explore.title")}>
      <div className="max-w-5xl mx-auto space-y-6 px-4 pb-10">
        <div className="soft-card px-5 py-4 border-[hsl(var(--primary)/0.18)]">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2 text-teal-800 mb-1">
                <Sparkles className="h-4 w-4" />
                <span className="text-xs font-semibold uppercase tracking-wide">{t("pages.explore.badge")}</span>
              </div>
              <h1 className="text-xl font-semibold text-slate-900">{t("pages.explore.headline")}</h1>
              <p className="text-sm text-muted-foreground mt-1">
                {t("pages.explore.intro")}
              </p>
            </div>
            {step >= 2 ? (
              <button
                type="button"
                className="shrink-0 h-9 px-3 rounded-xl border border-teal-600/30 bg-teal-50 text-teal-900 text-sm font-medium inline-flex items-center gap-1.5 hover:bg-teal-100"
                onClick={startNewExploreQuery}
              >
                <RotateCcw className="h-3.5 w-3.5" />
                {t("pages.explore.newQuery")}
              </button>
            ) : null}
          </div>
          <div className="mt-4 flex flex-wrap gap-2">
            {steps.map((s) => (
              <span
                key={s.id}
                className={`inline-flex items-center gap-1 rounded-full px-3 py-1 text-xs font-medium border ${
                  step === s.id
                    ? "border-teal-600/40 bg-teal-50 text-teal-900"
                    : step > s.id
                      ? "border-emerald-300/50 bg-emerald-50/70 text-emerald-900"
                      : "border-border/70 bg-muted/20 text-muted-foreground"
                }`}
              >
                <span>{s.id}.</span>
                {s.label}
                {s.id < steps.length ? <ChevronRight className="h-3 w-3 opacity-60" /> : null}
              </span>
            ))}
          </div>
        </div>

        {error ? (
          <div className="rounded-xl border border-rose-300/70 bg-rose-50 px-4 py-3 text-sm text-rose-900">
            {error}
          </div>
        ) : null}

        {!loadingSector ? <ExploreSourceIssueSummary sourceStatuses={sourceStatuses} /> : null}

        {step === 1 ? (
          <div className="soft-card px-5 py-5 space-y-5">
            <h2 className="text-sm font-semibold text-slate-900">{t("pages.explore.step1Title")}</h2>
            <p className="text-xs text-muted-foreground max-w-3xl">
              Napište otázku přirozenou větou — AI pochopí segment, geo i relevantní data a nejdřív zobrazí rychlý
              orientační náhled. Detailní analýza se dopočítá na pozadí.
            </p>

            <div className="space-y-3 rounded-xl border border-teal-200/70 bg-teal-50/40 px-4 py-4">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-teal-900">1. Váš dotaz</h3>
              <label className="space-y-1.5 block">
                <span className="text-xs font-medium text-slate-600">Na co se chcete zeptat?</span>
                <div className="relative">
                <textarea
                  className="w-full min-h-[96px] px-3 py-2 pr-12 rounded-xl border border-border bg-card text-sm"
                  placeholder="např. Má smysl vyrábět automotive v Polsku? Jak české stavebnictví ovlivní vysoké sazby?"
                  value={question}
                  onChange={(e) => setQuestion(e.target.value)}
                />
                  <VoiceInputButton value={question} onChange={setQuestion} className="absolute right-2 top-2 h-8 w-8" />
                </div>
              </label>
              {queryUnderstandingPreview?.primary_segment ? (
                <div className="rounded-lg border border-teal-300/60 bg-white/80 px-3 py-2 text-xs text-teal-950 space-y-1">
                  <div>
                    <span className="font-medium">AI segment:</span>{" "}
                    {queryUnderstandingPreview.primary_segment}
                    {Array.isArray(queryUnderstandingPreview.supporting_segments) && queryUnderstandingPreview.supporting_segments.length
                      ? ` (+ ${queryUnderstandingPreview.supporting_segments.join(", ")})`
                      : ""}
                  </div>
                  {queryUnderstandingPreview?.geo?.primary_country_or_region ? (
                    <div>
                      <span className="font-medium">AI geo:</span> {queryUnderstandingPreview.geo.primary_country_or_region}
                    </div>
                  ) : queryUnderstandingPreview?.geo?.geo_warning === "country_missing" ? (
                    <div className="text-amber-800">Geo: země v dotazu nebyla rozpoznána (globální kontext).</div>
                  ) : null}
                </div>
              ) : null}
              {pendingClarification ? (
                <div className="rounded-xl border border-amber-300/80 bg-amber-50/90 px-4 py-3 text-sm text-amber-950 space-y-3">
                  <p className="font-semibold text-base">
                    {isProductionTypeClarification(pendingClarification)
                      ? "O jaký typ výroby jde?"
                      : exploreClarificationMessage(pendingClarification)}
                  </p>
                  {isProductionTypeClarification(pendingClarification) ? (
                    <p className="text-xs text-amber-900/85">
                      Dotaz je sice odpověditelný, ale typ výroby nebyl upřesněn. Vyberte segment, nebo spusťte
                      rychlý obecný náhled pro zpracovatelský průmysl (fallback).
                    </p>
                  ) : (
                    <p className="text-xs text-amber-900/85">
                      Upřesněte segment, aby se načetly správné ukazatele.
                    </p>
                  )}
                  <div className="flex flex-wrap gap-2">
                    {exploreClarificationOptions(pendingClarification).map((option) => {
                      const key = String(option?.segment_id || option?.label || "").trim();
                      const label = resolveSectorLabelForClarificationOption(option, managerSectorById)
                        || String(option?.label || key).trim();
                      if (!label) return null;
                      return (
                        <button
                          key={key}
                          type="button"
                          className="h-9 px-3 rounded-xl border border-amber-400/70 bg-white hover:bg-amber-100 text-sm font-medium"
                          disabled={loadingSector || loadingRefine || loadingSummarize}
                          onClick={() => void confirmClarificationChoice(option)}
                        >
                          {label}
                        </button>
                      );
                    })}
                  </div>
                  {isProductionTypeClarification(pendingClarification) ? (
                    <button
                      type="button"
                      className="h-9 px-3 rounded-xl border border-teal-600/40 bg-teal-50 hover:bg-teal-100 text-sm font-medium text-teal-950"
                      disabled={loadingSector || loadingRefine || loadingSummarize}
                      onClick={() => void confirmClarificationFallback()}
                    >
                      Spustit obecný náhled pro zpracovatelský průmysl
                    </button>
                  ) : null}
                </div>
              ) : null}
              {assumptionFallbackActive ? (
                <div className="rounded-lg border border-sky-300/70 bg-sky-50 px-3 py-2 text-xs text-sky-950">
                  Analýza běží pro obecný zpracovatelský průmysl, protože typ výroby nebyl upřesněn.
                </div>
              ) : null}
              {exploreExpertMode && exploreStepTimings ? (
                <div className="text-[10px] text-slate-500 font-mono">
                  QU {exploreStepTimings.query_understanding_ms} ms
                  {exploreStepTimings.clarification_render_ms
                    ? ` · clar ${exploreStepTimings.clarification_render_ms} ms`
                    : ""}
                  {exploreStepTimings.curated_plan_ms
                    ? ` · plán ${exploreStepTimings.curated_plan_ms} ms`
                    : ""}
                </div>
              ) : null}
            </div>

            {exploreExpertMode ? (
            <details className="rounded-xl border border-border/70 bg-muted/10 overflow-hidden group">
              <summary className="cursor-pointer select-none px-4 py-3 list-none flex items-center justify-between gap-3 [&::-webkit-details-marker]:hidden">
                <div>
                  <div className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                    Expertní nastavení
                  </div>
                  <p className="text-[11px] text-muted-foreground mt-1">
                    {EXPERT_SETTINGS_HELP}
                  </p>
                </div>
                <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground transition-transform group-open:rotate-180" />
              </summary>
              <div className="border-t border-border/60 px-4 py-4 space-y-5">

            <div className="space-y-3 rounded-xl border border-border/70 bg-muted/10 px-4 py-4">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">{t("pages.explore.analysisType")}</h3>
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  className={`h-9 px-3 rounded-xl text-sm border ${
                    managerAnalysisMode === "sector"
                      ? "bg-teal-700 text-white border-teal-800"
                      : "bg-card border-border hover:bg-muted/40"
                  }`}
                  onClick={() => setManagerAnalysisMode("sector")}
                >
                  {t("pages.explore.sectorAnalysis")}
                </button>
                <button
                  type="button"
                  className={`h-9 px-3 rounded-xl text-sm border ${
                    managerAnalysisMode === "macro"
                      ? "bg-teal-700 text-white border-teal-800"
                      : "bg-card border-border hover:bg-muted/40"
                  }`}
                  onClick={() => setManagerAnalysisMode("macro")}
                >
                  Ekonomický dotaz (makro)
                </button>
              </div>
              <p className="text-[11px] text-slate-500">
                Makro režim nevyžaduje segment — použije kurátorované makro řady podle země/regionu a otázky.
              </p>
            </div>

            {managerAnalysisMode === "sector" ? (
            <div className="space-y-3 rounded-xl border border-border/70 bg-muted/10 px-4 py-4">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Hlavní segment (override)</h3>
              <label className="space-y-1.5 block max-w-xl">
                <span className="text-xs font-medium text-slate-600">Odvětví / segment</span>
                <div className="flex items-center gap-2">
                  <select
                    className="w-full h-10 px-3 rounded-xl border border-border bg-card text-sm"
                    value={sector}
                    onChange={(e) => handlePrimarySectorChange(e.target.value)}
                  >
                    <option value="">Automaticky z dotazu…</option>
                    {managerSectorOptions.map((label) => (
                      <option key={label} value={label}>
                        {label}
                      </option>
                    ))}
                  </select>
                  {sector ? (
                    <button
                      type="button"
                      className="h-10 w-10 shrink-0 rounded-xl border border-border bg-card hover:bg-muted/40 inline-flex items-center justify-center text-slate-600"
                      onClick={() => handlePrimarySectorChange("")}
                      aria-label="Vymazat hlavní segment"
                      title="Vymazat výběr"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  ) : null}
                </div>
                {sectorOptionsNotice ? (
                  <p className="text-[11px] text-amber-700">{sectorOptionsNotice}</p>
                ) : null}
              </label>
              {matchedPresetLabel ? (
                <p className="text-[11px] text-teal-800/90">
                  Rozpoznané odvětví: <span className="font-medium">{matchedPresetLabel}</span>
                </p>
              ) : null}
            </div>
            ) : null}

            <div className="space-y-3 rounded-xl border border-border/70 bg-muted/10 px-4 py-4">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                {managerAnalysisMode === "sector" ? "3. Geo kontext" : "2. Geo kontext"}
              </h3>
              <div className="space-y-2">
                <span className="text-xs font-medium text-slate-600">Režim</span>
                <div className="flex flex-wrap gap-2">
                  {EXPLORE_GEO_MODES.map((m) => (
                    <button
                      key={m.id}
                      type="button"
                      className={`h-9 px-3 rounded-xl text-sm border ${
                        geoMode === m.id
                          ? "bg-teal-700 text-white border-teal-800"
                          : "bg-card border-border hover:bg-muted/40"
                      }`}
                      onClick={() => {
                        setGeoMode(m.id);
                        resetGeoRefine();
                      }}
                    >
                      {m.label}
                    </button>
                  ))}
                </div>
              </div>
              {geoMode === "continent" ? (
                <div className="space-y-2">
                  <span className="text-xs font-medium text-slate-600">Kontinent</span>
                  <div className="flex flex-wrap gap-2">
                    {EXPLORE_CONTINENTS.map((c) => (
                      <button
                        key={c.id}
                        type="button"
                        className={`h-9 px-3 rounded-xl text-sm border ${
                          selectedContinent === c.id
                            ? "bg-violet-700 text-white border-violet-800"
                            : "bg-card border-border hover:bg-muted/40"
                        }`}
                        onClick={() => {
                          setSelectedContinent(c.id);
                          resetGeoRefine();
                        }}
                      >
                        {c.label}
                      </button>
                    ))}
                  </div>
                </div>
              ) : null}
              {geoMode === "countries" ? (
                <div className="space-y-3 max-w-2xl">
                  <label className="space-y-1.5 block">
                    <span className="text-xs font-medium text-slate-600">Hlavní a přidružené země</span>
                    <div className="space-y-2">
                      {countrySelections.map((value, index) => (
                        <div key={`country-row-${index}`} className="flex items-center gap-2">
                          <div className="min-w-0 flex-1 space-y-1">
                            <div className="text-[11px] text-slate-500">
                              {index === 0 ? "Hlavní země" : `Přidružená země ${index}`}
                            </div>
                            <ExploreCountrySelect
                              countryGroups={exploreCountryGroups}
                              countries={countryOptions}
                              value={value}
                              placeholder={
                                index === 0 ? "Vyberte hlavní zemi…" : "Vyberte přidruženou zemi…"
                              }
                              onChange={(code) => updateCountrySelection(index, code)}
                            />
                          </div>
                          {countrySelections.length > 1 ? (
                            <button
                              type="button"
                              className="h-10 w-10 shrink-0 rounded-xl border border-border bg-card hover:bg-muted/40 inline-flex items-center justify-center text-slate-600"
                              onClick={() => removeCountrySelection(index)}
                              aria-label={index === 0 ? "Odebrat hlavní zemi" : "Odebrat přidruženou zemi"}
                            >
                              <X className="h-4 w-4" />
                            </button>
                          ) : null}
                        </div>
                      ))}
                    </div>
                  </label>
                  <div className="flex flex-wrap gap-2 items-center">
                    <button
                      type="button"
                      className="h-9 px-3 rounded-xl border border-border bg-card hover:bg-muted/40 text-sm"
                      onClick={() => addCountrySelection("")}
                    >
                      + Přidat přidruženou zemi
                    </button>
                    <button
                      type="button"
                      className="h-9 px-3 rounded-xl border border-violet-400/50 bg-violet-50 hover:bg-violet-100 text-violet-950 text-sm inline-flex items-center gap-2 disabled:opacity-60"
                      disabled={loadingCountrySuggest || !selectedCountryCodes.length}
                      onClick={() => void fetchCountrySuggestions()}
                    >
                      {loadingCountrySuggest ? (
                        <Loader2 className="h-4 w-4 animate-spin" />
                      ) : (
                        <Sparkles className="h-4 w-4" />
                      )}
                      AI +
                    </button>
                    {primaryCountryLabel ? (
                      <span className="text-[11px] text-slate-600">
                        Hlavní: <span className="font-medium">{primaryCountryLabel}</span>
                        {relatedCountryLabels.length ? (
                          <>
                            {" "}
                            · Přidružené: <span className="font-medium">{relatedCountryLabels.join(", ")}</span>
                          </>
                        ) : null}
                      </span>
                    ) : null}
                  </div>
                  <p className="text-[11px] text-slate-500">
                    První země je hlavní fokus pro hledání a skórování. Další země slouží jako přidružený kontext,
                    sousedé nebo srovnání.
                  </p>
                  {countrySuggestError ? (
                    <p className="text-xs text-amber-900 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
                      {countrySuggestError}
                    </p>
                  ) : null}
                  {countrySuggestNotice ? (
                    <p className="text-xs text-emerald-900 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-2">
                      {countrySuggestNotice}
                    </p>
                  ) : null}
                  {SHOW_EXPLORE_COUNTRY_QUICK_HINTS ? (
                    <div className="flex flex-wrap gap-1.5">
                      {countryHints.map((c) => {
                        const active = selectedCountryCodes.includes(c.code);
                        return (
                          <button
                            key={c.code}
                            type="button"
                            className={`h-8 px-2.5 rounded-lg text-xs border ${
                              active
                                ? "bg-teal-100 border-teal-600 text-teal-950"
                                : "bg-card border-border hover:bg-muted/40"
                            }`}
                            onClick={() => toggleQuickCountry(c.code)}
                          >
                            {c.label}
                          </button>
                        );
                      })}
                    </div>
                  ) : null}
                </div>
              ) : null}
              {geoMode === "none" ? (
                <p className="text-xs text-slate-600">
                  Svět — hledání i analýza použijí globální kotvy (USA, Japonsko, eurozóna).
                </p>
              ) : null}
            </div>

            {managerAnalysisMode === "sector" ? (
            <div className="space-y-3 rounded-xl border border-border/70 bg-muted/10 px-4 py-4">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                4. Hierarchie sektorů a doplňkový kontext
              </h3>
              {String(sector || "").trim() ? (
                <div className="space-y-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <div className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                      Související segmenty pro analýzu
                    </div>
                    {loadingRelationshipRows ? (
                      <span className="inline-flex items-center gap-1 text-[11px] text-slate-500">
                        <Loader2 className="h-3.5 w-3.5 animate-spin" />
                        Aktualizuji váhy…
                      </span>
                    ) : null}
                    {unifiedRelatedItems.length ? (
                      <button
                        type="button"
                        className="ml-auto h-8 px-2.5 rounded-lg border border-slate-300 bg-white text-[11px] text-slate-700 hover:bg-slate-50"
                        onClick={clearAllRelatedSegments}
                      >
                        Odebrat vše
                      </button>
                    ) : null}
                  </div>
                  <ManagerSectorHierarchyEditor
                    primarySector={sector}
                    relatedRows={unifiedRelatedItems}
                    editable
                    onPromoteRelated={applySectorHierarchySwap}
                    onRemoveRelated={removeUnifiedRelatedItem}
                    onReorderRelated={reorderUnifiedRelatedItem}
                    promoteDisabled={loadingSector || loadingRefine || loadingSummarize}
                    removeDisabled={loadingSector || loadingRefine || loadingSummarize}
                    reorderDisabled={loadingSector || loadingRefine || loadingSummarize}
                  />
                  <p className="text-[11px] text-slate-500">
                    Doporučené segmenty z relationship tabulky a ručně přidané jsou na jednom místě. Přetažením
                    změníte prioritu — rank a váha se přepočítají pro finální skóring. Každou položku lze odebrat — i
                    všechny. Tlačítko <span className="font-medium">Hlavní</span> přehodí primární sektor ještě před
                    spuštěním analýzy.
                  </p>
                  <div className="grid gap-3 max-w-2xl">
                    <div className="flex flex-wrap items-end gap-2">
                      <label className="flex-1 min-w-[220px] space-y-1.5">
                        <span className="text-xs font-medium text-slate-600">Přidat segment</span>
                        <select
                          className="w-full h-10 px-3 rounded-xl border border-border bg-card text-sm"
                          value={pendingRelatedSegmentAdd}
                          onChange={(e) => setPendingRelatedSegmentAdd(e.target.value)}
                        >
                          <option value="">Vyberte segment…</option>
                          {supplementarySegmentOptions.map((label) => (
                            <option key={label} value={label}>
                              {label}
                            </option>
                          ))}
                        </select>
                      </label>
                      <button
                        type="button"
                        className="h-10 px-3 rounded-xl border border-border bg-card hover:bg-muted/40 text-sm"
                        disabled={!pendingRelatedSegmentAdd}
                        onClick={addRelatedSegmentFromPicker}
                      >
                        Přidat
                      </button>
                      <button
                        type="button"
                        className="h-10 px-3 rounded-xl border border-violet-400/50 bg-violet-50 hover:bg-violet-100 text-violet-950 text-sm inline-flex items-center gap-2 disabled:opacity-60"
                        disabled={loadingSupplementarySuggest || !String(sector || "").trim()}
                        onClick={() => void fetchSupplementarySuggestions()}
                      >
                        {loadingSupplementarySuggest ? (
                          <Loader2 className="h-4 w-4 animate-spin" />
                        ) : (
                          <Sparkles className="h-4 w-4" />
                        )}
                        AI návrhy
                      </button>
                    </div>
                  </div>
                  {combinedRelatedSegments ? (
                    <p className="text-[11px] text-slate-600">
                      Do analýzy se odešle: <span className="font-medium">{combinedRelatedSegments}</span>
                    </p>
                  ) : (
                    <p className="text-[11px] text-slate-600 italic">
                      Bez souvisejících segmentů — analýza poběží jen pro hlavní sektor.
                    </p>
                  )}
                </div>
              ) : (
                <p className="text-xs text-slate-600">
                  Po zadání hlavního segmentu se zde zobrazí související segmenty včetně vah a ranků z relationship tabulky.
                </p>
              )}
              {supplementarySuggestError ? (
                <p className="text-xs text-amber-900 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
                  {supplementarySuggestError}
                </p>
              ) : null}
              {supplementarySuggestNotice ? (
                <p className="text-xs text-emerald-900 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-2">
                  {supplementarySuggestNotice}
                </p>
              ) : null}
              {SHOW_EXPLORE_RELATED_TOPIC_AI_SUGGESTIONS ? (
                <>
                  <div className="flex flex-wrap gap-2 items-center">
                    <button
                      type="button"
                      className="h-9 px-3 rounded-xl border border-violet-400/50 bg-violet-50 hover:bg-violet-100 text-violet-950 text-sm inline-flex items-center gap-2"
                      disabled={loadingRelatedSuggest || !String(sector || "").trim()}
                      onClick={() => void fetchRelatedSuggestions()}
                    >
                      {loadingRelatedSuggest ? <Loader2 className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
                      Navrhnout témata AI
                    </button>
                    {relatedSuggestions.length > 0 ? (
                      <span className="text-[11px] text-muted-foreground">Kliknutím přidáte:</span>
                    ) : null}
                  </div>
                  {relatedSuggestError ? (
                    <p className="text-xs text-amber-900 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
                      {relatedSuggestError}
                    </p>
                  ) : null}
                  {relatedSuggestions.length > 0 ? (
                    <div className="flex flex-wrap gap-1.5">
                      {relatedSuggestions.map((s) => (
                        <button
                          key={s}
                          type="button"
                          className="h-8 px-2.5 rounded-lg text-xs border border-violet-300/60 bg-violet-50/80 hover:bg-violet-100 text-violet-950"
                          onClick={() => appendRelatedSuggestion(s)}
                        >
                          + {s}
                        </button>
                      ))}
                    </div>
                  ) : null}
                </>
              ) : null}
            </div>
            ) : null}

            <div className="rounded-xl border border-indigo-200/70 bg-indigo-50/50 px-3 py-3 space-y-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <div className="text-xs font-semibold text-indigo-950">Režim odpovědi (expert)</div>
                  <p className="text-[11px] text-indigo-900/80">
                    Výchozí pro manažerské UI je rychlý orientační náhled v2 + detail na pozadí.
                  </p>
                </div>
                <div className="flex flex-wrap gap-1.5">
                  <button
                    type="button"
                    className={`h-8 px-3 rounded-lg text-xs font-semibold border ${
                      isInstantSummarizeMode(summarizeMode)
                        ? "bg-indigo-700 text-white border-indigo-800"
                        : "bg-white border-indigo-200 text-indigo-950 hover:bg-indigo-50"
                    }`}
                    onClick={() => setSummarizeMode(DEFAULT_SUMMARIZE_MODE)}
                  >
                    Rychlý náhled v2 + detail
                  </button>
                  <button
                    type="button"
                    className={`h-8 px-3 rounded-lg text-xs font-semibold border ${
                      summarizeMode === "fast"
                        ? "bg-indigo-700 text-white border-indigo-800"
                        : "bg-white border-indigo-200 text-indigo-950 hover:bg-indigo-50"
                    }`}
                    onClick={() => setSummarizeMode("fast")}
                  >
                    Rychlý režim
                  </button>
                  <button
                    type="button"
                    className={`h-8 px-3 rounded-lg text-xs font-semibold border ${
                      summarizeMode === "full"
                        ? "bg-indigo-700 text-white border-indigo-800"
                        : "bg-white border-indigo-200 text-indigo-950 hover:bg-indigo-50"
                    }`}
                    onClick={() => setSummarizeMode("full")}
                  >
                    Jen detailní analýza
                  </button>
                </div>
              </div>
            </div>

              </div>
            </details>
            ) : null}

            <div className="space-y-3 rounded-xl border border-border/70 bg-muted/10 px-4 py-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Vlastní soubory</h3>
                  <p className="mt-1 text-xs text-muted-foreground max-w-2xl">
                    Volitelné — firemní data pro finální interpretaci a skóre.
                  </p>
                </div>
                {selectedUploadDocs.length ? (
                  <div className="rounded-lg border border-emerald-300/60 bg-emerald-50 px-3 py-2 text-xs text-emerald-950">
                    Vybrané soubory: <span className="font-semibold">{selectedUploadDocs.length}</span>
                  </div>
                ) : null}
              </div>

              {selectedUploadDocs.length ? (
                <div className="flex flex-wrap items-center gap-2 text-[11px]">
                  <span className="font-medium uppercase tracking-wide text-slate-500">Vlastní data v analýze:</span>
                  <button
                    type="button"
                    onClick={() => setUserDataParticipation("final_compare")}
                    className={`rounded-lg border px-2.5 py-1 ${
                      userDataParticipation === "final_compare"
                        ? "border-blue-600 bg-blue-600 text-white"
                        : "border-border/70 bg-white text-slate-600 hover:bg-slate-50"
                    }`}
                    title="Vaše data se použijí na závěr — analýza vás porovná s trhem (dnešní chování)."
                  >
                    Porovnat ve finále
                  </button>
                  <button
                    type="button"
                    onClick={() => setUserDataParticipation("upfront")}
                    className={`rounded-lg border px-2.5 py-1 ${
                      userDataParticipation === "upfront"
                        ? "border-blue-600 bg-blue-600 text-white"
                        : "border-border/70 bg-white text-slate-600 hover:bg-slate-50"
                    }`}
                    title="Vaše data vstoupí do hlavní analýzy hned od začátku, jako běžná řada."
                  >
                    Zahrnout od začátku
                  </button>
                </div>
              ) : null}

              <div className="rounded-xl border border-blue-200/70 bg-blue-50/40 px-3 py-3 space-y-2">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="text-xs font-semibold text-blue-950">Rozsah analýzy</div>
                    <p className="text-[11px] text-blue-900/75">
                      Ručně vybrané řady mohou doplnit automatický plán, nebo lze analyzovat jen váš výběr.
                    </p>
                  </div>
                  <div className="flex flex-wrap gap-1.5">
                    <button
                      type="button"
                      onClick={() => setManagerAnalysisScope("auto")}
                      className={`rounded-lg border px-2.5 py-1 text-[11px] font-medium ${
                        managerAnalysisScope !== "selected_only"
                          ? "border-blue-700 bg-blue-700 text-white"
                          : "border-blue-200 bg-white text-blue-900 hover:bg-blue-50"
                      }`}
                    >
                      Celková analýza
                    </button>
                    <button
                      type="button"
                      onClick={() => setManagerAnalysisScope("selected_only")}
                      className={`rounded-lg border px-2.5 py-1 text-[11px] font-medium ${
                        managerAnalysisScope === "selected_only"
                          ? "border-blue-700 bg-blue-700 text-white"
                          : "border-blue-200 bg-white text-blue-900 hover:bg-blue-50"
                      }`}
                    >
                      Pouze vybrané
                    </button>
                  </div>
                </div>
                {managerAnalysisScope === "selected_only" ? (
                  <p className="text-[11px] text-blue-950">
                    Do výpočtu vstoupí jen ručně vybrané katalogové řady, doplněné řady a vybrané uploady.
                  </p>
                ) : null}
              </div>

              {selectedUploadDocs.length ? (
                <div className="rounded-xl border border-emerald-200/80 bg-emerald-50/50 px-3 py-3 space-y-2">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="inline-flex items-center gap-1.5 text-xs font-semibold text-emerald-950">
                        <ShieldCheck className="h-3.5 w-3.5" />
                        Transparentnost vlastních dat
                      </div>
                      <p className="text-[11px] text-emerald-900/80">
                        Platí jen pro nahrané soubory. Veřejné řady tím zbytečně neomezujeme.
                      </p>
                    </div>
                    <div className="flex flex-wrap gap-1.5">
                      <button
                        type="button"
                        onClick={() => setUserDataPrivacyMode("strict_private")}
                        className={`rounded-lg border px-2.5 py-1 text-[11px] font-medium ${
                          userDataPrivacyMode === "strict_private"
                            ? "border-emerald-700 bg-emerald-700 text-white"
                            : "border-emerald-200 bg-white text-emerald-950 hover:bg-emerald-50"
                        }`}
                      >
                        Strict private
                      </button>
                      <button
                        type="button"
                        onClick={() => setUserDataPrivacyMode("safe_summary")}
                        className={`rounded-lg border px-2.5 py-1 text-[11px] font-medium ${
                          userDataPrivacyMode === "safe_summary"
                            ? "border-emerald-700 bg-emerald-700 text-white"
                            : "border-emerald-200 bg-white text-emerald-950 hover:bg-emerald-50"
                        }`}
                      >
                        Anonymní souhrny
                      </button>
                    </div>
                  </div>
                  <p className="text-[11px] text-emerald-950">
                    {userDataPrivacyMode === "strict_private"
                      ? "AI nedostane hodnoty z uploadu; vlastní data se použijí jen v lokálním výpočtu a grafu."
                      : "AI dostane jen agregované signály typu trend nebo meziroční změna, bez raw tabulky."}
                  </p>
                </div>
              ) : null}

              <div className="rounded-xl border border-border/70 bg-card overflow-hidden">
                <button
                  type="button"
                  onClick={() => setSeriesPickerOpen((v) => !v)}
                  className="w-full px-4 py-3 flex items-center justify-between gap-3 text-left"
                >
                  <div className="min-w-0">
                    <div className="text-xs font-medium text-slate-700">Vlastní řady (z katalogu)</div>
                    <p className="text-[11px] text-muted-foreground mt-1">
                      Volitelné — vámi vybrané řady vždy vstoupí do analýzy (AI je neodfiltruje).
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-2">
                    {userPickedSeries.length ? (
                      <span className="rounded-full border border-blue-300/60 bg-blue-50 px-2 py-0.5 text-[11px] font-medium text-blue-900">
                        {userPickedSeries.length} vybráno
                      </span>
                    ) : null}
                    <ChevronDown className={`h-4 w-4 text-muted-foreground transition ${seriesPickerOpen ? "rotate-180" : ""}`} />
                  </div>
                </button>

                {userPickedSeries.length ? (
                  <div className="px-4 pb-2 flex flex-wrap gap-1.5">
                    {userPickedSeries.map((r) => (
                      <span
                        key={pickedSeriesKey(r)}
                        className="inline-flex items-center gap-1 rounded-lg border border-blue-200 bg-blue-50 px-2 py-1 text-[11px] text-blue-900"
                      >
                        <span className="max-w-[16rem] truncate">{r.indicator_name || r.dataset_id}</span>
                        <button
                          type="button"
                          onClick={() => openPickedSeriesInCatalog(r)}
                          className="text-blue-700 hover:text-blue-900"
                          title="Otevřít v katalogu"
                          aria-label="Otevřít řadu v katalogu"
                        >
                          <ExternalLink className="h-3 w-3" />
                        </button>
                        <button
                          type="button"
                          onClick={() => togglePickedSeries(r)}
                          className="text-blue-700 hover:text-blue-900"
                          aria-label="Odebrat řadu"
                        >
                          <X className="h-3 w-3" />
                        </button>
                      </span>
                    ))}
                  </div>
                ) : null}

                {seriesPickerOpen ? (
                  <div className="border-t border-border/60 px-4 py-3 space-y-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <select
                        value={seriesPickerSource}
                        onChange={(e) => {
                          setSeriesPickerSource(e.target.value);
                          setSeriesPickerHits([]);
                        }}
                        className="h-8 rounded-md border border-border/70 bg-white px-2 text-xs"
                      >
                        <option value="arad">ČNB – ARAD</option>
                        <option value="eurostat">Eurostat</option>
                        <option value="ecb">ECB</option>
                        <option value="oecd">OECD</option>
                        <option value="imf">IMF</option>
                        <option value="worldbank">World Bank</option>
                        <option value="data360">World Bank (data360)</option>
                        <option value="csu">ČSÚ</option>
                      </select>
                      <input
                        value={seriesPickerQuery}
                        onChange={(e) => setSeriesPickerQuery(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === "Enter") {
                            e.preventDefault();
                            void searchSeriesPicker();
                          }
                        }}
                        placeholder="Hledat řadu v katalogu…"
                        className="h-8 min-w-[12rem] flex-1 rounded-md border border-border/70 bg-white px-2 text-xs"
                      />
                      <button
                        type="button"
                        onClick={() => void searchSeriesPicker()}
                        disabled={seriesPickerLoading}
                        className="h-8 px-2.5 rounded-md border border-border/70 bg-card text-xs inline-flex items-center gap-1 disabled:opacity-50"
                      >
                        {seriesPickerLoading ? <Loader2 className="h-3 w-3 animate-spin" /> : <Search className="h-3 w-3" />}
                        Hledat
                      </button>
                    </div>
                    {seriesPickerError ? <p className="text-[11px] text-rose-700">{seriesPickerError}</p> : null}
                    {seriesPickerHits.length ? (
                      <ul className="max-h-44 overflow-y-auto space-y-1">
                        {seriesPickerHits.map((row, idx) => {
                          const ref = catalogHitToPickedRef(row);
                          if (!ref.dataset_id) return null;
                          const isPicked = userPickedSeries.some(
                            (r) => pickedSeriesKey(r) === pickedSeriesKey(ref),
                          );
                          return (
                            <li key={`${ref.source}:${ref.dataset_id}:${idx}`} className="flex items-stretch gap-1.5">
                              <button
                                type="button"
                                onClick={() => togglePickedSeries(ref)}
                                className={`min-w-0 flex-1 text-left rounded-lg border px-2.5 py-1.5 flex items-center justify-between gap-2 ${
                                  isPicked
                                    ? "border-blue-400 bg-blue-50"
                                    : "border-border/70 bg-white hover:bg-slate-50"
                                }`}
                              >
                                <span className="min-w-0 text-[11.5px] text-slate-800 truncate">
                                  {ref.indicator_name || ref.dataset_id}
                                </span>
                                {isPicked ? (
                                  <X className="h-3.5 w-3.5 shrink-0 text-blue-700" />
                                ) : (
                                  <Plus className="h-3.5 w-3.5 shrink-0 text-slate-500" />
                                )}
                              </button>
                              <button
                                type="button"
                                onClick={() => openPickedSeriesInCatalog(row)}
                                className="h-auto w-8 shrink-0 inline-flex items-center justify-center rounded-lg border border-border/70 bg-white text-slate-600 hover:bg-slate-50"
                                title="Otevřít v katalogu"
                                aria-label="Otevřít řadu v katalogu"
                              >
                                <ExternalLink className="h-3.5 w-3.5" />
                              </button>
                            </li>
                          );
                        })}
                      </ul>
                    ) : null}
                  </div>
                ) : null}
              </div>

              <details className="rounded-xl border border-border/70 bg-card overflow-hidden group">
                <summary className="cursor-pointer select-none px-4 py-3 list-none flex items-center justify-between gap-3 [&::-webkit-details-marker]:hidden">
                  <div className="min-w-0">
                    <div className="text-xs font-medium text-slate-700">Vlastní soubory</div>
                    <p className="text-[11px] text-muted-foreground mt-1">
                      Volitelné — firemní data pro finální interpretaci a skóre.
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-2">
                    {selectedUploadDocs.length ? (
                      <span className="rounded-full border border-emerald-300/60 bg-emerald-50 px-2 py-0.5 text-[11px] font-medium text-emerald-900">
                        {selectedUploadDocs.length} vybráno
                      </span>
                    ) : null}
                    <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground transition-transform group-open:rotate-180" />
                  </div>
                </summary>
                <div className="border-t border-border/60 px-4 py-4 space-y-3">
                  <div className="flex flex-wrap items-center justify-end gap-2">
                    <label
                      aria-busy={uploadingUserFile ? "true" : undefined}
                      className={`inline-flex items-center gap-2 px-3 py-2 rounded-xl border border-dashed border-border/80 text-sm bg-card hover:bg-muted/30 ${
                        uploadingUserFile ? "cursor-wait opacity-80" : "cursor-pointer"
                      }`}
                    >
                      {uploadingUserFile ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
                      {uploadingUserFile ? "Nahrávám…" : "Přidat CSV nebo XLSX"}
                      <input
                        type="file"
                        accept=".csv,.xlsx,.xlsm"
                        className="hidden"
                        onChange={handleUserFileUpload}
                        disabled={uploadingUserFile}
                      />
                    </label>
                  </div>

                  {uploadError ? (
                    <div className="rounded-lg border border-amber-300/70 bg-amber-50 px-3 py-2 text-xs text-amber-950">
                      {uploadError}
                    </div>
                  ) : null}
                  {uploadNotice ? (
                    <div className="rounded-lg border border-emerald-300/70 bg-emerald-50 px-3 py-2 text-xs text-emerald-950">
                      {uploadNotice}
                    </div>
                  ) : null}

                  {loadingUserUploads ? (
                    <div className="text-xs text-muted-foreground inline-flex items-center gap-2">
                      <Loader2 className="h-3.5 w-3.5 animate-spin" />
                      Načítám vaše soubory…
                    </div>
                  ) : availableUploads.length > 0 ? (
                    <div className="grid gap-2">
                      {availableUploads.map((upload) => {
                        const uploadId = String(upload?.id || "").trim();
                        const checked = selectedUploadIds.includes(uploadId);
                        return (
                          <ExploreUserUploadRow
                            key={uploadId}
                            upload={upload}
                            checked={checked}
                            expanded={expandedUploadIds.includes(uploadId)}
                            preview={uploadPreviews[uploadId]}
                            onToggleSelect={toggleSelectedUpload}
                            onToggleExpand={toggleUploadExpanded}
                            formatUploadSize={formatUploadSize}
                          />
                        );
                      })}
                    </div>
                  ) : (
                    <div className="text-xs text-muted-foreground">
                      Zatím nemáte nahrané žádné soubory. Přidání je volitelné.
                    </div>
                  )}
                </div>
              </details>
            </div>

            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                className="h-10 px-4 rounded-xl bg-emerald-700 hover:bg-emerald-800 text-white text-sm font-medium inline-flex items-center gap-2 disabled:opacity-50"
                disabled={loadingSector || loadingRefine || loadingSummarize || String(question || "").trim().length < 2}
                onClick={() => void continueToQuestionPrepare()}
              >
                {loadingSector || loadingRefine || loadingSummarize ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                {loadingSector || loadingRefine || loadingSummarize ? "Vyhodnocuji…" : "Vyhodnotit"}
              </button>
              {loadingSector || loadingRefine || loadingSummarize ? (
                <button
                  type="button"
                  className="h-10 px-4 rounded-xl border border-rose-300 bg-rose-50 hover:bg-rose-100 text-rose-900 text-sm font-medium"
                  onClick={stopAnalysis}
                >
                  STOP
                </button>
              ) : null}
            </div>

            {loadHint && !pendingClarification ? (
              <div className="rounded-xl border border-sky-300/60 bg-sky-50/80 px-4 py-3 text-sm text-sky-950 inline-flex items-center gap-2">
                {loadingSector ? <Loader2 className="h-4 w-4 animate-spin shrink-0" /> : null}
                {loadHint}
              </div>
            ) : null}


            <ManagerQuickDataPreview preview={quickDataPreview} loading={quickDataPreviewLoading} />

            <ManagerDiscoverySummary meta={managerMeta} expertMode={exploreExpertMode} />

            {exploreExpertMode && !loadingSector && !loadingRefine && !loadingSummarize && allIndicators.length > 0 ? (
              <details className="rounded-xl border border-border/70 bg-muted/15 px-4 py-3 group">
                <summary className="cursor-pointer select-none text-sm font-medium text-slate-800 list-none flex items-center justify-between gap-2 [&::-webkit-details-marker]:hidden">
                  <span>Volitelně: upřesnit výběr řad ({allIndicators.length})</span>
                  <span className="text-[11px] font-normal text-muted-foreground">
                    Analýza běží automaticky z kurátorovaného plánu
                  </span>
                </summary>
                <div className="mt-3 space-y-4">
                  <div className="flex flex-wrap items-center justify-end gap-2">
                    <button
                      type="button"
                      className="h-8 px-3 rounded-lg text-xs font-medium border border-teal-700/30 bg-teal-50 hover:bg-teal-100 text-teal-950"
                      onClick={() => toggleAllIndicators(true)}
                    >
                      Zvolit vše ({allIndicators.length})
                    </button>
                    <button
                      type="button"
                      className="h-8 px-3 rounded-lg text-xs font-medium border border-border bg-card hover:bg-muted/40 text-slate-700"
                      onClick={() => toggleAllIndicators(false)}
                    >
                      Zrušit vše
                    </button>
                  </div>

                  {indicatorSections.length > 0
                    ? indicatorSections.map((section) => {
                        const allInSection = sectionFullySelected(section.items, selectedKeys);
                        return (
                          <div key={section.id} className="space-y-2 pt-2">
                            <div className="flex flex-wrap items-center justify-between gap-2">
                              <h3
                                className={`text-xs font-semibold uppercase tracking-wide ${
                                  section.highlight ? "text-indigo-700" : "text-slate-500"
                                }`}
                              >
                                {section.label}
                              </h3>
                              <button
                                type="button"
                                className="h-7 px-2.5 rounded-lg text-[11px] font-medium border border-border bg-card hover:bg-muted/40 text-slate-700"
                                onClick={() => toggleSectionSelection(section.items, !allInSection)}
                              >
                                {allInSection ? "Zrušit vše" : "Zvolit vše"}
                              </button>
                            </div>
                            <div className="grid gap-2 sm:grid-cols-2">
                              {section.items.map((item) => (
                                <ExploreIndicatorCard
                                  key={seriesKey(item)}
                                  item={item}
                                  checked={selectedKeys.has(seriesKey(item))}
                                  onToggle={() => toggleSeries(item)}
                                />
                              ))}
                            </div>
                          </div>
                        );
                      })
                    : null}

                  {!indicatorSections.length && sectorIndicators.length > 0 ? (
                    <div className="space-y-2 pt-2">
                      <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                        Odvětvové ukazatele
                      </h3>
                      <div className="grid gap-2 sm:grid-cols-2">
                        {sectorIndicators.map((item) => (
                          <ExploreIndicatorCard
                            key={seriesKey(item)}
                            item={item}
                            checked={selectedKeys.has(seriesKey(item))}
                            onToggle={() => toggleSeries(item)}
                          />
                        ))}
                      </div>
                    </div>
                  ) : null}

                  {!indicatorSections.length && macroIndicators.length > 0 ? (
                    <div className="space-y-2 pt-2">
                      <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                        Makroukazatele (volitelné)
                      </h3>
                      <p className="text-[11px] text-muted-foreground">
                        Makro se v analýze doplní automaticky. Zde lze přidat další volitelné řady.
                      </p>
                      <div className="grid gap-2 sm:grid-cols-2">
                        {macroIndicators.map((item) => (
                          <ExploreIndicatorCard
                            key={seriesKey(item)}
                            item={item}
                            checked={selectedKeys.has(seriesKey(item))}
                            onToggle={() => toggleSeries(item)}
                          />
                        ))}
                      </div>
                    </div>
                  ) : null}
                </div>
              </details>
            ) : null}

            {loadingSector ? (
              <ExploreSectorScanLoader
                sourceStatuses={sourceStatuses}
                active={loadingSector}
                compact={false}
              />
            ) : null}

            {loadingSector ? (
              <p className="text-xs text-teal-800/90 bg-teal-50 border border-teal-200/70 rounded-lg px-3 py-2">
                Sestavuji kurátorovaný plán řad pro AI — jednotlivé karty se během načítání nezobrazují.
              </p>
            ) : null}

            {loadHint && !showStep2PipelineProgress ? (
              <div className="rounded-xl border border-amber-300/70 bg-amber-50 px-4 py-3 text-sm text-amber-950">
                {loadHint}
              </div>
            ) : null}
          </div>
        ) : null}

        {step === 2 ? (
          <div className="soft-card px-5 py-5 space-y-4">
            <h2 className="text-sm font-semibold text-slate-900">Krok 2 — Výsledek a skóre</h2>
            <div className="rounded-xl border border-sky-300/60 bg-sky-50/70 px-4 py-3 text-sm text-sky-950 space-y-1">
              <p>
                <span className="font-medium">Segment:</span> {sector || "—"}
                {" · "}
                <span className="font-medium">Geo:</span> {geoSummaryLabel}
                {refinedSeries.length ? ` · ${refinedSeries.length} řad` : ""}
              </p>
              <p>
                <span className="font-medium">Otázka:</span> {String(question || "").trim() || "—"}
              </p>
              {combinedRelatedSegments ? (
                <p>
                  <span className="font-medium">Související segmenty:</span> {combinedRelatedSegments}
                </p>
              ) : (
                <p>
                  <span className="font-medium">Související segmenty:</span> žádné (jen hlavní sektor)
                </p>
              )}
              {selectedUploadDocs.length ? (
                <p>
                  <span className="font-medium">Vlastní soubory:</span>{" "}
                  {selectedUploadDocs.map((row) => String(row?.original_name || "").trim()).filter(Boolean).join(", ")}
                </p>
              ) : null}
              <p className="text-[11px] text-sky-900/80">
                Výstup níže obsahuje hlavní závěr, klíčová čísla a také skóre prostředí a skóre pro vaši otázku.
              </p>
            </div>
            {loadingSector || loadingRefine || loadingSummarize ? (
              <div className="rounded-xl border border-indigo-300/60 bg-indigo-50/90 px-4 py-3 text-sm text-indigo-950 space-y-1">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="inline-flex items-center gap-2 font-medium">
                    <Loader2 className="h-4 w-4 animate-spin shrink-0" />
                    {loadingSector
                      ? "Načítám kurátorovaný plán řad…"
                      : loadingRefine
                        ? "Připravuji kontext pro AI…"
                        : "AI analyzuje vybrané ukazatele…"}
                  </span>
                  <div className="flex items-center gap-2">
                    {pipelineEtaSec > 0 ? (
                      <span className="text-[12px] font-mono tabular-nums text-indigo-800/90">
                        {pipelineSecondsLeft > 0
                          ? `zbývá ${formatExploreCountdownSec(pipelineSecondsLeft)}`
                          : `stále běží · ${formatExploreEtaSec(pipelineElapsedSec).replace("~", "")}`}
                      </span>
                    ) : null}
                    <button
                      type="button"
                      className="h-8 px-3 rounded-lg border border-rose-300 bg-rose-50 hover:bg-rose-100 text-rose-900 text-xs font-medium"
                      onClick={stopAnalysis}
                    >
                      STOP
                    </button>
                  </div>
                </div>
                {pipelineEtaSec > 0 ? (
                  <p className="text-[11px] text-indigo-900/80">
                    Celkový odhad {formatExploreEtaSec(pipelineEtaSec)}
                    {pipelineFetchLine ? ` · ${pipelineFetchLine}` : ""}
                  </p>
                ) : pipelineFetchLine ? (
                  <p className="text-[11px] text-indigo-900/80">{pipelineFetchLine}</p>
                ) : null}
              </div>
            ) : null}
            {loadHint && !showStep2PipelineProgress ? (
              <div className="rounded-xl border border-amber-300/70 bg-amber-50 px-4 py-3 text-sm text-amber-950">
                {loadHint}
              </div>
            ) : null}
            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                className="h-10 px-4 rounded-xl border border-teal-600/30 bg-teal-50 text-teal-900 hover:bg-teal-100 text-sm font-medium inline-flex items-center gap-1.5"
                onClick={startNewExploreQuery}
              >
                <RotateCcw className="h-3.5 w-3.5" />
                Nový dotaz
              </button>
              <button
                type="button"
                className="h-10 px-4 rounded-xl border border-border bg-card hover:bg-muted/40 text-sm"
                onClick={() => setStep(1)}
              >
                Zpět na vstupy
              </button>
            </div>
            {loadingSummarize ? (
              <ExploreSummarizeLoader
                active={loadingSummarize}
                question={question}
                estimateSec={exploreSummarizeEstimateSec}
                refreshing={!!summarizeResult}
                serverHint={summarizePendingDetail || (summarizeJobId ? `AI úloha ${summarizeJobId} běží na serveru.` : "")}
              />
            ) : null}
            {summarizeResult && !loadingSummarize ? (
              <>
                {summarizeResult?.partial || summarizeResult?.instant_ready ? (
                  <ExploreInstantPreviewCard
                    result={summarizeResult}
                    detailLoading={
                      Boolean(detailJobId) &&
                      detailJobStatus !== "completed" &&
                      detailJobStatus !== "failed"
                    }
                  />
                ) : null}
                {detailJobId && detailJobStatus !== "completed" ? (
                  <ExploreDetailAnalysisLoader
                    status={detailJobStatus || "running"}
                    progressStep={detailProgressStep}
                    progressPercent={detailProgressPercent}
                    detailMessage={detailJobMessage}
                    failedMessage={DETAIL_JOB_FAILED_USER_MESSAGE}
                  />
                ) : null}
                {(() => {
                  const isInstantV2Flow =
                    String(summarizeResult?.summarize_mode || summarizeMode || "")
                      .trim()
                      .toLowerCase() === "instant_then_detail_v2";
                  const detailReportReady =
                    !isInstantV2Flow ||
                    summarizeResult?.detail_ready ||
                    detailJobStatus === "completed" ||
                    summarizeResult?.final_answer_source === "detail_job";
                  if (!detailReportReady) {
                    // Grafy jsou načtené už v instant fázi — manažer je vidí hned,
                    // detailní textový závěr se dopočítává na pozadí.
                    if (!chartSeries.length) return null;
                    return (
                      <ExploreReportCharts
                        result={summarizeResult}
                        chartSeries={chartSeries}
                        compareCountries={chartCompareCountries}
                        compareFxPairs={chartCompareFxPairs}
                      />
                    );
                  }
                  const report = (
                    <SummarizeResultDisplay
                      result={summarizeResult}
                      exploreMeta={exploreMetaForFollowup}
                      onResultPatch={(updater) => setSummarizeResult((prev) => updater(prev))}
                      onRequestFullRefresh={handleExploreFullRefresh}
                      onRequestDetailAnalysis={handleExploreDetailAnalysis}
                      currentSummarizeMode={summarizeMode}
                      detailAnalysisAvailable={refinedSeries.length > 0}
                      chartSeries={chartSeries}
                      compareCountries={chartCompareCountries}
                      compareFxPairs={chartCompareFxPairs}
                      expertMode={exploreExpertMode}
                    />
                  );
                  return isInstantV2Flow ? (
                    <ExploreDetailAnalysisSection result={summarizeResult}>{report}</ExploreDetailAnalysisSection>
                  ) : (
                    report
                  );
                })()}
              </>
            ) : null}
          </div>
        ) : null}
      </div>
    </AppShell>
  );
}
