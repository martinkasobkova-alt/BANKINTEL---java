import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const args = new Map();
for (let i = 2; i < process.argv.length; i += 2) {
  args.set(process.argv[i], process.argv[i + 1]);
}

const baseUrl = args.get("--base-url") ?? "http://127.0.0.1:8091";
const casesPath = path.resolve(args.get("--cases") ?? "evaluation/manager-explorer-cold-path-cases.json");
const outputDir = path.resolve(args.get("--output") ?? "outputs/manager-explorer-cold-path/baseline");
const backendPid = Number(args.get("--pid") ?? 0);
const repeatCount = Math.max(1, Number(args.get("--repeat") ?? 1));
const registryDir = path.resolve("backend-java/src/main/resources/search_v2");
const metricRegistry = JSON.parse(fs.readFileSync(path.join(registryDir, "metric_intent_registry.json"), "utf8"));
const sectorRegistry = JSON.parse(fs.readFileSync(path.join(registryDir, "institutional_sector_registry.json"), "utf8"));

function readCaseDocument(filePath) {
  const document = JSON.parse(fs.readFileSync(filePath, "utf8"));
  if (Array.isArray(document)) return document;
  const ownCases = Array.isArray(document.queries) ? document.queries : [];
  const includedCases = (document.includes ?? []).flatMap((includePath) =>
    readCaseDocument(path.resolve(path.dirname(filePath), includePath)));
  return [...ownCases, ...includedCases];
}

const baseCases = readCaseDocument(casesPath).map((caseDef, index) => ({
  ...caseDef,
  id: caseDef.id ?? caseDef.query_id ?? `case-${index + 1}`,
}));
const cases = Array.from({ length: repeatCount }, (_, repetition) => baseCases.map((caseDef) => ({
  ...caseDef,
  repetition: repetition + 1,
}))).flat();
fs.mkdirSync(outputDir, { recursive: true });

const fold = (value) => String(value ?? "")
  .normalize("NFD")
  .replace(/[\u0300-\u036f]/g, "")
  .toLowerCase();

const matches = (text, pattern) => new RegExp(pattern, "iu").test(text);
const normalizedSource = (value) => fold(value).replace(/[^a-z0-9]+/g, "");
const asArray = (value) => Array.isArray(value) ? value : value == null || value === "" ? [] : [value];
const canonicalValues = (value) => [...new Set(asArray(value).map((item) => fold(item).trim()).filter(Boolean))];

function registryIdsFor(values, entries) {
  const text = ` ${fold(asArray(values).join(" ")).replace(/[^a-z0-9]+/g, " ").trim()} `;
  if (text.trim() === "") return [];
  return entries
    .filter((entry) => [entry.id, ...(entry.aliases ?? [])].some((alias) => {
      const normalized = fold(alias).replace(/[^a-z0-9]+/g, " ").trim();
      return normalized && text.includes(` ${normalized} `);
    }))
    .map((entry) => fold(entry.id));
}

function resultRows(body) {
  const unique = new Map();
  for (const row of [...(body.sector_indicators ?? []), ...(body.macro_indicators ?? [])]) {
    const identity = [
      normalizedSource(row.canonical_source_id ?? row.source),
      fold(row.set_id ?? row.dataset_id ?? row.series_id ?? row.code ?? row.id),
      fold(row.title ?? row.indicator_name),
    ].join("|");
    if (!unique.has(identity)) unique.set(identity, row);
  }
  return [...unique.values()];
}

function rowText(row) {
  return fold([
    row.title,
    row.indicator_name,
    row.full_path,
    row.dataset_id,
    row.set_id,
    row.source,
    row.manager_category,
    row.sector_hint,
    JSON.stringify(row.geo ?? row.geography ?? row.dimensions ?? ""),
  ].join(" "));
}

function identityMatches(caseDef, row) {
  const expected = new Set([
    ...(caseDef.relevant_series_ids ?? []),
    ...Object.keys(caseDef.graded_judgments ?? {}),
  ].map(fold));
  if (!expected.size) return false;
  const identities = [row.set_id, row.dataset_id, row.series_id, row.code, row.id]
    .filter(Boolean)
    .flatMap((value) => [fold(value), ...fold(value).split(/[|/:]/g)])
    .filter(Boolean);
  return identities.some((identity) => expected.has(identity));
}

