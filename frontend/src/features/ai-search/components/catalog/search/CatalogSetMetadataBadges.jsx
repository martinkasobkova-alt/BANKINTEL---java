import React from "react";

export default function CatalogSetMetadataBadges({ sourceLabel, row }) {
  return (
    <div className="flex flex-wrap gap-1.5 mt-2" data-testid="catalog-result-meta">
      <span className="text-[10px] px-2 py-0.5 rounded-md border border-border/80 bg-muted/40 text-foreground/90">
        {sourceLabel}
      </span>
      {row.period || row.frekvence || row.query_params?.imf_frekvence ? (
        <span className="text-[10px] px-2 py-0.5 rounded-md border border-border/80 bg-card text-muted-foreground">
          Frekvence: {String(row.period || row.frekvence || row.query_params?.imf_frekvence)}
        </span>
      ) : null}
      {row.territory || row.geo || row.ref_area || row.query_params?.geo ? (
        <span className="text-[10px] px-2 py-0.5 rounded-md border border-border/80 bg-card text-muted-foreground">
          Geo: {String(row.territory || row.geo || row.ref_area || row.query_params?.geo)}
        </span>
      ) : null}
      {row.last_update || row.last_obs || row.end_period ? (
        <span className="text-[10px] px-2 py-0.5 rounded-md border border-border/80 bg-card text-muted-foreground">
          Poslední: {String(row.last_update || row.last_obs || row.end_period)}
        </span>
      ) : null}
      {row.match_score != null || row.relevance_score != null || row.final_rank != null ? (
        <span className="text-[10px] px-2 py-0.5 rounded-md border border-emerald-200/80 bg-emerald-50/80 text-emerald-950">
          Relevance: {String(row.match_score ?? row.relevance_score ?? row.final_rank)}
        </span>
      ) : null}
    </div>
  );
}
