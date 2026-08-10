import {
  pdfLinkToPreviewContext,
  resolvePdfLinkTargetKind,
} from "./archiveChartLink";

describe("resolvePdfLinkTargetKind", () => {
  it("detects video links", () => {
    expect(
      resolvePdfLinkTargetKind({
        target_kind: "video",
        source_type: "external_video",
      })
    ).toBe("video");
  });

  it("detects podcast links", () => {
    expect(
      resolvePdfLinkTargetKind({
        target_kind: "podcast",
        source_type: "external_podcast",
      })
    ).toBe("podcast");
  });

  it("defaults legacy chart links to chart", () => {
    expect(
      resolvePdfLinkTargetKind({
        source_type: "csu",
        set_id: "abc",
      })
    ).toBe("chart");
  });
});

describe("pdfLinkToPreviewContext", () => {
  it("builds video preview context", () => {
    const ctx = pdfLinkToPreviewContext({
      target_kind: "video",
      link_url: "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
      label: "Rozhovor",
    });
    expect(ctx?.kind).toBe("video");
    expect(ctx?.url).toContain("youtube.com");
  });

  it("builds podcast preview context", () => {
    const ctx = pdfLinkToPreviewContext({
      target_kind: "podcast",
      link_url: "https://open.spotify.com/episode/abc123",
      label: "Rozhovor",
    });
    expect(ctx?.kind).toBe("podcast");
    expect(ctx?.url).toContain("spotify.com");
  });
});
