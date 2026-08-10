import { chartTypeRequiresMultipleRenderSeries } from "@/lib/managerChartTypes";
import {
  applyAlignmentPreTransforms,
  applyLaggedComparison,
  buildAlignmentChartNotes,
  validateAlignmentPlanForRender,
} from "@/lib/managerChartAlignment";
import {
  applyIndexBase100,
  applyRollingAverage,
  applySeriesTransform,
  applySpread,
  applyRatio,
  applyTransformNone,
  normalizeTransformType,
  normalizeTransformWindow,
  SUPPORTED_TRANSFORM_TYPES,
} from "@/lib/managerChartTransforms";

function seriesRefForId(spec, seriesId) {
  const refs = Array.isArray(spec?.series_refs) ? spec.series_refs : [];
  const hit = refs.find((ref) => String(ref?.series_id || "") === String(seriesId));
  if (hit) return hit;
  return { series_id: seriesId, label: seriesId };
}

function buildSeriesEntry(match, values, transformApplied, axis = "left", ref = {}) {
  const chartSeries = match?.series || {};
  const seriesRef = ref?.series_id ? ref : seriesRefForId({}, match?.series_id);
  return {
    series_id: String(match?.series_id || seriesRef.series_id || ""),
    label: String(seriesRef.label || chartSeries.labelCs || chartSeries.name || match?.series_id || "Řada"),
    values,
    unit: String(seriesRef.unit || chartSeries.unit || ""),
    axis: axis === "right" || axis === "secondary" ? "right" : "left",
    transform_applied: transformApplied,
    source: String(seriesRef.source || chartSeries.source || ""),
    geo: String(seriesRef.geo || chartSeries.geo || ""),
    freq: String(seriesRef.freq || chartSeries.freq || ""),
  };
}

function findOtherRows(requiredMatches, otherSeriesId) {
  const hit = requiredMatches.find((m) => String(m.series_id) === String(otherSeriesId));
  return hit?.series?.rows || [];
}

function normalizeAxis(transformSpec) {
  const axis = String(transformSpec?.axis || "primary").toLowerCase();
  return axis === "secondary" ? "right" : "left";
}

function prepareResampledMatches(spec, requiredMatches) {
  const transforms = Array.isArray(spec?.transforms) ? spec.transforms : [];
  const resampleBySeries = new Map();
  for (const transformSpec of transforms) {
    const type = normalizeTransformType(transformSpec);
    if (type === "resample_to_common_frequency") {
      resampleBySeries.set(String(transformSpec.series_id || ""), transformSpec);
    }
  }
  if (!resampleBySeries.size) {
    return { ok: true, matches: requiredMatches, data_quality_notes: [] };
  }

  const qualityNotes = [];
  const matches = [];
  for (const match of requiredMatches) {
    const resampleSpec = resampleBySeries.get(String(match.series_id || ""));
    if (!resampleSpec) {
      matches.push(match);
      continue;
    }
    const result = applyAlignmentPreTransforms(match.series?.rows || [], resampleSpec);
    if (!result.ok) {
      return { ok: false, reason: result.reason || "resample_failed", data_quality_notes: qualityNotes };
    }
    qualityNotes.push(...(result.data_quality_notes || []));
    matches.push({
      ...match,
      series: { ...(match.series || {}), rows: result.transformed_series[0] },
    });
  }
  return { ok: true, matches, data_quality_notes: qualityNotes };
}

