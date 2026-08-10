import fs from "node:fs";
import path from "node:path";

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  args.set(process.argv[index], process.argv[index + 1]);
}

const inputDir = path.resolve(args.get("--input") ?? "outputs/manager-explorer-final-release-20260804/stability-100");
const outputPath = path.resolve(args.get("--output") ?? path.join(inputDir, "policy-simulation.json"));

function percentile(values, quantile) {
  if (!values.length) return null;
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil(quantile * sorted.length) - 1))];
}

function mean(values) {
  return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null;
}

function canonicalSource(value) {
  return String(value ?? "").trim().toLowerCase();
}

function identity(source, setId) {
  return `${canonicalSource(source)}|${String(setId ?? "").trim().toLowerCase()}`;
}

function wilsonUpper(successes, samples, z = 1.96) {
  if (!samples) return 1;
  const probability = successes / samples;
  const denominator = 1 + (z * z) / samples;
  const center = probability + (z * z) / (2 * samples);
  const margin = z * Math.sqrt((probability * (1 - probability)) / samples + (z * z) / (4 * samples * samples));
  return (center + margin) / denominator;
}

function sourceStatuses(run) {
  return Array.isArray(run.result?.observability?.source_statuses)
    ? run.result.observability.source_statuses
    : Array.isArray(run.result?.performance_profile?.source_statuses)
      ? run.result.performance_profile.source_statuses
      : [];
}

function previewItems(run) {
  return Array.isArray(run.result?.observability?.preview_items)
    ? run.result.observability.preview_items
    : Array.isArray(run.result?.performance_profile?.preview_items)
      ? run.result.performance_profile.preview_items
      : [];
}

function loadRuns(directory) {
  return fs.readdirSync(directory)
    .filter((file) => /^\d{3}-.*\.json$/.test(file))
    .sort()
    .map((file) => ({
      file,
      ...JSON.parse(fs.readFileSync(path.join(directory, file), "utf8")),
    }));
}

function buildSourceHistory(runs) {
  const history = new Map();
  for (const run of runs) {
    for (const status of sourceStatuses(run)) {
      const source = canonicalSource(status.source);
      if (!source) continue;
      const current = history.get(source) ?? { samples: 0, verifiedSuccesses: 0, outages: 0 };
      current.samples += 1;
      current.verifiedSuccesses += Number(status.verified_yield ?? 0) > 0 ? 1 : 0;
      current.outages += status.timeout || status.error_category ? 1 : 0;
      history.set(source, current);
    }
  }
  return history;
}

function simulateSourcePolicy(runs, history, policy) {
  const rows = [];
  let lowYieldSources = 0;
  for (const run of runs) {
    const statuses = sourceStatuses(run);
    const baselineWall = Number(run.result?.performance_profile?.retrieval_wall_ms ?? run.result?.observability?.retrieval_wall_ms ?? 0);
    let simulatedWall = 0;
    let retainedTerms = 0;
    let baselineTerms = 0;
    for (const status of statuses) {
      const source = canonicalSource(status.source);
      const sourceHistory = history.get(source) ?? { samples: 0, verifiedSuccesses: 0, outages: 0 };
      const upper = wilsonUpper(sourceHistory.verifiedSuccesses, sourceHistory.samples, policy.z);
      const lowYield = sourceHistory.samples >= policy.minimumSamples && upper < policy.successUpperThreshold;
      if (lowYield) lowYieldSources += 1;
      const terms = Array.isArray(status.term_timings) ? status.term_timings : [];
      baselineTerms += terms.length;
      const retained = lowYield ? terms.slice(0, policy.explorationTerms) : terms;
      retainedTerms += retained.length;
      const sourceDuration = retained.length
        ? Math.max(...retained.map((term) => Number(term.duration_ms ?? 0)))
        : Number(status.active_work_ms ?? status.duration_ms ?? 0);
      simulatedWall = Math.max(simulatedWall, sourceDuration);
    }
    rows.push({
      baselineWall,
      simulatedWall,
      savedMs: Math.max(0, baselineWall - simulatedWall),
      baselineTerms,
      retainedTerms,
    });
  }

  const baseline = rows.map((row) => row.baselineWall);
  const simulated = rows.map((row) => row.simulatedWall);
  const baselineTerms = rows.reduce((sum, row) => sum + row.baselineTerms, 0);
  const retainedTerms = rows.reduce((sum, row) => sum + row.retainedTerms, 0);
  return {
    policy,
    run_count: rows.length,
    low_yield_source_decisions: lowYieldSources,
    latency: {
      baseline_p50_ms: percentile(baseline, 0.5),
      baseline_p95_ms: percentile(baseline, 0.95),
      simulated_p50_ms: percentile(simulated, 0.5),
      simulated_p95_ms: percentile(simulated, 0.95),
      mean_gain_ms: Math.round(mean(rows.map((row) => row.savedMs)) ?? 0),
    },
    retrieval_term_reduction: baselineTerms
      ? 1 - retainedTerms / baselineTerms
      : 0,
    quality: {
      top1_delta: "not_identifiable",
      recall_at_5_delta: "not_identifiable",
      mrr_delta: "not_identifiable",
      source_diversity_delta: "not_identifiable",
      reason: "Trace events do not link every per-term candidate to the final judged ranking.",
    },
    guardrails_passed: false,
    deployment_decision: "observe_only",
  };
}

