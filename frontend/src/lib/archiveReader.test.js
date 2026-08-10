import {
  archiveHitIssueId,
  buildIssuePdfUrl,
  formatArchiveHitHeadline,
  normalizeArchiveHits,
} from "./archiveReader";

describe("archiveReader helpers", () => {
  test("buildIssuePdfUrl builds safe page url", () => {
    expect(buildIssuePdfUrl("abc123", 7)).toBe("/api/magazines/issues/abc123/file?reader_page=7&reader_zoom=page-width#page=1&view=FitH&toolbar=0&navpanes=0");
    expect(buildIssuePdfUrl("abc123", -2)).toBe("/api/magazines/issues/abc123/file?reader_page=1&reader_zoom=page-width#page=1&view=FitH&toolbar=0&navpanes=0");
    expect(buildIssuePdfUrl("abc123", 4, "page-fit")).toBe("/api/magazines/issues/abc123/file?reader_page=4&reader_zoom=page-fit#page=1&view=Fit&toolbar=0&navpanes=0&scrollbar=0");
    expect(buildIssuePdfUrl("abc123", 4, 130)).toBe("/api/magazines/issues/abc123/file?reader_page=4&reader_zoom=130#page=1&zoom=130&toolbar=0&navpanes=0");
    expect(buildIssuePdfUrl("", 5)).toBe("");
  });

  test("formatArchiveHitHeadline shows issue label for other issues", () => {
    const hit = { page: 5, issue: { id: "other", issue_label: "02/2026" } };
    expect(formatArchiveHitHeadline(hit, "current")).toBe("02/2026 · str. 5");
    expect(formatArchiveHitHeadline(hit, "other")).toBe("str. 5");
    expect(formatArchiveHitHeadline(hit, "")).toBe("02/2026 · str. 5");
    expect(archiveHitIssueId(hit)).toBe("other");
  });

  test("normalizeArchiveHits normalizes shape", () => {
    const out = normalizeArchiveHits([
      { chunk_id: "x1", page: "3", snippet: " hi " },
      null,
      { page: 0 },
    ]);
    expect(out).toEqual([
      { chunk_id: "x1", page: 3, snippet: "hi", issue: {} },
      { chunk_id: "", page: 1, snippet: "", issue: {} },
    ]);
  });
});
