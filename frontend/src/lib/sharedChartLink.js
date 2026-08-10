import { pdfLinkToPreviewContext } from "@/lib/archiveChartLink";

/** Normalizovaný odkaz na graf (stejný tvar jako shared_chart ve zprávách). */
export function normalizeSharedChart(raw) {
  if (!raw || typeof raw !== "object") return null;
  const title = String(raw.title || "").trim();
  const source_type = String(raw.source_type || "").trim();
  const set_id = String(raw.set_id || "").trim();
  const link_url = String(raw.link_url || "").trim();
  if (!title && !set_id && !link_url) return null;
  return { title, source_type, set_id, link_url };
}

/** Převod shared_chart ze zprávy na tvar pro ArchiveInlineChartPanel. */
export function sharedChartToPreviewLink(shared) {
  const norm = normalizeSharedChart(shared);
  if (!norm) return null;
  return {
    source_type: norm.source_type,
    set_id: norm.set_id,
    target_title: norm.title,
    link_url: norm.link_url,
    id: `${norm.source_type}:${norm.set_id}`,
  };
}

export function isSharedChartInlinePreviewable(shared) {
  const link = sharedChartToPreviewLink(shared);
  if (!link) return false;
  const ctx = pdfLinkToPreviewContext(link);
  return ctx?.kind === "catalog";
}

export const DASHBOARD_SHAREABLE_WIDGET_TYPES = new Set([
  "external_catalog_chart",
  "chart",
  "computed_chart",
  "user_upload_chart",
  "uploaded_data_chart",
  "arad_view",
  "eurostat_view",
  "csu_view",
  "ecb_view",
  "fred_view",
  "imf_view",
  "oecd_view",
  "bis_view",
  "data360_view",
  "dataset_view",
  "computed_view",
]);