function applyChartTransforms(spec, requiredMatches) {
  const prepared = prepareResampledMatches(spec, requiredMatches);
  if (!prepared.ok) {
    return { ok: false, reason: prepared.reason, data_quality_notes: prepared.data_quality_notes || [] };
  }
  const alignedMatches = prepared.matches;
  const qualityNotes = [...(prepared.data_quality_notes || [])];

  const chartType = String(spec?.chart_type || "").trim().toLowerCase();
  const transforms = Array.isArray(spec?.transforms) ? spec.transforms : [];
  const outputSeries = [];

  const lagTransform = transforms.find((t) => normalizeTransformType(t) === "lagged_comparison");
  if (lagTransform && alignedMatches.length >= 2) {
    const primary = alignedMatches.find((m) => String(m.series_id) === String(lagTransform.series_id)) || alignedMatches[0];
    const secondary = alignedMatches.find((m) => String(m.series_id) === String(lagTransform.other_series_id)) || alignedMatches[1];
    const lagResult = applyLaggedComparison(
      primary?.series?.rows || [],
      secondary?.series?.rows || [],
      lagTransform.lag_periods
    );
    if (!lagResult.ok) {
      return { ok: false, reason: lagResult.reason, data_quality_notes: qualityNotes };
    }
    qualityNotes.push(...(lagResult.data_quality_notes || []));
    outputSeries.push(
      buildSeriesEntry(primary, lagResult.transformed_series[0], "lagged_comparison", "left", seriesRefForId(spec, primary.series_id))
    );
    outputSeries.push(
      buildSeriesEntry(
        secondary,
        lagResult.transformed_series[1],
        "lagged_comparison",
        "right",
        seriesRefForId(spec, secondary.series_id)
      )
    );
    return { ok: true, series: outputSeries, data_quality_notes: qualityNotes };
  }

  if (chartType === "rolling_average_line") {
    const transformSpec = transforms[0] || {};
    const match = alignedMatches[0];
    const ref = seriesRefForId(spec, match?.series_id);
    const result = applyRollingAverage(match?.series?.rows || [], normalizeTransformWindow(transformSpec));
    if (!result.ok) return { ok: false, reason: result.reason, data_quality_notes: result.data_quality_notes };
    qualityNotes.push(...result.data_quality_notes);
    outputSeries.push(
      buildSeriesEntry(match, result.transformed_series[0], "rolling_average", "left", ref)
    );
    return { ok: true, series: outputSeries, data_quality_notes: qualityNotes };
  }

  if (chartType === "dual_axis_line") {
    for (const match of alignedMatches) {
      const transformSpec =
        transforms.find((t) => String(t.series_id) === String(match.series_id)) || {};
      const ref = seriesRefForId(spec, match.series_id);
      const result = applyTransformNone(match.series?.rows || []);
      if (!result.ok) {
        return { ok: false, reason: result.reason, data_quality_notes: result.data_quality_notes };
      }
      outputSeries.push(
        buildSeriesEntry(
          match,
          result.transformed_series[0],
          "none",
          normalizeAxis(transformSpec),
          ref
        )
      );
    }
    return { ok: true, series: outputSeries, data_quality_notes: qualityNotes };
  }

  if (chartType === "indexed_line" || chartType === "company_vs_sector") {
    const rowLists = alignedMatches.map((m) => m.series?.rows || []);
    const basePeriod =
      transforms.find((t) => normalizeTransformType(t) === "index_base_100")?.base_period || "auto_common_start";
    const result = applyIndexBase100(rowLists, { basePeriod });
    if (!result.ok) return { ok: false, reason: result.reason, data_quality_notes: result.data_quality_notes };
    qualityNotes.push(...result.data_quality_notes);
    result.transformed_series.forEach((values, idx) => {
      const ref = seriesRefForId(spec, alignedMatches[idx]?.series_id);
      outputSeries.push(
        buildSeriesEntry(alignedMatches[idx], values, "index_base_100", "left", ref)
      );
    });
    return { ok: true, series: outputSeries, data_quality_notes: qualityNotes };
  }

  const spreadTransform = transforms.find((t) => normalizeTransformType(t) === "spread");
  const ratioTransform = transforms.find((t) => normalizeTransformType(t) === "ratio");

  if (chartType === "spread_line" && spreadTransform) {
    const seriesId = spreadTransform.series_id;
    const otherId = spreadTransform.other_series_id;
    const primary = alignedMatches.find((m) => String(m.series_id) === String(seriesId));
    const otherRows = findOtherRows(alignedMatches, otherId);
    const result = applySpread(primary?.series?.rows || [], otherRows);
    if (!result.ok) return { ok: false, reason: result.reason, data_quality_notes: result.data_quality_notes };
    qualityNotes.push(...result.data_quality_notes);
    outputSeries.push(
      buildSeriesEntry(primary, result.transformed_series[0], "spread", "left", seriesRefForId(spec, seriesId))
    );
    return { ok: true, series: outputSeries, data_quality_notes: qualityNotes };
  }

  if (chartType === "ratio_line" && ratioTransform) {
    const seriesId = ratioTransform.series_id;
    const otherId = ratioTransform.other_series_id;
    const primary = alignedMatches.find((m) => String(m.series_id) === String(seriesId));
    const otherRows = findOtherRows(alignedMatches, otherId);
    const result = applyRatio(primary?.series?.rows || [], otherRows);
    if (!result.ok) return { ok: false, reason: result.reason, data_quality_notes: result.data_quality_notes };
    qualityNotes.push(...result.data_quality_notes);
    outputSeries.push(
      buildSeriesEntry(primary, result.transformed_series[0], "ratio", "left", seriesRefForId(spec, seriesId))
    );
    return { ok: true, series: outputSeries, data_quality_notes: qualityNotes };
  }

  if (transforms.length) {
    for (const transformSpec of transforms) {
      const type = normalizeTransformType(transformSpec);
      if (type === "resample_to_common_frequency" || type === "lagged_comparison") {
        continue;
      }
      if (!SUPPORTED_TRANSFORM_TYPES.has(type)) {
        return { ok: false, reason: "unsupported_transform", data_quality_notes: [] };
      }
      const seriesId = transformSpec.series_id;
      const match = alignedMatches.find((m) => String(m.series_id) === String(seriesId));
      if (!match) continue;

      let result;
      if (type === "spread") {
        result = applySpread(match.series?.rows || [], findOtherRows(alignedMatches, transformSpec.other_series_id));
      } else if (type === "ratio") {
        result = applyRatio(match.series?.rows || [], findOtherRows(alignedMatches, transformSpec.other_series_id));
      } else if (type === "rolling_average") {
        result = applyRollingAverage(match.series?.rows || [], normalizeTransformWindow(transformSpec));
      } else if (type === "index_base_100") {
        result = applyIndexBase100([match.series?.rows || []], { basePeriod: transformSpec.base_period });
        if (result.ok) result.transformed_series = result.transformed_series[0];
      } else {
        const ref = seriesRefForId(spec, seriesId);
        result = applySeriesTransform(type, match.series?.rows || [], {
          basePeriod: transformSpec.base_period,
          window: normalizeTransformWindow(transformSpec),
          freq: ref.freq,
        });
        if (result.ok && type !== "none") result.transformed_series = result.transformed_series[0];
      }

      if (!result.ok) return { ok: false, reason: result.reason, data_quality_notes: result.data_quality_notes };
      qualityNotes.push(...(result.data_quality_notes || []));
      const values = Array.isArray(result.transformed_series[0]) ? result.transformed_series[0] : result.transformed_series;
      outputSeries.push(
        buildSeriesEntry(
          match,
          values,
          type,
          normalizeAxis(transformSpec),
          seriesRefForId(spec, seriesId)
        )
      );
    }
  }

  if (!outputSeries.length) {
    for (const match of alignedMatches) {
      const ref = seriesRefForId(spec, match.series_id);
      const result = applyTransformNone(match.series?.rows || []);
      if (!result.ok) return { ok: false, reason: result.reason, data_quality_notes: result.data_quality_notes };
      outputSeries.push(buildSeriesEntry(match, result.transformed_series[0], "none", "left", ref));
    }
  }

  return { ok: true, series: outputSeries, data_quality_notes: qualityNotes };
}

