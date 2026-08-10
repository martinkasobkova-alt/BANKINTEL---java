import React from "react";
import { Eye, Plus, FileBarChart2, Sparkles, Layers } from "lucide-react";
import { formatExplorerBreadcrumb } from "@/lib/catalogColumnExplorer";

export default function CatalogResultCard({
  title,
  breadcrumb,
  catalogLabel,
  frequency,
  geo,
  lastDate,
  relevanceScore,
  aiExplanation,
  code,
  selected = false,
  onSelect,
  onPreview,
  onAdd,
  onDetail,
  previewOpen = false,
  canAdd = false,
  added = false,
  addBusy = false,
  previewable = true,
  detailActionLabel = "Detail",
  detailActionIsBrowse = false,
  detailActionIsQuickPreview = false,
  className = "",
}) {
  const metaBits = [
    frequency ? String(frequency) : "",
    geo ? `Geo: ${geo}` : "",
    lastDate ? String(lastDate) : "",
    relevanceScore != null && relevanceScore !== "" ? `Rel. ${relevanceScore}` : "",
  ].filter(Boolean);

  return (
    <article
      className={`catalog-result-card rounded-lg border bg-card px-3 py-2 shadow-sm transition-colors cursor-pointer ${
        selected
          ? "border-sky-300 ring-1 ring-sky-200/60"
          : "border-border/80 hover:border-sky-200/70 hover:bg-muted/20"
      } ${className}`}
      onClick={onSelect}
      data-testid="catalog-result-card"
    >
      {breadcrumb ? (
        <p className="text-[10px] text-muted-foreground truncate mb-0.5" title={breadcrumb}>
          {breadcrumb}
        </p>
      ) : null}

      <h3 className="text-[13px] font-medium text-foreground leading-snug line-clamp-2">{title}</h3>

      <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 mt-1 text-[10px] text-muted-foreground min-w-0">
        {catalogLabel ? (
          <span className="font-semibold text-foreground/75 shrink-0">{catalogLabel}</span>
        ) : null}
        {code ? <span className="font-mono truncate">{code}</span> : null}
        {metaBits.length ? (
          <span className="truncate hidden sm:inline">{metaBits.join(" · ")}</span>
        ) : null}
      </div>

      {aiExplanation ? (
        <p className="text-[11px] text-sky-900/90 mt-1 leading-snug line-clamp-2 flex gap-1 items-start">
          <Sparkles className="h-3 w-3 shrink-0 mt-0.5 text-sky-600" />
          <span>{aiExplanation}</span>
        </p>
      ) : null}

      <div
        className="flex flex-wrap items-center gap-1 mt-2 pt-1.5 border-t border-border/40"
        onClick={(e) => e.stopPropagation()}
      >
        {previewable ? (
          <button
            type="button"
            onClick={onPreview}
            className="inline-flex items-center gap-1 h-7 px-2 text-[11px] rounded-md border border-border/70 bg-card hover:bg-muted/50"
          >
            <Eye className="h-3 w-3" />
            {previewOpen ? "Skrýt" : "Data"}
          </button>
        ) : detailActionIsQuickPreview ? (
          <button
            type="button"
            onClick={onDetail}
            className="inline-flex items-center gap-1 h-7 px-2 text-[11px] rounded-md btn-mint"
          >
            <Eye className="h-3 w-3" />
            {detailActionLabel}
          </button>
        ) : null}
        {previewable && canAdd && !added ? (
          <button
            type="button"
            onClick={onAdd}
            disabled={addBusy}
            className="inline-flex items-center gap-1 h-7 px-2 text-[11px] rounded-md bg-sky-600 hover:bg-sky-700 text-white disabled:opacity-60"
          >
            <Plus className="h-3 w-3" />
            Přidat
          </button>
        ) : null}
        {!detailActionIsQuickPreview ? (
        <button
          type="button"
          onClick={onDetail}
          className={`inline-flex items-center gap-1 h-7 px-2 text-[11px] rounded-md border border-border/70 bg-card hover:bg-muted/50 ${
            detailActionIsBrowse ? "btn-mint" : ""
          }`}
        >
          {detailActionIsBrowse ? <Layers className="h-3 w-3" /> : <FileBarChart2 className="h-3 w-3" />}
          {detailActionLabel}
        </button>
        ) : null}
      </div>
    </article>
  );
}

export function catalogResultMetaFromRow(def, row) {
  return {
    title: String(row.name || row.title || row.set_id || "").trim(),
    breadcrumb: formatExplorerBreadcrumb(row.parentPath || row.path),
    catalogLabel: def?.label || "",
    frequency: row.period || row.frekvence || row.query_params?.imf_frekvence || "",
    geo: row.territory || row.geo || row.ref_area || row.query_params?.geo || "",
    lastDate: row.last_update || row.last_obs || row.end_period || "",
    relevanceScore: row.match_score ?? row.relevance_score ?? row.final_rank ?? "",
    code: String(row.set_id || ""),
  };
}
