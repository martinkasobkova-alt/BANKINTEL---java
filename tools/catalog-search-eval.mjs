#!/usr/bin/env node

import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import process from "node:process";

const ALL_SOURCES = [
  "arad",
  "csu",
  "eurostat",
  "ecb2",
  "fred",
  "imf",
  "data360",
  "bis",
  "oecd4",
  "commodities",
];

const SOURCE_CASES = [
  {
    id: "source-arad-bank-profit",
    q: "zisk bank cesko",
    sources: ["arad"],
    expectSource: "arad",
    expectAny: [/roe|rentabilita bank|zisk.*bank|profitability/],
    minRows: 1,
    useAi: false,
  },
  {
    id: "source-csu-real-estate-prices",
    q: "ceny nemovitosti",
    sources: ["csu"],
    expectSource: "csu",
    expectAny: [/ceny nemovitosti|kupni ceny nemovitosti|prumerne ceny nemovitosti/],
    minRows: 3,
    minVerified: 1,
    useAi: false,
  },
  {
    id: "source-eurostat-inflation-spain",
    q: "inflace spanelsko",
    sources: ["eurostat"],
    expectSource: "eurostat",
    expectAny: [/hicp|prc_hicp|inflation/],
    minRows: 3,
    minVerified: 1,
    useAi: false,
  },
  {
    id: "source-ecb2-roa-bank",
    q: "roa bank",
    sources: ["ecb2"],
    expectSource: "ecb2",
    expectAny: [/return on assets|roa|cbd2/],
    minRows: 3,
    minVerified: 1,
    useAi: false,
  },
  {
    id: "source-fred-brent-oil",
    q: "cena ropy brent",
    sources: ["fred"],
    expectSource: "fred",
    expectAny: [/brent|crude oil|poilbre/],
    minRows: 1,
    minVerified: 1,
    useAi: false,
  },
  {
    id: "source-imf-oil-price",
    q: "cena ropy",
    sources: ["imf"],
    expectSource: "imf",
    expectAny: [/brent crude|unit prices.*barrel|poil|crude/],
    minRows: 1,
    minVerified: 1,
    useAi: false,
  },
  {
    id: "source-data360-gdp-czechia",
    q: "gdp cesko",
    sources: ["data360"],
    expectSource: "data360",
    expectAny: [/hdp|gdp|rust hdp|gross domestic product/],
    minRows: 1,
    useAi: false,
  },
  {
    id: "source-bis-central-bank-assets",
    q: "central bank assets",
    sources: ["bis"],
    expectSource: "bis",
    expectAny: [/centralni bank|central bank.*assets|ws_cbta/],
    minRows: 1,
    useAi: false,
  },
  {
    id: "source-oecd-house-prices",
    q: "real house prices",
    sources: ["oecd4"],
    expectSource: "oecd4",
    expectAny: [/house prices|housing_prices|real house price|cen nemovitosti/],
    minRows: 1,
    useAi: false,
  },
  {
    id: "source-commodities-oil",
    q: "cena ropy",
    sources: ["commodities"],
    expectSource: "commodities",
    expectAny: [/crude oil|brent|wti/],
    minRows: 3,
    minVerified: 1,
    useAi: false,
  },
];

const CROSS_CASES = [
  {
    id: "cross-bank-profit-czechia",
    q: "zisk bank cesko",
    sources: ALL_SOURCES,
    expectSourcesAny: ["arad", "eurostat", "ecb2"],
    expectAny: [/roe|rentabilita|return on equity|profitability|zisk.*bank/],
    minRows: 3,
    useAi: true,
  },
  {
    id: "cross-roa-bank",
    q: "roa bank",
    sources: ALL_SOURCES,
    expectSourcesAny: ["ecb2"],
    expectAny: [/return on assets|roa|cbd2/],
    minRows: 3,
    minVerified: 1,
    useAi: true,
  },
  {
    id: "cross-oil-price",
    q: "cena ropy",
    sources: ALL_SOURCES,
    expectSourcesAny: ["fred", "imf", "commodities"],
    expectAny: [/brent|wti|crude oil|poil/],
    rejectTopAny: [/general government|percent of gdp|vyrobci ropy.*government/],
    minRows: 3,
    minVerified: 1,
    useAi: true,
  },
  {
    id: "cross-inflation-hungary",
    q: "inflace madarsko",
    sources: ALL_SOURCES,
    expectSourcesAny: ["eurostat", "data360", "imf"],
    expectAny: [/hicp|consumer price|cpi|inflace|inflation/],
    minRows: 3,
    minVerified: 1,
    useAi: true,
  },
  {
    id: "cross-real-estate-prices",
    q: "ceny nemovitosti",
    sources: ALL_SOURCES,
    expectSourcesAny: ["csu", "oecd4", "ecb2"],
    expectAny: [/ceny nemovitosti|kupni ceny|house price|housing_prices|real estate/],
    minRows: 3,
    minVerified: 1,
    useAi: true,
  },
  {
    id: "cross-eurusd",
    q: "eurusd",
    sources: ALL_SOURCES,
    expectSourcesAny: ["ecb2", "fred"],
    expectAny: [/euro.*us dollar|eur.*usd|exchange rate|exr|dexuseu/],
    minRows: 1,
    minVerified: 1,
    useAi: true,
  },
  {
    id: "cross-us-unemployment",
    q: "nezamestnanost usa",
    sources: ALL_SOURCES,
    expectSourcesAny: ["fred", "imf", "oecd4", "data360"],
    expectAny: [/unemployment|nezamestnanost|unrate/],
    minRows: 1,
    useAi: true,
  },
];

