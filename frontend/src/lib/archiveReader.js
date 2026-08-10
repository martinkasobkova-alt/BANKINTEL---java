export const ARCHIVE_SEARCH_SCOPE_ISSUE = "issue";
export const ARCHIVE_SEARCH_SCOPE_MAGAZINE = "magazine";

export function buildIssuePdfUrl(issueId, page, zoom = "page-width") {
  const iid = String(issueId || "").trim();
  if (!iid) return "";
  const p = Number(page);
  const safePage = Number.isFinite(p) && p > 0 ? Math.floor(p) : 1;
  const z = typeof zoom === "number" ? Math.floor(zoom) : String(zoom || "").trim();
  const safeZoom = Number.isFinite(z) && z > 0 ? String(z) : z || "page-width";
  const qp = `?reader_page=${safePage}&reader_zoom=${encodeURIComponent(safeZoom)}`;
  const fragment =
    safeZoom === "page-fit"
      ? "page=1&view=Fit&toolbar=0&navpanes=0&scrollbar=0"
      : safeZoom === "page-width"
        ? "page=1&view=FitH&toolbar=0&navpanes=0"
        : `page=1&zoom=${encodeURIComponent(safeZoom)}&toolbar=0&navpanes=0`;
  return `/api/magazines/issues/${encodeURIComponent(iid)}/file${qp}#${fragment}`;
}

/** PNG náhled stránky — souřadnice hotspotů sedí 1:1 s obrázkem (na rozdíl od iframe PDF vieweru). */
export function buildIssuePagePreviewUrl(issueId, page, width = 1200) {
  const iid = String(issueId || "").trim();
  if (!iid) return "";
  const p = Number(page);
  const safePage = Number.isFinite(p) && p > 0 ? Math.floor(p) : 1;
  const w = Number(width);
  const safeWidth = Number.isFinite(w) && w >= 320 ? Math.min(2400, Math.floor(w)) : 1200;
  return `/api/magazines/issues/${encodeURIComponent(iid)}/page-preview?page=${safePage}&width=${safeWidth}`;
}

export function normalizeArchiveHits(input) {
  if (!Array.isArray(input)) return [];
  return input
    .filter((x) => x && typeof x === "object")
    .map((x) => ({
      chunk_id: String(x.chunk_id || "").trim(),
      page: Number(x.page) > 0 ? Number(x.page) : 1,
      snippet: String(x.snippet || "").trim(),
      issue: x.issue && typeof x.issue === "object" ? x.issue : {},
    }));
}

export function archiveHitIssueId(hit) {
  const iss = hit?.issue;
  if (iss && typeof iss === "object") {
    return String(iss.id || "").trim();
  }
  return String(hit?.issue_id || "").trim();
}

/** Nadpis výsledku: u jiného čísla zobrazí označení čísla + stranu. */
export function formatArchiveHitHeadline(hit, currentIssueId) {
  const page = Number(hit?.page) > 0 ? Number(hit.page) : 1;
  const iss = hit?.issue && typeof hit.issue === "object" ? hit.issue : {};
  const targetId = archiveHitIssueId(hit);
  const label = String(iss.issue_label || iss.title || "").trim();
  const current = String(currentIssueId || "").trim();
  if (targetId && (!current || targetId !== current)) {
    return `${label || "Jiné číslo"} · str. ${page}`;
  }
  return `str. ${page}`;
}

export function archiveHitIsOtherIssue(hit, currentIssueId) {
  const targetId = archiveHitIssueId(hit);
  const current = String(currentIssueId || "").trim();
  return Boolean(targetId && (!current || targetId !== current));
}
