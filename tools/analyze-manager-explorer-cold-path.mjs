import fs from "node:fs";
import path from "node:path";

const options = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  options.set(process.argv[index], process.argv[index + 1]);
}

const baselineDir = path.resolve(options.get("--baseline") ?? "outputs/manager-explorer-cold-path/baseline-uncached");
const optimizedDir = path.resolve(options.get("--optimized") ?? "outputs/manager-explorer-cold-path/optimized-isolated");
const outputPath = path.resolve(options.get("--output") ?? "outputs/manager-explorer-cold-path/comparison.json");

const percentile = (values, p) => {
  if (!values.length) return 0;
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
};

const mean = (values) => values.length
  ? values.reduce((sum, value) => sum + value, 0) / values.length
  : 0;

const round = (value, digits = 2) => Number(Number(value ?? 0).toFixed(digits));
const number = (value) => Number.isFinite(Number(value)) ? Number(value) : 0;

function loadRun(directory) {
  const summary = JSON.parse(fs.readFileSync(path.join(directory, "summary.json"), "utf8"));
  const cases = new Map();
  for (const file of fs.readdirSync(directory).filter((name) => /^\d+-.*\.json$/.test(name)).sort()) {
    const document = JSON.parse(fs.readFileSync(path.join(directory, file), "utf8"));
    cases.set(document.result.id, document.result);
  }
  return { summary, cases };
}

const baseline = loadRun(baselineDir);
const optimized = loadRun(optimizedDir);

const phaseFields = [
  "query_understanding_ms",
  "planner_ms",
  "retrieval_wall_ms",
  "post_retrieval_ms",
  "ai_resolver_ms",
  "preview_initial_ms",
  "preview_retry_ms",
  "final_rank_initial_ms",
  "final_rank_retry_ms",
  "answer_ms",
  "total_ms",
];

function distribution(values) {
  return {
    mean: round(mean(values)),
    median: percentile(values, 50),
    p90: percentile(values, 90),
    p95: percentile(values, 95),
    max: Math.max(0, ...values),
  };
}