function legacyConstraintAssessment(caseDef, row) {
  const text = rowText(row);
  const requiredGroups = caseDef.required_groups ?? [];
  const expectedConcepts = caseDef.expected_concepts ?? caseDef.expected_terms ?? [];
  const conceptApplicable = requiredGroups.length > 0 || expectedConcepts.length > 0;
  const conceptOk = requiredGroups.length > 0
    ? requiredGroups.every((group) => group.some((pattern) => matches(text, fold(pattern))))
    : expectedConcepts.length === 0 || expectedConcepts.some((pattern) => matches(text, fold(pattern)));
  const expectedGeo = caseDef.expected_geo ?? caseDef.geo ?? [];
  const geoApplicable = expectedGeo.length > 0;
  const geoOk = !geoApplicable || expectedGeo.some((pattern) => matches(text, fold(pattern)));
  const acceptableSources = new Set((caseDef.acceptable_sources ?? []).map(normalizedSource));
  const requiredSource = normalizedSource(caseDef.required_source ?? "");
  const sourceApplicable = acceptableSources.size > 0 || Boolean(requiredSource);
  const source = normalizedSource(row.source);
  const sourceOk = (!requiredSource || source === requiredSource)
    && (!acceptableSources.size || acceptableSources.has(source));
  const expectedSectors = Array.isArray(caseDef.expected_sector)
    ? caseDef.expected_sector
    : caseDef.expected_sector ? [caseDef.expected_sector]
      : caseDef.category ? [caseDef.category] : [];
  const sectorApplicable = expectedSectors.length > 0;
  const sectorText = fold([row.manager_category, row.sector_hint].join(" "));
  const sectorOk = !sectorApplicable || expectedSectors.some((pattern) => matches(sectorText, fold(pattern)));
  const exactIdentity = identityMatches(caseDef, row);
  return {
    exactIdentity,
    conceptApplicable,
    conceptOk,
    geoApplicable,
    geoOk,
    sourceApplicable,
    sourceOk,
    sectorApplicable,
    sectorOk,
    relevant: exactIdentity || (conceptOk && geoOk && sourceOk && sectorOk),
  };
}

const CONSTRAINT = Object.freeze({
  NOT_APPLICABLE: "not_applicable",
  CORRECT: "correct",
  WRONG: "wrong",
  UNKNOWN: "unknown",
});

function canonicalConstraint(expected, actual) {
  if (!expected.length) return CONSTRAINT.NOT_APPLICABLE;
  if (!actual.length) return CONSTRAINT.UNKNOWN;
  return expected.some((value) => actual.includes(value)) ? CONSTRAINT.CORRECT : CONSTRAINT.WRONG;
}

function expectedGeoCodes(caseDef) {
  return canonicalValues(caseDef.expected_geo ?? caseDef.geo)
    .filter((value) => /^[a-z0-9]{2,3}$/.test(value));
}

function expectedSourceIds(caseDef) {
  const required = canonicalValues(caseDef.required_source).map(normalizedSource);
  return required.length
    ? required
    : canonicalValues(caseDef.acceptable_sources).map(normalizedSource);
}

function expectedRegistryIds(caseDef, entries, explicitField) {
  const explicit = canonicalValues(caseDef[explicitField]);
  if (explicit.length) return explicit;
  return registryIdsFor([
    caseDef.query,
    ...(caseDef.expected_concepts ?? []),
    ...(caseDef.expected_terms ?? []),
    ...(caseDef.required_groups ?? []).flat(),
  ], entries);
}