const ROUTE_CASES = [
  {
    id: "route-oil",
    q: "cena ropy",
    expectedAny: ["fred", "imf", "data360", "commodities"],
    rejectedFirstAny: ["arad", "csu", "eurostat"],
  },
  {
    id: "route-hungary-inflation",
    q: "inflace madarsko",
    expectedAny: ["eurostat", "imf", "fred", "data360"],
    rejectedFirstAny: ["arad", "csu"],
  },
  {
    id: "route-roa-bank",
    q: "roa bank",
    expectedAny: ["ecb2", "arad", "eurostat", "bis"],
    rejectedFirstAny: ["csu", "fred"],
  },
  {
    id: "route-real-estate",
    q: "ceny nemovitosti",
    expectedAny: ["csu", "oecd4", "ecb2"],
    rejectedFirstAny: ["fred", "imf"],
  },
  {
    id: "route-eurusd",
    q: "eurusd",
    expectedAny: ["ecb2", "fred"],
    rejectedFirstAny: ["csu", "eurostat"],
  },
];

const options = parseArgs(process.argv.slice(2));
const baseUrl = options.baseUrl ?? process.env.BANKINTEL_BASE_URL ?? "http://127.0.0.1:8081";
const reportPath = resolve(options.report ?? ".codex-run/catalog-search-eval-report.json");
const timeoutMs = Number(options.timeoutMs ?? 180_000);
const globalUseAi = options.ai ?? null;

const report = {
  baseUrl,
  startedAt: new Date().toISOString(),
  route: [],
  deepSearch: [],
  summary: {},
};

await checkBackend();
for (const testCase of ROUTE_CASES) {
  report.route.push(await runRouteCase(testCase));
}
for (const testCase of [...SOURCE_CASES, ...CROSS_CASES]) {
  report.deepSearch.push(await runDeepCase(testCase));
}
report.finishedAt = new Date().toISOString();
report.summary = summarize(report);

await mkdir(dirname(reportPath), { recursive: true });
await writeFile(reportPath, JSON.stringify(report, null, 2), "utf8");

printReport(report, reportPath);
process.exitCode = report.summary.failed === 0 ? 0 : 1;

async function checkBackend() {
  const status = await fetchJson("/api/catalog/status", null, 30_000, "GET");
  if (!status?.fts_db_available) {
    throw new Error("Catalog FTS DB is not available according to /api/catalog/status");
  }
}

async function runRouteCase(testCase) {
  const started = Date.now();
  try {
    const json = await fetchJson(
      "/api/catalog/deep-search/source-route",
      { q: testCase.q, sources: ALL_SOURCES, max_sources: 5 },
      timeoutMs,
    );
    const route = json?.source_route ?? {};
    const sources = route.sources ?? route.selected_sources ?? [];
    const firstTwo = sources.slice(0, 2);
    const expectedOk = testCase.expectedAny.some((source) => sources.includes(source));
    const rejectedOk = !(testCase.rejectedFirstAny ?? []).some((source) => firstTwo.includes(source));
    return {
      ...baseCaseResult(testCase, started),
      ok: Boolean(json?.ok) && expectedOk && rejectedOk,
      sources,
      candidatePool: route.source_candidate_pool ?? [],
      planner: route.planner ?? null,
      diagnostics: route.source_router ?? {},
      failures: compact([
        !json?.ok && "route endpoint returned !ok",
        !expectedOk && `route missing expected sources: ${testCase.expectedAny.join(", ")}`,
        !rejectedOk && `route starts with rejected sources: ${firstTwo.join(", ")}`,
      ]),
    };
  } catch (error) {
    return failedCase(testCase, started, error);
  }
}

