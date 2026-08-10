import {
  chartTypeRequiresMultipleSeries,
  countCommonObservations,
  countScatterPairedPoints,
  getManagerFinalScoreLabel,
  getManagerFinalScoreValue,
  hasValidLegacyScore,
  hasValidManagerFinalScore,
  isValidScoreNumber,
  matchSeriesIdToChartSeries,
  partitionManagerRecommendedCharts,
  resolveExecutiveScore,
  resolveManagerChartDisplayPlan,
  resolveManagerInterpretationContext,
  resolveManagerRunTraceDisplay,
  resolveManagerVerdict,
  resolveManagerRecommendedChartItems,
  resolveRequiredOptionalSeries,
  resolveScoringDisplay,
  resolveScoreBreakdownRows,
  resolveTopRelationships,
  shouldSuppressSectionPrimaryScore,
  validateResolvedManagerChart,
} from "./exploreManagerInterpretation";

const SAMPLE_RESULT = {
  manager_interpretation_context: {
    analysis_mode: "public_only",
    final_score: { combined_score: 6.8, label: "opatrně pozitivní" },
    public_score: { score: 6.5, label: "smíšené / mírně pozitivní" },
    score_breakdown: {
      segment_momentum_score: { score: 7.0, label: "opatrně pozitivní" },
      demand_outlook_score: { score: 6.5, label: "opatrně pozitivní" },
      cost_pressure_score: { score: 5.0, label: "smíšené / mírně pozitivní" },
      macro_financial_score: { score: 6.0, label: "smíšené / mírně pozitivní" },
      market_commodity_score: { score: 6.5, label: "opatrně pozitivní" },
      data_confidence_score: { score: 8.0, label: "silně pozitivní" },
    },
    key_findings: ["Celkové skóre 6.8/10 (opatrně pozitivní)."],
    derived_relationships: [
      {
        relationship_id: "wages_vs_gdp",
        type: "growth_gap",
        latest_value: 2.8,
        unit: "percentage_points",
        confidence: "medium",
        strength: "medium",
        interpretation: "Mzdy rostou rychleji než HDP.",
        limitations: ["Tento vztah neprokazuje kauzalitu."],
      },
    ],
    warnings_for_llm: ["Korelace není kauzalita."],
    limitations: ["1 proxy řada."],
  },
  analysis_score: {
    decision_score: 7.2,
    composite: 7.2,
  },
  manager_recommended_charts: [
    {
      chart_id: "wages_chart",
      chart_type: "line",
      title: "Mzdy vs výkon",
      purpose: "Ukázat tlak na marže.",
      manager_message: "Mzdy se odpoutávají od HDP.",
      series_ids: ["wages_yoy"],
      priority: 2,
      render_with_existing_dashboard_module: true,
    },
    {
      chart_id: "missing_chart",
      chart_type: "line",
      title: "Chybějící řada",
      purpose: "Test",
      manager_message: "Nemá data",
      series_ids: ["unknown_series_xyz"],
      priority: 3,
    },
  ],
};

const CHART_SERIES = [
  {
    name: "Růst mezd YoY",
    setId: "wages_yoy",
    seriesId: "wages_yoy",
    labelCs: "Růst mezd YoY",
    rows: [
      { x: "2023", y: 4 },
      { x: "2024", y: 5 },
    ],
  },
  {
    name: "HDP",
    setId: "gdp_yoy",
    seriesId: "gdp_yoy",
    rows: [
      { x: "2023", y: 2 },
      { x: "2024", y: 2.5 },
    ],
  },
];