function constraintAssessment(caseDef, row) {
  const text = rowText(row);
  const requiredGroups = caseDef.required_groups ?? [];
  const expectedConcepts = caseDef.expected_concepts ?? caseDef.expected_terms ?? [];
  const conceptApplicable = requiredGroups.length > 0 || expectedConcepts.length > 0;
  const conceptCorrect = requiredGroups.length > 0
    ? requiredGroups.every((group) => group.some((pattern) => matches(text, fold(pattern))))
    : expectedConcepts.some((pattern) => matches(text, fold(pattern)));
  const lexicalConceptStatus = !conceptApplicable
    ? CONSTRAINT.NOT_APPLICABLE
    : text.trim() === "" ? CONSTRAINT.UNKNOWN
      : conceptCorrect ? CONSTRAINT.CORRECT : CONSTRAINT.WRONG;

  const sourceStatus = canonicalConstraint(
    expectedSourceIds(caseDef),
    canonicalValues(row.canonical_source_id).map(normalizedSource));
  const geoStatus = canonicalConstraint(
    expectedGeoCodes(caseDef),
    canonicalValues(row.canonical_geo_codes));
  const sectorStatus = canonicalConstraint(
    expectedRegistryIds(caseDef, sectorRegistry.sectors ?? [], "expected_sector_ids"),
    canonicalValues(row.canonical_sector_ids));
  const metricStatus = canonicalConstraint(
    expectedRegistryIds(caseDef, metricRegistry.metrics ?? [], "expected_metric_intents"),
    canonicalValues(row.canonical_metric_intents));
  const conceptStatus = metricStatus === CONSTRAINT.CORRECT || metricStatus === CONSTRAINT.WRONG
    ? metricStatus
    : lexicalConceptStatus;
  const exactIdentity = identityMatches(caseDef, row);
  const requiredStatuses = [conceptStatus, sourceStatus, geoStatus, sectorStatus]
    .filter((status) => status !== CONSTRAINT.NOT_APPLICABLE);
  return {
    exactIdentity,
    conceptStatus,
    lexicalConceptStatus,
    sourceStatus,
    geoStatus,
    sectorStatus,
    metricStatus,
    relevant: exactIdentity || (requiredStatuses.length > 0
      && requiredStatuses.every((status) => status === CONSTRAINT.CORRECT)),
  };
}

function relevanceGrade(caseDef, row) {
  const assessment = constraintAssessment(caseDef, row);
  if (assessment.exactIdentity) {
    const identities = [row.set_id, row.dataset_id, row.series_id, row.code, row.id].map(fold);
    const judgment = Object.entries(caseDef.graded_judgments ?? {})
      .find(([id]) => identities.some((identity) => identity === fold(id) || identity.endsWith(`|${fold(id)}`)));
    return judgment ? Math.max(1, Number(judgment[1]) || 1) : 3;
  }
  return assessment.relevant ? 2 : 0;
}

function ndcgAt(grades, rankLimit) {
  const dcg = grades.slice(0, rankLimit).reduce((sum, grade, index) =>
    sum + ((2 ** grade) - 1) / Math.log2(index + 2), 0);
  const ideal = [...grades].sort((a, b) => b - a).slice(0, rankLimit).reduce((sum, grade, index) =>
    sum + ((2 ** grade) - 1) / Math.log2(index + 2), 0);
  return ideal > 0 ? dcg / ideal : 0;
}

function percentile(values, p) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}

function numericStats(values) {
  const clean = values.map(Number).filter(Number.isFinite);
  if (!clean.length) return { count: 0, p50: 0, p90: 0, p95: 0, max: 0, mean: 0, variance: 0 };
  const mean = clean.reduce((sum, value) => sum + value, 0) / clean.length;
  return {
    count: clean.length,
    p50: percentile(clean, 50),
    p90: percentile(clean, 90),
    p95: percentile(clean, 95),
    max: Math.max(...clean),
    mean: Math.round(mean),
    variance: clean.reduce((sum, value) => sum + ((value - mean) ** 2), 0) / clean.length,
  };
}

const stableSignature = (value) => JSON.stringify(value, (_key, item) => {
  if (item && typeof item === "object" && !Array.isArray(item)) {
    return Object.fromEntries(Object.entries(item).sort(([left], [right]) => left.localeCompare(right)));
  }
  return item;
});

function plannerTermSignature(profile) {
  return stableSignature(sourceStatuses(profile)
    .map((status) => ({
      source: status.source ?? "unknown",
      terms: (status.term_timings ?? []).map((term) => fold(term.term)).filter(Boolean).sort(),
    }))
    .sort((left, right) => left.source.localeCompare(right.source)));
}

function previewOutcomeSignature(profile) {
  return stableSignature(sourceStatuses(profile)
    .map((status) => ({
      source: status.source ?? "unknown",
      preview: status.preview ?? {},
    }))
    .sort((left, right) => left.source.localeCompare(right.source)));
}

function terminalSourceStatusSignature(profile) {
  return stableSignature(sourceStatuses(profile)
    .map((status) => [status.source ?? "unknown", status.terminal_status ?? "unknown"])
    .sort(([left], [right]) => left.localeCompare(right)));
}