async function runDeepCase(testCase) {
  const started = Date.now();
  const useAi = globalUseAi ?? testCase.useAi ?? true;
  try {
    const json = await fetchJson(
      "/api/catalog/deep-search",
      {
        q: testCase.q,
        sources: testCase.sources,
        limit_per_source: testCase.limitPerSource ?? 8,
        use_ai: useAi,
      },
      timeoutMs,
    );
    const rows = rankedRows(json);
    const topN = testCase.topN ?? 5;
    const top = rows.slice(0, topN);
    const sourceScoped = testCase.expectSource
      ? top.filter((row) => rowSource(row) === testCase.expectSource)
      : top;
    const matchingRows = sourceScoped.filter((row) => matchesAny(rowText(row), testCase.expectAny));
    const expectedSourceOk = !testCase.expectSourcesAny?.length
      || top.some((row) => testCase.expectSourcesAny.includes(rowSource(row)));
    const specificSourceOk = !testCase.expectSource || top.some((row) => rowSource(row) === testCase.expectSource);
    const semanticOk = matchingRows.length > 0;
    const rejectTopOk = !(testCase.rejectTopAny ?? []).some((pattern) => pattern.test(fold(top[0] ? rowText(top[0]) : "")));
    const minRowsOk = rows.length >= (testCase.minRows ?? 1);
    const minVerifiedOk = verifiedRows(rows).length >= (testCase.minVerified ?? 0);
    const unsafeDisplayedRows = rows.filter((row) => !rowHasUsablePreview(row));
    const displaySafetyOk = unsafeDisplayedRows.length === 0;
    return {
      ...baseCaseResult(testCase, started),
      ok: Boolean(json?.ok)
        && minRowsOk
        && minVerifiedOk
        && specificSourceOk
        && expectedSourceOk
        && semanticOk
        && rejectTopOk
        && displaySafetyOk,
      status: json?.status ?? null,
      aiRequested: json?.ai_requested ?? null,
      aiActive: json?.ai_active ?? null,
      aiPlanUsed: json?.ai_plan_used ?? null,
      plannedSources: json?.search_plan?.sources ?? [],
      sourceStatuses: json?.source_statuses ?? [],
      rowCount: rows.length,
      verifiedCount: verifiedRows(rows).length,
      unsafeDisplayedCount: unsafeDisplayedRows.length,
      unsafeDisplayed: unsafeDisplayedRows.slice(0, 5).map(compactRow),
      discardedCount: json?.discarded_candidates?.length ?? 0,
      top: top.map(compactRow),
      failures: compact([
        !json?.ok && `deep-search returned !ok: ${json?.error ?? "unknown error"}`,
        !minRowsOk && `only ${rows.length} rows, expected >= ${testCase.minRows ?? 1}`,
        !minVerifiedOk && `only ${verifiedRows(rows).length} verified rows, expected >= ${testCase.minVerified}`,
        !specificSourceOk && `top ${topN} missing source ${testCase.expectSource}`,
        !expectedSourceOk && `top ${topN} missing expected sources ${testCase.expectSourcesAny?.join(", ")}`,
        !semanticOk && `top ${topN} missing expected relevance pattern`,
        !rejectTopOk && `top result matches rejected pattern`,
        !displaySafetyOk && `${unsafeDisplayedRows.length} displayed rows do not have a verified usable preview`,
      ]),
    };
  } catch (error) {
    return failedCase(testCase, started, error);
  }
}

async function fetchJson(path, body, timeout, method = "POST") {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(`${baseUrl}${path}`, {
      method,
      headers: body ? { "content-type": "application/json" } : undefined,
      body: body ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    });
    const text = await response.text();
    let json;
    try {
      json = text ? JSON.parse(text) : {};
    } catch (error) {
      throw new Error(`Invalid JSON from ${path}: ${text.slice(0, 300)}`);
    }
    if (!response.ok) {
      throw new Error(`HTTP ${response.status} from ${path}: ${JSON.stringify(json).slice(0, 300)}`);
    }
    return json;
  } finally {
    clearTimeout(timer);
  }
}