describe("manager final score helpers", () => {
  it("hasValidManagerFinalScore true for combined_score 7.1", () => {
    const result = {
      manager_interpretation_context: { final_score: { combined_score: 7.1 } },
    };
    expect(hasValidManagerFinalScore(result)).toBe(true);
    expect(getManagerFinalScoreValue(result)).toBe(7.1);
  });

  it("hasValidManagerFinalScore false for invalid values", () => {
    expect(hasValidManagerFinalScore({})).toBe(false);
    expect(hasValidManagerFinalScore({ manager_interpretation_context: { final_score: { combined_score: null } } })).toBe(false);
    expect(hasValidManagerFinalScore({ manager_interpretation_context: { final_score: { combined_score: NaN } } })).toBe(false);
    expect(hasValidManagerFinalScore({ manager_interpretation_context: { final_score: { combined_score: "7" } } })).toBe(false);
    expect(hasValidManagerFinalScore({ manager_interpretation_context: { final_score: { combined_score: 0.5 } } })).toBe(false);
    expect(hasValidManagerFinalScore({ manager_interpretation_context: { final_score: { combined_score: 11 } } })).toBe(false);
  });

  it("getManagerFinalScoreValue prefers combined_score over public_score", () => {
    expect(getManagerFinalScoreValue(SAMPLE_RESULT)).toBe(6.8);
    expect(isValidScoreNumber(6.8)).toBe(true);
  });

  it("getManagerFinalScoreLabel uses explicit label or range", () => {
    expect(getManagerFinalScoreLabel(SAMPLE_RESULT)).toBe("opatrně pozitivní");
    expect(getManagerFinalScoreLabel({ manager_interpretation_context: { final_score: { combined_score: 8.5 } } })).toBe("silně pozitivní");
    expect(getManagerFinalScoreLabel({ manager_interpretation_context: { final_score: { combined_score: 2.0 } } })).toBe("silně negativní");
  });
});

describe("resolveScoringDisplay", () => {
  it("uses manager_final_score and hides legacy hero", () => {
    const display = resolveScoringDisplay(SAMPLE_RESULT);
    expect(display.scoring_source).toBe("manager_final_score");
    expect(display.showManagerPanel).toBe(true);
    expect(display.showLegacyHero).toBe(false);
    expect(display.legacy_score_hidden_due_to_manager_score).toBe(true);
  });

  it("falls back to legacy hero when manager score missing", () => {
    const display = resolveScoringDisplay({ analysis_score: { decision_score: 6.5 } });
    expect(display.scoring_source).toBe("legacy_fallback");
    expect(display.showLegacyHero).toBe(true);
    expect(hasValidLegacyScore({ analysis_score: { decision_score: 6.5 } })).toBe(true);
  });

  it("returns none when no scores available", () => {
    const display = resolveScoringDisplay({});
    expect(display.scoring_source).toBe("none");
    expect(display.showLegacyHero).toBe(false);
  });

  it("never enables both primary score heroes", () => {
    const manager = resolveScoringDisplay(SAMPLE_RESULT);
    expect(manager.showManagerPanel && manager.showLegacyHero).toBe(false);
    const legacy = resolveScoringDisplay({ analysis_score: { decision_score: 6.0 } });
    expect(legacy.showLegacyHero).toBe(true);
    expect(legacy.scoring_source).toBe("legacy_fallback");
  });
});