export function validateManagerRenderPayload(payload, spec) {
  const chartType = String(payload?.chart_type || spec?.chart_type || "").trim().toLowerCase();
  const series = Array.isArray(payload?.series) ? payload.series : [];
  const requiresMultiple = chartTypeRequiresMultipleRenderSeries(chartType, spec);

  if (requiresMultiple && chartType !== "spread_line" && chartType !== "ratio_line" && series.length < 2) {
    return { ok: false, reason: "insufficient_render_series" };
  }

  if (chartType === "dual_axis_line") {
    const left = series.filter((s) => s.axis !== "right");
    const right = series.filter((s) => s.axis === "right");
    if (left.length < 1 || right.length < 1) {
      return { ok: false, reason: "unsupported_dual_axis_renderer" };
    }
    const units = new Set(series.map((s) => String(s.unit || "").trim()).filter(Boolean));
    if (units.size > 1 && right.length === 0) {
      return { ok: false, reason: "unsupported_dual_axis_renderer" };
    }
  }

  for (const row of series) {
    if (!Array.isArray(row.values) || row.values.length < 2) {
      return { ok: false, reason: "insufficient_render_points" };
    }
  }

  return { ok: true, reason: "ok" };
}

/**
 * Z validovaného recommended chartu sestaví renderovatelný multi-series payload.
 */
