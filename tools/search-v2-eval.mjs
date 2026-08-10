#!/usr/bin/env node

const baseUrl = process.env.BANKINTEL_API_URL || "http://127.0.0.1:8080";
const max = Number(process.env.SEARCH_V2_EVAL_MAX || process.argv[2] || 40);
const useAi = !["0", "false", "no", "off"].includes(String(process.env.SEARCH_V2_EVAL_AI ?? "true").toLowerCase());
const mode = process.env.SEARCH_EVAL_MODE || "metadata_only";
const previewTopN = Number(process.env.SEARCH_EVAL_PREVIEW_TOP_N || 5);

const response = await fetch(`${baseUrl.replace(/\/$/, "")}/api/catalog/search-v2/evaluate`, {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({ max, use_ai: useAi, mode, preview_top_n: previewTopN }),
});

if (!response.ok) {
  console.error(`Search V2 eval failed: HTTP ${response.status}`);
  console.error(await response.text());
  process.exit(1);
}

const json = await response.json();
console.log(JSON.stringify(json, null, 2));
