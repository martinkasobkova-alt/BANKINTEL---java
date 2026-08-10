import { coerceChartNumeric } from "@/lib/chartZoomHelpers";

/**
 * Min / max / median across categorical points (e.g. countries in latest data mode).
 * @param {Array<{ x?: string, y?: number, value?: number, period?: string, name?: string }>} rows
 * @returns {{ min: { label: string, value: number }, max: { label: string, value: number }, median: number, count: number } | null}
 */
export function computeValueCompareStats(rows) {
  const pts = (Array.isArray(rows) ? rows : [])
    .map((r) => {
      const y = coerceChartNumeric(r?.y ?? r?.value);
      const label = String(r?.x ?? r?.period ?? r?.name ?? "").trim();
      return { label, y };
    })
    .filter((p) => p.label && p.y != null && Number.isFinite(p.y));
  if (pts.length < 2) return null;

  let minPt = pts[0];
  let maxPt = pts[0];
  for (const p of pts) {
    if (p.y < minPt.y) minPt = p;
    if (p.y > maxPt.y) maxPt = p;
  }

  const values = pts.map((p) => p.y).sort((a, b) => a - b);
  const mid = Math.floor(values.length / 2);
  const median =
    values.length % 2 === 1 ? values[mid] : (values[mid - 1] + values[mid]) / 2;

  return {
    min: { label: minPt.label, value: minPt.y },
    max: { label: maxPt.label, value: maxPt.y },
    median,
    count: pts.length,
  };
}

/** Layout density for summary cards inside resizable chart tiles. */
export function resolveValueCompareSummaryDensity({
  miniChartMode = false,
  veryNarrowWidget = false,
  chartCompact = false,
  fsExpand = false,
} = {}) {
  if (fsExpand) return "expanded";
  if (miniChartMode || veryNarrowWidget || chartCompact) return "mini";
  return "normal";
}

const KPI_SUMMARY_MODES = new Set(["auto", "full", "compact", "mini", "hidden"]);

export function normalizeKpiSummaryMode(mode) {
  const m = String(mode || "auto").trim().toLowerCase();
  return KPI_SUMMARY_MODES.has(m) ? m : "auto";
}

/**
 * Product-level KPI strip mode. This is separate from chart density because
 * catalog previews often look like full-width widgets while living in small shells.
 */
export function resolveKpiSummaryMode({
  mode = "auto",
  catalogLivePreview = false,
  catalogChartSize = "",
  fsExpand = false,
  hasStats = true,
  isMobileChartUi = false,
} = {}) {
  if (!hasStats) return "hidden";

  const explicit = normalizeKpiSummaryMode(mode);
  if (explicit !== "auto") return explicit;

  const size = String(catalogChartSize || "").trim().toLowerCase();
  // Smallest catalog shell only — single KPI cell.
  if (size === "compact") return "mini";

  if (catalogLivePreview && size === "fullscreen") {
    if (isMobileChartUi) return "mini";
    return "compact";
  }

  // Fullscreen gets larger typography.
  if (fsExpand) return "full";

  return "compact";
}