function rankedRows(json) {
  const rows = [...(json?.verified ?? []), ...(json?.possible ?? [])];
  return rows
    .map((row, index) => ({ row, index }))
    .sort((a, b) => {
      const ar = numericRank(a.row.final_rank);
      const br = numericRank(b.row.final_rank);
      if (ar !== br) return ar - br;
      return a.index - b.index;
    })
    .map(({ row }) => row);
}

function verifiedRows(rows) {
  return rows.filter(rowHasUsablePreview);
}

function rowHasUsablePreview(row) {
  const previewStatus = String(row.preview_status ?? "").toLowerCase();
  const status = String(row.status ?? row.result_tier ?? "").toLowerCase();
  const count = Number(row.preview_row_count ?? 0);
  const available = row.preview_available === true;
  return (previewStatus === "verified" || status === "verified") && (available || count > 0);
}

function rowText(row) {
  return [
    rowSource(row),
    row.catalog_id,
    row.set_id,
    row.title,
    row.name,
    row.full_path,
    row.unit,
    row.why_relevant,
    row.preview_status,
    row.semantic_match_level,
    JSON.stringify(row.row ?? {}),
  ]
    .filter(Boolean)
    .join(" | ");
}

function rowSource(row) {
  return String(row.source ?? row.source_type ?? row.catalog_id ?? "").toLowerCase();
}

function matchesAny(text, patterns = []) {
  const folded = fold(text);
  return patterns.some((pattern) => pattern.test(folded));
}

function compactRow(row) {
  return {
    rank: row.final_rank ?? null,
    source: rowSource(row),
    setId: row.set_id ?? null,
    title: row.title ?? row.name ?? "",
    preview: row.preview_status ?? row.status ?? null,
    rows: row.preview_row_count ?? null,
    score: row.final_score ?? row._search_score ?? null,
    semantic: row.semantic_match_level ?? null,
  };
}

function fold(value) {
  return String(value ?? "")
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase();
}

function numericRank(value) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : Number.MAX_SAFE_INTEGER;
}

function baseCaseResult(testCase, started) {
  return {
    id: testCase.id,
    q: testCase.q,
    sources: testCase.sources ?? ALL_SOURCES,
    durationMs: Date.now() - started,
  };
}

function failedCase(testCase, started, error) {
  return {
    ...baseCaseResult(testCase, started),
    ok: false,
    failures: [error?.message ?? String(error)],
  };
}

function summarize(currentReport) {
  const route = currentReport.route;
  const deep = currentReport.deepSearch;
  const all = [...route, ...deep];
  const failed = all.filter((item) => !item.ok);
  return {
    total: all.length,
    passed: all.length - failed.length,
    failed: failed.length,
    routePassed: route.filter((item) => item.ok).length,
    routeTotal: route.length,
    deepPassed: deep.filter((item) => item.ok).length,
    deepTotal: deep.length,
    failedIds: failed.map((item) => item.id),
  };
}

function printReport(currentReport, path) {
  const summary = currentReport.summary;
  console.log(`\nCatalog search eval: ${summary.passed}/${summary.total} passed`);
  console.log(`Route: ${summary.routePassed}/${summary.routeTotal}; deep-search: ${summary.deepPassed}/${summary.deepTotal}`);
  for (const group of [
    ["route", currentReport.route],
    ["deep", currentReport.deepSearch],
  ]) {
    const [label, items] = group;
    console.log(`\n${label.toUpperCase()}`);
    for (const item of items) {
      const mark = item.ok ? "PASS" : "FAIL";
      const detail = item.ok
        ? item.sources?.join?.(",") || item.top?.[0]?.title || ""
        : item.failures.join("; ");
      console.log(`${mark} ${item.id} (${item.durationMs} ms) ${detail}`);
      if (!item.ok && item.top?.length) {
        for (const row of item.top.slice(0, 3)) {
          console.log(`  #${row.rank ?? "?"} [${row.source}] ${row.title} (${row.setId}) ${row.preview ?? ""}`);
        }
      }
    }
  }
  console.log(`\nReport written to ${path}`);
}

function compact(items) {
  return items.filter(Boolean);
}

function parseArgs(args) {
  const out = {};
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === "--base-url") out.baseUrl = args[++i];
    else if (arg === "--report") out.report = args[++i];
    else if (arg === "--timeout-ms") out.timeoutMs = args[++i];
    else if (arg === "--ai") out.ai = true;
    else if (arg === "--no-ai") out.ai = false;
  }
  return out;
}