describe("chart matching and validation", () => {
  it("matches series by exact series_id", () => {
    const match = matchSeriesIdToChartSeries("wages_yoy", CHART_SERIES);
    expect(match.series?.setId).toBe("wages_yoy");
    expect(match.confidence).toBe("high");
  });

  it("matches series by normalized suffix variant", () => {
    const match = matchSeriesIdToChartSeries("gdp", CHART_SERIES);
    expect(match.series?.setId).toBe("gdp_yoy");
    expect(["high", "medium"]).toContain(match.confidence);
  });

  it("validateResolvedManagerChart rejects empty payload", () => {
    const item = {
      spec: { chart_id: "x", chart_type: "line", title: "X", series_ids: ["x"] },
      series: [],
      required_series_ids: ["x"],
      required_series: [],
      missing_series_ids: ["x"],
      match_confidence: "none",
    };
    const validation = validateResolvedManagerChart(item);
    expect(validation.ok).toBe(false);
    expect(validation.reason).toBe("missing_required_series");
  });

  it("validateResolvedManagerChart rejects all-null values", () => {
    const item = {
      spec: { chart_id: "bad", chart_type: "line", title: "Bad", series_ids: ["a"] },
      series: [{ setId: "a", rows: [{ x: "2023", y: NaN }, { x: "2024", y: NaN }] }],
      required_series_ids: ["a"],
      required_series: [{ setId: "a", rows: [{ x: "2023", y: NaN }, { x: "2024", y: NaN }] }],
      missing_series_ids: [],
      match_confidence: "high",
    };
    const validation = validateResolvedManagerChart(item);
    expect(validation.ok).toBe(false);
    expect(validation.reason).toBe("all_values_null");
  });

  it("validateResolvedManagerChart accepts valid mapped chart", () => {
    const resolved = resolveManagerRecommendedChartItems(
      [SAMPLE_RESULT.manager_recommended_charts[0]],
      CHART_SERIES
    )[0];
    const validation = validateResolvedManagerChart(resolved);
    expect(validation.ok).toBe(true);
    expect(validation.point_count).toBeGreaterThanOrEqual(2);
  });

  it("resolveManagerRecommendedChartItems reports missing series_ids", () => {
    const resolved = resolveManagerRecommendedChartItems(
      SAMPLE_RESULT.manager_recommended_charts,
      CHART_SERIES
    );
    const missing = resolved.find((row) => row.spec.chart_id === "missing_chart");
    expect(missing.missing_series_ids).toContain("unknown_series_xyz");
  });

  it("partitionManagerRecommendedCharts keeps only valid charts", () => {
    const { valid, invalid } = partitionManagerRecommendedCharts(
      SAMPLE_RESULT.manager_recommended_charts,
      CHART_SERIES
    );
    expect(valid.length).toBe(1);
    expect(invalid.length).toBe(1);
    expect(valid[0].spec.chart_id).toBe("wages_chart");
  });

  it("resolveManagerChartDisplayPlan falls back when all recommended charts invalid", () => {
    const plan = resolveManagerChartDisplayPlan(
      {
        manager_recommended_charts: [SAMPLE_RESULT.manager_recommended_charts[1]],
      },
      CHART_SERIES
    );
    expect(plan.mode).toBe("fallback");
    expect(plan.showUnmappedMessage).toBe(true);
    expect(plan.useFallbackChartPayload).toBe(true);
  });

  it("resolveManagerChartDisplayPlan uses manager mode for valid charts", () => {
    const plan = resolveManagerChartDisplayPlan(SAMPLE_RESULT, CHART_SERIES);
    expect(plan.mode).toBe("manager");
    expect(plan.validCharts.length).toBe(1);
    expect(plan.useFallbackChartPayload).toBe(false);
  });
});

const TWO_SERIES = [
  {
    name: "Series A",
    setId: "series_a",
    seriesId: "series_a",
    rows: [
      { x: "2022", y: 1 },
      { x: "2023", y: 2 },
      { x: "2024", y: 3 },
    ],
  },
  {
    name: "Series B",
    setId: "series_b",
    seriesId: "series_b",
    rows: [
      { x: "2022", y: 2 },
      { x: "2023", y: 3 },
      { x: "2024", y: 4 },
    ],
  },
];

const DISJOINT_SERIES = [
  {
    setId: "series_a",
    seriesId: "series_a",
    rows: [
      { x: "2020", y: 1 },
      { x: "2021", y: 2 },
    ],
  },
  {
    setId: "series_b",
    seriesId: "series_b",
    rows: [
      { x: "2023", y: 3 },
      { x: "2024", y: 4 },
    ],
  },
];

function validateChartSpec(spec, seriesList) {
  const resolved = resolveManagerRecommendedChartItems([spec], seriesList)[0];
  return validateResolvedManagerChart(resolved);
}