function prefixAtVerifiedTarget(items, target, minimumSources) {
  const sources = new Set();
  let verified = 0;
  for (let index = 0; index < items.length; index += 1) {
    const item = items[index];
    if (item.verified === true || item.terminal_status === "success") {
      verified += 1;
      sources.add(canonicalSource(item.source));
    }
    if (verified >= target && sources.size >= minimumSources) return index + 1;
  }
  return items.length;
}

function simulatePreviewPolicy(runs, policy) {
  const rows = [];
  for (const run of runs) {
    const items = previewItems(run);
    const prefixLength = prefixAtVerifiedTarget(items, policy.verifiedTarget, policy.minimumSources);
    const retained = items.slice(0, prefixLength);
    const retainedIds = new Set(retained.map((item) => identity(item.source, item.set_id)));
    const allIds = new Set(items.map((item) => identity(item.source, item.set_id)));
    const top1Id = identity(run.result?.top1?.source, run.result?.top1?.set_id);
    const top1Traceable = allIds.has(top1Id);
    const top1Preserved = top1Traceable ? retainedIds.has(top1Id) : null;
    const relevantTop5 = (run.result?.rows ?? [])
      .slice(0, 5)
      .filter((row) => Number(row.relevance_grade ?? 0) > 0)
      .map((row) => identity(row.source, row.set_id));
    const recallTraceable = relevantTop5.every((id) => allIds.has(id));
    const recallPreserved = recallTraceable ? relevantTop5.every((id) => retainedIds.has(id)) : null;
    const elapsed = (item) => Number(item.queue_wait_ms ?? 0) + Number(item.fetch_ms ?? 0)
      + Number(item.parse_ms ?? 0) + Number(item.validation_ms ?? 0);
    const baselineElapsed = items.length ? Math.max(...items.map(elapsed)) : 0;
    const retainedElapsed = retained.length ? Math.max(...retained.map(elapsed)) : 0;
    const baselineRemote = items.filter((item) => item.fetch_type === "remote").length;
    const retainedRemote = retained.filter((item) => item.fetch_type === "remote").length;
    rows.push({
      early: prefixLength < items.length,
      savedMs: Math.max(0, baselineElapsed - retainedElapsed),
      baselineRemote,
      retainedRemote,
      top1Traceable,
      top1Preserved,
      recallTraceable,
      recallPreserved,
    });
  }

  const top1Known = rows.filter((row) => row.top1Traceable);
  const recallKnown = rows.filter((row) => row.recallTraceable);
  const remoteBaseline = rows.reduce((sum, row) => sum + row.baselineRemote, 0);
  const remoteRetained = rows.reduce((sum, row) => sum + row.retainedRemote, 0);
  const lateTop1Changes = top1Known.filter((row) => !row.top1Preserved).length;
  const lateRecallChanges = recallKnown.filter((row) => !row.recallPreserved).length;
  const completeQualityCoverage = top1Known.length === rows.length && recallKnown.length === rows.length;
  return {
    policy,
    run_count: rows.length,
    early_completed_runs: rows.filter((row) => row.early).length,
    mean_wall_gain_ms: Math.round(mean(rows.map((row) => row.savedMs)) ?? 0),
    remote_fetch_reduction: remoteBaseline ? 1 - remoteRetained / remoteBaseline : 0,
    late_fetch_changed_top1: lateTop1Changes,
    late_fetch_changed_recall_at_5: lateRecallChanges,
    quality: {
      identifiable_top1_runs: top1Known.length,
      identifiable_recall_at_5_runs: recallKnown.length,
      top1_delta_on_identifiable_runs: top1Known.length ? -lateTop1Changes / top1Known.length : null,
      recall_at_5_delta_on_identifiable_runs: recallKnown.length ? -lateRecallChanges / recallKnown.length : null,
      mrr_delta: "not_identifiable",
      source_diversity_delta: "not_identifiable",
      complete_quality_coverage: completeQualityCoverage,
    },
    guardrails_passed: completeQualityCoverage && lateTop1Changes === 0 && lateRecallChanges === 0,
    deployment_decision: completeQualityCoverage && lateTop1Changes === 0 && lateRecallChanges === 0
      ? "eligible_for_review"
      : "do_not_deploy",
  };
}