export function buildManagerChartSeriesPayload(resolvedChart) {
  const spec = resolvedChart?.spec || {};
  const chartType = String(spec.chart_type || "").trim().toLowerCase();

  if (resolvedChart?.isScoreBreakdown) {
    return { ok: false, reason: "score_breakdown_not_chart_payload" };
  }

  const requiredMatches = (resolvedChart?.seriesMatches || []).filter((m) => m.required !== false);
  if (!requiredMatches.length) {
    return { ok: false, reason: "no_matched_series" };
  }

  const alignmentValidation = validateAlignmentPlanForRender(spec.alignment_plan);
  if (!alignmentValidation.ok) {
    return {
      ok: false,
      reason: alignmentValidation.reason,
      alignment_plan: alignmentValidation.alignment_plan,
      data_quality_notes: buildAlignmentChartNotes(spec.alignment_plan, spec.data_quality_notes),
    };
  }

  const transformResult = applyChartTransforms(spec, requiredMatches);
  if (!transformResult.ok) {
    return {
      ok: false,
      reason: transformResult.reason,
      data_quality_notes: transformResult.data_quality_notes || [],
    };
  }

  const xValues = transformResult.series?.[0]?.values?.map((row) => row.x) || [];
  const alignmentNotes = buildAlignmentChartNotes(spec.alignment_plan, spec.data_quality_notes);
  const payload = {
    chart_id: String(spec.chart_id || ""),
    chart_type: chartType,
    title: String(spec.title || spec.chart_id || ""),
    purpose: String(spec.purpose || ""),
    manager_message: String(spec.manager_message || ""),
    series: transformResult.series,
    x_values: xValues,
    annotations: Array.isArray(spec.annotations) ? spec.annotations : [],
    data_quality_notes: [
      ...alignmentNotes,
      ...(transformResult.data_quality_notes || []),
    ],
    alignment_plan: spec.alignment_plan || null,
    relationship_id: spec.relationship_id || null,
    dual_axis:
      chartType === "dual_axis_line" ||
      spec.alignment_plan?.alignment_strategy === "dual_axis" ||
      spec.alignment_plan?.unit_alignment?.method === "dual_axis",
  };

  const renderValidation = validateManagerRenderPayload(payload, spec);
  if (!renderValidation.ok) {
    return { ok: false, reason: renderValidation.reason, payload };
  }

  return { ok: true, payload, reason: "ok" };
}

export { buildSeriesEntry, seriesRefForId };
