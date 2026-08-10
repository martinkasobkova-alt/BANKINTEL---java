import { sourceLabel } from "@/lib/exploreManagerPayload";

/**
 * Lidsky čitelný zdroj + technické ID série pro graf v Manager Explorer reportu.
 */
export function formatExploreChartSource(series) {
  const rawSource = String(series?.source || "").trim().toLowerCase();
  const label =
    String(series?.sourceLabel || series?.source_label || "").trim() ||
    sourceLabel({ source: rawSource, source_type: rawSource });
  const datasetId = String(series?.setId || series?.set_id || "").trim();
  const safeLabel = label || "Neuvedený zdroj";
  return {
    label: safeLabel,
    datasetId,
    line: datasetId ? `${safeLabel} · ${datasetId}` : safeLabel,
  };
}