describe("multi-series chart validation", () => {
  it("dual_axis_line with one mapped series is invalid", () => {
    const validation = validateChartSpec(
      {
        chart_id: "dual_one",
        chart_type: "dual_axis_line",
        series_ids: ["series_a", "series_b"],
      },
      [TWO_SERIES[0]]
    );
    expect(validation.ok).toBe(false);
    expect(validation.chart_type_requires_multiple_series).toBe(true);
    expect(validation.missing_series_ids).toContain("series_b");
    expect(["missing_required_series", "insufficient_required_series"]).toContain(validation.reason);
  });

  it("dual_axis_line with two valid series is valid", () => {
    const validation = validateChartSpec(
      {
        chart_id: "dual_two",
        chart_type: "dual_axis_line",
        series_ids: ["series_a", "series_b"],
      },
      TWO_SERIES
    );
    expect(validation.ok).toBe(true);
    expect(validation.matched_required_series_count).toBe(2);
    expect(validation.common_observation_count).toBeGreaterThanOrEqual(2);
  });

  it("indexed_line with one series is invalid", () => {
    const validation = validateChartSpec(
      {
        chart_id: "indexed_one",
        chart_type: "indexed_line",
        series_ids: ["series_a", "series_b"],
      },
      [TWO_SERIES[0]]
    );
    expect(validation.ok).toBe(false);
    expect(validation.reason).toBe("missing_required_series");
  });

  it("spread_line without precomputed spread and without second series is invalid", () => {
    const validation = validateChartSpec(
      {
        chart_id: "spread_missing",
        chart_type: "spread_line",
        series_ids: ["series_a", "series_b"],
        transforms: [{ series_id: "series_a", transform: "spread_with", other_series_id: "series_b" }],
      },
      [TWO_SERIES[0]]
    );
    expect(validation.ok).toBe(false);
    expect(validation.chart_type_requires_multiple_series).toBe(true);
  });

  it("company_vs_sector without company or sector series is invalid", () => {
    const validation = validateChartSpec(
      {
        chart_id: "company_sector",
        chart_type: "company_vs_sector",
        series_ids: ["company_metric", "sector_metric"],
      },
      [
        {
          setId: "company_metric",
          seriesId: "company_metric",
          rows: [
            { x: "2023", y: 1 },
            { x: "2024", y: 2 },
          ],
        },
      ]
    );
    expect(validation.ok).toBe(false);
    expect(validation.missing_series_ids).toContain("sector_metric");
  });

  it("multi-series chart with zero period overlap is invalid", () => {
    const validation = validateChartSpec(
      {
        chart_id: "no_overlap",
        chart_type: "indexed_line",
        series_ids: ["series_a", "series_b"],
      },
      DISJOINT_SERIES
    );
    expect(validation.ok).toBe(false);
    expect(validation.reason).toBe("insufficient_common_observations");
    expect(validation.common_observation_count).toBe(0);
    expect(countCommonObservations(DISJOINT_SERIES)).toBe(0);
  });

  it("scatter with fewer than 3 paired points is invalid", () => {
    const validation = validateChartSpec(
      {
        chart_id: "scatter_thin",
        chart_type: "scatter",
        series_ids: ["series_a", "series_b"],
      },
      [
        {
          setId: "series_a",
          seriesId: "series_a",
          rows: [
            { x: "2023", y: 1 },
            { x: "2024", y: 2 },
          ],
        },
        {
          setId: "series_b",
          seriesId: "series_b",
          rows: [
            { x: "2023", y: 2 },
            { x: "2024", y: 3 },
          ],
        },
      ]
    );
    expect(validation.ok).toBe(false);
    expect(validation.reason).toBe("insufficient_scatter_pairs");
    expect(countScatterPairedPoints(TWO_SERIES)).toBe(3);
  });

  it("optional_series_ids may be missing without invalidating chart", () => {
    const spec = {
      chart_id: "line_optional",
      chart_type: "line",
      series_ids: ["series_a", "annotation_band"],
      required_series_ids: ["series_a"],
      optional_series_ids: ["annotation_band"],
    };
    const resolved = resolveManagerRecommendedChartItems([spec], [TWO_SERIES[0]])[0];
    expect(resolved.missing_series_ids).toEqual([]);
    expect(resolved.missing_optional_series_ids).toContain("annotation_band");
    const validation = validateResolvedManagerChart(resolved);
    expect(validation.ok).toBe(true);
    expect(validation.required_series_count).toBe(1);
  });

  it("falls back to chart_payload when all recommended charts fail", () => {
    const plan = resolveManagerChartDisplayPlan(
      {
        manager_recommended_charts: [
          {
            chart_id: "dual_fail",
            chart_type: "dual_axis_line",
            series_ids: ["series_a", "series_b"],
          },
        ],
      },
      [TWO_SERIES[0]]
    );
    expect(plan.mode).toBe("fallback");
    expect(plan.showUnmappedMessage).toBe(true);
    expect(plan.useFallbackChartPayload).toBe(true);
    expect(plan.validCharts.length).toBe(0);
  });
});

