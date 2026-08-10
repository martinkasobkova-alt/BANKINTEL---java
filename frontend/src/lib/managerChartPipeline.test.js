import {
  applyIndexBase100,
  applyRatio,
  applySpread,
} from "@/lib/managerChartTransforms";
import { buildManagerChartSeriesPayload } from "@/lib/managerChartPayload";
import {
  matchSeriesRefToChartSeries,
  partitionManagerRecommendedCharts,
  resolveManagerChartDisplayPlan,
  resolveManagerRecommendedChartItems,
  validateRequiredSeriesMatchConfidence,
} from "@/lib/exploreManagerInterpretation";

const SERIES_A = {
  name: "Series A",
  setId: "payload_set_a",
  seriesId: "series_a",
  signal: "wages",
  segmentId: "macro_economy",
  geo: "CZ",
  freq: "A",
  unit: "%",
  labelCs: "Mzdy",
  rows: [
    { x: "2022", y: 100 },
    { x: "2023", y: 110 },
    { x: "2024", y: 120 },
  ],
};

const SERIES_B = {
  name: "Series B",
  setId: "payload_set_b",
  seriesId: "series_b",
  signal: "gdp",
  segmentId: "macro_economy",
  geo: "CZ",
  freq: "A",
  unit: "index",
  labelCs: "HDP",
  rows: [
    { x: "2022", y: 200 },
    { x: "2023", y: 210 },
    { x: "2024", y: 220 },
  ],
};

function resolvedItem(spec, seriesList = [SERIES_A, SERIES_B]) {
  return resolveManagerRecommendedChartItems([spec], seriesList)[0];
}