const runs = loadRuns(inputDir);
const history = buildSourceHistory(runs);
const sourcePolicies = [
  { id: "yield-conservative", minimumSamples: 50, successUpperThreshold: 0.05, explorationTerms: 2, z: 2.576 },
  { id: "yield-balanced", minimumSamples: 30, successUpperThreshold: 0.10, explorationTerms: 2, z: 1.96 },
  { id: "yield-aggressive", minimumSamples: 20, successUpperThreshold: 0.20, explorationTerms: 1, z: 1.645 },
].map((policy) => simulateSourcePolicy(runs, history, policy));
const previewPolicies = [
  { id: "preview-target-3-diverse-2", verifiedTarget: 3, minimumSources: 2 },
  { id: "preview-target-5-diverse-2", verifiedTarget: 5, minimumSources: 2 },
  { id: "preview-target-5-diverse-3", verifiedTarget: 5, minimumSources: 3 },
].map((policy) => simulatePreviewPolicy(runs, policy));

const sourceHistory = Object.fromEntries([...history.entries()].map(([source, value]) => [source, {
  ...value,
  verified_success_rate: value.samples ? value.verifiedSuccesses / value.samples : 0,
  verified_success_wilson_upper_95: wilsonUpper(value.verifiedSuccesses, value.samples),
}]));

const report = {
  generated_at: new Date().toISOString(),
  input_directory: inputDir,
  run_count: runs.length,
  methodology: {
    source_yield: "Source identity is used only as a key for rolling history. Low-yield decisions require a minimum sample count and an upper confidence bound below the policy threshold. New sources retain the full budget; a single outage cannot classify a source as long-term low yield.",
    preview: "Policies stop only after a verified target and minimum source diversity. Latency uses the observed preview item elapsed-time prefix. Quality is reported only when final judged identities can be linked to preview items.",
    local_mirror_ordering: "not_simulated_without_semantic_equivalence_metadata",
  },
  source_history: sourceHistory,
  source_yield_policies: sourcePolicies,
  preview_early_completion_policies: previewPolicies,
  deployment: {
    source_yield: "observe_only",
    preview_early_completion: previewPolicies.some((policy) => policy.guardrails_passed)
      ? "eligible_for_review_only"
      : "do_not_deploy",
    reason: "No policy has complete evidence for Top-1, Recall@5, MRR and source-diversity guardrails across all runs.",
  },
};

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify(report, null, 2));
