import {
  chartBlockSnippet,
  parseArticleBody,
  podcastEmbedUrl,
  videoEmbedUrl,
} from "./articleBodyFormat";

describe("parseArticleBody", () => {
  it("parses headings and bold text", () => {
    const blocks = parseArticleBody("# Hlavní\n\nOdstavec s **tučným** textem.");
    expect(blocks[0]).toMatchObject({ type: "heading", level: 1, text: "Hlavní" });
    expect(blocks[1]).toMatchObject({ type: "paragraph" });
    expect(blocks[1].text).toContain("**tučným**");
  });

  it("parses chart fenced blocks", () => {
    const body = `Úvod\n\n:::chart\n{"title":"Inflace","source_type":"csu","set_id":"abc","link_url":"/search/catalog"}\n:::\n\nZávěr`;
    const blocks = parseArticleBody(body);
    expect(blocks.some((b) => b.type === "chart" && b.chart?.set_id === "abc")).toBe(true);
    expect(blocks.some((b) => b.type === "paragraph" && b.text === "Závěr")).toBe(true);
  });

  it("builds chart snippet", () => {
    const snippet = chartBlockSnippet({
      title: "Test",
      source_type: "arad",
      set_id: "x",
      link_url: "/search/catalog",
    });
    expect(snippet).toContain(":::chart");
    expect(snippet).toContain('"set_id":"x"');
  });
});

describe("videoEmbedUrl", () => {
  it("converts youtube watch URLs", () => {
    expect(videoEmbedUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).toBe(
      "https://www.youtube.com/embed/dQw4w9WgXcQ"
    );
  });
});

describe("podcastEmbedUrl", () => {
  it("converts spotify episode URLs", () => {
    expect(podcastEmbedUrl("https://open.spotify.com/episode/abc123XYZ")).toBe(
      "https://open.spotify.com/embed/episode/abc123XYZ"
    );
  });

  it("converts localized spotify episode URLs", () => {
    expect(podcastEmbedUrl("https://open.spotify.com/intl-cs/episode/abc123XYZ")).toBe(
      "https://open.spotify.com/embed/episode/abc123XYZ"
    );
  });

  it("converts apple podcasts URLs", () => {
    expect(
      podcastEmbedUrl("https://podcasts.apple.com/us/podcast/the-daily/id1200361736")
    ).toBe("https://embed.podcasts.apple.com/us/podcast/the-daily/id1200361736");
  });
});