describe("manager chart payload builder", () => {
  it("dual_axis_line payload contains two series, not only series[0]", () => {
    const item = resolvedItem({
      chart_id: "dual",
      chart_type: "dual_axis_line",
      series_ids: ["series_a", "series_b"],
      transforms: [
        { series_id: "series_a", type: "none", axis: "primary" },
        { series_id: "series_b", type: "none", axis: "secondary" },
      ],
    });
    const built = buildManagerChartSeriesPayload(item);
    expect(built.ok).toBe(true);
    expect(built.payload.series.length).toBe(2);
    expect(built.payload.series.some((s) => s.axis === "right")).toBe(true);
    expect(built.payload.dual_axis).toBe(true);
  });

  it("indexed_line applies index_base_100 on both series", () => {
    const item = resolvedItem({
      chart_id: "indexed",
      chart_type: "indexed_line",
      series_ids: ["series_a", "series_b"],
      transforms: [
        { series_id: "series_a", type: "index_base_100", base_period: "auto_common_start" },
        { series_id: "series_b", type: "index_base_100", base_period: "auto_common_start" },
      ],
    });
    const built = buildManagerChartSeriesPayload(item);
    expect(built.ok).toBe(true);
    expect(built.payload.series.length).toBe(2);
    expect(built.payload.series[0].transform_applied).toBe("index_base_100");
    expect(built.payload.series[0].values[0].y).toBe(100);
    expect(built.payload.series[1].values[0].y).toBe(100);
  });

  it("spread_line computes spread from two series", () => {
    const item = resolvedItem({
      chart_id: "spread",
      chart_type: "spread_line",
      series_ids: ["series_a", "series_b"],
      transforms: [{ series_id: "series_a", type: "spread", other_series_id: "series_b" }],
    });
    const built = buildManagerChartSeriesPayload(item);
    expect(built.ok).toBe(true);
    expect(built.payload.series.length).toBe(1);
    expect(built.payload.series[0].transform_applied).toBe("spread");
    expect(built.payload.series[0].values.find((r) => r.x === "2023")?.y).toBe(-100);
  });

  it("ratio_line computes ratio from two series", () => {
    const item = resolvedItem({
      chart_id: "ratio",
      chart_type: "ratio_line",
      series_ids: ["series_a", "series_b"],
      transforms: [{ series_id: "series_a", type: "ratio", other_series_id: "series_b" }],
    });
    const built = buildManagerChartSeriesPayload(item);
    expect(built.ok).toBe(true);
    expect(built.payload.series[0].values.find((r) => r.x === "2024")?.y).toBeCloseTo(120 / 220);
  });

  it("transform fails when denominator is zero", () => {
    const rowsB = [
      { x: "2022", y: 0 },
      { x: "2023", y: 10 },
    ];
    const result = applyRatio(SERIES_A.rows, rowsB);
    expect(result.ok).toBe(false);
    expect(result.reason).toBe("zero_denominator");
  });

  it("transform fails when base period missing", () => {
    const result = applyIndexBase100([[{ x: "2020", y: 1 }], [{ x: "2021", y: 2 }]]);
    expect(result.ok).toBe(false);
    expect(result.reason).toBe("missing_base_period");
  });

  it("multi-series chart with one render series is rejected in partition", () => {
    const { valid, invalid } = partitionManagerRecommendedCharts(
      [
        {
          chart_id: "indexed_fail",
          chart_type: "indexed_line",
          series_ids: ["series_a"],
        },
      ],
      [SERIES_A]
    );
    expect(valid.length).toBe(0);
    expect(invalid.length).toBe(1);
  });

  it("rejects multi-series chart when required series matched only by fuzzy title", () => {
    const fuzzyOnly = [
      {
        name: "Something wages related long",
        setId: "other_a",
        seriesId: "other_a",
        labelCs: "Something wages related long label",
        rows: SERIES_A.rows,
      },
      {
        name: "Something gdp related long",
        setId: "other_b",
        seriesId: "other_b",
        labelCs: "Something gdp related long label",
        rows: SERIES_B.rows,
      },
    ];
    const item = resolvedItem(
      {
        chart_id: "fuzzy_dual",
        chart_type: "dual_axis_line",
        series_ids: ["wages", "gdp"],
        transforms: [
          { series_id: "wages", type: "none", axis: "primary" },
          { series_id: "gdp", type: "none", axis: "secondary" },
        ],
      },
      fuzzyOnly
    );
    const confidence = validateRequiredSeriesMatchConfidence(item.seriesMatches, item.spec);
    expect(confidence.ok).toBe(false);
    const { valid } = partitionManagerRecommendedCharts(
      [item.spec],
      fuzzyOnly
    );
    expect(valid.length).toBe(0);
  });

  it("chart_payload_set_id match has high confidence", () => {
    const match = matchSeriesRefToChartSeries(
      { chart_payload_set_id: "payload_set_a", series_id: "unknown" },
      "unknown",
      [SERIES_A]
    );
    expect(match.confidence).toBe("high");
    expect(match.method).toBe("chart_payload_set_id");
  });

  it("signal + segment_id + geo + freq composite match works", () => {
    const match = matchSeriesRefToChartSeries(
      {
        series_id: "missing",
        signal: "wages",
        segment_id: "macro_economy",
        geo: "CZ",
        freq: "A",
      },
      "missing",
      [SERIES_A]
    );
    expect(match.confidence).toBe("high");
    expect(match.method).toBe("signal_segment_geo_freq");
  });

  it("dual_axis without right axis series fails render validation", () => {
    const item = resolvedItem({
      chart_id: "dual_bad",
      chart_type: "dual_axis_line",
      series_ids: ["series_a", "series_b"],
      transforms: [
        { series_id: "series_a", type: "none", axis: "primary" },
        { series_id: "series_b", type: "none", axis: "primary" },
      ],
    });
    const built = buildManagerChartSeriesPayload(item);
    expect(built.ok).toBe(false);
    expect(built.reason).toBe("unsupported_dual_axis_renderer");
  });

  it("uses chart_payload fallback when all manager charts fail", () => {
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
      [SERIES_A]
    );
    expect(plan.mode).toBe("fallback");
    expect(plan.useFallbackChartPayload).toBe(true);
  });

  it("partition attaches renderPayload for valid multi-series charts", () => {
    const { valid } = partitionManagerRecommendedCharts(
      [
        {
          chart_id: "indexed_ok",
          chart_type: "indexed_line",
          series_ids: ["series_a", "series_b"],
          transforms: [
            { series_id: "series_a", type: "index_base_100" },
            { series_id: "series_b", type: "index_base_100" },
          ],
        },
      ],
      [SERIES_A, SERIES_B]
    );
    expect(valid.length).toBe(1);
    expect(valid[0].renderPayload?.series?.length).toBe(2);
  });
});