const phaseKeys = [
  "planner_ms",
  "source_routing_ms",
  "retrieval_wall_ms",
  "term_expansion_ms",
  "candidate_normalization_ms",
  "geo_compatibility_ms",
  "sector_compatibility_ms",
  "metric_compatibility_ms",
  "post_retrieval_ms",
  "ai_resolver_ms",
  "preview_initial_ms",
  "preview_queue_wait_ms",
  "preview_fetch_ms",
  "preview_parse_ms",
  "preview_validation_ms",
  "validator_ms",
  "cancellation_delay_ms",
  "retry_fallback_ms",
  "answer_ms",
  "total_ms",
];

function sourceStatuses(profile) {
  return Array.isArray(profile?.source_statuses) ? profile.source_statuses : [];
}

function observability(profile) {
  return {
    phases: Object.fromEntries(phaseKeys.map((key) => [key, profile?.[key] ?? null])),
    sources: sourceStatuses(profile).map((status) => ({
      source: status.source ?? "unknown",
      started_at: status.started_at ?? null,
      ended_at: status.ended_at ?? null,
      queue_ms: status.queue_ms ?? status.queue_wait_ms ?? 0,
      active_work_ms: status.active_work_ms ?? status.duration_ms ?? 0,
      term_count: status.term_count ?? status.terms_requested ?? 0,
      candidate_count: status.candidate_count ?? status.count ?? 0,
      unique_candidate_count: status.unique_candidate_count ?? status.count ?? 0,
      verified_yield: status.verified_yield ?? 0,
      possible_yield: status.possible_yield ?? 0,
      terminal_status: status.terminal_status ?? (status.ok === false ? "error" : "unknown"),
      budget_exhausted: Boolean(status.budget_exhausted),
      error_category: status.error_category ?? "",
      remote_call_count: status.remote_call_count ?? 0,
      local_index_call_count: status.local_index_call_count ?? 0,
      preview: status.preview ?? null,
    })),
    per_term_fts: Array.isArray(profile?.per_term_fts_ms) ? profile.per_term_fts_ms : [],
    per_source_sidecar: profile?.per_source_sidecar_scan_ms ?? {},
    preview_items: profile?.preview_initial?.preview_items ?? [],
  };
}

const traceEnvelopeFields = [
  "request_id",
  "discovery_run_id",
  "full_discovery_run_count",
  "cache_hit",
  "fallback_reason",
  "serving_time_ms",
  "cached_compute_time_ms",
  "source_routing",
  "candidate_count",
  "preview_count",
  "validator_outcome",
  "terminal_status",
];

function traceEnvelope(body) {
  return Object.fromEntries(traceEnvelopeFields.map((field) => [field, body?.[field] ?? null]));
}

async function processStats() {
  if (!backendPid || process.platform !== "win32") return null;
  const { execFileSync } = await import("node:child_process");
  try {
    const script = `$p=Get-Process -Id ${backendPid}; [pscustomobject]@{cpu_s=$p.CPU;working_set=$p.WorkingSet64;threads=$p.Threads.Count}|ConvertTo-Json -Compress`;
    return JSON.parse(execFileSync("powershell", ["-NoProfile", "-Command", script], { encoding: "utf8" }));
  } catch {
    return null;
  }
}

