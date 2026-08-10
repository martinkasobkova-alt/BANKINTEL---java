import React from "react";

export default function CatalogPipelineDiagnosticsPanel({ diagnostics }) {
  const data = diagnostics && typeof diagnostics === "object" ? diagnostics : {};
  const searchedSources = Array.isArray(data.searchedSources) ? data.searchedSources : [];
  const resolvedGeo = data.resolvedGeo && typeof data.resolvedGeo === "object" ? data.resolvedGeo : {};
  const durationMsTotal = Number(data.durationMsTotal || 0);
  const retrievalQueryCap = Number(data.retrievalQueryCap || 0);
  const timeoutCount = Number(data.timeoutCount || 0);
  const fallbackCount = Number(data.fallbackCount || 0);

  const visible = searchedSources.length > 0 || durationMsTotal > 0 || data.queryDomain;
  if (!visible) return null;

  return (
    <details className="text-[11px] rounded-xl px-3 py-2.5 border border-slate-200 canvas-dark:border-slate-700/50 bg-slate-50/85 canvas-dark:bg-slate-950/35 leading-snug">
      <summary className="cursor-pointer select-none font-medium text-foreground/90">Diagnostika pipeline</summary>
      <ul className="mt-2 space-y-1 list-none font-mono text-[10px] text-muted-foreground break-words">
        {data.queryDomain ? <li>query_domain: {data.queryDomain}</li> : null}
        {Object.keys(resolvedGeo).length > 0 ? <li>resolved_geo: {JSON.stringify(resolvedGeo)}</li> : null}
        {searchedSources.length > 0 ? <li>searched_sources: {searchedSources.join(", ")}</li> : null}
        {durationMsTotal > 0 ? <li>duration_ms_total: {durationMsTotal}</li> : null}
        {retrievalQueryCap > 0 ? <li>retrieval_query_cap: {retrievalQueryCap}</li> : null}
        <li>timeout_count: {timeoutCount}</li>
        <li>fallback_count: {fallbackCount}</li>
        {data.gptModel ? <li>gpt_model: {data.gptModel}</li> : null}
      </ul>
    </details>
  );
}
