import {
  computeAnalysisPortfolioBudget,
  mergeExploreManagerPayloads,
  parseExploreManagerPayload,
  selectAnalysisPortfolio,
} from "@/lib/exploreManagerPayload";

describe("selectAnalysisPortfolio", () => {
  it("preserves explicit selections and fills the portfolio across indicator families", () => {
    const rows = [
      { source_type: "eurostat", set_id: "npl", default_selected: true, manager_category: "sector_indicators" },
      { source_type: "eurostat", set_id: "leverage", default_selected: true, manager_category: "sector_indicators" },
      { source_type: "ecb2", set_id: "roa", manager_category: "financial_indicators" },
      { source_type: "ecb2", set_id: "mortgages", manager_category: "leading_indicators" },
      { source_type: "imf", set_id: "inflation", manager_category: "macro_indicators" },
    ];

    const selected = selectAnalysisPortfolio(rows, 5);

    expect(selected.map((row) => row.set_id)).toEqual([
      "npl",
      "leverage",
      "roa",
      "mortgages",
      "inflation",
    ]);
  });

  it("uses the safe minimum when no request context is supplied", () => {
    const rows = Array.from({ length: 30 }, (_, index) => ({
      source_type: "source",
      set_id: `series-${index}`,
      manager_category: `family-${index % 5}`,
    }));

    expect(selectAnalysisPortfolio(rows).length).toBe(8);
  });

  it("does not let generated preset defaults crowd out other sources", () => {
    const rows = [
      { source: "fred", set_id: "fred-1", default_selected: true, from_preset: true, manager_series_tier: "must_have" },
      { source: "fred", set_id: "fred-2", default_selected: true, from_preset: true, manager_series_tier: "must_have" },
      { source: "eurostat", set_id: "roe", default_selected: true, from_preset: true, manager_series_tier: "medium" },
      { source: "ecb", set_id: "mortgage", default_selected: true, from_preset: true, manager_series_tier: "medium" },
    ];

    expect(selectAnalysisPortfolio(rows, 3).map((row) => row.set_id)).toEqual(["fred-1", "roe", "mortgage"]);
  });
});

describe("computeAnalysisPortfolioBudget", () => {
  const rows = Array.from({ length: 40 }, (_, index) => ({
    source_type: ["eurostat", "ecb2", "fred", "imf"][index % 4],
    set_id: `series-${index}`,
    manager_category: `family-${index % 8}`,
    indicator_role: `role-${index % 5}`,
  }));

  it("grows for broad multi-geo analyses while remaining bounded", () => {
    const budget = computeAnalysisPortfolioBudget({
      analysis_plan: {
        related_segments: ["a", "b", "c"],
        country_context: { country_codes: ["CZ", "DE", "AT"] },
        selection_stats: { broad_analysis: true },
      },
      multi_sector_comparison: { sectors: [{}, {}, {}] },
    }, rows);

    expect(budget).toBeGreaterThan(14);
    expect(budget).toBeLessThanOrEqual(30);
  });

  it("feeds the computed budget into payload preselection", () => {
    const payload = {
      analysis_plan: {
        related_segments: ["a", "b"],
        country_context: { country_codes: ["CZ", "DE"] },
        selection_stats: { broad_analysis: true },
      },
      sector_indicators: rows,
    };

    const parsed = parseExploreManagerPayload(payload);
    expect(parsed.preselectKeys).toHaveLength(parsed.analysisBudget);
    expect(parsed.analysisBudget).toBeGreaterThan(14);
  });
});

describe("mergeExploreManagerPayloads", () => {
  it("keeps verified discovery order and supplements it with curated candidates", () => {
    const curated = {
      sector_indicators: [
        { source: "eurostat", dataset_id: "roe" },
        { source: "ecb", dataset_id: "mortgage" },
      ],
    };
    const discovery = {
      ok: true,
      sector_indicators: [
        { source: "eurostat", dataset_id: "roe", verified: true },
        { source: "eurostat", dataset_id: "npl", verified: true },
      ],
    };

    const merged = mergeExploreManagerPayloads(curated, discovery);

    expect(merged.sector_indicators.map((row) => row.dataset_id)).toEqual(["roe", "npl", "mortgage"]);
    expect(merged.sector_indicators[0].verified).toBe(true);
  });
});