describe("manager chart alignment", () => {
  it("alignment_plan index_base_100 applies transform to all required series", () => {
    const item = resolvedItem({
      chart_id: "aligned_index",
      chart_type: "indexed_line",
      series_ids: ["series_a", "series_b"],
      alignment_plan: {
        safety: { ok: true, confidence: "high", warnings: [] },
        alignment_strategy: "index_base_100",
        manager_explanation: "Řady jsou převedeny na společný index 100.",
        required_transforms: [
          { series_id: "series_a", type: "index_base_100" },
          { series_id: "series_b", type: "index_base_100" },
        ],
      },
      transforms: [
        { series_id: "series_a", type: "index_base_100" },
        { series_id: "series_b", type: "index_base_100" },
      ],
    });
    const built = buildManagerChartSeriesPayload(item);
    expect(built.ok).toBe(true);
    expect(built.payload.series.every((s) => s.transform_applied === "index_base_100")).toBe(true);
    expect(built.payload.data_quality_notes.some((n) => n.includes("index"))).toBe(true);
  });

  it("unsupported transform invalidates chart", () => {
    const item = resolvedItem({
      chart_id: "bad_transform",
      chart_type: "indexed_line",
      series_ids: ["series_a", "series_b"],
      alignment_plan: {
        safety: { ok: true },
        required_transforms: [{ series_id: "series_a", type: "custom_backend_only" }],
      },
      transforms: [{ series_id: "series_a", type: "custom_backend_only" }],
    });
    const built = buildManagerChartSeriesPayload(item);
    expect(built.ok).toBe(false);
    expect(built.reason).toBe("unsupported_alignment_transform");
  });

  it("safety.ok false invalidates chart", () => {
    const item = resolvedItem({
      chart_id: "rejected",
      chart_type: "indexed_line",
      series_ids: ["series_a", "series_b"],
      alignment_plan: {
        safety: { ok: false, reject_reason: "unit_alignment_rejected" },
        alignment_strategy: "reject",
      },
    });
    const built = buildManagerChartSeriesPayload(item);
    expect(built.ok).toBe(false);
    expect(built.reason).toBe("alignment_rejected");
  });

  it("frequency resample monthly to quarterly works", () => {
    const monthly = {
      ...SERIES_A,
      seriesId: "energy_m",
      freq: "M",
      rows: [
        { x: "2024-01", y: 10 },
        { x: "2024-02", y: 20 },
        { x: "2024-03", y: 30 },
        { x: "2024-04", y: 40 },
        { x: "2024-05", y: 50 },
        { x: "2024-06", y: 60 },
      ],
    };
    const quarterly = {
      ...SERIES_B,
      seriesId: "output_q",
      freq: "Q",
      unit: "index",
      rows: [
        { x: "2024-Q1", y: 100 },
        { x: "2024-Q2", y: 110 },
      ],
    };
    const item = resolveManagerRecommendedChartItems(
      [
        {
          chart_id: "resample",
          chart_type: "indexed_line",
          series_ids: ["energy_m", "output_q"],
          alignment_plan: {
            safety: { ok: true },
            alignment_strategy: "index_base_100",
            manager_explanation: "Měsíční data byla agregována na kvartály.",
            required_transforms: [
              { series_id: "energy_m", type: "resample_to_common_frequency", target_frequency: "Q", method: "mean" },
              { series_id: "energy_m", type: "index_base_100" },
              { series_id: "output_q", type: "index_base_100" },
            ],
          },
          transforms: [
            { series_id: "energy_m", type: "resample_to_common_frequency", target_frequency: "Q", method: "mean" },
            { series_id: "energy_m", type: "index_base_100" },
            { series_id: "output_q", type: "index_base_100" },
          ],
        },
      ],
      [monthly, quarterly]
    )[0];
    const built = buildManagerChartSeriesPayload(item);
    expect(built.ok).toBe(true);
    expect(built.payload.data_quality_notes.some((n) => n.includes("kvartál") || n.includes("Q"))).toBe(true);
  });

  it("partition rejects chart when alignment fails", () => {
    const { valid, invalid } = partitionManagerRecommendedCharts(
      [
        {
          chart_id: "align_fail",
          chart_type: "indexed_line",
          series_ids: ["series_a", "series_b"],
          alignment_plan: {
            safety: { ok: false, reject_reason: "unit_alignment_rejected" },
            alignment_strategy: "reject",
          },
        },
      ],
      [SERIES_A, SERIES_B]
    );
    expect(valid.length).toBe(0);
    expect(invalid.length).toBe(1);
    expect(invalid[0].validation.reason).toBe("alignment_rejected");
  });

  it("payload includes alignment explanation notes", () => {
    const item = resolvedItem({
      chart_id: "notes",
      chart_type: "dual_axis_line",
      series_ids: ["series_a", "series_b"],
      alignment_plan: {
        safety: { ok: true },
        alignment_strategy: "dual_axis",
        manager_explanation: "Graf používá dvě osy kvůli rozdílným jednotkám.",
        unit_alignment: { method: "dual_axis" },
        required_transforms: [
          { series_id: "series_a", type: "none", axis: "primary" },
          { series_id: "series_b", type: "none", axis: "secondary" },
        ],
      },
      transforms: [
        { series_id: "series_a", type: "none", axis: "primary" },
        { series_id: "series_b", type: "none", axis: "secondary" },
      ],
    });
    const built = buildManagerChartSeriesPayload(item);
    expect(built.ok).toBe(true);
    expect(built.payload.dual_axis).toBe(true);
    expect(built.payload.data_quality_notes).toContain("Graf používá dvě osy kvůli rozdílným jednotkám.");
  });

  it("forecast alignment note is included", () => {
    const item = resolvedItem({
      chart_id: "fc",
      chart_type: "line",
      series_ids: ["series_a"],
      alignment_plan: {
        safety: { ok: true },
        forecast_split: { needed: true, forecast_from: "2026" },
        manager_explanation: "Scénář.",
      },
    });
    const built = buildManagerChartSeriesPayload({
      ...item,
      seriesMatches: item.seriesMatches.filter((m) => m.series_id === "series_a"),
    });
    expect(built.payload?.data_quality_notes?.some((n) => n.includes("2026"))).toBe(true);
  });
});

describe("spread transform direct", () => {
  it("applySpread returns expected values", () => {
    const result = applySpread(SERIES_A.rows, SERIES_B.rows);
    expect(result.ok).toBe(true);
    expect(result.transformed_series[0].length).toBeGreaterThanOrEqual(2);
  });
});