function summarizeRun(run) {
  const results = [...run.cases.values()];
  const phases = Object.fromEntries(phaseFields.map((field) => [
    field,
    distribution(results.map((item) => number(item.performance_profile?.[field] ?? item[field]))),
  ]));

  const laneInstances = results.flatMap((item) => (item.performance_profile?.source_statuses ?? []).map((lane) => ({
    query: item.id,
    source: lane.source,
    duration_ms: number(lane.duration_ms),
    queue_ms: number(lane.queue_wait_ms ?? lane.queue_ms),
    active_work_ms: number(lane.active_work_ms ?? lane.duration_ms),
    sidecar_ms: number(lane.sidecar_ms),
    retrieval_ms: number(lane.retrieval_ms),
    scoring_ms: number(lane.scoring_ms),
    count: number(lane.candidate_count ?? lane.count),
    unique_candidate_count: number(lane.unique_candidate_count ?? lane.count),
    verified_yield: number(lane.verified_yield),
    retrieved_before_cap: number(lane.retrieved_before_cap),
    terms_requested: number(lane.term_count ?? lane.terms_requested),
    terms_completed: number(lane.term_completed ?? lane.terms_completed),
    terms_cancelled: number(lane.term_cancelled),
    terms_budget_exhausted: number(lane.terms_budget_exhausted),
    remote_call_count: number(lane.remote_call_count),
    local_index_call_count: number(lane.local_index_call_count),
    terminal_status: lane.terminal_status ?? null,
    error_category: lane.error_category ?? null,
    mode: lane.mode ?? null,
    ok: lane.ok !== false,
  })));

  const lanesBySource = new Map();
  for (const lane of laneInstances) {
    const values = lanesBySource.get(lane.source) ?? [];
    values.push(lane);
    lanesBySource.set(lane.source, values);
  }

  const source_lanes = [...lanesBySource.entries()].map(([source, lanes]) => ({
    source,
    calls: lanes.length,
    duration_ms: distribution(lanes.map((lane) => lane.duration_ms)),
    queue_ms_mean: round(mean(lanes.map((lane) => lane.queue_ms))),
    active_work_ms_mean: round(mean(lanes.map((lane) => lane.active_work_ms))),
    sidecar_ms_mean: round(mean(lanes.map((lane) => lane.sidecar_ms))),
    candidate_yield_mean: round(mean(lanes.map((lane) => lane.count))),
    unique_candidate_yield_mean: round(mean(lanes.map((lane) => lane.unique_candidate_count))),
    verified_yield_mean: round(mean(lanes.map((lane) => lane.verified_yield))),
    empty_rate: round(lanes.filter((lane) => lane.count === 0).length / lanes.length, 4),
    error_rate: round(lanes.filter((lane) => !lane.ok).length / lanes.length, 4),
    timeout_rate: round(lanes.filter((lane) => lane.terminal_status === "timeout").length / lanes.length, 4),
    cancelled_rate: round(lanes.filter((lane) => lane.terminal_status === "cancelled").length / lanes.length, 4),
    budget_exhausted_count: lanes.filter((lane) => lane.terminal_status === "budget_exhausted" || lane.terms_budget_exhausted > 0).length,
    terms_requested: lanes.reduce((sum, lane) => sum + lane.terms_requested, 0),
    terms_completed: lanes.reduce((sum, lane) => sum + lane.terms_completed, 0),
    terms_cancelled: lanes.reduce((sum, lane) => sum + lane.terms_cancelled, 0),
    remote_calls: lanes.reduce((sum, lane) => sum + lane.remote_call_count, 0),
    local_index_calls: lanes.reduce((sum, lane) => sum + lane.local_index_call_count, 0),
  })).sort((left, right) => right.duration_ms.mean - left.duration_ms.mean);

  const previewProfiles = results.map((item) => item.performance_profile?.preview_initial ?? {});
  const previewItems = previewProfiles.flatMap((profile) => profile.preview_items ?? []);
  const previewByFetchType = Object.fromEntries([...new Set(previewItems.map((item) => item.fetch_type ?? "unknown"))]
    .sort()
    .map((fetchType) => {
      const items = previewItems.filter((item) => (item.fetch_type ?? "unknown") === fetchType);
      return [fetchType, {
        count: items.length,
        fetch_ms: distribution(items.map((item) => number(item.fetch_ms))),
        queue_wait_ms: distribution(items.map((item) => number(item.queue_wait_ms))),
        success_rate: round(items.filter((item) => item.terminal_status === "success").length / items.length, 4),
        timeout_rate: round(items.filter((item) => String(item.terminal_status).includes("timeout")).length / items.length, 4),
      }];
    }));
  const latencies = results.map((item) => item.wall_ms);
  const cpu = results.map((item) => number(item.process_cpu_seconds));
  const memoryDeltas = results.map((item) => number(item.working_set_after) - number(item.working_set_before));
  const threadDeltas = results.map((item) => number(item.thread_count_after) - number(item.thread_count_before));

  return {
    query_count: results.length,
    cold_path_verified: results.every((item) => !item.cache_hit),
    latency_ms: distribution(latencies),
    relevance: run.summary.relevance,
    phases,
    source_lanes,
    preview: {
      phase_ms: distribution(previewProfiles.map((profile) => number(profile.preview_ms_total))),
      queue_wait_ms: distribution(previewItems.map((item) => number(item.queue_wait_ms))),
      fetch_ms: distribution(previewItems.map((item) => number(item.fetch_ms))),
      parse_ms: distribution(previewItems.map((item) => number(item.parse_ms))),
      validation_ms: distribution(previewItems.map((item) => number(item.validation_ms))),
      by_fetch_type: previewByFetchType,
    },
    top_slowest_lane_instances: [...laneInstances]
      .sort((left, right) => right.duration_ms - left.duration_ms)
      .slice(0, 10),
    slow_zero_yield_lanes: laneInstances
      .filter((lane) => lane.count === 0 && lane.duration_ms >= 5_000)
      .sort((left, right) => right.duration_ms - left.duration_ms)
      .slice(0, 10),
    calls: {
      source_lane_calls: laneInstances.length,
      index_term_requests: laneInstances.reduce((sum, lane) => sum + lane.terms_requested, 0),
      index_terms_completed: laneInstances.reduce((sum, lane) => sum + lane.terms_completed, 0),
      index_terms_cancelled: laneInstances.reduce((sum, lane) => sum + lane.terms_cancelled, 0),
      source_remote_calls: laneInstances.reduce((sum, lane) => sum + lane.remote_call_count, 0),
      source_local_index_calls: laneInstances.reduce((sum, lane) => sum + lane.local_index_call_count, 0),
      preview_remote_fetches: previewProfiles.reduce((sum, profile) => sum + number(profile.preview_remote_fetch_count), 0),
      preview_cache_hits: previewProfiles.reduce((sum, profile) => sum + number(profile.preview_cache_hit_count), 0),
      preview_successes: previewProfiles.reduce((sum, profile) => sum + number(profile.preview_success_count), 0),
      preview_timeouts: previewProfiles.reduce((sum, profile) => sum + number(profile.preview_timeout_count), 0),
      preview_unfinished: previewProfiles.reduce((sum, profile) => sum + number(profile.preview_unfinished_count), 0),
    },
    resources: {
      cpu_seconds: distribution(cpu),
      working_set_delta_bytes: distribution(memoryDeltas),
      thread_delta: distribution(threadDeltas),
      working_set_peak_after_bytes: Math.max(...results.map((item) => number(item.working_set_after))),
      thread_peak_after: Math.max(...results.map((item) => number(item.thread_count_after))),
    },
  };
}