const runStarted = new Date().toISOString();
const results = [];
for (const [index, caseDef] of cases.entries()) {
  const beforeProcess = await processStats();
  const started = performance.now();
  const response = await fetch(`${baseUrl}/api/explore/sector`, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json" },
    body: JSON.stringify({
      sector: "",
      question: caseDef.query,
      query_only: true,
      force_live_deep_search: true,
      broader_search: false,
      analysis_mode: "sector",
    }),
  });
  const body = await response.json();
  const wallMs = Math.round(performance.now() - started);
  const afterProcess = await processStats();
  if (!response.ok) throw new Error(`${caseDef.id}: HTTP ${response.status} ${JSON.stringify(body)}`);
  if (body.cache_hit) throw new Error(`${caseDef.id}: benchmark received a cache hit; cold-path profile is not active`);

  const rows = resultRows(body);
  const assessments = rows.map((row) => constraintAssessment(caseDef, row));
  const grades = rows.map((row) => relevanceGrade(caseDef, row));
  const relevantRanks = grades.map((grade, rank) => grade > 0 ? rank + 1 : null).filter(Boolean);
  const sources = [...new Set(rows.map((row) => row.source).filter(Boolean))];
  const profile = body.performance_profile ?? {};
  const item = {
    id: caseDef.id,
    query: caseDef.query,
    wall_ms: wallMs,
    server_total_ms: profile.total_ms ?? body.serving_time_ms ?? null,
    query_understanding_ms: profile.query_understanding_ms ?? null,
    cache_hit: Boolean(body.cache_hit),
    result_count: rows.length,
    relevant_ranks: relevantRanks,
    top1: relevantRanks.includes(1),
    recall_at_3: relevantRanks.some((rank) => rank <= 3),
    recall_at_5: relevantRanks.some((rank) => rank <= 5),
    reciprocal_rank: relevantRanks.length ? 1 / relevantRanks[0] : 0,
    ndcg_at_10: ndcgAt(grades, 10),
    source_constraint_status: assessments[0]?.sourceStatus ?? CONSTRAINT.UNKNOWN,
    geo_constraint_status: assessments[0]?.geoStatus ?? CONSTRAINT.UNKNOWN,
    sector_constraint_status: assessments[0]?.sectorStatus ?? CONSTRAINT.UNKNOWN,
    metric_constraint_status: assessments[0]?.metricStatus ?? CONSTRAINT.UNKNOWN,
    concept_constraint_status: assessments[0]?.conceptStatus ?? CONSTRAINT.UNKNOWN,
    source_diversity: sources.length,
    sources,
    verified_count: Number(profile.verified_count ?? 0),
    planner_term_signature: plannerTermSignature(profile),
    preview_outcome_signature: previewOutcomeSignature(profile),
    terminal_source_status_signature: terminalSourceStatusSignature(profile),
    top1_canonical_signature: rows.length ? stableSignature({
      source: rows[0].canonical_source_id ?? null,
      geo: rows[0].canonical_geo_codes ?? [],
      sector: rows[0].canonical_sector_ids ?? [],
      metric: rows[0].canonical_metric_intents ?? [],
    }) : "",
    top1_identity: rows.length ? `${normalizedSource(rows[0].source)}|${fold(rows[0].set_id ?? rows[0].dataset_id)}` : "",
    candidate_identities: rows.map((row) => `${normalizedSource(row.source)}|${fold(row.set_id ?? row.dataset_id)}`),
    process_cpu_seconds: beforeProcess && afterProcess ? Math.max(0, afterProcess.cpu_s - beforeProcess.cpu_s) : null,
    working_set_before: beforeProcess?.working_set ?? null,
    working_set_after: afterProcess?.working_set ?? null,
    thread_count_before: beforeProcess?.threads ?? null,
    thread_count_after: afterProcess?.threads ?? null,
    performance_profile: profile,
    observability: observability(profile),
    trace_envelope: traceEnvelope(body),
    rows: rows.map((row, rowIndex) => ({
      source: row.source,
      set_id: row.set_id,
      title: row.title ?? row.indicator_name,
      manager_category: row.manager_category,
      sector_hint: row.sector_hint,
      canonical_source_id: row.canonical_source_id,
      canonical_geo_codes: row.canonical_geo_codes,
      canonical_sector_ids: row.canonical_sector_ids,
      canonical_metric_intents: row.canonical_metric_intents,
      geo_scope: row.geo_scope,
      sector_scope: row.sector_scope,
      canonical_metadata_provenance: row.canonical_metadata_provenance,
      constraint_assessment: assessments[rowIndex],
      legacy_constraint_assessment: legacyConstraintAssessment(caseDef, row),
      relevance_grade: grades[rowIndex],
    })),
  };
  results.push(item);
  const repetitionSuffix = repeatCount > 1 ? `-run-${String(caseDef.repetition).padStart(2, "0")}` : "";
  fs.writeFileSync(path.join(outputDir, `${String(index + 1).padStart(3, "0")}-${caseDef.id}${repetitionSuffix}.json`), JSON.stringify({ case: caseDef, result: item, response: body }, null, 2));
  fs.writeFileSync(path.join(outputDir, "partial-results.json"), JSON.stringify(results, null, 2));
  console.log(`[${index + 1}/${cases.length}] ${caseDef.id}: ${wallMs} ms, rows=${rows.length}, firstRelevant=${relevantRanks[0] ?? "none"}`);
}