describe("section score suppression", () => {
  it("suppresses per-section primary score when manager final_score exists", () => {
    expect(shouldSuppressSectionPrimaryScore(SAMPLE_RESULT)).toBe(true);
    expect(shouldSuppressSectionPrimaryScore({ analysis_score: { decision_score: 6.5 } })).toBe(false);
  });
});

describe("required vs optional series resolution", () => {
  it("derives required series from relationship charts", () => {
    const resolved = resolveRequiredOptionalSeries({
      chart_type: "indexed_line",
      relationship_id: "wages_vs_gdp",
      series_ids: ["wages_yoy", "gdp_yoy"],
    });
    expect(resolved.required_series_ids).toEqual(["wages_yoy", "gdp_yoy"]);
    expect(chartTypeRequiresMultipleSeries("indexed_line", {})).toBe(true);
  });
});

describe("legacy interpretation helpers", () => {
  it("resolves executive score from manager context", () => {
    const ctx = resolveManagerInterpretationContext(SAMPLE_RESULT);
    const exec = resolveExecutiveScore(ctx);
    expect(exec.score).toBe(6.8);
  });

  it("resolves score breakdown rows", () => {
    const rows = resolveScoreBreakdownRows(SAMPLE_RESULT.manager_interpretation_context);
    expect(rows.length).toBeGreaterThanOrEqual(6);
  });

  it("resolves top relationships", () => {
    const rels = resolveTopRelationships(SAMPLE_RESULT.manager_interpretation_context);
    expect(rels[0].relationship_id).toBe("wages_vs_gdp");
  });
});

describe("resolveManagerRunTraceDisplay", () => {
  it("flags consistency_error from manager_run_trace", () => {
    const display = resolveManagerRunTraceDisplay({
      primary_segment: "automotive",
      manager_run_trace: {
        query_understanding: { primary_segment: "automotive" },
        final_report: {
          main_section_segment: "chemicals_materials",
          key_numbers_segments: ["chemicals_materials"],
          chart_segments: ["chemicals_materials"],
        },
        consistency_flags: {
          consistency_error: true,
          charts_match_primary_segment: false,
        },
      },
    });
    expect(display.consistencyError).toBe(true);
    expect(display.chartsMismatch).toBe(true);
    expect(display.primarySegment).toBe("automotive");
    expect(display.mainSectionSegment).toBe("chemicals_materials");
  });

  it("does not flag consistency when segments align", () => {
    const display = resolveManagerRunTraceDisplay({
      manager_run_trace: {
        query_understanding: { primary_segment: "automotive" },
        final_report: { main_section_segment: "automotive", chart_segments: ["automotive"] },
        consistency_flags: { consistency_error: false, charts_match_primary_segment: true },
      },
    });
    expect(display.consistencyError).toBe(false);
    expect(display.chartsMismatch).toBe(false);
  });
});

describe("resolveManagerVerdict", () => {
  it("returns structured verdict fields", () => {
    const verdict = resolveManagerVerdict({
      manager_verdict: {
        verdict_headline: "Opatrná expanze.",
        business_conclusion: "Data nepodporují agresivní krok.",
        top_reasons: [{ title: "Produkce", value: "100", period: "2025", change: "+1%", source: "Eurostat" }],
        recommendation: "Pilotně.",
        key_risks: ["Poptávka"],
        decision_triggers: ["Objednávky"],
        brief_limitations: "Veřejná data.",
      },
    });
    expect(verdict.hasStructuredVerdict).toBe(true);
    expect(verdict.headline).toBe("Opatrná expanze.");
    expect(verdict.topReasons.length).toBe(1);
  });
});