function candidateIdentity(row) {
  return `${row.source ?? ""}|${row.set_id ?? ""}`;
}

const candidateComparisons = [];
for (const [id, before] of baseline.cases) {
  const after = optimized.cases.get(id);
  if (!after) continue;
  const beforeIds = before.rows.map(candidateIdentity);
  const afterIds = after.rows.map(candidateIdentity);
  const beforeUnique = new Set(beforeIds);
  const afterUnique = new Set(afterIds);
  const overlap = [...beforeUnique].filter((identity) => afterUnique.has(identity));
  const union = new Set([...beforeUnique, ...afterUnique]);
  candidateComparisons.push({
    id,
    before_count: beforeIds.length,
    after_count: afterIds.length,
    exact_overlap_count: overlap.length,
    jaccard: round(union.size ? overlap.length / union.size : 1, 4),
    top1_same: beforeIds[0] === afterIds[0],
    before_top3: beforeIds.slice(0, 3),
    after_top3: afterIds.slice(0, 3),
  });
}

const beforeSummary = summarizeRun(baseline);
const afterSummary = summarizeRun(optimized);
const improvement = (before, after) => round(before ? ((before - after) / before) * 100 : 0);

const comparison = {
  generated_at: new Date().toISOString(),
  inputs: { baseline: baselineDir, optimized: optimizedDir },
  baseline: beforeSummary,
  optimized: afterSummary,
  deltas: {
    median_latency_reduction_percent: improvement(beforeSummary.latency_ms.median, afterSummary.latency_ms.median),
    p90_latency_reduction_percent: improvement(beforeSummary.latency_ms.p90, afterSummary.latency_ms.p90),
    p95_latency_reduction_percent: improvement(beforeSummary.latency_ms.p95, afterSummary.latency_ms.p95),
    max_latency_reduction_percent: improvement(beforeSummary.latency_ms.max, afterSummary.latency_ms.max),
    top1_accuracy: round(number(afterSummary.relevance.top1_accuracy) - number(beforeSummary.relevance.top1_accuracy), 4),
    recall_at_3: round(number(afterSummary.relevance.recall_at_3) - number(beforeSummary.relevance.recall_at_3), 4),
    recall_at_5: round(number(afterSummary.relevance.recall_at_5) - number(beforeSummary.relevance.recall_at_5), 4),
    mrr: round(number(afterSummary.relevance.mrr) - number(beforeSummary.relevance.mrr), 4),
    source_diversity: round(number(afterSummary.relevance.average_source_diversity) - number(beforeSummary.relevance.average_source_diversity), 4),
    exact_top1_stability_rate: round(mean(candidateComparisons.map((item) => Number(item.top1_same))), 4),
    average_candidate_jaccard: round(mean(candidateComparisons.map((item) => item.jaccard)), 4),
  },
  candidate_comparisons: candidateComparisons,
  measurement_notes: [
    "preview_remote_fetches counts instrumented preview requests only; it is not an estimate of every upstream HTTP call",
    "index_term_requests counts requested FTS variants; completed counts variants that finished before a lane deadline",
    "manifest relevance regex can miss dimension-bearing datasets whose title omits geography; candidate identities are reported separately",
  ],
};

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, JSON.stringify(comparison, null, 2));
console.log(JSON.stringify({
  baseline_latency_ms: beforeSummary.latency_ms,
  optimized_latency_ms: afterSummary.latency_ms,
  deltas: comparison.deltas,
  baseline_relevance: beforeSummary.relevance,
  optimized_relevance: afterSummary.relevance,
  baseline_calls: beforeSummary.calls,
  optimized_calls: afterSummary.calls,
}, null, 2));