const latencies = results.map((item) => item.wall_ms);
const mean = (key) => results.reduce((sum, item) => sum + Number(item[key] ?? 0), 0) / Math.max(1, results.length);
function constraintSummary(statusKey) {
  const statuses = results.map((item) => item[statusKey]);
  const applicable = statuses.filter((status) => status !== CONSTRAINT.NOT_APPLICABLE);
  const evaluable = applicable.filter((status) => status === CONSTRAINT.CORRECT || status === CONSTRAINT.WRONG);
  const count = (status) => applicable.filter((value) => value === status).length;
  return {
    applicable_rows: applicable.length,
    evaluable_rows: evaluable.length,
    correct_rows: count(CONSTRAINT.CORRECT),
    wrong_rows: count(CONSTRAINT.WRONG),
    unknown_rows: count(CONSTRAINT.UNKNOWN),
    accuracy_among_evaluable: evaluable.length
      ? evaluable.filter((status) => status === CONSTRAINT.CORRECT).length / evaluable.length
      : null,
    unknown_rate: applicable.length ? count(CONSTRAINT.UNKNOWN) / applicable.length : null,
    wrong_rate: applicable.length ? count(CONSTRAINT.WRONG) / applicable.length : null,
    evaluator_coverage: applicable.length ? evaluable.length / applicable.length : null,
  };
}

