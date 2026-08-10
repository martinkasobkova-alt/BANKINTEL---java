import {
  metricHighlightClass,
  scoreHighlightClass,
  tokenizeExploreCommentText,
} from "./exploreCommentScoreHighlight";

describe("tokenizeExploreCommentText", () => {
  it("highlights score and rating in economic briefing sentence", () => {
    const text =
      "Ekonomické prostředí pro Zdravotnictví a farmacie v Finsku hodnotíme na 5.8/10 (smíšený). Pro zadanou manažerskou otázku vychází decision score 5.2/10.";
    const tokens = tokenizeExploreCommentText(text);
    const highlighted = tokens.filter((t) => t.kind !== "text").map((t) => t.value);
    expect(highlighted).toContain("5.8/10");
    expect(highlighted).toContain("(smíšený)");
    expect(highlighted).toContain("5.2/10");
  });

  it("highlights coherence evaluation phrase", () => {
    const text = "Sektorová koherence: sektorové signály jsou rozptýlené.";
    const tokens = tokenizeExploreCommentText(text);
    const highlighted = tokens.filter((t) => t.kind !== "text").map((t) => t.value).join(" ");
    expect(highlighted).toMatch(/sektorové signály jsou rozptýlené/i);
  });

  it("highlights percentages and macro numbers in main comment", () => {
    const text =
      "klíčová čísla ukazují v Německu v roce 2026 jen slabý růst HDP 0,79 %, inflaci 2,65 % a nezaměstnanost 3,86 %";
    const tokens = tokenizeExploreCommentText(text);
    const highlighted = tokens.filter((t) => t.kind !== "text").map((t) => t.value);
    expect(highlighted).toContain("0,79 %");
    expect(highlighted).toContain("2,65 %");
    expect(highlighted).toContain("3,86 %");
    expect(highlighted.some((v) => /slabý růst/i.test(v))).toBe(true);
  });

  it("highlights sector comment metrics dates and changes", () => {
    const text =
      "Poslední historická hodnota 130.644220351696 (2023-04-01), mezioddobní změna -3.4 %, emise 99.33 Mt CO2 eq. (+4.4 %).";
    const tokens = tokenizeExploreCommentText(text);
    const highlighted = tokens.filter((t) => t.kind !== "text").map((t) => t.value);
    expect(highlighted).toContain("130.644220351696");
    expect(highlighted).toContain("2023-04-01");
    expect(highlighted).toContain("-3.4 %");
    expect(highlighted).toContain("99.33 Mt CO2 eq.");
    expect(highlighted).toContain("+4.4 %");
  });

  it("highlights qualitative rating without parentheses", () => {
    const text = "Obecné prostředí pro bankovnictví a finance je smíšené až slabší";
    const tokens = tokenizeExploreCommentText(text);
    const highlighted = tokens.filter((t) => t.kind !== "text").map((t) => t.value).join(" ");
    expect(highlighted).toMatch(/smíšené až slabší/i);
  });
});

describe("scoreHighlightClass", () => {
  it("returns color class for numeric score", () => {
    expect(scoreHighlightClass("5.8/10")).toMatch(/amber|teal|emerald|rose/);
  });
});

describe("metricHighlightClass", () => {
  it("styles negative and positive changes differently", () => {
    expect(metricHighlightClass("-3.4 %")).toMatch(/rose/);
    expect(metricHighlightClass("+4.4 %")).toMatch(/emerald/);
    expect(metricHighlightClass("2,65 %")).toMatch(/202/);
  });
});
