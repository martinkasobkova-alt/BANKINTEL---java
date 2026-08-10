import {
  buildCatalogHubSearchUrl,
  buildCatalogHubScopeUrl,
  CATALOG_HUB_PATH,
  DEFAULT_HEADER_CATALOG_ID,
  parseCatalogHeaderFromLocation,
  AI_SCOPE_ALL,
  AI_SCOPE_SELECTED,
} from "@/lib/catalogHeaderSearch";

describe("catalogHeaderSearch", () => {
  it("builds AI catalog search url for all sources (default)", () => {
    const url = buildCatalogHubSearchUrl({
      q: "inflace Cesko",
      aiScope: AI_SCOPE_ALL,
      submitNonce: 123,
    });
    expect(url).toContain("ai=1");
    expect(url).toContain("runDeep=1");
    expect(url).toContain("q=inflace+Cesko");
    expect(url).not.toContain("scope=");
    expect(url).not.toContain("aiScope=");
  });

  it("builds AI catalog search url for single source", () => {
    const url = buildCatalogHubSearchUrl({
      q: "HDP",
      aiScope: "eurostat",
      submitNonce: 456,
    });
    expect(url).toContain("ai=1");
    expect(url).toContain("runDeep=1");
    expect(url).toContain("scope=eurostat");
    expect(url).toContain("q=HDP");
    expect(url).not.toContain("aiScope=");
  });

  it("builds AI catalog search url for selected sources", () => {
    const url = buildCatalogHubSearchUrl({
      q: "HDP",
      aiScope: AI_SCOPE_SELECTED,
      submitNonce: 789,
    });
    expect(url).toContain("ai=1");
    expect(url).toContain("runDeep=1");
    expect(url).toContain("aiScope=selected");
    expect(url).not.toContain("scope=");
  });

  it("always includes ai=1 and runDeep=1", () => {
    const url = buildCatalogHubSearchUrl({ q: "test", submitNonce: 1 });
    expect(url).toContain("ai=1");
    expect(url).toContain("runDeep=1");
  });

  it("parses location search params — single source scope", () => {
    const parsed = parseCatalogHeaderFromLocation("?q=test&scope=imf&ai=1");
    expect(parsed.q).toBe("test");
    expect(parsed.catalogId).toBe("imf");
    expect(parsed.aiScope).toBe("imf");
    expect(parsed.useAi).toBe(true);
  });

  it("parses location search params — selected sources scope", () => {
    const parsed = parseCatalogHeaderFromLocation("?q=test&aiScope=selected");
    expect(parsed.q).toBe("test");
    expect(parsed.aiScope).toBe(AI_SCOPE_SELECTED);
  });

  it("parses a concrete source as stronger than stale selected scope", () => {
    const parsed = parseCatalogHeaderFromLocation("?q=test&scope=arad&aiScope=selected");
    expect(parsed.aiScope).toBe("arad");
  });

  it("parses location search params — defaults to all sources", () => {
    const parsed = parseCatalogHeaderFromLocation("?q=test");
    expect(parsed.aiScope).toBe(AI_SCOPE_ALL);
    expect(parsed.useAi).toBe(true);
  });

  it("falls back to default catalog id", () => {
    const parsed = parseCatalogHeaderFromLocation("?q=test");
    expect(parsed.catalogId).toBe(DEFAULT_HEADER_CATALOG_ID);
  });

  it("updates scope in url without search submit", () => {
    const url = buildCatalogHubScopeUrl({
      catalogId: "oecd4",
      currentSearch: "?q=inflace&scope=arad&aiScope=selected",
    });
    expect(url).toBe(`${CATALOG_HUB_PATH}?q=inflace&scope=oecd4`);
  });
});