function falseNegativeCorrections() {
  const keys = [
    ["source", "sourceOk", "sourceStatus"],
    ["geo", "geoOk", "geoStatus"],
    ["sector", "sectorOk", "sectorStatus"],
    ["metric", "conceptOk", "metricStatus"],
    ["concept", "conceptOk", "conceptStatus"],
  ];
  const corrections = [];
  for (const result of results) {
    const top = result.rows[0];
    if (!top) continue;
    for (const [constraint, legacyKey, canonicalKey] of keys) {
      if (top.legacy_constraint_assessment?.[legacyKey] === false
          && top.constraint_assessment?.[canonicalKey] === CONSTRAINT.CORRECT) {
        corrections.push({ id: result.id, constraint, source: top.source, set_id: top.set_id });
      }
    }
  }
  return corrections;
}
const phaseDistributions = Object.fromEntries(phaseKeys.map((key) => [
  key,
  numericStats(results.map((item) => item.performance_profile?.[key]).filter((value) => value != null)),
]));
const sourceSamples = new Map();
for (const result of results) {
  for (const status of result.observability.sources) {
    if (!sourceSamples.has(status.source)) sourceSamples.set(status.source, []);
    sourceSamples.get(status.source).push(status);
  }
}
const stabilityGroups = new Map();
for (const result of results) {
  if (!stabilityGroups.has(result.id)) stabilityGroups.set(result.id, []);
  stabilityGroups.get(result.id).push(result);
}
const jaccard = (left, right) => {
  const a = new Set(left);
  const b = new Set(right);
  const union = new Set([...a, ...b]);
  if (!union.size) return 1;
  return [...a].filter((value) => b.has(value)).length / union.size;
};
const stability = Object.fromEntries([...stabilityGroups.entries()].map(([id, samples]) => {
  const topCounts = new Map();
  for (const sample of samples) topCounts.set(sample.top1_identity, (topCounts.get(sample.top1_identity) ?? 0) + 1);
  let pairCount = 0;
  let pairwiseJaccard = 0;
  for (let left = 0; left < samples.length; left++) {
    for (let right = left + 1; right < samples.length; right++) {
      pairCount++;
      pairwiseJaccard += jaccard(samples[left].candidate_identities, samples[right].candidate_identities);
    }
  }
  const uniqueCount = (field) => new Set(samples.map((sample) => sample[field])).size;
  const terminalStatuses = samples.flatMap((sample) => sample.observability.sources
    .map((source) => source.terminal_status));
  const plannerVaries = uniqueCount("planner_term_signature") > 1;
  const previewVaries = uniqueCount("preview_outcome_signature") > 1;
  const terminalVaries = uniqueCount("terminal_source_status_signature") > 1;
  const canonicalVaries = uniqueCount("top1_canonical_signature") > 1;
  const top1Varies = topCounts.size > 1;
  const meanJaccard = pairCount ? pairwiseJaccard / pairCount : 1;
  const classifications = [];
  if (plannerVaries) classifications.push("A_planner_llm");
  if (terminalVaries && terminalStatuses.some((status) => status === "timeout" || status === "error")) {
    classifications.push("B_external_source_availability");
  }
  if (previewVaries) classifications.push("C_preview_variability");
  if (terminalVaries && terminalStatuses.some((status) => status === "budget_exhausted" || status === "cancelled")) {
    classifications.push("D_budget_cancellation");
  }
  if (top1Varies && meanJaccard >= 0.8 && !plannerVaries && !terminalVaries && !previewVaries) {
    classifications.push("E_rerank_tie");
  }
  if (canonicalVaries) classifications.push("F_metadata_canonicalization");
  return [id, {
    runs: samples.length,
    top1_mode_stability: samples.length ? Math.max(...topCounts.values()) / samples.length : 0,
    unique_top1_count: topCounts.size,
    mean_pairwise_candidate_jaccard: meanJaccard,
    verified_count: numericStats(samples.map((sample) => sample.verified_count)),
    planner_term_unique_signatures: uniqueCount("planner_term_signature"),
    preview_outcome_unique_signatures: uniqueCount("preview_outcome_signature"),
    terminal_source_status_unique_signatures: uniqueCount("terminal_source_status_signature"),
    canonical_top1_unique_signatures: uniqueCount("top1_canonical_signature"),
    root_cause_classifications: classifications,
  }];
}));
const sourceDistributions = Object.fromEntries([...sourceSamples.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([source, samples]) => [
  source,
  {
    runs: samples.length,
    queue_ms: numericStats(samples.map((sample) => sample.queue_ms)),
    active_work_ms: numericStats(samples.map((sample) => sample.active_work_ms)),
    candidate_count: numericStats(samples.map((sample) => sample.candidate_count)),
    verified_yield: numericStats(samples.map((sample) => sample.verified_yield)),
    zero_candidate_rate: samples.filter((sample) => sample.candidate_count === 0).length / samples.length,
    zero_verified_yield_rate: samples.filter((sample) => sample.verified_yield === 0).length / samples.length,
    budget_exhaustion_rate: samples.filter((sample) => sample.budget_exhausted || sample.terminal_status === "budget_exhausted").length / samples.length,
    timeout_rate: samples.filter((sample) => sample.terminal_status === "timeout").length / samples.length,
    error_rate: samples.filter((sample) => sample.terminal_status === "error").length / samples.length,
    terminal_status_counts: Object.fromEntries([...new Set(samples.map((sample) => sample.terminal_status))].map((status) => [
      status,
      samples.filter((sample) => sample.terminal_status === status).length,
    ])),
    remote_calls: samples.reduce((sum, sample) => sum + Number(sample.remote_call_count ?? 0), 0),
    local_index_calls: samples.reduce((sum, sample) => sum + Number(sample.local_index_call_count ?? 0), 0),
  },
]));
const summary = {
  run_started: runStarted,
  run_finished: new Date().toISOString(),
  base_url: baseUrl,
  query_count: results.length,
  cold_path_verified: results.every((item) => !item.cache_hit),
  latency_ms: {
    median: percentile(latencies, 50),
    p90: percentile(latencies, 90),
    p95: percentile(latencies, 95),
    min: Math.min(...latencies),
    max: Math.max(...latencies),
  },
  relevance: {
    top1_accuracy: mean("top1"),
    recall_at_3: mean("recall_at_3"),
    recall_at_5: mean("recall_at_5"),
    mrr: mean("reciprocal_rank"),
    ndcg_at_10: mean("ndcg_at_10"),
    canonical_constraints: {
      source: constraintSummary("source_constraint_status"),
      geo: constraintSummary("geo_constraint_status"),
      sector: constraintSummary("sector_constraint_status"),
      metric: constraintSummary("metric_constraint_status"),
      concept: constraintSummary("concept_constraint_status"),
    },
    false_negative_corrections: falseNegativeCorrections(),
    average_source_diversity: mean("source_diversity"),
  },
  observability: {
    phase_distributions: phaseDistributions,
    source_distributions: sourceDistributions,
    trace_envelope_coverage: Object.fromEntries(traceEnvelopeFields.map((field) => [field, {
      present: results.filter((item) => Object.hasOwn(item.trace_envelope, field)).length,
      non_null: results.filter((item) => item.trace_envelope[field] != null).length,
      total: results.length,
    }])),
  },
  stability,
  results,
};
fs.writeFileSync(path.join(outputDir, "summary.json"), JSON.stringify(summary, null, 2));
console.log(JSON.stringify({ latency_ms: summary.latency_ms, relevance: summary.relevance }, null, 2));
