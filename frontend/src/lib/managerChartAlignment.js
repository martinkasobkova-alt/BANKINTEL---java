import { compareChartPeriods } from "@/lib/exploreChartCompare";

export const FRONTEND_SUPPORTED_ALIGNMENT_TRANSFORMS = new Set([
  "none",
  "index_base_100",
  "yoy_change",
  "period_change",
  "rolling_average",
  "spread",
  "ratio",
  "percent_gap",
  "resample_to_common_frequency",
  "lagged_comparison",
]);

function validRows(rows) {
  return (Array.isArray(rows) ? rows : [])
    .filter((row) => row && row.x != null && row.x !== "" && Number.isFinite(Number(row.y)))
    .map((row) => ({ x: String(row.x).trim(), y: Number(row.y) }));
}

function parsePeriodKey(x) {
  const s = String(x || "").trim();
  const mq = s.match(/^(\d{4})-(\d{2})$/);
  if (mq) return { kind: "M", year: Number(mq[1]), month: Number(mq[2]), key: s };
  const qq = s.match(/^(\d{4})-Q(\d)$/i);
  if (qq) return { kind: "Q", year: Number(qq[1]), quarter: Number(qq[2]), key: s };
  const ya = s.match(/^(\d{4})$/);
  if (ya) return { kind: "A", year: Number(ya[1]), key: s };
  return { kind: "unknown", key: s };
}

function monthToQuarter(year, month) {
  return `${year}-Q${Math.ceil(month / 3)}`;
}

export function resampleRows(rows, { targetFrequency = "Q", method = "mean" } = {}) {
  const cleaned = validRows(rows);
  if (!cleaned.length) return { ok: false, transformed_series: [], reason: "insufficient_points" };

  if (targetFrequency !== "Q") {
    return { ok: false, transformed_series: [], reason: "unsupported_resample_target" };
  }

  const buckets = new Map();
  for (const row of cleaned) {
    const parsed = parsePeriodKey(row.x);
    let bucketKey = row.x;
    if (parsed.kind === "M") {
      bucketKey = monthToQuarter(parsed.year, parsed.month);
    } else if (parsed.kind === "Q") {
      bucketKey = parsed.key;
    } else if (parsed.kind === "A") {
      bucketKey = parsed.key;
    }
    if (!buckets.has(bucketKey)) buckets.set(bucketKey, []);
    buckets.get(bucketKey).push(row.y);
  }

  const out = [];
  for (const [x, values] of [...buckets.entries()].sort((a, b) => compareChartPeriods(a[0], b[0]))) {
    if (!values.length) continue;
    let y;
    if (method === "sum") y = values.reduce((s, v) => s + v, 0);
    else if (method === "last") y = values[values.length - 1];
    else y = values.reduce((s, v) => s + v, 0) / values.length;
    out.push({ x, y });
  }

  if (out.length < 2) return { ok: false, transformed_series: [], reason: "insufficient_points_after_resample" };
  return { ok: true, transformed_series: [out], reason: "ok", data_quality_notes: [`Agregace na ${targetFrequency} (${method})`] };
}

export function applyLaggedComparison(primaryRows, secondaryRows, lagPeriods = 2) {
  const a = validRows(primaryRows).sort((x, y) => compareChartPeriods(x.x, y.x));
  const b = validRows(secondaryRows).sort((x, y) => compareChartPeriods(x.x, y.x));
  const lag = Math.max(0, Number(lagPeriods) || 0);
  if (a.length < 2 || b.length <= lag) {
    return { ok: false, transformed_series: [], reason: "insufficient_points_for_lag" };
  }
  const laggedB = b.slice(lag);
  const trimmedA = a.slice(0, laggedB.length);
  const paired = [];
  for (let i = 0; i < Math.min(trimmedA.length, laggedB.length); i += 1) {
    paired.push({ x: trimmedA[i].x, y: trimmedA[i].y, y2: laggedB[i].y });
  }
  if (paired.length < 2) return { ok: false, transformed_series: [], reason: "insufficient_points_for_lag" };
  return {
    ok: true,
    transformed_series: [paired.map((p) => ({ x: p.x, y: p.y })), paired.map((p) => ({ x: p.x, y: p.y2 }))],
    reason: "ok",
    data_quality_notes: [`Zpoždění ${lag} období`],
  };
}

export function validateAlignmentPlanForRender(alignmentPlan) {
  const plan = alignmentPlan && typeof alignmentPlan === "object" ? alignmentPlan : null;
  if (!plan) {
    return { ok: true, reason: "no_alignment_plan" };
  }
  const safety = plan.safety || {};
  if (safety.ok === false) {
    return {
      ok: false,
      reason: "alignment_rejected",
      reject_reason: safety.reject_reason || "alignment_rejected",
      alignment_plan: plan,
    };
  }
  const transforms = Array.isArray(plan.required_transforms) ? plan.required_transforms : [];
  for (const t of transforms) {
    const type = String(t?.type || t?.transform || "none").toLowerCase();
    if (!FRONTEND_SUPPORTED_ALIGNMENT_TRANSFORMS.has(type)) {
      return {
        ok: false,
        reason: "unsupported_alignment_transform",
        transform: type,
        alignment_plan: plan,
      };
    }
  }
  if (plan.alignment_strategy === "reject") {
    return { ok: false, reason: "alignment_rejected", alignment_plan: plan };
  }
  return { ok: true, reason: "ok", alignment_plan: plan };
}

export function buildAlignmentChartNotes(alignmentPlan, dataQualityNotes = []) {
  const notes = [...(Array.isArray(dataQualityNotes) ? dataQualityNotes : [])];
  const plan = alignmentPlan || {};
  if (plan.manager_explanation && !notes.includes(plan.manager_explanation)) {
    notes.unshift(plan.manager_explanation);
  }
  const fs = plan.forecast_split || {};
  if (fs.needed && fs.forecast_from && !notes.some((n) => String(n).includes("prognóz"))) {
    notes.push(`Od období ${fs.forecast_from} jde o prognózu.`);
  }
  return notes.filter(Boolean);
}

export function applyAlignmentPreTransforms(rows, transformSpec) {
  const type = String(transformSpec?.type || transformSpec?.transform || "").toLowerCase();
  if (type === "resample_to_common_frequency") {
    return resampleRows(rows, {
      targetFrequency: transformSpec.target_frequency || "Q",
      method: transformSpec.method || "mean",
    });
  }
  return { ok: true, transformed_series: [validRows(rows)], reason: "ok", data_quality_notes: [] };
}
