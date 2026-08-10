import { foldId } from "@/lib/exploreManagerInterpretation";

/**
 * Normalizes one "used series" reference (string or object) into a consistent shape.
 */
export function normalizeUsedSeriesRow(row) {
  if (typeof row === "string") {
    return { title: String(row || "").trim() };
  }
  if (row && typeof row === "object") {
    return {
      title: String(row.title || row.name || "").trim(),
      source_type: String(row.source_type || row.source || "").trim() || null,
      series_id: String(row.series_id || "").trim() || null,
      dataset_id: String(row.dataset_id || row.set_id || "").trim() || null,
      set_id: String(row.set_id || row.dataset_id || "").trim() || null,
      fact: row.fact ? String(row.fact) : null,
      question_link: row.question_link ? String(row.question_link) : null,
      status: row.status ? String(row.status) : null,
      reason: row.reason ? String(row.reason) : null,
      query_params: row.query_params && typeof row.query_params === "object" ? row.query_params : null,
    };
  }
  return { title: String(row || "").trim() };
}

/**
 * Builds a lookup index (by title, by series id) from `series_coverage`.
 *
 * `series_coverage` is an array of per-series rows on some response shapes, but a plain
 * summary object ({loaded, failed, requested} counts) on others (see ExploreSummarizeService /
 * ExploreInstantThenDetailService on the backend) - only the array shape carries per-series
 * rows to index, so anything else (object, null, undefined) safely yields an empty index
 * instead of throwing "is not iterable".
 */
export function buildSeriesCoverageIndex(coverageRows) {
  const byTitle = new Map();
  const bySeriesId = new Map();
  for (const row of Array.isArray(coverageRows) ? coverageRows : []) {
    if (!row || typeof row !== "object") continue;
    const titleKey = foldId(row.title);
    if (titleKey && !byTitle.has(titleKey)) byTitle.set(titleKey, row);
    const sid = String(row.series_id || "").trim();
    if (sid && !bySeriesId.has(foldId(sid))) bySeriesId.set(foldId(sid), row);
  }
  return { byTitle, bySeriesId };
}

/**
 * Enriches a normalized "used series" row with matching coverage data (series/dataset ids,
 * fact, status, reason) looked up by series id first, then by title.
 */
export function enrichUsedSeriesRow(row, coverageIndex) {
  const normalized = normalizeUsedSeriesRow(row);
  if (!coverageIndex) return normalized;
  const fromId = normalized.series_id
    ? coverageIndex.bySeriesId.get(foldId(normalized.series_id))
    : null;
  const fromTitle = normalized.title ? coverageIndex.byTitle.get(foldId(normalized.title)) : null;
  const match = fromId || fromTitle;
  if (!match) return normalized;
  return {
    ...normalized,
    series_id: normalized.series_id || match.series_id || null,
    dataset_id: normalized.dataset_id || match.dataset_id || null,
    set_id: normalized.set_id || match.dataset_id || null,
    fact: normalized.fact || match.fact || null,
    question_link: normalized.question_link || match.question_link || null,
    status: normalized.status || match.status || null,
    reason: normalized.reason || match.reason || null,
    source_type: normalized.source_type || match.source_type || null,
    query_params: normalized.query_params || match.query_params || null,
  };
}
