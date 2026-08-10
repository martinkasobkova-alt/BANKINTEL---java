import { resolveHeroSectionScores } from "./exploreAnalysisInsights";

describe("resolveHeroSectionScores", () => {
  it("returns 8 areas with decision scores", () => {
    const     rows = resolveHeroSectionScores({
      section_scores_detail: {
        sector: { decision_score: 7.2 },
        macro: { score: 5.8 },
        regional_economy: { decision_score: 5.0 },
        neighbors: { decision_score: 6.0 },
        global: { decision_score: 4.0 },
      },
      section_scores: {
        commodities: 6.5,
      },
    });

    expect(rows).toHaveLength(8);
    expect(rows.find((r) => r.id === "sector")?.score).toBe(7.2);
    expect(rows.find((r) => r.id === "macro")?.score).toBe(5.8);
    expect(rows.find((r) => r.id === "commodities")?.score).toBe(6.5);
    expect(rows.find((r) => r.id === "regional_economy")?.score).toBe(5);
  });
});
